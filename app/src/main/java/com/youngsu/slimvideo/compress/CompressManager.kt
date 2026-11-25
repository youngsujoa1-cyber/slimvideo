package com.youngsu.slimvideo.compress

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 압축 작업 하나를 앱 전체에서 관리한다.
 *
 * 화면을 돌리거나 다른 탭으로 옮겨도 작업은 계속되고, 알림 서비스도 이 상태를 본다.
 */
object CompressManager {

    private const val TAG = "CompressManager"

    enum class Stage { PREPARING, VIDEO, FINISHING }

    sealed interface State {
        data object Idle : State
        data class Running(
            val info: VideoInfo,
            val plan: CompressMath.Plan,
            val progress: Int,
            val stage: Stage
        ) : State

        data class Success(val result: CompressResult) : State
        data class Failure(val error: CompressError, val audioDropped: Boolean = false) : State
        data object Cancelled : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)
    @Volatile private var job: Job? = null

    val isRunning: Boolean get() = _state.value is State.Running

    /** 지금 상태에 맞는 압축 계획을 계산한다 (화면에서 예상 용량을 미리 보여 줄 때도 쓴다) */
    fun plan(info: VideoInfo, mode: Int, codec: Int, targetBytes: Long): CompressMath.Plan =
        CompressMath.plan(
            mode,
            codec,
            info.width,
            info.height,
            info.rotation,
            info.fps,
            info.videoBitrate,
            info.durationUs,
            info.sizeBytes,
            targetBytes,
            info.hasAudio,
            info.audioMime
        )

    fun start(context: Context, request: CompressRequest): Boolean {
        if (isRunning) return false
        val appContext = context.applicationContext
        val plan = plan(request.info, request.mode, request.codec, request.targetBytes)
        cancelRequested.set(false)
        _state.value = State.Running(request.info, plan, 0, Stage.PREPARING)
        val startedAt = SystemClock.elapsedRealtime()

        job = scope.launch {
            if (!OutputStore.hasSpaceFor(appContext, request.info.sizeBytes)) {
                _state.value = State.Failure(CompressError.STORAGE)
                return@launch
            }
            val temp = OutputStore.tempFile(appContext)
            try {
                val engine = TranscodeEngine(appContext)
                var lastProgress = -1
                val outcome = engine.run(
                    request = request,
                    plan = plan,
                    targetFile = temp,
                    onProgress = { ratio ->
                        val percent = (ratio * 100f).toInt().coerceIn(0, 99)
                        if (percent != lastProgress) {
                            lastProgress = percent
                            val current = _state.value
                            if (current is State.Running) {
                                _state.value = current.copy(
                                    progress = percent,
                                    stage = if (percent >= 93) Stage.FINISHING else Stage.VIDEO
                                )
                            }
                        }
                    },
                    isCancelled = { cancelRequested.get() }
                )

                _state.value = State.Running(
                    request.info, plan, 99, Stage.FINISHING
                )
                val name = OutputStore.targetName(request.info.displayName, request.mode)
                val uri = OutputStore.save(appContext, outcome.file, name)
                val outputBytes = runCatching {
                    appContext.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                }.getOrNull().takeIf { it != null && it > 0 }
                    ?: runCatching { outcome.file.length() }.getOrDefault(0L)

                _state.value = State.Success(
                    CompressResult(
                        outputUri = uri,
                        displayName = name,
                        outputBytes = outputBytes,
                        sourceBytes = request.info.sizeBytes,
                        width = outcome.width,
                        height = outcome.height,
                        rotation = plan.rotation,
                        codec = outcome.codec,
                        durationUs = request.info.durationUs,
                        audioDropped = outcome.audioDropped,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    )
                )
            } catch (cancelled: CompressException) {
                if (cancelled.error == CompressError.CANCELLED) {
                    _state.value = State.Cancelled
                } else {
                    Log.w(TAG, "압축 실패: ${cancelled.error}", cancelled)
                    _state.value = State.Failure(cancelled.error)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "압축 실패", t)
                _state.value = State.Failure(CompressError.ENGINE)
            } finally {
                runCatching { temp.delete() }
                job = null
            }
        }
        return true
    }

    fun cancel() {
        if (!isRunning) return
        cancelRequested.set(true)
    }

    /** 결과 화면을 닫고 처음 상태로 */
    fun reset() {
        if (isRunning) return
        _state.value = State.Idle
    }
}
