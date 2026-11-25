package com.youngsu.slimvideo.data

import android.net.Uri
import com.youngsu.slimvideo.compress.VideoInfo

/** 화면을 옮겨 다녀도 마지막으로 고른 동영상을 기억한다 (언어 변경·회전에도 유지) */
object Session {
    var uri: Uri? = null
    var info: VideoInfo? = null

    fun set(uri: Uri?, info: VideoInfo?) {
        this.uri = uri
        this.info = info
    }

    fun clear() {
        uri = null
        info = null
    }
}
