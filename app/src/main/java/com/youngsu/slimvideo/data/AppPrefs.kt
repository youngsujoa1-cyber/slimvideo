package com.youngsu.slimvideo.data

import android.content.Context
import com.youngsu.slimvideo.compress.CompressMath

/**
 * 앱 설정 — 기본 압축 방식, 기본 코덱, 목표 용량 기억.
 *
 * 언어는 AppCompat 이 직접 저장하므로(AppLocalesMetadataHolderService) 여기서 다루지 않는다.
 */
class AppPrefs private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("slim_video_prefs", Context.MODE_PRIVATE)

    /** 기본 압축 방식 (CompressMath.MODE_*) */
    var defaultMode: Int
        get() = prefs.getInt(KEY_MODE, CompressMath.MODE_KEEP_QUALITY)
            .takeIf { it in CompressMath.MODE_KEEP_QUALITY..CompressMath.MODE_TARGET }
            ?: CompressMath.MODE_KEEP_QUALITY
        set(value) = prefs.edit().putInt(KEY_MODE, value).apply()

    /** 기본 출력 코덱 (CompressMath.CODEC_*) */
    var defaultCodec: Int
        get() = prefs.getInt(KEY_CODEC, CompressMath.CODEC_HEVC)
            .takeIf { it == CompressMath.CODEC_HEVC || it == CompressMath.CODEC_H264 }
            ?: CompressMath.CODEC_HEVC
        set(value) = prefs.edit().putInt(KEY_CODEC, value).apply()

    /** 마지막으로 고른 목표 용량(MB) */
    var targetMegabytes: Int
        get() = prefs.getInt(KEY_TARGET_MB, 25)
        set(value) = prefs.edit().putInt(KEY_TARGET_MB, value).apply()

    /** 첫 실행 언어 선택을 이미 마쳤는지 — false 면 앱을 켤 때 언어 선택 화면이 뜬다 */
    var languageChosen: Boolean
        get() = prefs.getBoolean(KEY_LANG_CHOSEN, false)
        set(value) {
            // 화면이 곧바로 다시 만들어져도 값이 남아 있도록 commit 으로 바로 쓴다
            prefs.edit().putBoolean(KEY_LANG_CHOSEN, value).commit()
        }

    /** 기기에서 H.265 인코더를 쓸 수 있는지 (한 번 확인하고 기억) */
    var hevcKnown: Boolean
        get() = prefs.getBoolean(KEY_HEVC_CHECKED, false)
        set(value) = prefs.edit().putBoolean(KEY_HEVC_CHECKED, value).apply()

    var hevcSupported: Boolean
        get() = prefs.getBoolean(KEY_HEVC_SUPPORTED, false)
        set(value) = prefs.edit().putBoolean(KEY_HEVC_SUPPORTED, value).apply()

    companion object {
        private const val KEY_MODE = "default_mode"
        private const val KEY_CODEC = "default_codec"
        private const val KEY_TARGET_MB = "target_mb"
        private const val KEY_LANG_CHOSEN = "language_chosen"
        private const val KEY_HEVC_CHECKED = "hevc_checked"
        private const val KEY_HEVC_SUPPORTED = "hevc_supported"

        @Volatile private var instance: AppPrefs? = null

        fun get(context: Context): AppPrefs =
            instance ?: synchronized(this) {
                instance ?: AppPrefs(context).also { instance = it }
            }
    }
}
