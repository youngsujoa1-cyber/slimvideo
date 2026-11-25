package com.youngsu.slimvideo.compress

import android.net.Uri
import java.io.File

/** 압축할 원본 동영상의 정보 */
data class VideoInfo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    /** 휴대폰이 저장하고 있는 방향의 해상도 (회전 적용 전) */
    val width: Int,
    val height: Int,
    /** 0 / 90 / 180 / 270 */
    val rotation: Int,
    val durationUs: Long,
    val videoMime: String,
    val videoBitrate: Int,
    val fps: Int,
    val audioMime: String?,
    val audioSampleRate: Int,
    val audioChannels: Int
) {
    private val display = CompressMath.displaySize(width, height, rotation)

    /** 사용자가 화면에서 보는 가로/세로 */
    val displayWidth: Int get() = display[0]
    val displayHeight: Int get() = display[1]

    val hasAudio: Boolean get() = audioMime != null

    val videoCodecLabel: String
        get() = when (videoMime.lowercase()) {
            "video/avc" -> "H.264"
            "video/hevc" -> "H.265"
            "video/x-vnd.on2.vp8" -> "VP8"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/av01" -> "AV1"
            "video/mp4v-es" -> "MPEG-4"
            "video/3gpp" -> "H.263"
            else -> videoMime.substringAfter('/').uppercase()
        }

    val audioCodecLabel: String
        get() = when (audioMime?.lowercase()) {
            null -> ""
            "audio/mp4a-latm" -> "AAC"
            "audio/opus" -> "Opus"
            "audio/vorbis" -> "Vorbis"
            "audio/3gpp" -> "AMR"
            "audio/raw" -> "PCM"
            else -> audioMime.substringAfter('/').uppercase()
        }

    val containerLabel: String
        get() {
            val lower = displayName.lowercase()
            val ext = File(displayName).extension.uppercase()
            return if (ext.isNotBlank() && lower.contains('.')) ext else "VIDEO"
        }

    val resolutionLabel: String get() = "$displayWidth × $displayHeight"
}
