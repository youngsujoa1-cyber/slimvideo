package com.youngsu.slimvideo.compress

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * 하드웨어 디코더 → GL → 하드웨어 인코더 → MediaMuxer 로 동영상을 다시 인코딩한다.
 *
 * 외부 FFmpeg 없이 Android 기본 API 만 쓰므로:
 *  - 추가 용량이 없고(앱이 가볍다),
 *  - 기기의 하드웨어 인코더를 써서 배터리·속도가 좋고,
 *  - H.264/H.265/VP8/VP9 등 기기가 디코딩할 수 있는 원본은 모두 받는다.
 *
 * 오디오:
 *  - AAC 는 압축된 샘플을 그대로 옮긴다(passthrough) — 화질·음질 손실 없음, 가장 빠름.
 *  - 그 밖의 코덱(Opus·Vorbis·AMR)은 AAC 128kbps 로 다시 인코딩한다.
 *  - 오디오 트랙이 없는 무음 영상은 그대로 영상만 인코딩한다.
 */
class TranscodeEngine(private val context: Context) {

    companion object {
        private const val TAG = "TranscodeEngine"
        private const val TIMEOUT_US = 10_000L
        /** 오디오 샘플을 담아 둘 버퍼 크기 */
        private const val AUDIO_BUFFER_BYTES = 512 * 1024
    }

    class Outcome(
        val file: File,
        val codec: Int,
        val audioDropped: Boolean,
        val width: Int,
        val height: Int
    )

