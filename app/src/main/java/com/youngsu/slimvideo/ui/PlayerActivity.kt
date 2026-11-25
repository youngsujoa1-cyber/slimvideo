package com.youngsu.slimvideo.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.youngsu.slimvideo.R

/** 앱 안 미리보기 플레이어 — 저장한 결과를 바로 재생해 본다 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URI = "video_uri"

        fun intent(context: Context, uri: Uri): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_URI, uri.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        if (uri == null) {
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg_deep)) }
        setContentView(root)

        val video = VideoView(this).apply {
            setVideoURI(uri)
            setOnPreparedListener { player ->
                player.isLooping = true
                start()
            }
            setMediaController(MediaController(this@PlayerActivity).also { it.setAnchorView(this) })
        }
        root.addView(
            video,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val close = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x99000000.toInt())
            }
            setOnClickListener { finish() }
        }
        close.addView(
            Ui.icon(this, R.drawable.ic_close, 18f, getColor(R.color.text_primary)).apply {
                layoutParams = FrameLayout.LayoutParams(dp(18f), dp(18f), Gravity.CENTER)
            }
        )
        root.addView(
            close,
            FrameLayout.LayoutParams(dp(40f), dp(40f), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(16f)
                topMargin = dp(24f)
            }
        )

        video.setOnErrorListener { _, _, _ ->
            finish()
            true
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
