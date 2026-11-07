package com.yunlu.kongjian.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of an APK extraction run. */
data class ApkExtractResult(
    val dir: File? = null,
    val fileCount: Int = 0,
    val totalBytes: Long = 0L,
    val includeSplits: Boolean = false,
    val error: String? = null,
) {
    val success: Boolean get() = dir != null && error == null

    /** `base.apk` alone cannot install an app that ships configuration splits. */
    val installable: Boolean get() = !includeSplits || fileCount > 1
}

/**
 * Copies installed APKs out of the read-only system partition.
 *
 * Design notes:
 *  - Extraction always targets this app's private dir (`filesDir/apk_extract`).
 *    No storage permission is needed on any API level, and the resulting files
 *    are readable by our own FileProvider, so they can be shared or copied to
 *    the public Download collection afterwards.
 *  - `saveToDownloads` uses MediaStore on API 29+ (permission-free) and falls
 *    back to the legacy public dir below that, which needs WRITE_EXTERNAL_STORAGE.
 *  - System partitions stay untouched: we only ever read from them.
 */
@Singleton
class ApkExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val packageManager: PackageManager = context.packageManager

    /**
     * Copy [packageName]'s APK (optionally with all split APKs) into private storage.
     * Always runs on IO.
     */
    suspend fun extract(packageName: String, includeSplits: Boolean): ApkExtractResult =
        withContext(Dispatchers.IO) {
            val appInfo = try {
                packageManager.getApplicationInfo(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                return@withContext ApkExtractResult(error = "ERR_APP_GONE")
            }

            val base = appInfo.sourceDir?.let { File(it) }
            if (base == null || !base.exists() || !base.canRead()) {
                return@withContext ApkExtractResult(error = "ERR_NO_SOURCE")
            }

            val sources = mutableListOf(base)
            if (includeSplits) {
                // splitSourceDirs holds every split APK path; null for monolithic apps.
                appInfo.splitSourceDirs?.forEach { path ->
                    File(path).takeIf { it.exists() && it.canRead() }?.let(sources::add)
                }
            }

            val label = try {
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                packageName
            }
            val version = try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
            } catch (_: PackageManager.NameNotFoundException) {
                "unknown"
            }

            // 主包不叫 base.apk，用「应用名-v版本-包名.apk」，用户一眼能认出来
            val safeLabel = label.replace(UNSAFE_CHARS, "_").take(40).ifBlank { packageName }
            val safeVersion = version.replace(UNSAFE_CHARS, "_")
            val mainApkName = "$safeLabel-v$safeVersion-$packageName.apk"

            val target = File(extractRoot(), folderName(label, version, packageName))
            target.deleteRecursively()
            if (!target.mkdirs()) {
                return@withContext ApkExtractResult(error = "ERR_MKDIR")
            }

            var copied = 0
            var totalBytes = 0L
            for (src in sources) {
                // 主包用可读名；配置分片保留原名（split_config.*.apk），安装时按内容识别
                val dstName = if (src == base) mainApkName else src.name
                val dst = File(target, dstName)
                try {
                    src.copyTo(dst, overwrite = true)
                    totalBytes += dst.length()
                    copied++
                } catch (e: IOException) {
                    return@withContext ApkExtractResult(
                        dir = target,
                        fileCount = copied,
                        totalBytes = totalBytes,
                        includeSplits = includeSplits,
                        error = "ERR_COPY:${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }

            if (copied == 0) {
                target.deleteRecursively()
                ApkExtractResult(error = "ERR_EMPTY")
            } else {
                ApkExtractResult(
                    dir = target,
                    fileCount = copied,
                    totalBytes = totalBytes,
                    includeSplits = includeSplits,
                )
            }
        }

    /**
     * Publish the extracted files into the public Download collection.
     * API 29+ goes through MediaStore (no permission); below that it writes to
     * `Download/云麓空间` and needs WRITE_EXTERNAL_STORAGE (checked by caller).
     */
    suspend fun saveToDownloads(dir: File): SaveResult = withContext(Dispatchers.IO) {
        val files = dir.listFiles { f -> f.isFile }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return@withContext SaveResult(error = "ERR_EMPTY")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            var saved = 0
            files.forEach { src ->
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(MediaStore.Downloads.MIME_TYPE, MIME_APK)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = try {
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                } catch (_: Exception) {
                    null
                }
                if (uri == null) return@forEach
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { input -> input.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    saved++
                } catch (_: Exception) {
                    resolver.delete(uri, null, null)
                }
            }
            if (saved == 0) {
                SaveResult(error = "ERR_SAVE")
            } else {
                SaveResult(count = saved, uri = files.firstOrNull()?.let { uriFor(it) })
            }
        } else {
            val dest = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOAD_SUBDIR,
            )
            if (!dest.exists() && !dest.mkdirs()) return@withContext SaveResult(error = "ERR_SAVE")
            var saved = 0
            files.forEach { src ->
                try {
                    src.copyTo(File(dest, src.name), overwrite = true)
                    saved++
                } catch (_: IOException) {
                    // keep going; partial success is still useful
                }
            }
            if (saved == 0) SaveResult(error = "ERR_SAVE") else SaveResult(count = saved)
        }
    }

    /** Share the extracted APK(s) through the system sheet. */
    fun buildShareIntent(dir: File, chooserTitle: String): Intent? {
        val files = dir.listFiles { f -> f.isFile }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return null

        val uris = ArrayList<Uri>(files.size)
        files.forEach { file ->
            val uri = runCatching { uriFor(file) }.getOrNull() ?: return null
            uris += uri
        }
        if (uris.isEmpty()) return null

        val inner = if (uris.size == 1) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uris[0], MIME_APK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = MIME_APK
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return Intent.createChooser(inner, chooserTitle)
    }

    /** Previously extracted bundles, newest first — lets users re-share later. */
    fun listExtracted(): List<File> =
        extractRoot().listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /** Delete every extracted bundle (used by the settings page). */
    fun clearAll(): Int {
        val root = extractRoot()
        val count = root.listFiles()?.size ?: 0
        root.deleteRecursively()
        return count
    }

    /**
     * Build an Intent that opens the public "Download/云麓空间" folder in the
     * system file manager (via DocumentsContract, API 21+). The private
     * filesDir/apk_extract is not browsable by other apps on Android 11+, so
     * the settings page first syncs bundles into Downloads (saveToDownloads),
     * then launches this intent.
     */
    fun buildOpenDownloadsDirIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            "primary:${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIR",
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    // ---------------------------------------------------------------- internals

    private fun extractRoot(): File = File(context.filesDir, EXTRACT_DIR)

    private fun folderName(label: String, version: String, packageName: String): String {
        val safeLabel = label.replace(UNSAFE_CHARS, "_").take(40).ifBlank { packageName }
        val safeVersion = version.replace(UNSAFE_CHARS, "_")
        return "$safeLabel-$safeVersion-${Integer.toHexString(packageName.hashCode())}"
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.$FILE_PROVIDER_SUFFIX", file)

    companion object {
        const val MIME_APK = "application/vnd.android.package-archive"
        const val EXTRACT_DIR = "apk_extract"
        const val DOWNLOAD_SUBDIR = "云麓空间"
        const val FILE_PROVIDER_SUFFIX = "fileprovider"
        private val UNSAFE_CHARS = Regex("[^\\p{L}\\p{N}._-]")
    }
}

/** Outcome of publishing an extracted bundle to the public Download collection. */
data class SaveResult(val count: Int = 0, val uri: Uri? = null, val error: String? = null) {
    val success: Boolean get() = error == null && count > 0
}