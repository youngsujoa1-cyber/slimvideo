package com.youngsu.slimvideo.compress

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * 압축 결과 저장.
 *
 * - Android 10 이상: MediaStore 의 Movies/SlimVideo 폴더 (권한 필요 없음)
 * - Android 8~9: 외부 저장소의 Movies/SlimVideo 폴더 (WRITE_EXTERNAL_STORAGE 필요)
 *
 * 인코더는 임시 파일에 쓰고, 완성된 뒤에 갤러리로 복사한다.
 */
object OutputStore {

    private const val TAG = "OutputStore"
    private const val FOLDER = "SlimVideo"

    fun tempFile(context: Context): File {
        // 큰 영상도 담을 수 있도록 외부 앱 전용 폴더를 먼저 쓰고, 없으면 캐시에 쓴다
        val base = context.getExternalFilesDir(null) ?: context.cacheDir
        val dir = File(base, "compress")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "slim_${System.currentTimeMillis()}.mp4")
    }

    /** 결과를 담을 만한 여유 공간이 있는지 (원본의 40% 정도면 충분) */
    fun hasSpaceFor(context: Context, sourceBytes: Long): Boolean {
        val base = context.getExternalFilesDir(null) ?: context.cacheDir
        val free = runCatching { base.usableSpace }.getOrDefault(Long.MAX_VALUE)
        val needed = (sourceBytes * 0.4).toLong().coerceAtLeast(8L * 1024 * 1024)
        return free > needed
    }

    /** 결과 파일 이름 — 원본 이름을 알아볼 수 있게 남긴다 */
    fun targetName(sourceName: String, mode: Int): String {
        val base = sourceName.substringBeforeLast('.').ifBlank { "video" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(60)
        val suffix = when (mode) {
            CompressMath.MODE_KEEP_QUALITY -> "slim"
            CompressMath.MODE_BALANCE -> "share"
            CompressMath.MODE_LIGHT -> "light"
            else -> "fit"
        }
        return "${base}_${suffix}_${System.currentTimeMillis() % 100000}.mp4"
    }

    fun save(context: Context, tempFile: File, displayName: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, tempFile, displayName)
        } else {
            saveLegacy(context, tempFile, displayName)
        }
    }

    private fun saveWithMediaStore(context: Context, tempFile: File, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + File.separator + FOLDER
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw CompressException(CompressError.STORAGE)
        try {
            resolver.openOutputStream(uri)?.use { output ->
                tempFile.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
            } ?: throw CompressException(CompressError.STORAGE)
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            Log.w(TAG, "MediaStore 저장 실패", t)
            throw if (t is CompressException) t else CompressException(CompressError.STORAGE, t)
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, tempFile: File, displayName: String): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw CompressException(CompressError.STORAGE)
        val target = File(dir, displayName)
        try {
            tempFile.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            }
        } catch (t: Throwable) {
            runCatching { target.delete() }
            throw CompressException(CompressError.STORAGE, t)
        }
        MediaScannerConnection.scanFile(
            context, arrayOf(target.absolutePath), arrayOf("video/mp4"), null
        )
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            target
        )
    }
}
