package com.youngsu.slimvideo.ui

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.youngsu.slimvideo.BuildConfig
import com.youngsu.slimvideo.R
import com.youngsu.slimvideo.compress.CompressMath
import com.youngsu.slimvideo.data.AppLanguage
import com.youngsu.slimvideo.data.AppPrefs

/** 설정 — 언어 · 기본 압축 방식 · 기본 코덱 · 광고/개인정보 안내 */
class SettingsScreen(host: MainActivity) : Screen(host) {

    override val view: FrameLayout = FrameLayout(host).apply {
        setBackgroundColor(host.getColor(R.color.bg))
    }

    private val prefs = AppPrefs.get(host)
    private val scroll = ScrollView(host).apply { isFillViewport = true }
    private val col = Ui.column(host)
    private var attachTop = 0

    init {
        scroll.addView(
            col,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        view.addView(
            scroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        render()
    }

    /** 인셋이 바뀌면 여백만 갱신한다 — 레이아웃 도중에 화면을 다시 만들지 않는다 */
    override fun setSystemInsets(top: Int) {
        if (top == attachTop) return
        attachTop = top
        applyContentPadding()
    }

    private fun applyContentPadding() {
        col.setPadding(host.dp(20f), attachTop + host.dp(10f), host.dp(20f), host.dp(32f))
    }

    override fun refresh() {
        render()
    }

    private fun render() {
        // 레이아웃이 진행 중이면 화면을 갈아엎지 않는다 — 아직 측정되지 않은 뷰가 그려져 빈 화면이 된다
        if (view.isInLayout) {
            view.post { render() }
            return
        }
        col.removeAllViews()
        applyContentPadding()

        col.addView(
            Ui.text(host, host.getString(R.string.tab_settings), 21f, host.getColor(R.color.text_primary), bold = true)
        )
        col.addView(Ui.spacing(host, 18f))

        // 언어
        col.addView(Ui.sectionTitle(host, host.getString(R.string.settings_language)))
        col.addView(Ui.spacing(host, 8f))
        col.addView(
            settingCard(
                iconRes = R.drawable.ic_settings,
                title = host.getString(R.string.settings_app_language),
                value = languageLabel()
            ) { showLanguageDialog() }
        )

        col.addView(Ui.spacing(host, 18f))
        col.addView(Ui.sectionTitle(host, host.getString(R.string.settings_defaults)))
        col.addView(Ui.spacing(host, 8f))
        col.addView(
            settingCard(
                iconRes = R.drawable.ic_bolt,
                title = host.getString(R.string.settings_default_mode),
                value = modeLabel(prefs.defaultMode)
            ) { showModeDialog() }
        )
        col.addView(Ui.spacing(host, 8f))
        col.addView(
            settingCard(
                iconRes = R.drawable.ic_compress,
                title = host.getString(R.string.settings_default_codec),
                value = if (prefs.defaultCodec == CompressMath.CODEC_HEVC) "H.265" else "H.264"
            ) { showCodecDialog() }
        )

        col.addView(Ui.spacing(host, 22f))
        col.addView(Ui.sectionTitle(host, host.getString(R.string.settings_ads)))
        col.addView(Ui.spacing(host, 8f))
        col.addView(
            infoCard(host.getString(R.string.settings_ads_body))
        )

        col.addView(Ui.spacing(host, 22f))
        col.addView(Ui.sectionTitle(host, host.getString(R.string.settings_privacy)))
        col.addView(Ui.spacing(host, 8f))
        col.addView(infoCard(host.getString(R.string.settings_privacy_body)))

        col.addView(Ui.spacing(host, 22f))
        col.addView(Ui.sectionTitle(host, host.getString(R.string.settings_about)))
        col.addView(Ui.spacing(host, 8f))
        col.addView(infoCard(host.getString(R.string.settings_engine_body), title = host.getString(R.string.settings_engine)))
        col.addView(Ui.spacing(host, 10f))
        col.addView(
            Ui.text(
                host,
                host.getString(R.string.settings_version) + " " + BuildConfig.VERSION_NAME,
                11.5f,
                host.getColor(R.color.text_tertiary)
            ).apply { gravity = Gravity.CENTER }
        )
    }

    // -------------------------------------------------------------- cards

    private fun settingCard(iconRes: Int, title: String, value: String, onClick: () -> Unit): View {
        val card = Ui.row(host).apply {
            setPadding(host.dp(14f), host.dp(13f), host.dp(14f), host.dp(13f))
            background = Ui.rounded(host, host.getColor(R.color.surface_alt), 18f, host.getColor(R.color.outline))
        }
        val circle = Ui.circle(host, 34f, host.getColor(R.color.accent_dim))
        circle.addView(Ui.icon(host, iconRes, 17f, host.getColor(R.color.accent)).apply {
            layoutParams = FrameLayout.LayoutParams(host.dp(17f), host.dp(17f), Gravity.CENTER)
        })
        card.addView(circle)
        card.addView(
            Ui.text(host, title, 14f, host.getColor(R.color.text_primary), bold = true).apply {
                setPadding(host.dp(12f), 0, host.dp(8f), 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        card.addView(Ui.text(host, value, 12f, host.getColor(R.color.accent), bold = true))
        Ui.clickable(card) { onClick() }
        return card
    }

    private fun infoCard(text: String, title: String? = null): View {
        val card = Ui.column(host).apply {
            setPadding(host.dp(14f), host.dp(13f), host.dp(14f), host.dp(13f))
            background = Ui.rounded(host, host.getColor(R.color.surface), 18f, host.getColor(R.color.outline_soft), ripple = false)
        }
        if (title != null) {
            card.addView(Ui.text(host, title, 13f, host.getColor(R.color.text_primary), bold = true))
            card.addView(Ui.text(host, text, 11.5f, host.getColor(R.color.text_secondary)).apply {
                setPadding(0, host.dp(5f), 0, 0)
                setLineSpacing(host.dp(2f).toFloat(), 1f)
            })
        } else {
            card.addView(Ui.text(host, text, 11.5f, host.getColor(R.color.text_secondary)).apply {
                setLineSpacing(host.dp(2f).toFloat(), 1f)
            })
        }
        return card
    }

    // ------------------------------------------------------------ dialogs

    private fun languageLabel(): String {
        val tag = AppLanguage.currentTag()
        return if (tag.isEmpty()) host.getString(R.string.language_system) else AppLanguage.label(host, tag)
    }

    private fun showLanguageDialog() {
        val tags = AppLanguage.TAGS
        val labels = tags.map { tag -> AppLanguage.label(host, tag) }.toTypedArray()
        val current = tags.indexOf(AppLanguage.currentTag()).coerceAtLeast(0)
        // 제목 + 단일 선택 목록만 쓴다 (설명 문구를 같이 넣으면 목록이 가려질 수 있다)
        MaterialAlertDialogBuilder(host)
            .setTitle(R.string.settings_app_language)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                AppLanguage.apply(tags[which])
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun modeLabel(mode: Int): String = when (mode) {
        CompressMath.MODE_KEEP_QUALITY -> host.getString(R.string.mode_quality_title)
        CompressMath.MODE_BALANCE -> host.getString(R.string.mode_balance_title)
        CompressMath.MODE_LIGHT -> host.getString(R.string.mode_light_title)
        else -> host.getString(R.string.mode_target_title)
    }

    private fun showModeDialog() {
        val modes = intArrayOf(
            CompressMath.MODE_KEEP_QUALITY,
            CompressMath.MODE_BALANCE,
            CompressMath.MODE_LIGHT,
            CompressMath.MODE_TARGET
        )
        val labels = modes.map { modeLabel(it) }.toTypedArray()
        val current = modes.indexOf(prefs.defaultMode).coerceAtLeast(0)
        MaterialAlertDialogBuilder(host)
            .setTitle(R.string.settings_default_mode)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                prefs.defaultMode = modes[which]
                render()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showCodecDialog() {
        val codecs = intArrayOf(CompressMath.CODEC_HEVC, CompressMath.CODEC_H264)
        val labels = arrayOf(
            host.getString(R.string.codec_hevc),
            host.getString(R.string.codec_h264)
        )
        val current = codecs.indexOf(prefs.defaultCodec).coerceAtLeast(0)
        MaterialAlertDialogBuilder(host)
            .setTitle(R.string.settings_default_codec)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                prefs.defaultCodec = codecs[which]
                render()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }
}
