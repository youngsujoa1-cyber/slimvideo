package com.youngsu.slimvideo.compress

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
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
 * v1.0.2 에서 실제 기기 실패를 막기 위해 손본 것
 *  1. **`createInputSurface()` 를 `start()` 전에** 부른다. (MediaCodec 문서·CTS 기준 — start() 뒤에
 *     부르면 기기에 따라 IllegalStateException 이 나고, 그때까지는 아무 영상도 만들지 못했다)
 *  2. 인코더는 ①요청 코덱 이름으로 → ②MediaCodecList 의 하드웨어 코덱 이름으로 → ③16 배수 해상도로
 *     → ④H.264 로 차례로 다시 시도한다. `createEncoderByType` 하나만 믿지 않는다.
 *  3. 디코더도 이름으로 한 번 더 시도하고, 실패하면 어느 단계에서 무엇이 났는지 남긴다.
 *  4. 인코더 입력 프레임의 표시 시각을 SurfaceTexture 의 timestamp 대신 **디코더 PTS** 로 직접 넣고
 *     0 부터 시작하도록 맞춘다. (기기에 따라 timestamp 가 0 이거나 시스템 시각이라 프레임이 버려졌다)
 *  5. 프레임이 화면으로 넘어오지 않으면 3번까지만 기다리고 바로 원인을 알려 준다(8분을 헛돌지 않게).
 *  6. 오디오는 ①다시 인코딩 → ②원본 AAC 그대로 → ③무음 순서로 내려가고, 타임스탬프는 항상 증가하게
 *     보정한다. (역전된 PTS 로 MediaMuxer 가 IllegalStateException 을 내는 경우가 흔하다)
 *
 * 오디오:
 *  - AAC 는 압축된 샘플을 그대로 옮긴다(passthrough) — 화질·음질 손실 없음, 가장 빠름.
 *  - 초경량 모드이거나 AAC 가 아닌 코덱(Opus·Vorbis·AMR)은 AAC 로 다시 인코딩한다.
 *  - 오디오 트랙이 없는 무음 영상은 그대로 영상만 인코딩한다.
 */
class TranscodeEngine(private val context: Context) {

    companion object {
        private const val TAG = "TranscodeEngine"
        private const val TIMEOUT_US = 10_000L
        /** 오디오 샘플을 담아 둘 버퍼 크기 */
        private const val AUDIO_BUFFER_BYTES = 512 * 1024
        /** 디코더가 화면으로 프레임을 보내 주기를 기다리는 시간 */
        private const val FRAME_WAIT_MS = 2_000L
        /** 첫 프레임을 이만큼 연속으로 놓치면 디코더가 화면에 그리지 못하는 것으로 본다 */
        private const val MAX_LEADING_MISSES = 3
        /** EOS 를 보낸 뒤 남은 출력을 기다리는 횟수 (10ms × 400 = 4초) */
        private const val EOS_IDLE_ROUNDS = 400
    }

    class Outcome(
        val file: File,
        val codec: Int,
        val audioDropped: Boolean,
        val width: Int,
        val height: Int
    )

    private class EncoderSession(
        val codec: MediaCodec,
        val surface: Surface,
        val mime: String,
        val width: Int,
        val height: Int
    )

