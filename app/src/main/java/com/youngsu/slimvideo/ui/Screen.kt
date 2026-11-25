package com.youngsu.slimvideo.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.activity.result.contract.ActivityResultContract

/** 하단 탭 하나가 보여 주는 화면 */
abstract class Screen(protected val host: MainActivity) {

    abstract val view: View

    /** 탭이 다시 보일 때 최신 내용으로 */
    open fun refresh() {}

    /** 다른 탭으로 옮겨 갈 때 */
    open fun onHidden() {}

    /** 상태바 높이만큼 위를 비켜 준다 */
    open fun setSystemInsets(top: Int) {}
}

/**
 * 동영상 고르기.
 *
 * 갤러리가 주는 "video/*" 뿐 아니라 파일 앱에서 MOV · MKV · WEBM · 3GP · AVI 를
 * 직접 고를 수 있도록 MIME 목록을 함께 넘긴다. 기기에 따라 목록을 무시하는 앱도 있어
 * type 은 "*/*" 로 두고 EXTRA_MIME_TYPES 로 걸러지게 한다.
 */
class PickVideoContract : ActivityResultContract<Unit, Uri?>() {

    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "video/*",
                    "video/mp4",
                    "video/quicktime",
                    "video/x-matroska",
                    "video/webm",
                    "video/3gpp",
                    "video/3gpp2",
                    "video/x-msvideo",
                    "video/x-m4v",
                    "video/mp2t",
                    "application/x-matroska"
                )
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}
