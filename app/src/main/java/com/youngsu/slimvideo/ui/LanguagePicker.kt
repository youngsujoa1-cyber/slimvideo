package com.youngsu.slimvideo.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.youngsu.slimvideo.R
import com.youngsu.slimvideo.data.AppLanguage

/**
 * 첫 실행 언어 선택 화면.
 *
 * 앱을 처음 켰을 때 한 번만 뜬다. 목록에서 하나를 누르면 **그 자리에서 바로** 그 언어로 바뀌고
 * (AppCompat 로케일), 다시는 뜨지 않는다. 이후에는 `설정 → 앱 언어` 에서 언제든 바꾼다.
 *
 * 대화상자 대신 화면 전체를 덮는 뷰로 만들어 6개 항목이 잘리거나 가려지지 않게 했다.
 */
class LanguagePicker(host: MainActivity, private val onPick: (String) -> Unit) {

    val view: FrameLayout = FrameLayout(host).apply {
        setBackgroundColor(host.getColor(R.color.bg))
        isClickable = true
        isFocusable = true
    }

    init {
        val scroll = ScrollView(host).apply { isFillViewport = true }
        val col = Ui.column(host).apply {
            setPadding(host.dp(22f), host.dp(14f), host.dp(22f), host.dp(24f))
        }
        scroll.addView(
            col,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val logo = ImageView(host).apply {
            setImageResource(R.drawable.hero_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(host.dp(60f), host.dp(60f))
        }
        col.addView(logo)
        col.addView(Ui.spacing(host, 16f))
        col.addView(
            Ui.text(
                host,
                host.getString(R.string.choose_language_title),
                23f,
                host.getColor(R.color.text_primary),
                bold = true
            )
        )
        col.addView(
            Ui.text(
                host,
                host.getString(R.string.choose_language_hint),
                12.5f,
                host.getColor(R.color.text_secondary)
            ).apply { setPadding(0, host.dp(8f), 0, 0) }
        )

        col.addView(Ui.spacing(host, 20f))

        val current = AppLanguage.currentTag()
        AppLanguage.TAGS.forEach { tag ->
            col.addView(buildRow(host, tag, selected = tag == current))
            col.addView(Ui.spacing(host, 9f))
        }

        col.addView(Ui.spacing(host, 6f))
        col.addView(
            Ui.text(
                host,
                host.getString(R.string.choose_language_note),
                11.5f,
                host.getColor(R.color.text_tertiary)
            ).apply { gravity = android.view.Gravity.CENTER }
        )

        view.addView(
            scroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    private fun buildRow(host: MainActivity, tag: String, selected: Boolean): View {
        val row = Ui.row(host).apply {
            setPadding(host.dp(16f), host.dp(15f), host.dp(14f), host.dp(15f))
            background = Ui.rounded(
                host,
                if (selected) host.getColor(R.color.accent_dim) else host.getColor(R.color.surface_alt),
                18f,
                if (selected) host.getColor(R.color.accent) else host.getColor(R.color.outline)
            )
        }
        row.addView(
            Ui.text(
                host,
                AppLanguage.label(host, tag),
                15.5f,
                if (selected) host.getColor(R.color.text_primary) else host.getColor(R.color.text_primary),
                bold = selected
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val mark = ImageView(host).apply {
            setImageResource(if (selected) R.drawable.ic_check else R.drawable.ic_arrow_down)
            imageTintList = android.content.res.ColorStateList.valueOf(
                if (selected) host.getColor(R.color.accent) else host.getColor(R.color.text_tertiary)
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (!selected) rotation = -90f
            layoutParams = LinearLayout.LayoutParams(host.dp(18f), host.dp(18f))
        }
        row.addView(mark)
        Ui.clickable(row) { onPick(tag) }
        return row
    }
}
