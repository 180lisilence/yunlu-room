package com.yunlu.kongjian.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of an archive run. */
data class ArchiveResult(
    val file: File? = null,
    val apkCount: Int = 0,
    val totalBytes: Long = 0L,
    val error: String? = null,
) {
    val success: Boolean get() = file != null && error == null
}

/** One `.kaz` archive in the local archive library. */
data class ArchiveEntry(
    val file: File,
    val packageName: String?,
    val label: String?,
    val versionName: String?,
    val versionCode: Long,
    val archivedTime: Long,
    val apkCount: Int,
    val sizeBytes: Long,
)

/** Outcome of restoring an archive into a directory of APK files. */
data class RestoreResult(
    val baseApk: File? = null,
    val splitApks: List<File> = emptyList(),
    val error: String? = null,
) {
    val success: Boolean get() = baseApk != null && error == null
}

/**
 * V3 B4: application archive (the "third state" besides installed / uninstalled).
 *
 * An archive bundles the full APK set plus a metadata manifest into a single
 * `.kaz` file (a zip that stores APKs uncompressed so they can be random-read).
 * The purpose is "install into an environment that does NOT already have the
 * app" — restoring over an existing install is NOT guaranteed because of the
 * signature check (declared honestly in README.txt and the UI).
 */
@Singleton
class ArchiveManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val packageManager: PackageManager = context.packageManager

    /** Where archives live: this app's private dir, no permission needed. */
    private fun archiveRoot(): File = File(context.filesDir, ARCHIVE_DIR)

    /**
     * Archive [packageName] into a `.kaz` bundle (base APK + optional splits +
     * manifest.json + README.txt). APKs are stored (not deflated) to keep the
     * file random-readable and to avoid re-compressing already-compressed data.
     */
    suspend fun archive(packageName: String, includeSplits: Boolean = true): ArchiveResult =
        withContext(Dispatchers.IO) {
            val appInfo = try {
                packageManager.getApplicationInfo(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                return@withContext ArchiveResult(error = "ERR_APP_GONE")
            }

            val base = appInfo.sourceDir?.let { File(it) }
            if (base == null || !base.exists() || !base.canRead()) {
                return@withContext ArchiveResult(error = "ERR_NO_SOURCE")
            }

            val sources = mutableListOf(base)
            if (includeSplits) {
                appInfo.splitSourceDirs?.forEach { path ->
                    File(path).takeIf { it.exists() && it.canRead() }?.let(sources::add)
                }
            }

            // Guard against exhausting storage: a single archive may not exceed 4GB.
            var totalBytes = 0L
            sources.forEach { totalBytes += it.length() }
            if (totalBytes > MAX_ARCHIVE_BYTES) {
                return@withContext ArchiveResult(error = "ERR_TOO_LARGE")
            }

            val label = try {
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                packageName
            }
            val pkgInfo = try {
                packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            val versionName = pkgInfo?.versionName ?: "unknown"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo?.longVersionCode ?: 0L
            } else {
                @Suppress("DEPRECATION")
                (pkgInfo?.versionCode ?: 0).toLong()
            }
            val permissions = pkgInfo?.requestedPermissions?.toList().orEmpty()

            val root = archiveRoot()
            if (!root.exists() && !root.mkdirs()) {
                return@withContext ArchiveResult(error = "ERR_MKDIR")
            }
            val safeLabel = label.replace(UNSAFE_CHARS, "_").take(40).ifBlank { packageName }
            val safeVersion = versionName.replace(UNSAFE_CHARS, "_")
            val outFile = File(root, "$safeLabel-v$safeVersion-$packageName.kaz")

            val manifestJson = buildManifestJson(
                packageName = packageName,
                label = label,
                versionName = versionName,
                versionCode = versionCode,
                firstInstallTime = pkgInfo?.firstInstallTime ?: 0L,
                permissions = permissions,
                sources = sources,
            )
            val readme = buildReadme(packageName, label, versionName)

            try {
                ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
                    putStoredBytes(zip, "README.txt", readme.toByteArray())
                    putStoredBytes(zip, "manifest.json", manifestJson.toByteArray())
                    // Base APK first (installed first), then splits.
                    putStored(zip, "apks/base.apk", base)
                    sources.drop(1).forEach { putStored(zip, "apks/${it.name}", it) }
                }
            } catch (e: IOException) {
                outFile.delete()
                return@withContext ArchiveResult(error = "ERR_ZIP:${e.message ?: e.javaClass.simpleName}")
            }

            ArchiveResult(file = outFile, apkCount = sources.size, totalBytes = totalBytes)
        }

    /** Archives in the library, newest first. */
    fun listArchives(): List<ArchiveEntry> =
        archiveRoot().listFiles { f -> f.isFile && f.name.endsWith(".kaz") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val manifest = readManifest(file)
                ArchiveEntry(
                    file = file,
                    packageName = manifest?.optString("packageName")?.takeIf { it.isNotBlank() },
                    label = manifest?.optString("label")?.takeIf { it.isNotBlank() },
                    versionName = manifest?.optString("versionName")?.takeIf { it.isNotBlank() },
                    versionCode = manifest?.optLong("versionCode", 0L) ?: 0L,
                    archivedTime = manifest?.optLong("archivedTime", 0L) ?: 0L,
                    apkCount = manifest?.optInt("apkCount", 0) ?: 0,
                    sizeBytes = file.length(),
                )
            }
            .orEmpty()

    fun deleteArchive(file: File): Boolean = file.delete()

    fun totalArchiveBytes(): Long =
        archiveRoot().listFiles { f -> f.isFile && f.name.endsWith(".kaz") }
            ?.sumOf { it.length() } ?: 0L

    /**
     * Unpack an archive into `cacheDir/restore/<pkg>` so the caller can hand
     * the base + split APKs to the system installer. base.apk is returned first.
     */
    suspend fun restore(file: File): RestoreResult = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) {
            return@withContext RestoreResult(error = "ERR_ARCHIVE_GONE")
        }
        val outDir = File(context.cacheDir, "restore/${file.nameWithoutExtension}")
        outDir.deleteRecursively()
        if (!outDir.mkdirs()) {
            return@withContext RestoreResult(error = "ERR_MKDIR")
        }
        try {
            val written = mutableListOf<File>()
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.startsWith("apks/")) {
                        val out = File(outDir, entry.name.substringAfterLast('/'))
                        out.outputStream().buffered().use { outStream ->
                            zip.copyTo(outStream)
                        }
                        written += out
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            // base.apk installs first; everything else is a split.
            val base = written.firstOrNull { it.name == "base.apk" }
            val splits = written.filter { it.name != "base.apk" }.sortedBy { it.name }
            if (base == null) {
                RestoreResult(error = "ERR_NO_BASE")
            } else {
                RestoreResult(baseApk = base, splitApks = splits)
            }
        } catch (e: IOException) {
            RestoreResult(error = "ERR_UNZIP:${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Re-hash every APK inside the archive and compare against the manifest. */
    suspend fun verify(file: File): Boolean = withContext(Dispatchers.IO) {
        val manifest = readManifest(file) ?: return@withContext false
        val apks = manifest.optJSONArray("apks") ?: return@withContext false
        try {
            val stored = mutableMapOf<String, String>()
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.startsWith("apks/")) {
                        stored[entry.name.substringAfterLast('/')] =
                            sha256Stream(zip)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            for (i in 0 until apks.length()) {
                val item = apks.getJSONObject(i)
                val name = item.optString("name")
                val expected = item.optString("sha256")
                val actual = stored[name] ?: return@withContext false
                if (actual != expected) return@withContext false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- internals

    private fun buildManifestJson(
        packageName: String,
        label: String,
        versionName: String,
        versionCode: Long,
        firstInstallTime: Long,
        permissions: List<String>,
        sources: List<File>,
    ): String {
        val apks = JSONArray()
        // base is stored as base.apk regardless of its original file name.
        val names = listOf("base.apk") + sources.drop(1).map { it.name }
        sources.forEachIndexed { index, file ->
            apks.put(
                JSONObject().apply {
                    put("name", names.getOrElse(index) { file.name })
                    put("size", file.length())
                    put("sha256", sha256(file))
                }
            )
        }
        return JSONObject().apply {
            put("format", "yunlu.kaz")
            put("formatVersion", 1)
            put("packageName", packageName)
            put("label", label)
            put("versionName", versionName)
            put("versionCode", versionCode)
            put("firstInstallTime", firstInstallTime)
            put("archivedTime", System.currentTimeMillis())
            put("abi", Build.SUPPORTED_ABIS.joinToString(","))
            put("apkCount", sources.size)
            put("permissions", JSONArray(permissions))
            put("apks", apks)
            put(
                "note",
                "Restore installs into an environment WITHOUT this app. Installing " +
                    "over an existing install is not guaranteed (signature check).",
            )
        }.toString(2)
    }

    private fun buildReadme(packageName: String, label: String, versionName: String): String =
        """
        云麓空间 · 应用归档包 (.kaz)
        ===========================
        应用：$label
        包名：$packageName
        版本：$versionName

        内容
        ----
        - apks/base.apk          主包（最先安装）
        - apks/split_config.*.apk 配置分片（按原文件名）
        - manifest.json          包名/版本/安装时间/权限快照/ABI/每个 APK 的 SHA-256

        还原方法
        --------
        将 apks/ 下所有 APK 传到目标设备，安装 base.apk 后按提示逐个安装分片；
        或在本应用「归档库」中点击还原（会调起系统安装器）。

        重要限制
        --------
        本归档用于「装到没有该应用的环境」。若目标机已存在同一包名的应用，
        因签名校验，不保证能原位覆盖安装。
        """.trimIndent()

    private fun readManifest(file: File): JSONObject? = try {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "manifest.json") {
                    return@use JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        null
    } catch (_: Exception) {
        null
    }

    private fun putStored(zip: ZipOutputStream, name: String, file: File) {
        val crc = CRC32()
        var size = 0L
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buf).also { read = it } > 0) {
                crc.update(buf, 0, read)
                size += read
            }
        }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            this.crc = crc.value
        }
        zip.putNextEntry(entry)
        file.inputStream().buffered().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun putStoredBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32()
        crc.update(bytes)
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun sha256(file: File): String =
        file.inputStream().buffered().use { sha256Stream(it) }

    /** Streaming SHA-256 so multi-hundred-MB APKs never land in memory whole. */
    private fun sha256Stream(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var read: Int
        while (input.read(buf).also { read = it } > 0) {
            digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val ARCHIVE_DIR = "archive"
        const val ARCHIVE_EXT = ".kaz"
        const val MIME_ARCHIVE = "application/vnd.yunlu.kaz"
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        /** 4GB per-archive cap to prevent storage exhaustion. */
        private const val MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024
        private val UNSAFE_CHARS = Regex("[^\\p{L}\\p{N}._-]")
    }
}
