package com.youngsu.slimvideo.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.youngsu.slimvideo.R
import com.youngsu.slimvideo.compress.CompressMath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 보관함 — Movies/SlimVideo 에 저장된 압축 결과를 보여 준다 */
class LibraryScreen(host: MainActivity) : Screen(host) {

    override val view: FrameLayout = FrameLayout(host).apply {
        setBackgroundColor(host.getColor(R.color.bg))
    }

    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scroll = ScrollView(host).apply { isFillViewport = true }
    private val col = Ui.column(host)
    private var attachTop = 0
    private var items: List<LibraryItem> = emptyList()
    private var loading = false
    private val thumbCache = HashMap<Uri, Bitmap?>()

    init {
        scroll.addView(
            col,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        view.addView(
            scroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        renderEmpty(loading = true)
        refresh()
    }

    override fun refresh() {
        if (loading) return
        loading = true
        io.execute {
            val loaded = queryItems()
            mainHandler.post {
                loading = false
                items = loaded
                render()
            }
        }
    }

    /** 인셋이 바뀌면 여백만 갱신한다 — 레이아웃 도중에 화면을 다시 만들지 않는다 */
    override fun setSystemInsets(top: Int) {
        if (top == attachTop) return
        attachTop = top
        applyContentPadding()
    }

    private fun applyContentPadding() {
        col.setPadding(host.dp(20f), attachTop + host.dp(10f), host.dp(20f), host.dp(28f))
    }

    // ---------------------------------------------------------------- data

    private data class LibraryItem(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long,
        val dateAdded: Long,
        val durationMs: Long
    )

    private fun queryItems(): List<LibraryItem> {
        val result = ArrayList<LibraryItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION
        )
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("${android.os.Environment.DIRECTORY_MOVIES}/SlimVideo%")
        } else {
            @Suppress("DEPRECATION")
            val column = MediaStore.Video.Media.DATA
            selection = "$column LIKE ?"
            args = arrayOf("%/Movies/SlimVideo/%")
        }
        runCatching {
            host.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                while (cursor.moveToNext() && result.size < 300) {
                    val id = cursor.getLong(idIndex)
                    result.add(
                        LibraryItem(
                            uri = ContentUris.withAppendedId(collection, id),
                            name = cursor.getString(nameIndex) ?: "video",
                            sizeBytes = cursor.getLong(sizeIndex),
                            dateAdded = cursor.getLong(dateIndex) * 1000L,
                            durationMs = if (cursor.isNull(durationIndex)) 0L else cursor.getLong(durationIndex)
                        )
                    )
                }
            }
        }
        return result
    }

    // -------------------------------------------------------------- render

    private fun render() {
        // 레이아웃이 진행 중이면 화면을 갈아엎지 않는다 — 아직 측정되지 않은 뷰가 그려져 빈 화면이 된다
        if (view.isInLayout) {
            view.post { render() }
            return
        }
        col.removeAllViews()
        applyContentPadding()

        val header = Ui.row(host)
        header.addView(
            Ui.text(host, host.getString(R.string.library_title), 21f, host.getColor(R.color.text_primary), bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (items.isNotEmpty()) {
            header.addView(
                Ui.pill(
                    host,
                    items.size.toString(),
                    host.getColor(R.color.surface_high),
                    host.getColor(R.color.text_secondary),
                    11f
                )
            )
        }
        col.addView(header)
        col.addView(Ui.spacing(host, 16f))

        if (items.isEmpty()) {
            col.addView(buildEmpty())
            return
        }
        items.forEach { item ->
            col.addView(buildRow(item))
            col.addView(Ui.spacing(host, 8f))
        }
    }

    private fun renderEmpty(loading: Boolean) {
        if (view.isInLayout) {
            view.post { renderEmpty(loading) }
            return
        }
        col.removeAllViews()
        applyContentPadding()
        col.addView(
            Ui.text(host, host.getString(R.string.library_title), 21f, host.getColor(R.color.text_primary), bold = true)
        )
        col.addView(Ui.spacing(host, 16f))
        col.addView(
            Ui.text(
                host,
                if (loading) host.getString(R.string.compressing) else host.getString(R.string.library_empty),
                13f,
                host.getColor(R.color.text_tertiary)
            )
        )
    }

    private fun buildEmpty(): View {
        val box = Ui.column(host).apply {
            gravity = Gravity.CENTER
            setPadding(0, host.dp(60f), 0, host.dp(20f))
        }
        val circle = Ui.circle(host, 72f, host.getColor(R.color.surface_alt), host.getColor(R.color.outline))
        circle.addView(Ui.icon(host, R.drawable.ic_library, 30f, host.getColor(R.color.text_tertiary)).apply {
            layoutParams = FrameLayout.LayoutParams(host.dp(30f), host.dp(30f), Gravity.CENTER)
        })
        box.addView(circle)
        box.addView(
            Ui.text(host, host.getString(R.string.library_empty), 15f, host.getColor(R.color.text_primary), bold = true)
                .apply {
                    gravity = Gravity.CENTER
                    setPadding(0, host.dp(16f), 0, 0)
                }
        )
        box.addView(
            Ui.text(host, host.getString(R.string.library_empty_hint), 12f, host.getColor(R.color.text_tertiary))
                .apply {
                    gravity = Gravity.CENTER
                    setPadding(host.dp(24f), host.dp(6f), host.dp(24f), 0)
                }
        )
        return box
    }

    private fun buildRow(item: LibraryItem): View {
        val card = Ui.row(host).apply {
            setPadding(host.dp(10f), host.dp(10f), host.dp(6f), host.dp(10f))
            background = Ui.rounded(host, host.getColor(R.color.surface_alt), 18f, host.getColor(R.color.outline))
        }
        val thumb = Ui.roundedImage(host, 14f)
        card.addView(thumb, LinearLayout.LayoutParams(host.dp(64f), host.dp(64f)))
        loadThumb(item.uri, thumb)

        val texts = Ui.column(host).apply {
            addView(
                Ui.text(host, item.name, 13f, host.getColor(R.color.text_primary), bold = true, maxLines = 1)
            )
            val meta = StringBuilder()
            meta.append(CompressMath.formatBytes(item.sizeBytes))
            if (item.durationMs > 0) meta.append(" · ").append(CompressMath.formatDuration(item.durationMs * 1000L))
            if (item.dateAdded > 0) {
                meta.append(" · ").append(
                    SimpleDateFormat("M/d", Locale.getDefault()).format(Date(item.dateAdded))
                )
            }
            addView(
                Ui.text(host, meta.toString(), 11f, host.getColor(R.color.text_secondary)).apply {
                    setPadding(0, host.dp(4f), 0, 0)
                }
            )
        }
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = host.dp(10f)
        })

        val play = iconButton(R.drawable.ic_play, host.getColor(R.color.text_primary))
        Ui.clickable(play) { host.playVideo(item.uri) }
        card.addView(play)

        val share = iconButton(R.drawable.ic_share, host.getColor(R.color.text_secondary))
        Ui.clickable(share) { host.shareVideo(item.uri) }
        card.addView(share)

        val delete = iconButton(R.drawable.ic_trash, host.getColor(R.color.danger))
        Ui.clickable(delete) { confirmDelete(item) }
        card.addView(delete)

        return card
    }

    private fun iconButton(iconRes: Int, tint: Int): FrameLayout = FrameLayout(host).apply {
        layoutParams = LinearLayout.LayoutParams(host.dp(38f), host.dp(38f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x00000000)
        }
        addView(Ui.icon(host, iconRes, 17f, tint).apply {
            layoutParams = FrameLayout.LayoutParams(host.dp(17f), host.dp(17f), Gravity.CENTER)
        })
    }

    private fun loadThumb(uri: Uri, target: ImageView) {
        thumbCache[uri]?.let {
            target.setImageBitmap(it)
            return
        }
        io.execute {
            val retriever = MediaMetadataRetriever()
            val bitmap = try {
                retriever.setDataSource(host, uri)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (t: Throwable) {
                null
            } finally {
                runCatching { retriever.release() }
            }
            mainHandler.post {
                thumbCache[uri] = bitmap
                if (bitmap != null) target.setImageBitmap(bitmap)
            }
        }
    }

    private fun confirmDelete(item: LibraryItem) {
        MaterialAlertDialogBuilder(host)
            .setTitle(R.string.library_delete_title)
            .setMessage(host.getString(R.string.library_delete_body, item.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val deleted = runCatching { host.contentResolver.delete(item.uri, null, null) }
                    .getOrDefault(0) > 0
                if (deleted) {
                    host.toast(host.getString(R.string.deleted))
                } else {
                    host.toast(host.getString(R.string.delete_failed))
                }
                refresh()
            }
            .show()
    }
}
