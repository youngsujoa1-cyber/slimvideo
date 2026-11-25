package com.youngsu.slimvideo.compress

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 디코더가 그린 프레임을 인코더 입력 Surface 로 그대로 옮기는 최소 GL 파이프라인.
 *
 * - 프레임 자체는 건드리지 않는다(회전은 MediaMuxer 의 orientation hint 로 유지).
 * - SurfaceTexture 의 변환 행렬을 그대로 써서 크롭·스트라이드가 있는 기기에서도 화면이 깨지지 않는다.
 */
internal class GlBlitter(
    outputSurface: Surface,
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    textureWidth: Int,
    textureHeight: Int
) {

    companion object {
        private const val TAG = "GlBlitter"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    private val eglDisplay: EGLDisplay
    private val eglContext: EGLContext
    private val eglSurface: EGLSurface

    private val textureId: Int
    private val program: Int
    private val aPosition: Int
    private val aTextureCoord: Int
    private val uTextureMatrix: Int
    private val uTexture: Int

    private val vertexBuffer: FloatBuffer

    private val callbackThread = HandlerThread("slimvideo-gl-callback").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    private val frameLock = Object()
    private var frameAvailable = false
    private val textureMatrix = FloatArray(16)

    val surfaceTexture: SurfaceTexture
    val inputSurface: Surface

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) fail("eglGetDisplay 실패")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) fail("eglInitialize 실패")

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0
        ) {
            fail("eglChooseConfig 실패")
        }
        val config = configs[0] ?: fail("EGLConfig 없음")

        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) fail("eglCreateContext 실패")

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, outputSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) fail("eglCreateWindowSurface 실패")

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            fail("eglMakeCurrent 실패")
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
        )
        checkGlError("텍스처 준비")

        program = buildProgram()
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureCoord = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uTextureMatrix = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        uTexture = GLES20.glGetUniformLocation(program, "sTexture")

        // 화면을 채우는 사각형 (삼각형 스트립)
        vertexBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f
                ))
                position(0)
            }

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setDefaultBufferSize(textureWidth, textureHeight)
        surfaceTexture.setOnFrameAvailableListener(
            {
                synchronized(frameLock) {
                    frameAvailable = true
                    frameLock.notifyAll()
                }
            },
            callbackHandler
        )
        inputSurface = Surface(surfaceTexture)
    }

    /** 다음 프레임이 올 때까지 기다린다 (이미 와 있으면 바로 true) */
    fun awaitNewFrame(timeoutMs: Long): Boolean {
        synchronized(frameLock) {
            if (frameAvailable) {
                frameAvailable = false
                return true
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!frameAvailable) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return false
                try {
                    frameLock.wait(remaining)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            frameAvailable = false
            return true
        }
    }

    /** 현재 프레임을 인코더 Surface 로 그린다. 반환값은 프레임 타임스탬프(ns) */
    fun drawFrame(): Long {
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(textureMatrix)

        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniformMatrix4fv(uTextureMatrix, 1, false, textureMatrix, 0)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(aTextureCoord)
        GLES20.glVertexAttribPointer(aTextureCoord, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTextureCoord)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        checkGlError("drawFrame")

        val timestampNs = surfaceTexture.timestamp
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            fail("eglSwapBuffers 실패")
        }
        return timestampNs
    }

    fun release() {
        runCatching { surfaceTexture.setOnFrameAvailableListener(null) }
        runCatching { inputSurface.release() }
        runCatching { surfaceTexture.release() }
        runCatching {
            if (program != 0) GLES20.glDeleteProgram(program)
            val textures = intArrayOf(textureId)
            GLES20.glDeleteTextures(1, textures, 0)
        }
        runCatching {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        runCatching { callbackThread.quitSafely() }
    }

    // ------------------------------------------------------------- shaders

    private fun buildProgram(): Int {
        val vertexShader = loadShader(
            GLES20.GL_VERTEX_SHADER,
            """
            uniform mat4 uTextureMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uTextureMatrix * aTextureCoord).xy;
            }
            """.trimIndent()
        )
        val fragmentShader = loadShader(
            GLES20.GL_FRAGMENT_SHADER,
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
            """.trimIndent()
        )
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            fail("프로그램 링크 실패: $log")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            fail("셰이더 컴파일 실패: $log")
        }
        return shader
    }

    private fun checkGlError(where: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "$where: glError 0x${Integer.toHexString(error)}")
        }
    }

    private fun fail(message: String): Nothing {
        Log.e(TAG, message)
        throw IllegalStateException(message)
    }
}
