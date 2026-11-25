package com.youngsu.slimvideo.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.youngsu.slimvideo.R
import com.youngsu.slimvideo.compress.CompressError
import com.youngsu.slimvideo.compress.CompressManager
import com.youngsu.slimvideo.compress.CompressMath
import com.youngsu.slimvideo.compress.CompressRequest
import com.youngsu.slimvideo.compress.CompressResult
import com.youngsu.slimvideo.compress.MediaProbe
import com.youngsu.slimvideo.compress.VideoInfo
import com.youngsu.slimvideo.data.AppPrefs
import com.youngsu.slimvideo.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * 압축 탭.
 *
 * 상태는 [CompressManager] 하나만 본다 — 고르기 → 옵션 → 진행 → 결과.
 */
class HomeScreen(host: MainActivity) : Screen(host) {

    override val view: FrameLayout = FrameLayout(host).apply {
        setBackgroundColor(host.getColor(R.color.bg))
    }

    private val prefs = AppPrefs.get(host)
    private val io = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mode = prefs.defaultMode
    private var codec = prefs.defaultCodec
    private var targetMb = prefs.targetMegabytes
    private var hevcAvailable: Boolean? = null
    private var loadingThumb = false
    private val thumbCache = HashMap<Uri, Bitmap?>()
    private var lastRenderedKey: String? = null
    private var attachTop = 0

    // 진행률은 화면을 다시 만들지 않고 숫자만 바꾼다
    private var percentLabel: TextView? = null
    private var stageLabel: TextView? = null
    private var progressBar: ProgressBar? = null

    init {
        checkHevcSupport()
        host.lifecycleScope.launch {
            CompressManager.state.collect { render() }
        }
    }

    // ------------------------------------------------------------------ API

    fun loadVideo(uri: Uri) {
        // 이전 결과·오류 화면이 남아 있으면 지운다
        CompressManager.reset()
        Session.set(uri, null)
        lastRenderedKey = null
        render()
        io.execute {
            val info = runCatching { MediaProbe.probe(host, uri) }.getOrNull()
            mainHandler.post {
                if (info != null) {
                    Session.set(uri, info)
                    render()
                } else {
                    Session.clear()
                    host.toast(host.getString(R.string.error_unsupported))
                    render()
                }
            }
        }
    }

    override fun refresh() {
        render()
    }

    /**
     * 상태바 높이가 바뀌면 **여백만** 고친다.
     *
     * 레이아웃 도중에 화면을 통째로 다시 만들면(removeAllViews + addView) 아직 측정되지 않은
     * 뷰가 그대로 그려져 빈 화면이 된다. 그래서 인셋이 바뀔 때는 패딩만 갱신한다.
     */
    override fun setSystemInsets(top: Int) {
        if (top == attachTop) return
        attachTop = top
        val child = view.getChildAt(0) ?: return
        val content = if (child is ScrollView) child.getChildAt(0) else child
        (content as? View)?.updatePadding(top = attachTop + dp2(10f))
    }

    // --------------------------------------------------------------- render

