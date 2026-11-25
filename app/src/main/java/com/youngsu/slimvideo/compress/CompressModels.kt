package com.youngsu.slimvideo.compress

import android.net.Uri

/** 압축 요청 — 무엇을, 어떤 방식으로 */
data class CompressRequest(
    val uri: Uri,
    val info: VideoInfo,
    val mode: Int,
    val codec: Int,
    val targetBytes: Long
)

/** 압축 결과 */
data class CompressResult(
    val outputUri: Uri,
    val displayName: String,
    val outputBytes: Long,
    val sourceBytes: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val codec: Int,
    val durationUs: Long,
    val audioDropped: Boolean,
    val elapsedMs: Long
) {
    val reductionPercent: Double get() = CompressMath.reductionPercent(sourceBytes, outputBytes)
    val savedBytes: Long get() = (sourceBytes - outputBytes).coerceAtLeast(0L)
    val resolutionLabel: String
        get() {
            val display = CompressMath.displaySize(width, height, rotation)
            return "${display[0]} × ${display[1]}"
        }
    val codecLabel: String get() = if (codec == CompressMath.CODEC_HEVC) "H.265" else "H.264"
}

/** 압축 실패 이유 — 화면 문구로 바뀐다 */
enum class CompressError {
    NO_VIDEO_TRACK,
    UNREADABLE,
    UNSUPPORTED_CODEC,
    NO_ENCODER,
    STORAGE,
    ENGINE,
    CANCELLED
}

class CompressException(
    val error: CompressError,
    cause: Throwable? = null,
    val detail: String? = null
) : Exception(detail ?: error.name, cause)
