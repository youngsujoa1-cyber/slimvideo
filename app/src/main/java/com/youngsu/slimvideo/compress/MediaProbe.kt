package com.youngsu.slimvideo.compress

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log

/**
 * 동영상 파일을 열어 트랙 정보를 읽는다.
 *
 * MP4 · MOV · MKV · WEBM · 3GP · M4V · AVI 등 컨테이너가 달라도 MediaExtractor 가
 * 읽을 수 있으면 여기서 같은 VideoInfo 로 정리된다.
 */
object MediaProbe {

    private const val TAG = "MediaProbe"

    class ProbeException(val reason: Reason, cause: Throwable? = null) : Exception(cause) {
        enum class Reason { NO_VIDEO_TRACK, UNREADABLE, UNSUPPORTED }
    }

    fun probe(context: Context, uri: Uri): VideoInfo {
        val name = queryName(context, uri)
        val size = querySize(context, uri)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (t: Throwable) {
            Log.w(TAG, "열 수 없는 파일: $uri", t)
            throw ProbeException(ProbeException.Reason.UNREADABLE, t)
        }

        try {
            var videoTrack = -1
            var videoFormat: MediaFormat? = null
            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrack < 0) {
                    videoTrack = i
                    videoFormat = format
                } else if (mime.startsWith("audio/") && audioTrack < 0) {
                    audioTrack = i
                    audioFormat = format
                }
            }

            if (videoTrack < 0 || videoFormat == null) {
                throw ProbeException(ProbeException.Reason.NO_VIDEO_TRACK)
            }

            val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "video/unknown"
            var width = intOr(videoFormat, MediaFormat.KEY_WIDTH, 0)
            var height = intOr(videoFormat, MediaFormat.KEY_HEIGHT, 0)
            var rotation = normalizeRotation(intOr(videoFormat, MediaFormat.KEY_ROTATION, 0))
            var durationUs = longOr(videoFormat, MediaFormat.KEY_DURATION, 0L)
            var fps = fpsOr(videoFormat, 0)
            val bitrate = intOr(videoFormat, MediaFormat.KEY_BIT_RATE, 0)
            var sampleRate = if (audioFormat != null) intOr(audioFormat, MediaFormat.KEY_SAMPLE_RATE, 0) else 0
            var channels = if (audioFormat != null) intOr(audioFormat, MediaFormat.KEY_CHANNEL_COUNT, 0) else 0

            // 일부 컨테이너(MKV·AVI)는 포맷에 해상도/회전/길이가 비어 있다 — 메타데이터로 보완
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                if (width <= 0 || height <= 0) {
                    width = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    height = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                }
                if (rotation == 0) {
                    rotation = normalizeRotation(
                        retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    )
                }
                if (durationUs <= 0) {
                    durationUs = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        .toLong() * 1000L
                }
                if (fps <= 0) {
                    fps = fpsFromCaptureRate(retriever)
                }
                if (sampleRate <= 0 && audioFormat != null) {
                    sampleRate = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                }
            } catch (t: Throwable) {
                Log.d(TAG, "메타데이터 보완 실패", t)
            } finally {
                runCatching { retriever.release() }
            }

            if (width <= 0 || height <= 0) {
                throw ProbeException(ProbeException.Reason.UNSUPPORTED)
            }

            val audioMime = audioFormat?.getString(MediaFormat.KEY_MIME)
            val audioBitrate = if (audioFormat != null) intOr(audioFormat, MediaFormat.KEY_BIT_RATE, 0) else 0
            if (audioMime != null && !codecExists(audioMime, false)) {
                // 디코더가 없는 오디오는 무음 처리보다 원본을 살리기 어렵다 → 무음으로 진행하고 알린다
                Log.w(TAG, "오디오 디코더 없음: $audioMime")
            }

            return VideoInfo(
                uri = uri,
                displayName = name,
                sizeBytes = if (size > 0) size else estimateBytes(bitrate, durationUs),
                width = width,
                height = height,
                rotation = rotation,
                durationUs = if (durationUs > 0) durationUs else 0L,
                videoMime = mime,
                videoBitrate = bitrate,
                fps = if (fps > 0) fps else 30,
                audioMime = audioMime,
                audioSampleRate = if (sampleRate > 0) sampleRate else 44_100,
                audioChannels = if (channels > 0) channels else 2,
                audioBitrate = audioBitrate
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun normalizeRotation(value: Int): Int = CompressMath.normalizeRotation(value)

    /** MediaMetadataRetriever 의 문자열 메타데이터를 정수로 */
    private fun MediaMetadataRetriever.intMetadata(key: Int): Int =
        extractMetadata(key)?.trim()?.toIntOrNull() ?: 0

    /** 파일 이름 — 갤러리가 주지 않으면 URI 마지막 조각을 쓴다 */
    fun queryName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
    }

    fun querySize(context: Context, uri: Uri): Long {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
                    }
                }
        }
        return 0L
    }

    private fun intOr(format: MediaFormat, key: String, fallback: Int): Int =
        runCatching { if (format.containsKey(key)) format.getInteger(key) else fallback }
            .getOrDefault(fallback)

    private fun longOr(format: MediaFormat, key: String, fallback: Long): Long =
        runCatching { if (format.containsKey(key)) format.getLong(key) else fallback }
            .getOrDefault(fallback)

    private fun fpsOr(format: MediaFormat, fallback: Int): Int {
        val fromKey = runCatching {
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE) else 0
        }.getOrDefault(0)
        if (fromKey > 0) return fromKey
        return runCatching {
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() else 0
        }.getOrDefault(fallback).takeIf { it > 0 } ?: fallback
    }

    private fun fpsFromCaptureRate(retriever: MediaMetadataRetriever): Int = runCatching {
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toFloatOrNull()?.toInt() ?: 0
    }.getOrDefault(0)

    private fun estimateBytes(bitrate: Int, durationUs: Long): Long =
        if (bitrate > 0 && durationUs > 0) bitrate.toLong() * durationUs / 8_000_000L else 0L

    /** 해당 MIME 을 다루는 코덱이 기기에 있는지 */
    fun codecExists(mime: String, encoder: Boolean): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder == encoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }.getOrDefault(false)

    /**
     * 해당 MIME 을 다룰 수 있는 코덱 이름 목록 — 하드웨어 코덱을 앞에 둔다.
     * createEncoderByType/createDecoderByType 이 실패하는 기기에서 이름으로 다시 시도할 때 쓴다.
     */
    fun codecNames(mime: String, encoder: Boolean): List<String> = runCatching {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info ->
                info.isEncoder == encoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        infos.sortedBy { info -> if (hardware(info)) 0 else 1 }.map { it.name }.distinct()
    }.getOrDefault(emptyList())

    /** 하드웨어 코덱인지 (API 29 부터 알 수 있다) */
    private fun hardware(info: android.media.MediaCodecInfo): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            true
        }

    fun hevcEncoderAvailable(): Boolean = codecExists("video/hevc", true)

    fun avcEncoderAvailable(): Boolean = codecExists("video/avc", true)
}