    /**
     * @param onProgress 0f..1f
     * @param isCancelled 매 프레임 확인 — true 면 CompressException(CANCELLED)
     */
    fun run(
        request: CompressRequest,
        plan: CompressMath.Plan,
        targetFile: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Outcome {
        val info = request.info
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var gl: GlBlitter? = null
        var audioCopier: AudioTrackCopier? = null
        var audioTempFile: File? = null
        var encoderInputSurface: android.view.Surface? = null
        var audioDropped = false
        var usedCodec = plan.codec

        try {
            videoExtractor = MediaExtractor().apply { setDataSource(context, request.uri, null) }
            val videoTrack = findTrack(videoExtractor, "video/")
                ?: throw CompressException(CompressError.NO_VIDEO_TRACK)
            videoExtractor.selectTrack(videoTrack)
            val sourceFormat = videoExtractor.getTrackFormat(videoTrack)

            // 1) 인코더 준비 (H.265 요청인데 기기에 없으면 H.264 로)
            val encoderMime = resolveEncoderMime(plan.codec)
            usedCodec = if (encoderMime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                CompressMath.CODEC_HEVC
            } else {
                CompressMath.CODEC_H264
            }
            // 기기 인코더가 16 정렬을 요구하는 경우가 있어, 실패하면 16 단위로 맞춰 한 번 더 시도한다
            var outWidth = plan.outputWidth
            var outHeight = plan.outputHeight

            fun buildEncoderFormat(width: Int, height: Int): MediaFormat =
                MediaFormat.createVideoFormat(encoderMime, width, height).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, plan.videoBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, plan.outputFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                }

            fun startEncoder(width: Int, height: Int): MediaCodec {
                val codec = MediaCodec.createEncoderByType(encoderMime)
                codec.configure(
                    buildEncoderFormat(width, height), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
                )
                codec.start()
                return codec
            }

            encoder = try {
                startEncoder(outWidth, outHeight)
            } catch (first: Throwable) {
                runCatching { encoder?.release() }
                encoder = null
                val alignedW = align16(outWidth)
                val alignedH = align16(outHeight)
                if (alignedW == outWidth && alignedH == outHeight) {
                    throw CompressException(CompressError.NO_ENCODER, first)
                }
                try {
                    startEncoder(alignedW, alignedH).also {
                        outWidth = alignedW
                        outHeight = alignedH
                    }
                } catch (second: Throwable) {
                    throw CompressException(CompressError.NO_ENCODER, second)
                }
            }
            encoderInputSurface = encoder.createInputSurface()
            val inputSurface = encoderInputSurface!!

            // 2) 디코더 → GL → 인코더 Surface
            gl = GlBlitter(
                inputSurface,
                viewportWidth = outWidth,
                viewportHeight = outHeight,
                textureWidth = info.width,
                textureHeight = info.height
            )

            val decoderMime = info.videoMime
            decoder = try {
                MediaCodec.createDecoderByType(decoderMime).apply {
                    configure(sourceFormat, gl.inputSurface, null, 0)
                    start()
                }
            } catch (t: Throwable) {
                throw CompressException(CompressError.UNSUPPORTED_CODEC, t)
            }

            // 3) 오디오 준비
            var audioFormat: MediaFormat? = null
            var audioSourceExtractor: MediaExtractor? = null
            if (plan.audioStrategy != AudioStrategy.NONE) {
                when (plan.audioStrategy) {
                    AudioStrategy.COPY -> {
                        audioSourceExtractor = MediaExtractor().apply {
                            setDataSource(context, request.uri, null)
                        }
                        val audioTrack = findTrack(audioSourceExtractor, "audio/")
                        if (audioTrack >= 0) {
                            audioSourceExtractor.selectTrack(audioTrack)
                            audioFormat = audioSourceExtractor.getTrackFormat(audioTrack)
                        } else {
                            audioSourceExtractor.release()
                            audioSourceExtractor = null
                        }
                    }
                    AudioStrategy.TRANSCODE -> {
                        val temp = File(targetFile.parentFile, "audio_${System.currentTimeMillis()}.m4a")
                        val ok = AudioAacConverter.convert(
                            context, request.uri, temp, isCancelled
                        )
                        if (ok) {
                            audioTempFile = temp
                            audioSourceExtractor = MediaExtractor().apply {
                                setDataSource(temp.absolutePath)
                            }
                            val audioTrack = findTrack(audioSourceExtractor, "audio/")
                            if (audioTrack >= 0) {
                                audioSourceExtractor.selectTrack(audioTrack)
                                audioFormat = audioSourceExtractor.getTrackFormat(audioTrack)
                            } else {
                                audioSourceExtractor.release()
                                audioSourceExtractor = null
                            }
                        } else {
                            runCatching { temp.delete() }
                            audioDropped = true
                            Log.w(TAG, "오디오를 AAC 로 바꾸지 못해 무음으로 진행")
                        }
                    }
                    AudioStrategy.NONE -> Unit
                }
            }
            audioExtractor = audioSourceExtractor
            if (audioFormat != null) {
                audioCopier = AudioTrackCopier(audioSourceExtractor!!, audioFormat)
            }

            // 4) 합치기 시작
            muxer = MediaMuxer(targetFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                setOrientationHint(plan.rotation)
            }
            val muxerLocal = muxer
            var muxerStarted = false
            var videoOutTrack = -1
            var audioOutTrack = -1
            val bufferInfo = MediaCodec.BufferInfo()
            var videoEosWritten = false
            var decoderEos = false
            var lastRenderedPtsUs = -1L
            var lastVideoPtsUs = 0L
            var videoSamplesWritten = 0
            val frameIntervalUs = if (plan.outputFps > 0) 1_000_000L / plan.outputFps else 0L
            val durationUs = if (info.durationUs > 0) info.durationUs else 1L

            fun drainEncoder(endOfStream: Boolean) {
                var idleRounds = 0
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            // EOS 를 보낸 뒤에는 EOS 플래그가 올 때까지 기다린다 (기기가 응답하지 않으면 포기)
                            if (videoEosWritten) return
                            idleRounds++
                            if (idleRounds > 300) return
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = encoder.outputFormat
                            videoOutTrack = muxerLocal.addTrack(outFormat)
                            if (audioCopier != null) {
                                audioOutTrack = runCatching {
                                    muxerLocal.addTrack(audioCopier!!.format)
                                }.getOrElse {
                                    Log.w(TAG, "오디오 트랙을 합칠 수 없음 — 무음으로 진행", it)
                                    audioCopier = null
                                    audioDropped = true
                                    -1
                                }
                            }
                            muxerLocal.start()
                            muxerStarted = true
                            audioCopier?.prime()
                        }
                        outIndex >= 0 -> {
                            val encoded = encoder.getOutputBuffer(outIndex)
                            if (encoded != null) {
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    bufferInfo.size = 0
                                }
                                if (bufferInfo.size > 0 && muxerStarted) {
                                    encoded.position(bufferInfo.offset)
                                    encoded.limit(bufferInfo.offset + bufferInfo.size)
                                    lastVideoPtsUs = bufferInfo.presentationTimeUs
                                    // 영상 시간에 맞춰 오디오를 끼워 넣는다 (재생 호환성)
                                    audioCopier?.let { copier ->
                                        if (audioOutTrack >= 0) {
                                            copier.writeUntil(muxerLocal, audioOutTrack, lastVideoPtsUs)
                                        }
                                    }
                                    muxerLocal.writeSampleData(videoOutTrack, encoded, bufferInfo)
                                    videoSamplesWritten++
                                }
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                videoEosWritten = true
                                return
                            }
                        }
                    }
                }
            }

            // 디코더 입력 → 출력(렌더) → 인코더 출력 순환
            while (!decoderEos) {
                if (isCancelled()) throw CompressException(CompressError.CANCELLED)

                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inIndex)
                    if (inputBuffer == null) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        inputBuffer.clear()
                        val size = videoExtractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, videoExtractor.sampleTime, 0)
                            videoExtractor.advance()
                        }
                    }
                }

                while (true) {
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    if (outIndex < 0) continue

                    val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val hasFrame = bufferInfo.size > 0
                    var render = hasFrame
                    if (hasFrame && frameIntervalUs > 0 && !isEos && lastRenderedPtsUs >= 0) {
                        // 프레임레이트 상한(예: 60fps → 30fps)에 맞춰 건너뛴다
                        if (bufferInfo.presentationTimeUs - lastRenderedPtsUs < frameIntervalUs * 0.85) {
                            render = false
                        }
                    }
                    if (hasFrame) {
                        decoder.releaseOutputBuffer(outIndex, render)
                        if (render) {
                            lastRenderedPtsUs = bufferInfo.presentationTimeUs
                            if (gl.awaitNewFrame(200)) {
                                runCatching { gl.drawFrame() }
                                drainEncoder(false)
                            }
                        }
                    } else {
                        decoder.releaseOutputBuffer(outIndex, false)
                    }

                    if (isEos) {
                        decoderEos = true
                        break
                    }
                }

                drainEncoder(false)

                if (info.durationUs > 0) {
                    val progress = ((lastRenderedPtsUs.coerceAtLeast(0L).toFloat() / durationUs) * 0.93f)
                    onProgress(progress.coerceIn(0f, 0.93f))
                }
            }

            onProgress(0.95f)

            // 5) 마무리 — 인코더 EOS 후 남은 오디오까지 모두 쓴다
            runCatching { encoder.signalEndOfInputStream() }
            drainEncoder(true)
            if (muxerStarted) {
                audioCopier?.let { copier ->
                    if (audioOutTrack >= 0) copier.writeAll(muxerLocal, audioOutTrack)
                }
            }

            if (!muxerStarted || videoSamplesWritten == 0) {
                throw CompressException(CompressError.ENGINE, detail = "합치지 못했습니다")
            }
            // moov 박스를 파일에 확정한다 — 실패하면 결과 파일이 깨진 것이므로 성공으로 넘기지 않는다
            try {
                muxerLocal.stop()
            } catch (t: Throwable) {
                throw CompressException(CompressError.ENGINE, t, "moov 박스를 쓰지 못했습니다")
            }
            runCatching { muxerLocal.release() }
            muxer = null

            if (targetFile.length() <= 0) {
                throw CompressException(CompressError.ENGINE, detail = "결과 파일이 비었습니다")
            }

            onProgress(1f)
            return Outcome(targetFile, usedCodec, audioDropped, outWidth, outHeight)
        } catch (cancelled: CompressException) {
            runCatching { targetFile.delete() }
            throw cancelled
        } catch (t: Throwable) {
            Log.w(TAG, "압축 실패", t)
            runCatching { targetFile.delete() }
            throw CompressException(CompressError.ENGINE, t)
        } finally {
            runCatching { encoderInputSurface?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { gl?.release() }
            runCatching { if (muxer != null) muxer.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
            audioTempFile?.let { runCatching { it.delete() } }
        }
    }

    /** 인코더가 좋아하는 16 배수로 내림 (최소 16) */
    private fun align16(value: Int): Int = ((value / 16) * 16).coerceAtLeast(16)

    /** 요청 코덱이 기기에 있는지 확인하고 실제로 쓸 MIME 을 정한다 */
    private fun resolveEncoderMime(codec: Int): String {
        val hevc = MediaFormat.MIMETYPE_VIDEO_HEVC
        val avc = MediaFormat.MIMETYPE_VIDEO_AVC
        return if (codec == CompressMath.CODEC_HEVC && MediaProbe.codecExists(hevc, true)) {
            hevc
        } else {
            if (!MediaProbe.codecExists(avc, true)) {
                throw CompressException(CompressError.NO_ENCODER)
            }
            avc
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    /**
     * AAC 샘플을 시간 순서대로 합치기에 넣는 도우미.
     *
     * 영상 프레임이 나올 때마다 그 시각까지의 오디오를 먼저 쓰고, 마지막에 남은 것을 모두 쓴다.
     */
    private class AudioTrackCopier(
        private val extractor: MediaExtractor,
        val format: MediaFormat
    ) {
        private val buffer: ByteBuffer = ByteBuffer.allocateDirect(AUDIO_BUFFER_BYTES)
        private val info = MediaCodec.BufferInfo()
        private var hasCurrent = false
        private var currentTimeUs = 0L
        private var currentSize = -1
        private var primed = false

        fun prime() {
            if (primed) return
            primed = true
            hasCurrent = advance()
        }

        fun writeUntil(muxer: MediaMuxer, track: Int, ptsUs: Long) {
            while (hasCurrent && currentTimeUs <= ptsUs) {
                writeCurrent(muxer, track)
                hasCurrent = advance()
            }
        }

        fun writeAll(muxer: MediaMuxer, track: Int) {
            while (hasCurrent) {
                writeCurrent(muxer, track)
                hasCurrent = advance()
            }
        }

        private fun writeCurrent(muxer: MediaMuxer, track: Int) {
            if (currentSize <= 0) return
            buffer.position(0)
            buffer.limit(currentSize)
            info.set(0, currentSize, currentTimeUs, 0)
            runCatching { muxer.writeSampleData(track, buffer, info) }
        }

        private fun advance(): Boolean {
            if (currentSize >= 0) extractor.advance()
            buffer.clear()
            val size = runCatching { extractor.readSampleData(buffer, 0) }.getOrDefault(-1)
            if (size < 0) {
                currentSize = -1
                return false
            }
            currentSize = size
            currentTimeUs = extractor.sampleTime
            return true
        }
    }
}