    private class PreparedAudio(
        val extractor: MediaExtractor,
        val format: MediaFormat,
        val tempFile: File?
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
        var session: EncoderSession? = null
        var muxer: MediaMuxer? = null
        var gl: GlBlitter? = null
        var audioTempFile: File? = null
        var audioDropped = false

        try {
            // ---------------------------------------------------------- 1) 원본 열기
            val extractor = try {
                MediaExtractor().apply { setDataSource(context, request.uri, null) }
            } catch (t: Throwable) {
                throw CompressException(CompressError.UNREADABLE, t, "[추출기] ${brief(t)}")
            }
            videoExtractor = extractor

            val videoTrack = findTrack(extractor, "video/")
            if (videoTrack < 0) {
                throw CompressException(CompressError.NO_VIDEO_TRACK, detail = "[추출기] 영상 트랙 없음")
            }
            extractor.selectTrack(videoTrack)
            val sourceFormat = try {
                extractor.getTrackFormat(videoTrack)
            } catch (t: Throwable) {
                throw CompressException(CompressError.UNREADABLE, t, "[추출기] 트랙 정보 ${brief(t)}")
            }

            // ---------------------------------------------------------- 2) 인코더
            val encoderSession = openEncoderWithFallback(plan)
            session = encoderSession
            val encoder = encoderSession.codec
            val outWidth = encoderSession.width
            val outHeight = encoderSession.height
            val usedCodec = if (encoderSession.mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                CompressMath.CODEC_HEVC
            } else {
                CompressMath.CODEC_H264
            }

            // ---------------------------------------------------------- 3) GL 연결
            val blitter = try {
                GlBlitter(
                    outputSurface = encoderSession.surface,
                    viewportWidth = outWidth,
                    viewportHeight = outHeight,
                    textureWidth = info.width,
                    textureHeight = info.height
                )
            } catch (t: Throwable) {
                throw CompressException(CompressError.ENGINE, t, "[GL] ${brief(t)}")
            }
            gl = blitter

            // ---------------------------------------------------------- 4) 오디오
            val audio = prepareAudio(
                request, plan, targetFile.parentFile ?: context.cacheDir, isCancelled
            )
            audioExtractor = audio?.extractor
            audioTempFile = audio?.tempFile
            if (audio == null && plan.hasAudio()) {
                audioDropped = true
                Log.w(TAG, "오디오를 살릴 수 없어 무음으로 진행")
            }
            val copier = audio?.let { AudioTrackCopier(it.extractor, it.format) }

            // ---------------------------------------------------------- 5) 디코더
            decoder = openDecoder(info.videoMime, sourceFormat, blitter.inputSurface)

            // ---------------------------------------------------------- 6) 합치기
            val muxerLocal = try {
                MediaMuxer(targetFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                    setOrientationHint(plan.rotation)
                }
            } catch (t: Throwable) {
                throw CompressException(CompressError.STORAGE, t, "[먹서] ${brief(t)}")
            }
            muxer = muxerLocal
            var muxerStarted = false
            var videoOutTrack = -1
            var audioOutTrack = -1
            var audioFailed = copier == null
            val bufferInfo = MediaCodec.BufferInfo()
            val frameInfo = MediaCodec.BufferInfo()
            var videoEosWritten = false
            var lastVideoPtsUs = -1L
            var lastSourcePtsUs = -1L
            var videoSamplesWritten = 0
            /** 원본 PTS 를 0 부터 시작하도록 옮기는 값 */
            var ptsOffsetUs = -1L
            val frameIntervalUs = if (plan.outputFps > 0) 1_000_000L / plan.outputFps else 0L
            val durationUs = if (info.durationUs > 0) info.durationUs else 1L

            fun startMuxer(videoFormat: MediaFormat) {
                videoOutTrack = try {
                    muxerLocal.addTrack(videoFormat)
                } catch (t: Throwable) {
                    throw CompressException(CompressError.ENGINE, t, "[먹서] 영상 트랙 ${brief(t)}")
                }
                if (copier != null) {
                    audioOutTrack = runCatching { muxerLocal.addTrack(copier.format) }.getOrElse { t ->
                        Log.w(TAG, "오디오 트랙을 합칠 수 없음 — 무음으로 진행", t)
                        audioFailed = true
                        audioDropped = true
                        -1
                    }
                }
                muxerLocal.start()
                muxerStarted = true
                if (!audioFailed) copier?.prime()
            }

            fun writeAudioUntil(videoPtsUs: Long) {
                if (audioFailed || audioOutTrack < 0 || copier == null) return
                try {
                    copier.offsetUs = ptsOffsetUs.coerceAtLeast(0L)
                    copier.writeUntil(muxerLocal, audioOutTrack, videoPtsUs)
                } catch (t: Throwable) {
                    Log.w(TAG, "오디오 샘플을 쓰지 못해 무음으로 진행", t)
                    audioFailed = true
                    audioDropped = true
                }
            }

            fun drainEncoder(endOfStream: Boolean) {
                var idleRounds = 0
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0L)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            if (videoEosWritten) return
                            idleRounds++
                            if (idleRounds > EOS_IDLE_ROUNDS) {
                                Log.w(TAG, "EOS 뒤 인코더 출력이 멈춰 남은 프레임을 포기합니다")
                                return
                            }
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) startMuxer(encoder.outputFormat)
                        }
                        outIndex >= 0 -> {
                            val encoded = encoder.getOutputBuffer(outIndex)
                            var size = bufferInfo.size
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) size = 0
                            if (size > 0 && encoded != null) {
                                if (!muxerStarted) startMuxer(encoder.outputFormat)
                                if (muxerStarted && videoOutTrack >= 0) {
                                    var pts = bufferInfo.presentationTimeUs
                                    if (pts < 0) pts = 0
                                    // 같은 시각이 두 번 나오면 MediaMuxer 가 거부한다
                                    if (pts <= lastVideoPtsUs) pts = lastVideoPtsUs + 1
                                    lastVideoPtsUs = pts
                                    val sample = MediaCodec.BufferInfo().apply {
                                        set(
                                            bufferInfo.offset,
                                            size,
                                            pts,
                                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME
                                        )
                                    }
                                    encoded.position(bufferInfo.offset)
                                    encoded.limit(bufferInfo.offset + size)
                                    writeAudioUntil(pts)
                                    muxerLocal.writeSampleData(videoOutTrack, encoded, sample)
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

            // ------------------------------------------------- 7) 디코드 → 그리기 → 인코드
            var decoderEos = false
            var renderedFrames = 0
            var leadingMisses = 0
            var frameTimeouts = 0
            /** 원본 끝을 디코더에 한 번만 알린다 (EOS 를 두 번 넣으면 예외가 날 수 있다) */
            var inputEosQueued = false
            /** 마지막으로 디코더 출력이 나온 시각 — 응답이 끊기면 오래 기다리지 않는다 */
            var lastActivityMs = android.os.SystemClock.elapsedRealtime()

            while (!decoderEos) {
                if (isCancelled()) throw CompressException(CompressError.CANCELLED)
                if (android.os.SystemClock.elapsedRealtime() - lastActivityMs > 15_000L) {
                    throw CompressException(
                        CompressError.ENGINE,
                        detail = "[디코더] 15초 동안 아무 출력도 없었습니다 ($renderedFrames 프레임 처리)"
                    )
                }

                if (!inputEosQueued) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)
                        if (inputBuffer == null) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEosQueued = true
                        } else {
                            inputBuffer.clear()
                            val size = try {
                                videoExtractor.readSampleData(inputBuffer, 0)
                            } catch (t: Throwable) {
                                throw CompressException(CompressError.UNREADABLE, t, "[추출기] ${brief(t)}")
                            }
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEosQueued = true
                            } else {
                                val sampleTime = videoExtractor.sampleTime.coerceAtLeast(0L)
                                decoder.queueInputBuffer(inIndex, 0, size, sampleTime, 0)
                                videoExtractor.advance()
                            }
                        }
                    }
                } else {
                    // 남은 출력을 기다리는 동안 CPU 를 독점하지 않는다
                    Thread.sleep(2L)
                }

                while (true) {
                    val outIndex = decoder.dequeueOutputBuffer(frameInfo, 0L)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                    if (outIndex < 0) continue

                    val isEos = frameInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    lastActivityMs = android.os.SystemClock.elapsedRealtime()
                    val hasFrame = frameInfo.size > 0
                    var render = hasFrame
                    if (hasFrame && frameIntervalUs > 0 && !isEos && lastSourcePtsUs >= 0) {
                        // 프레임레이트 상한(예: 60fps → 30fps)에 맞춰 건너뛴다
                        if (frameInfo.presentationTimeUs - lastSourcePtsUs < frameIntervalUs * 0.85) {
                            render = false
                        }
                    }

                    if (!hasFrame || !render) {
                        decoder.releaseOutputBuffer(outIndex, false)
                    } else {
                        if (ptsOffsetUs < 0) ptsOffsetUs = frameInfo.presentationTimeUs.coerceAtLeast(0L)
                        val ptsUs = (frameInfo.presentationTimeUs - ptsOffsetUs).coerceAtLeast(0L)
                        decoder.releaseOutputBuffer(outIndex, true)
                        if (blitter.awaitNewFrame(FRAME_WAIT_MS)) {
                            try {
                                blitter.drawFrame(ptsUs * 1000L)
                            } catch (t: Throwable) {
                                throw CompressException(CompressError.ENGINE, t, "[GL] ${brief(t)}")
                            }
                            lastSourcePtsUs = frameInfo.presentationTimeUs
                            renderedFrames++
                            leadingMisses = 0
                            drainEncoder(false)
                        } else {
                            frameTimeouts++
                            if (renderedFrames == 0) {
                                leadingMisses++
                                if (leadingMisses >= MAX_LEADING_MISSES) {
                                    throw CompressException(
                                        CompressError.ENGINE,
                                        detail = "[디코더] 프레임이 인코더로 넘어오지 않았습니다"
                                    )
                                }
                            }
                        }
                    }

                    if (isEos) {
                        decoderEos = true
                        break
                    }
                }

                drainEncoder(false)

                if (info.durationUs > 0) {
                    val progress = ((lastSourcePtsUs.coerceAtLeast(0L).toFloat() / durationUs) * 0.93f)
                    onProgress(progress.coerceIn(0f, 0.93f))
                } else if (renderedFrames > 0) {
                    onProgress(0.5f)
                }
            }

            onProgress(0.95f)

            if (renderedFrames == 0) {
                throw CompressException(
                    CompressError.ENGINE,
                    detail = "[디코더] 그릴 수 있는 프레임이 없었습니다"
                )
            }

            // ---------------------------------------------------------- 8) 마무리
            runCatching { encoder.signalEndOfInputStream() }
            drainEncoder(true)
            if (muxerStarted && !audioFailed && audioOutTrack >= 0 && copier != null) {
                try {
                    copier.offsetUs = ptsOffsetUs.coerceAtLeast(0L)
                    copier.writeAll(muxerLocal, audioOutTrack)
                } catch (t: Throwable) {
                    Log.w(TAG, "남은 오디오를 쓰지 못했습니다", t)
                    audioDropped = true
                }
            }

            if (!muxerStarted || videoSamplesWritten == 0) {
                throw CompressException(
                    CompressError.ENGINE,
                    detail = "[인코더] 인코딩된 프레임이 없습니다 ($renderedFrames 프레임 렌더, $frameTimeouts 대기 실패)"
                )
            }
            // moov 박스를 파일에 확정한다 — 실패하면 결과 파일이 깨진 것이므로 성공으로 넘기지 않는다
            try {
                muxerLocal.stop()
            } catch (t: Throwable) {
                throw CompressException(CompressError.ENGINE, t, "[먹서] ${brief(t)}")
            }
            runCatching { muxerLocal.release() }
            muxer = null

            if (targetFile.length() <= 0) {
                throw CompressException(CompressError.ENGINE, detail = "[먹서] 결과 파일이 비었습니다")
            }

            onProgress(1f)
            return Outcome(targetFile, usedCodec, audioDropped, outWidth, outHeight)
        } catch (cancelled: CompressException) {
            runCatching { targetFile.delete() }
            throw cancelled
        } catch (t: Throwable) {
            Log.w(TAG, "압축 실패", t)
            runCatching { targetFile.delete() }
            throw CompressException(CompressError.ENGINE, t, "[엔진] ${brief(t)}")
        } finally {
            runCatching { session?.surface?.release() }
            runCatching { session?.codec?.stop() }
            runCatching { session?.codec?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { gl?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
            audioTempFile?.let { runCatching { it.delete() } }
        }
    }

    // ------------------------------------------------------------- 인코더

    /**
     * 인코더를 연다. 요청 코덱이 안 되면 H.264 로 내려가고, 해상도도 16 배수로 다시 시도한다.
     * `createInputSurface()` 는 반드시 `start()` **전에** 부른다.
     */
    private fun openEncoderWithFallback(plan: CompressMath.Plan): EncoderSession {
        val hevc = MediaFormat.MIMETYPE_VIDEO_HEVC
        val avc = MediaFormat.MIMETYPE_VIDEO_AVC
        val mimes = if (plan.codec == CompressMath.CODEC_HEVC && MediaProbe.codecExists(hevc, true)) {
            listOf(hevc, avc)
        } else {
            listOf(avc)
        }
        val errors = StringBuilder()
        for (mime in mimes) {
            try {
                return openEncoder(mime, plan)
            } catch (t: Throwable) {
                Log.w(TAG, "인코더 열기 실패 ($mime)", t)
                errors.append(mime.substringAfter('/')).append(": ").append(brief(t)).append(" / ")
            }
        }
        throw CompressException(CompressError.NO_ENCODER, detail = "[인코더] $errors")
    }

    private fun openEncoder(mime: String, plan: CompressMath.Plan): EncoderSession {
        val sizes = ArrayList<Pair<Int, Int>>()
        sizes.add(plan.outputWidth to plan.outputHeight)
        val alignedW = (plan.outputWidth / 16) * 16
        val alignedH = (plan.outputHeight / 16) * 16
        if (alignedW >= 16 && alignedH >= 16 && (alignedW != plan.outputWidth || alignedH != plan.outputHeight)) {
            sizes.add(alignedW to alignedH)
        }
        // null = createEncoderByType, 나머지는 이름으로
        val names = ArrayList<String?>()
        names.add(null)
        names.addAll(MediaProbe.codecNames(mime, true))

        var last: Throwable? = null
        for ((width, height) in sizes) {
            for (name in names) {
                var codec: MediaCodec? = null
                try {
                    codec = if (name == null) {
                        MediaCodec.createEncoderByType(mime)
                    } else {
                        MediaCodec.createByCodecName(name)
                    }
                    val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, plan.videoBitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, plan.outputFps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                        )
                    }
                    codec.configure(
                        format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
                    )
                    // ★ start() 전에 입력 Surface 를 받는다 (문서·CTS 기준 순서)
                    val surface = codec.createInputSurface()
                    codec.start()
                    Log.i(
                        TAG,
                        "인코더 ${codec.name} $width×$height $mime ${plan.videoBitrate}bps ${plan.outputFps}fps"
                    )
                    return EncoderSession(codec, surface, mime, width, height)
                } catch (t: Throwable) {
                    last = t
                    Log.w(TAG, "인코더 시도 실패 ${name ?: mime} $width×$height", t)
                    runCatching { codec?.release() }
                }
            }
        }
        throw last ?: IllegalStateException("인코더를 열 수 없습니다")
    }

    // ------------------------------------------------------------- 디코더

    private fun openDecoder(mime: String, format: MediaFormat, surface: Surface): MediaCodec {
        var firstError: Throwable? = null
        try {
            return MediaCodec.createDecoderByType(mime).apply {
                configure(format, surface, null, 0)
                start()
            }
        } catch (t: Throwable) {
            firstError = t
            Log.w(TAG, "디코더 생성 실패 ($mime) — 이름으로 재시도", t)
        }
        for (name in MediaProbe.codecNames(mime, false)) {
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createByCodecName(name)
                codec.configure(format, surface, null, 0)
                codec.start()
                Log.i(TAG, "디코더 $name")
                return codec
            } catch (t: Throwable) {
                Log.w(TAG, "디코더 시도 실패 $name", t)
                runCatching { codec?.release() }
            }
        }
        throw CompressException(
            CompressError.UNSUPPORTED_CODEC,
            firstError,
            "[디코더] $mime ${firstError?.let { brief(it) } ?: ""}"
        )
    }

    // --------------------------------------------------------------- 오디오

    /**
     * 오디오 원본을 준비한다.
     *
     * 1) 계획이 "다시 인코딩" 이면 AAC 로 다시 만들어 임시 m4a 를 쓴다.
     * 2) 실패했거나 원본이 이미 AAC 면 원본 트랙을 그대로 옮긴다.
     * 3) 둘 다 안 되면 null — 부르는 쪽이 무음으로 진행한다.
     */
    private fun prepareAudio(
        request: CompressRequest,
        plan: CompressMath.Plan,
        dir: File,
        isCancelled: () -> Boolean
    ): PreparedAudio? {
        if (!plan.hasAudio()) return null

        if (plan.audioReencode) {
            val temp = File(dir, "audio_${System.currentTimeMillis()}.m4a")
            val ok = runCatching {
                AudioAacConverter.convert(context, request.uri, temp, plan.audioBitrate, isCancelled)
            }.getOrDefault(false)
            if (ok && temp.length() > 0) {
                val extractor = runCatching {
                    MediaExtractor().apply { setDataSource(temp.absolutePath) }
                }.getOrNull()
                val track = if (extractor != null) findTrack(extractor, "audio/") else -1
                if (extractor != null && track >= 0) {
                    extractor.selectTrack(track)
                    return PreparedAudio(extractor, extractor.getTrackFormat(track), temp)
                }
                runCatching { extractor?.release() }
            }
            runCatching { temp.delete() }
            Log.w(TAG, "오디오 재인코딩 실패 — 원본 그대로 옮기는 방법으로 대체")
        }

        val extractor = runCatching {
            MediaExtractor().apply { setDataSource(context, request.uri, null) }
        }.getOrNull() ?: return null
        val track = findTrack(extractor, "audio/")
        if (track < 0) {
            extractor.release()
            return null
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)
        if (mime != MediaFormat.MIMETYPE_AUDIO_AAC) {
            extractor.release()
            Log.w(TAG, "그대로 옮길 수 없는 오디오 코덱: $mime")
            return null
        }
        return PreparedAudio(extractor, format, null)
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    /** 예외를 화면에 보여 줄 짧은 문장으로 */
    private fun brief(t: Throwable): String {
        val message = t.message?.replace('\n', ' ')?.take(120).orEmpty()
        val name = t.javaClass.simpleName
        return if (message.isBlank()) name else "$name: $message"
    }

    /**
     * AAC 샘플을 시간 순서대로 합치기에 넣는 도우미.
     *
     * 영상 프레임이 나올 때마다 그 시각까지의 오디오를 먼저 쓰고, 마지막에 남은 것을 모두 쓴다.
     * 원본 PTS 에서 [offsetUs] 를 빼 0 부터 시작하게 하고, 같은 시각이 두 번 나오지 않게 한다.
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
        private var currentFlags = 0
        private var primed = false
        private var lastWrittenUs = -1L

        /** 원본 PTS 를 0 부터 시작하도록 빼는 값 */
        var offsetUs = 0L

        fun prime() {
            if (primed) return
            primed = true
            hasCurrent = advance()
        }

        fun writeUntil(muxer: MediaMuxer, track: Int, ptsUs: Long) {
            while (hasCurrent && (currentTimeUs - offsetUs) <= ptsUs) {
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
            var pts = (currentTimeUs - offsetUs).coerceAtLeast(0L)
            if (pts <= lastWrittenUs) pts = lastWrittenUs + 1
            lastWrittenUs = pts
            buffer.position(0)
            buffer.limit(currentSize)
            info.set(0, currentSize, pts, currentFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
            muxer.writeSampleData(track, buffer, info)
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
            currentTimeUs = extractor.sampleTime.coerceAtLeast(0L)
            currentFlags = runCatching { extractor.sampleFlags }.getOrDefault(0)
            return true
        }
    }
}
