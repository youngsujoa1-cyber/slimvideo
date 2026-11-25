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

/**
 * AAC 가 아닌 오디오(Opus·Vorbis·AMR 등)를 AAC 로 다시 인코딩해 임시 m4a 로 만든다.
 *
 * 임시 파일을 만들면 최종 합치는 단계에서는 "AAC 트랙을 그대로 옮기는" 한 가지 경로만
 * 쓰면 되므로, 합치는 코드가 단순해지고 실패 지점이 줄어든다.
 */
internal object AudioAacConverter {

    private const val TAG = "AudioAacConverter"
    private const val TIMEOUT_US = 10_000L

    /**
     * 성공하면 true. 실패하면 false (부르는 쪽에서 원본 복사 또는 무음으로 진행)
     *
     * @param targetBitrate AAC 출력 비트레이트 (초경량 모드는 96k, 그 밖에는 128k)
     */
    fun convert(
        context: Context,
        uri: Uri,
        targetFile: File,
        targetBitrate: Int,
        isCancelled: () -> Boolean
    ): Boolean {
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
            val audioTrack = findTrack(extractor, "audio/") ?: return false
            extractor.selectTrack(audioTrack)
            val sourceFormat = extractor.getTrackFormat(audioTrack)

            val sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: return false
            decoder = MediaCodec.createDecoderByType(sourceMime).also {
                it.configure(sourceFormat, null, null, 0)
                it.start()
            }

            val info = MediaCodec.BufferInfo()
            var encoderStarted = false
            var muxerStarted = false
            var outputTrack = -1
            var inputEos = false
            var outputEos = false
            var sawDecoderEos = false
            val deadline = System.currentTimeMillis() + 180_000L

            while (!outputEos) {
                if (isCancelled()) return false
                if (System.currentTimeMillis() > deadline) {
                    Log.w(TAG, "오디오 변환이 시간 안에 끝나지 않음")
                    return false
                }

                // 1) 원본에서 압축된 오디오를 디코더로 넣는다
                if (!inputEos) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2) 디코딩된 PCM 을 꺼내 AAC 인코더로 넣는다
                var decoderBusy = false
                while (true) {
                    val outIndex = decoder.dequeueOutputBuffer(info, 0)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            decoderBusy = true
                            break
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val pcmFormat = decoder.outputFormat
                            if (!encoderStarted) {
                                val sampleRate = runCatching {
                                    pcmFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                }.getOrDefault(sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                                val channels = runCatching {
                                    pcmFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                }.getOrDefault(2).coerceIn(1, 2)
                                val aacFormat = MediaFormat.createAudioFormat(
                                    MediaFormat.MIMETYPE_AUDIO_AAC,
                                    normalizeSampleRate(sampleRate),
                                    channels
                                ).apply {
                                    setInteger(
                                        MediaFormat.KEY_AAC_PROFILE,
                                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                                    )
                                    setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate.coerceIn(32_000, 320_000))
                                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                                }
                                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                                    .also {
                                        it.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                                        it.start()
                                    }
                                encoderStarted = true
                            }
                        }
                        outIndex >= 0 -> {
                            val pcm = decoder.getOutputBuffer(outIndex) ?: continue
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (info.size > 0 && !isConfig && encoder != null) {
                                pcm.position(info.offset)
                                pcm.limit(info.offset + info.size)
                                val encIn = waitForInput(encoder)
                                if (encIn >= 0) {
                                    val encBuffer = encoder.getInputBuffer(encIn)
                                    if (encBuffer != null) {
                                        encBuffer.clear()
                                        encBuffer.put(pcm)
                                        encoder.queueInputBuffer(
                                            encIn, 0, info.size, info.presentationTimeUs, 0
                                        )
                                    } else {
                                        Log.w(TAG, "AAC 인코더 입력 버퍼 없음")
                                    }
                                } else {
                                    Log.w(TAG, "AAC 인코더가 입력을 받지 않음 — 이 구간 오디오 누락")
                                }
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawDecoderEos = true
                                if (encoderStarted && encoder != null) {
                                    val encIn = waitForInput(encoder)
                                    if (encIn >= 0) {
                                        encoder.queueInputBuffer(
                                            encIn, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                    }
                                }
                                break
                            }
                        }
                    }
                }

                // 3) AAC 인코더 출력을 임시 m4a 에 쓴다
                if (encoder != null && encoderStarted) {
                    while (true) {
                        val encOut = encoder.dequeueOutputBuffer(info, if (decoderBusy) TIMEOUT_US else 0L)
                        when {
                            encOut == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                            encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (!muxerStarted) {
                                    muxer = MediaMuxer(
                                        targetFile.absolutePath,
                                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                                    )
                                    outputTrack = muxer!!.addTrack(encoder.outputFormat)
                                    muxer!!.start()
                                    muxerStarted = true
                                }
                            }
                            encOut >= 0 -> {
                                val data = encoder.getOutputBuffer(encOut) ?: continue
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    info.size = 0
                                }
                                if (info.size > 0 && muxerStarted) {
                                    data.position(info.offset)
                                    data.limit(info.offset + info.size)
                                    muxer!!.writeSampleData(outputTrack, data, info)
                                }
                                encoder.releaseOutputBuffer(encOut, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    outputEos = true
                                    break
                                }
                            }
                        }
                    }
                }

                if (inputEos && sawDecoderEos && !encoderStarted) {
                    // 디코더가 아무 PCM 도 내지 않았다 — 변환할 것이 없음
                    return false
                }
            }

            return muxerStarted && targetFile.length() > 0
        } catch (t: Throwable) {
            Log.w(TAG, "오디오 변환 실패", t)
            return false
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { if (muxer != null) { muxer.stop() } }
            runCatching { muxer?.release() }
            runCatching { extractor?.release() }
        }
    }

    private fun normalizeSampleRate(rate: Int): Int = when {
        rate <= 0 -> 44_100
        rate > 48_000 -> 48_000
        else -> rate
    }

    /** 인코더 입력 버퍼를 조금 기다려서 받는다 (없으면 PCM 을 잃는다) */
    private fun waitForInput(encoder: MediaCodec): Int {
        val deadline = System.currentTimeMillis() + 200L
        while (true) {
            val index = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) return index
            if (System.currentTimeMillis() > deadline) return -1
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return null
    }
}