    private fun render() {
        // 레이아웃이 진행 중이면 화면을 갈아엎지 않는다 — 아직 측정되지 않은 뷰가 그려져 빈 화면이 된다
        if (view.isInLayout) {
            view.post { render() }
            return
        }
        val state = CompressManager.state.value

        val live = percentLabel
        if (state is CompressManager.State.Running && live != null && view.childCount == 1) {
            live.text = host.getString(R.string.progress_percent, state.progress)
            progressBar?.progress = state.progress
            stageLabel?.text = stageText(state.stage)
            return
        }
        if (state !is CompressManager.State.Running) {
            percentLabel = null
            stageLabel = null
            progressBar = null
        }

        val key = stateKey(state)
        if (key == lastRenderedKey) return
        lastRenderedKey = key

        val scrollPos = (view.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        view.removeAllViews()
        val child: View = when (state) {
            is CompressManager.State.Running -> buildRunning(state)
            is CompressManager.State.Success -> buildResult(state.result)
            else -> buildIdle(state)
        }
        view.addView(
            child,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        (child as? ScrollView)?.scrollTo(0, scrollPos)
        applyTopInset(child)
    }

    private fun applyTopInset(child: View) {
        if (child is ScrollView && child.childCount > 0) {
            child.getChildAt(0).updatePadding(top = attachTop + dp2(10f))
        }
    }

    private fun stateKey(state: CompressManager.State): String = when (state) {
        is CompressManager.State.Running -> "run-${state.progress}-${state.stage}"
        is CompressManager.State.Success -> "ok-${state.result.outputUri}"
        is CompressManager.State.Failure -> "fail-${state.error}-${state.detail}"
        CompressManager.State.Cancelled -> "cancel"
        CompressManager.State.Idle ->
            "idle-${Session.uri}-${Session.info?.displayName}-$mode-$codec-$targetMb"
    }

    // ---------------------------------------------------------------- idle

    private fun buildIdle(state: CompressManager.State): View {
        val scroll = ScrollView(host).apply { isFillViewport = true }
        val col = Ui.column(host).apply {
            setPadding(dp2(20f), attachTop + dp2(10f), dp2(20f), dp2(28f))
        }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        col.addView(buildHeader())
        col.addView(Ui.spacing(host, 18f))

        (state as? CompressManager.State.Failure)?.let {
            col.addView(buildBanner(errorText(it.error), error = true))
            val detail = it.detail?.takeIf { text -> text.isNotBlank() }
            if (detail != null) {
                col.addView(
                    Ui.text(
                        host,
                        host.getString(R.string.error_detail, detail.take(180)),
                        10.5f,
                        host.getColor(R.color.text_tertiary)
                    ).apply {
                        setPadding(dp2(4f), dp2(6f), dp2(4f), 0)
                        setLineSpacing(dp2(2f).toFloat(), 1f)
                    }
                )
            }
            col.addView(Ui.spacing(host, 14f))
        }
        if (state is CompressManager.State.Cancelled) {
            col.addView(buildBanner(host.getString(R.string.cancelled), error = false))
            col.addView(Ui.spacing(host, 14f))
        }

        col.addView(buildVideoCard())
        col.addView(Ui.spacing(host, 22f))

        col.addView(Ui.sectionTitle(host, host.getString(R.string.section_mode)))
        col.addView(Ui.spacing(host, 10f))
        ModeOption.entries.forEach { option ->
            col.addView(buildModeCard(option))
            col.addView(Ui.spacing(host, 8f))
        }

        col.addView(Ui.spacing(host, 12f))
        col.addView(Ui.sectionTitle(host, host.getString(R.string.section_codec)))
        col.addView(Ui.spacing(host, 10f))
        col.addView(buildCodecSelector())

        if (mode == CompressMath.MODE_TARGET) {
            col.addView(Ui.spacing(host, 18f))
            col.addView(Ui.sectionTitle(host, host.getString(R.string.section_target)))
            col.addView(Ui.spacing(host, 10f))
            col.addView(buildTargetChips())
            col.addView(Ui.spacing(host, 8f))
            col.addView(
                Ui.label(host, host.getString(R.string.target_hint), host.getColor(R.color.text_tertiary)).apply {
                    setLineSpacing(dp2(2f).toFloat(), 1f)
                }
            )
        }

        col.addView(Ui.spacing(host, 20f))
        col.addView(buildEstimate())
        col.addView(Ui.spacing(host, 14f))
        col.addView(buildStartButton())
        return scroll
    }

    private fun buildHeader(): View {
        val row = Ui.row(host)
        val logo = ImageView(host).apply {
            setImageResource(R.drawable.hero_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        row.addView(logo, LinearLayout.LayoutParams(dp2(46f), dp2(46f)))

        val titles = Ui.column(host).apply {
            addView(
                Ui.text(host, host.getString(R.string.home_title), 22f, host.getColor(R.color.text_primary), bold = true)
            )
            addView(
                Ui.text(host, host.getString(R.string.home_subtitle), 12.5f, host.getColor(R.color.text_secondary)).apply {
                    setPadding(0, dp2(3f), 0, 0)
                }
            )
        }
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp2(12f)
        })
        return row
    }

    private fun buildBanner(message: String, error: Boolean): View {
        val bg = if (error) host.getColor(R.color.danger_dim) else host.getColor(R.color.surface_alt)
        val fg = if (error) host.getColor(R.color.danger) else host.getColor(R.color.text_secondary)
        return Ui.text(host, message, 12.5f, fg).apply {
            setPadding(dp2(14f), dp2(11f), dp2(14f), dp2(11f))
            background = Ui.rounded(host, bg, 14f, ripple = false)
        }
    }

    private fun buildVideoCard(): View {
        val info = Session.info
        if (info == null) {
            val card = Ui.row(host).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp2(16f), dp2(18f), dp2(16f), dp2(18f))
                background = Ui.rounded(host, host.getColor(R.color.surface_alt), 22f, host.getColor(R.color.outline))
            }
            val circle = Ui.circle(host, 48f, host.getColor(R.color.accent_dim))
            circle.addView(Ui.icon(host, R.drawable.ic_video, 24f, host.getColor(R.color.accent)).apply {
                layoutParams = FrameLayout.LayoutParams(dp2(24f), dp2(24f), Gravity.CENTER)
            })
            card.addView(circle)
            val texts = Ui.column(host).apply {
                addView(Ui.text(host, host.getString(R.string.pick_video), 16f, host.getColor(R.color.text_primary), bold = true))
                addView(Ui.text(host, host.getString(R.string.pick_hint), 11.5f, host.getColor(R.color.text_tertiary)).apply {
                    setPadding(0, dp2(4f), 0, 0)
                })
            }
            card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp2(14f)
            })
            card.addView(Ui.icon(host, R.drawable.ic_arrow_down, 18f, host.getColor(R.color.text_tertiary)))
            Ui.clickable(card) { host.pickVideo() }
            return card
        }

        val card = Ui.row(host).apply {
            setPadding(dp2(12f), dp2(12f), dp2(12f), dp2(12f))
            background = Ui.rounded(host, host.getColor(R.color.surface_alt), 22f, host.getColor(R.color.outline))
        }
        val thumb = Ui.roundedImage(host, 16f)
        card.addView(thumb, LinearLayout.LayoutParams(dp2(76f), dp2(76f)))
        loadThumbnail(info.uri, thumb)

        val texts = Ui.column(host).apply {
            addView(
                Ui.text(host, info.displayName, 14f, host.getColor(R.color.text_primary), bold = true, maxLines = 1)
            )
            addView(
                Ui.text(
                    host,
                    "${info.resolutionLabel} · ${CompressMath.formatDuration(info.durationUs)} · " +
                        info.videoCodecLabel,
                    11.5f,
                    host.getColor(R.color.text_secondary)
                ).apply { setPadding(0, dp2(4f), 0, 0) }
            )
            addView(
                Ui.text(
                    host,
                    CompressMath.formatBytes(info.sizeBytes) + if (info.hasAudio) " · ${info.audioCodecLabel}" else " · " + host.getString(R.string.info_audio_none),
                    11.5f,
                    host.getColor(R.color.text_tertiary)
                ).apply { setPadding(0, dp2(2f), 0, 0) }
            )
        }
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp2(12f)
        })

        val change = Ui.text(host, host.getString(R.string.pick_change), 11.5f, host.getColor(R.color.accent), bold = true).apply {
            setPadding(dp2(10f), dp2(8f), dp2(4f), dp2(8f))
        }
        Ui.clickable(change) { host.pickVideo() }
        card.addView(change)
        return card
    }

    private fun loadThumbnail(uri: Uri, target: ImageView) {
        if (thumbCache.containsKey(uri)) {
            thumbCache[uri]?.let { target.setImageBitmap(it) }
            return
        }
        if (loadingThumb) return
        loadingThumb = true
        io.execute {
            val retriever = MediaMetadataRetriever()
            val bitmap: Bitmap? = try {
                retriever.setDataSource(host, uri)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (t: Throwable) {
                null
            } finally {
                runCatching { retriever.release() }
            }
            mainHandler.post {
                loadingThumb = false
                thumbCache[uri] = bitmap
                if (bitmap != null) target.setImageBitmap(bitmap)
            }
        }
    }

    private fun buildModeCard(option: ModeOption): View {
        val selected = option.mode == mode
        val card = Ui.column(host).apply {
            setPadding(dp2(14f), dp2(12f), dp2(14f), dp2(12f))
            background = Ui.rounded(
                host,
                if (selected) host.getColor(R.color.accent_dim) else host.getColor(R.color.surface),
                18f,
                if (selected) host.getColor(R.color.accent) else host.getColor(R.color.outline),
                if (selected) 1.5f else 1f
            )
        }
        val titleRow = Ui.row(host)
        val radio = FrameLayout(host).apply {
            layoutParams = LinearLayout.LayoutParams(dp2(20f), dp2(20f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (selected) host.getColor(R.color.accent) else Color.TRANSPARENT)
                setStroke(dp2(1.5f), if (selected) host.getColor(R.color.accent) else host.getColor(R.color.outline))
            }
        }
        if (selected) {
            radio.addView(View(host).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(host.getColor(R.color.on_accent))
                }
                layoutParams = FrameLayout.LayoutParams(dp2(8f), dp2(8f), Gravity.CENTER)
            })
        }
        titleRow.addView(radio)
        titleRow.addView(
            Ui.text(host, host.getString(option.titleRes), 14.5f, host.getColor(R.color.text_primary), bold = true).apply {
                setPadding(dp2(10f), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (option.badgeRes != null) {
            titleRow.addView(
                Ui.pill(
                    host,
                    host.getString(option.badgeRes),
                    host.getColor(R.color.emerald_dim),
                    host.getColor(R.color.emerald),
                    10f
                )
            )
        }
        card.addView(titleRow)
        card.addView(
            Ui.text(host, host.getString(option.descRes), 11.5f, host.getColor(R.color.text_secondary)).apply {
                setPadding(dp2(30f), dp2(5f), 0, 0)
            }
        )
        Ui.clickable(card) {
            mode = option.mode
            prefs.defaultMode = option.mode
            lastRenderedKey = null
            render()
        }
        return card
    }

    private fun buildCodecSelector(): View {
        val hevcOk = hevcAvailable != false
        val row = Ui.row(host)
        val gap = dp2(8f)
        row.addView(
            codecOption(CompressMath.CODEC_HEVC, host.getString(R.string.codec_hevc), hevcOk),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = gap }
        )
        row.addView(
            codecOption(CompressMath.CODEC_H264, host.getString(R.string.codec_h264), true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val wrap = Ui.column(host)
        wrap.addView(row)
        val hint = when {
            !hevcOk -> host.getString(R.string.hevc_unsupported)
            codec == CompressMath.CODEC_HEVC -> host.getString(R.string.codec_hevc_hint)
            else -> host.getString(R.string.codec_h264_hint)
        }
        wrap.addView(Ui.text(host, hint, 11f, host.getColor(R.color.text_tertiary)).apply {
            setPadding(0, dp2(8f), 0, 0)
            setLineSpacing(dp2(2f).toFloat(), 1f)
        })
        return wrap
    }

    private fun codecOption(codecValue: Int, label: String, enabled: Boolean): View {
        val selected = codec == codecValue && enabled
        val text = Ui.text(
            host,
            label,
            12.5f,
            if (!enabled) host.getColor(R.color.text_tertiary)
            else if (selected) host.getColor(R.color.text_primary)
            else host.getColor(R.color.text_secondary),
            bold = selected
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp2(10f), dp2(13f), dp2(10f), dp2(13f))
            background = Ui.rounded(
                host,
                if (selected) host.getColor(R.color.accent_dim) else host.getColor(R.color.surface),
                14f,
                if (selected) host.getColor(R.color.accent) else host.getColor(R.color.outline)
            )
        }
        if (enabled) {
            Ui.clickable(text) {
                codec = codecValue
                prefs.defaultCodec = codecValue
                lastRenderedKey = null
                render()
            }
        }
        return text
    }

    private fun buildTargetChips(): View {
        val row = Ui.row(host)
        listOf(10, 25, 50, 100).forEachIndexed { index, mb ->
            val selected = targetMb == mb
            val chip = Ui.text(
                host,
                host.getString(R.string.target_under, "$mb MB"),
                12f,
                if (selected) host.getColor(R.color.on_accent) else host.getColor(R.color.text_primary),
                bold = true
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp2(12f), dp2(10f), dp2(12f), dp2(10f))
                background = GradientDrawable().apply {
                    cornerRadius = dpf2(999f)
                    setColor(if (selected) host.getColor(R.color.accent) else host.getColor(R.color.surface_high))
                }
            }
            Ui.clickable(chip) {
                targetMb = mb
                prefs.targetMegabytes = mb
                lastRenderedKey = null
                render()
            }
            row.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index < 3) rightMargin = dp2(8f)
            })
        }
        return row
    }

    private fun currentPlan(): CompressMath.Plan? {
        val info = Session.info ?: return null
        val effectiveCodec = if (codec == CompressMath.CODEC_HEVC && hevcAvailable == false) {
            CompressMath.CODEC_H264
        } else {
            codec
        }
        return CompressManager.plan(info, mode, effectiveCodec, targetMb.toLong() * 1024 * 1024)
    }

    private fun buildEstimate(): View {
        val plan = currentPlan()
        val card = Ui.column(host).apply {
            setPadding(dp2(14f), dp2(12f), dp2(14f), dp2(12f))
            background = Ui.rounded(host, host.getColor(R.color.surface), 16f, host.getColor(R.color.outline_soft), ripple = false)
        }
        if (plan == null) {
            card.addView(Ui.text(host, host.getString(R.string.est_background), 11.5f, host.getColor(R.color.text_tertiary)))
            return card
        }
        val row = Ui.row(host)
        row.addView(
            Ui.text(
                host,
                host.getString(
                    R.string.est_output,
                    CompressMath.formatBytes(plan.estimatedBytes),
                    plan.estimatedReductionPercent.toInt()
                ),
                13f,
                host.getColor(R.color.text_primary),
                bold = true
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            Ui.pill(
                host,
                "${plan.displayWidth}×${plan.displayHeight} · " +
                    if (plan.codec == CompressMath.CODEC_HEVC) "H.265" else "H.264",
                host.getColor(R.color.surface_high),
                host.getColor(R.color.text_secondary),
                10f
            )
        )
        card.addView(row)
        return card
    }

    private fun buildStartButton(): View {
        val ready = Session.info != null
        val button = Ui.button(
            host,
            if (ready) host.getString(R.string.start_compress) else host.getString(R.string.pick_video),
            primary = true,
            iconRes = R.drawable.ic_bolt
        ) {
            val info = Session.info
            val uri = Session.uri
            if (info == null || uri == null) {
                host.pickVideo()
                return@button
            }
            val effectiveCodec = if (codec == CompressMath.CODEC_HEVC && hevcAvailable == false) {
                CompressMath.CODEC_H264
            } else {
                codec
            }
            host.beginCompression(
                CompressRequest(
                    uri = uri,
                    info = info,
                    mode = mode,
                    codec = effectiveCodec,
                    targetBytes = targetMb.toLong() * 1024 * 1024
                )
            )
        }
        if (!ready) {
            button.alpha = 0.85f
        }
        return button
    }

    // ------------------------------------------------------------- running

    private fun buildRunning(state: CompressManager.State.Running): View {
        val col = Ui.column(host).apply {
            gravity = Gravity.CENTER
            setPadding(dp2(28f), attachTop + dp2(10f), dp2(28f), dp2(28f))
        }

        val circle = Ui.circle(host, 190f, host.getColor(R.color.surface_alt), host.getColor(R.color.outline))
        val progress = ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = state.progress
            progressDrawable = host.getDrawable(R.drawable.progress_compress)
            layoutParams = FrameLayout.LayoutParams(dp2(140f), dp2(10f), Gravity.CENTER)
        }
        val percent = Ui.text(
            host,
            host.getString(R.string.progress_percent, state.progress),
            44f,
            host.getColor(R.color.text_primary),
            bold = true
        ).apply { gravity = Gravity.CENTER }
        val stageLabel = Ui.text(
            host,
            stageText(state.stage),
            12f,
            host.getColor(R.color.text_secondary)
        ).apply { gravity = Gravity.CENTER }
        percentLabel = percent
        this.stageLabel = stageLabel
        progressBar = progress

        circle.addView(percent, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER; topMargin = dp2(-14f) })
        circle.addView(stageLabel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER; topMargin = dp2(46f) })
        circle.addView(progress, FrameLayout.LayoutParams(dp2(140f), dp2(10f), Gravity.CENTER).apply {
            topMargin = dp2(76f)
        })
        col.addView(circle)
        col.addView(Ui.spacing(host, 26f))
        col.addView(
            Ui.text(host, host.getString(R.string.compressing), 18f, host.getColor(R.color.text_primary), bold = true)
                .apply { gravity = Gravity.CENTER }
        )
        col.addView(
            Ui.text(host, state.info.displayName, 12.5f, host.getColor(R.color.text_secondary), maxLines = 2)
                .apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp2(6f), 0, 0)
                }
        )
        col.addView(Ui.spacing(host, 10f))
        col.addView(
            Ui.text(host, host.getString(R.string.compressing_hint), 11.5f, host.getColor(R.color.text_tertiary))
                .apply {
                    gravity = Gravity.CENTER
                    setLineSpacing(dp2(2f).toFloat(), 1f)
                }
        )
        col.addView(Ui.spacing(host, 26f))
        val cancel = Ui.button(host, host.getString(R.string.cancel), primary = false) {
            host.cancelCompression()
        }
        col.addView(cancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return col
    }

    // -------------------------------------------------------------- result

    private fun buildResult(result: CompressResult): View {
        val scroll = ScrollView(host).apply { isFillViewport = true }
        val col = Ui.column(host).apply {
            setPadding(dp2(20f), attachTop + dp2(10f), dp2(20f), dp2(28f))
        }
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val headRow = Ui.row(host)
        val check = Ui.circle(host, 34f, host.getColor(R.color.emerald_dim))
        check.addView(Ui.icon(host, R.drawable.ic_check, 18f, host.getColor(R.color.emerald)).apply {
            layoutParams = FrameLayout.LayoutParams(dp2(18f), dp2(18f), Gravity.CENTER)
        })
        headRow.addView(check)
        headRow.addView(
            Ui.text(host, host.getString(R.string.done_title), 21f, host.getColor(R.color.text_primary), bold = true).apply {
                setPadding(dp2(10f), 0, 0, 0)
            }
        )
        col.addView(headRow)
        col.addView(
            Ui.text(host, host.getString(R.string.done_saved), 12f, host.getColor(R.color.text_secondary)).apply {
                setPadding(0, dp2(6f), 0, 0)
            }
        )
        col.addView(Ui.spacing(host, 18f))

        // 전/후 비교
        val compare = Ui.row(host).apply {
            setPadding(dp2(16f), dp2(16f), dp2(16f), dp2(16f))
            background = Ui.rounded(host, host.getColor(R.color.surface_alt), 22f, host.getColor(R.color.outline), ripple = false)
        }
        val before = Ui.column(host).apply {
            addView(Ui.text(host, host.getString(R.string.before), 11f, host.getColor(R.color.text_tertiary), bold = true))
            addView(Ui.text(host, CompressMath.formatBytes(result.sourceBytes), 17f, host.getColor(R.color.text_secondary), bold = true).apply {
                setPadding(0, dp2(6f), 0, 0)
            })
        }
        val after = Ui.column(host).apply {
            addView(Ui.text(host, host.getString(R.string.after), 11f, host.getColor(R.color.emerald), bold = true))
            addView(Ui.text(host, CompressMath.formatBytes(result.outputBytes), 24f, host.getColor(R.color.text_primary), bold = true).apply {
                setPadding(0, dp2(2f), 0, 0)
            })
        }
        compare.addView(before, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        compare.addView(Ui.icon(host, R.drawable.ic_arrow_down, 20f, host.getColor(R.color.text_tertiary)).apply {
            rotation = -90f
        }, LinearLayout.LayoutParams(dp2(24f), dp2(24f)))
        compare.addView(after, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.END
        })
        col.addView(compare)
        col.addView(Ui.spacing(host, 10f))

        val pillRow = Ui.row(host)
        pillRow.addView(
            Ui.pill(
                host,
                host.getString(R.string.reduction_percent, result.reductionPercent.toInt()),
                host.getColor(R.color.emerald_dim),
                host.getColor(R.color.emerald),
                12f
            )
        )
        pillRow.addView(
            Ui.pill(
                host,
                host.getString(R.string.saved_amount, CompressMath.formatBytes(result.savedBytes)),
                host.getColor(R.color.surface_high),
                host.getColor(R.color.text_secondary),
                11f
            ),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp2(8f)
            }
        )
        col.addView(pillRow)

        col.addView(Ui.spacing(host, 18f))

        // 미리보기 카드
        val preview = FrameLayout(host).apply {
            background = Ui.rounded(host, host.getColor(R.color.surface), 20f, host.getColor(R.color.outline))
        }
        val thumb = Ui.roundedImage(host, 20f)
        preview.addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2(180f)))
        loadThumbnail(result.outputUri, thumb)
        val playBadge = FrameLayout(host).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE67C5CFF.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(dp2(58f), dp2(58f), Gravity.CENTER)
        }
        playBadge.addView(Ui.icon(host, R.drawable.ic_play, 24f, host.getColor(R.color.on_accent)).apply {
            layoutParams = FrameLayout.LayoutParams(dp2(24f), dp2(24f), Gravity.CENTER)
        })
        preview.addView(playBadge)
        Ui.clickable(preview) { host.playVideo(result.outputUri) }
        col.addView(preview)

        col.addView(Ui.spacing(host, 10f))
        col.addView(
            Ui.text(
                host,
                "${result.resolutionLabel} · ${result.codecLabel} · ${CompressMath.formatDuration(result.durationUs)}",
                12f,
                host.getColor(R.color.text_secondary)
            )
        )
        if (result.audioDropped) {
            col.addView(Ui.spacing(host, 8f))
            col.addView(buildBanner(host.getString(R.string.audio_dropped), error = false))
        }

        col.addView(Ui.spacing(host, 18f))
        val actions = Ui.row(host)
        val share = Ui.button(host, host.getString(R.string.share), primary = false, iconRes = R.drawable.ic_share) {
            host.shareVideo(result.outputUri)
        }
        val play = Ui.button(host, host.getString(R.string.play), primary = false, iconRes = R.drawable.ic_play) {
            host.playVideo(result.outputUri)
        }
        actions.addView(share, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp2(8f)
        })
        actions.addView(play, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(actions)

        col.addView(Ui.spacing(host, 10f))
        col.addView(
            Ui.button(host, host.getString(R.string.compress_another), primary = true) {
                Session.clear()
                CompressManager.reset()
                lastRenderedKey = null
                host.pickVideo()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return scroll
    }

    // -------------------------------------------------------------- helpers

    private fun checkHevcSupport() {
        io.execute {
            val available = MediaProbe.hevcEncoderAvailable()
            mainHandler.post {
                hevcAvailable = available
                if (!available && codec == CompressMath.CODEC_HEVC) {
                    codec = CompressMath.CODEC_H264
                }
                lastRenderedKey = null
                render()
            }
        }
    }

    private fun stageText(stage: CompressManager.Stage): String = when (stage) {
        CompressManager.Stage.PREPARING -> host.getString(R.string.compressing_stage_audio)
        CompressManager.Stage.VIDEO -> host.getString(R.string.compressing_stage_video)
        CompressManager.Stage.FINISHING -> host.getString(R.string.compressing_stage_finish)
    }

    private fun errorText(error: CompressError): String = when (error) {
        CompressError.NO_VIDEO_TRACK -> host.getString(R.string.error_no_video_track)
        CompressError.UNREADABLE -> host.getString(R.string.error_read)
        CompressError.UNSUPPORTED_CODEC -> host.getString(R.string.error_unsupported)
        CompressError.NO_ENCODER -> host.getString(R.string.error_encoder)
        CompressError.STORAGE -> host.getString(R.string.error_storage)
        CompressError.CANCELLED -> host.getString(R.string.cancelled)
        CompressError.ENGINE -> host.getString(R.string.error_generic)
    }

    private fun dp2(value: Float): Int = host.dp(value)

    private fun dpf2(value: Float): Float = host.dpf(value)

    /** 압축 방식 4가지 */
    private enum class ModeOption(
        val mode: Int,
        val titleRes: Int,
        val descRes: Int,
        val badgeRes: Int?
    ) {
        QUALITY(CompressMath.MODE_KEEP_QUALITY, R.string.mode_quality_title, R.string.mode_quality_desc, R.string.mode_quality_badge),
        BALANCE(CompressMath.MODE_BALANCE, R.string.mode_balance_title, R.string.mode_balance_desc, null),
        LIGHT(CompressMath.MODE_LIGHT, R.string.mode_light_title, R.string.mode_light_desc, null),
        TARGET(CompressMath.MODE_TARGET, R.string.mode_target_title, R.string.mode_target_desc, null)
    }
}
