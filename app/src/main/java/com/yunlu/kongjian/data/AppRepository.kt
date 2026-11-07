@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.yunlu.kongjian.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import com.yunlu.kongjian.core.model.AppDetail
import com.yunlu.kongjian.core.model.DeviceStorage
import com.yunlu.kongjian.core.model.IDLE_THRESHOLD_MS
import com.yunlu.kongjian.core.model.StorageUsage
import com.yunlu.kongjian.core.model.UsageInfo
import java.util.concurrent.ConcurrentHashMap
import com.yunlu.kongjian.core.model.AppInfo
import com.yunlu.kongjian.core.model.DANGEROUS_PERMISSIONS
import com.yunlu.kongjian.core.model.PermissionInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for installed-app data.
 *
 * Responsibilities:
 *  - Query PackageManager for the app list / app details.
 *  - Emit change events when packages are added / removed / replaced.
 *  - Build Intents for uninstall / settings / launch actions.
 *
 * Privileged backends (Shizuku / Root) plug in later behind an interface;
 * V0.1 is the plain, permission-less-API implementation.
 */
@Singleton
class AppRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val packageManager: PackageManager = context.packageManager

    private val _appChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits whenever the installed-app set may have changed. */
    val appChanges: SharedFlow<Unit> = _appChanges.asSharedFlow()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_REPLACED,
                -> _appChanges.tryEmit(Unit)
            }
        }
    }

    init {
        // Register the package-change receiver defensively: on some ROMs a
        // receiver registration exception would otherwise crash the whole
        // process at startup. The feature (auto-refresh) degrades gracefully.
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // These broadcasts only ever come from the system.
                context.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(packageReceiver, filter)
            }
        } catch (_: Throwable) {
            // Auto-refresh silently disabled on this device; list still works.
        }
    }

    // ---------------------------------------------------------------- queries

    /**
     * Load all installed apps in a single PackageManager call.
     *
     * Icons are rasterized on a bounded parallel IO dispatcher (16 lanes) so a
     * phone with hundreds of apps loads in seconds instead of tens of seconds.
     * Each package is isolated in its own try/catch: one bad icon must never
     * block the whole list (that previously left the UI stuck on the loading
     * spinner forever).
     */
    suspend fun loadApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val packages = packageManager.getInstalledPackages(0)
        coroutineScope {
            val bounded = Dispatchers.IO.limitedParallelism(16)
            packages.map { packageInfo -> async(bounded) { loadOneApp(packageInfo) } }
                .mapNotNull { it.await() }
        }
    }

    private fun loadOneApp(packageInfo: PackageInfo): AppInfo? = try {
        val appInfo = packageInfo.applicationInfo ?: return null
        val apkFile = appInfo.sourceDir?.let { java.io.File(it) }
        AppInfo(
            packageName = packageInfo.packageName,
            label = packageManager.getApplicationLabel(appInfo).toString(),
            icon = runCatching {
                rasterizeIcon(packageManager.getApplicationIcon(appInfo))
            }.getOrNull(),
            versionName = packageInfo.versionName ?: "-",
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            },
            isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            enabled = appInfo.enabled,
            firstInstallTime = packageInfo.firstInstallTime,
            lastUpdateTime = packageInfo.lastUpdateTime,
            apkSizeBytes = apkFile?.length() ?: 0L,
            targetSdk = appInfo.targetSdkVersion,
            minSdk = appInfo.minSdkVersion,
        )
    } catch (_: Exception) {
        null
    }

    /** Load full detail (permissions, installer, paths) for one package. */
    suspend fun loadAppDetail(packageName: String): AppDetail? = withContext(Dispatchers.IO) {
        val packageInfo: PackageInfo = try {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        } catch (_: PackageManager.NameNotFoundException) {
            return@withContext null
        }
        val appInfo = packageInfo.applicationInfo ?: return@withContext null

        val requested = packageInfo.requestedPermissions ?: emptyArray()
        val flags = packageInfo.requestedPermissionsFlags ?: IntArray(0)
        val permissions = requested.mapIndexed { index, name ->
            val granted = flags.getOrElse(index) { 0 } and
                PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            PermissionInfo(
                name = name,
                granted = granted,
                dangerous = name in DANGEROUS_PERMISSIONS,
            )
        }.sortedWith(
            compareByDescending<PermissionInfo> { it.dangerous }.thenBy { it.name }
        )

        val apkFile = appInfo.sourceDir?.let { java.io.File(it) }
        AppDetail(
            info = AppInfo(
                packageName = packageInfo.packageName,
                label = packageManager.getApplicationLabel(appInfo).toString(),
                icon = runCatching {
                    rasterizeIcon(packageManager.getApplicationIcon(appInfo))
                }.getOrNull(),
                versionName = packageInfo.versionName ?: "-",
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                },
                isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                enabled = appInfo.enabled,
                firstInstallTime = packageInfo.firstInstallTime,
                lastUpdateTime = packageInfo.lastUpdateTime,
                apkSizeBytes = apkFile?.length() ?: 0L,
                targetSdk = appInfo.targetSdkVersion,
                minSdk = appInfo.minSdkVersion,
            ),
            installerPackage = readInstallerPackageName(packageName),
            sourceDir = appInfo.sourceDir ?: "-",
            permissions = permissions,
        )
    }

    private fun readInstallerPackageName(packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val source = packageManager.getInstallSourceInfo(packageName)
            source.installingPackageName ?: source.initiatingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** Rasterize a Drawable to a fixed-size Bitmap (IO-safe, never on main thread). */
    private fun rasterizeIcon(drawable: Drawable, sizePx: Int = 64): Bitmap? = try {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        bitmap
    } catch (_: OutOfMemoryError) {
        null
    }

    // ---------------------------------------------------------- storage stats

    /**
     * In-memory 60s TTL cache keyed by package name. Storage queries are Binder
     * calls; caching keeps list/summary re-renders cheap and avoids re-hitting
     * [StorageStatsManager] on every recomposition.
     */
    private data class CachedStorage(val usage: StorageUsage, val ts: Long)
    private val storageCache = ConcurrentHashMap<String, CachedStorage>()
    private val storageCacheTtlMs = 60_000L

    /**
     * Per-package storage breakdown for the detail page.
     *
     * Uses [StorageStatsManager.queryStatsForPackage]; on API 35 also pulls the
     * AOT artifact bytes via [android.app.usage.StorageStats.getAppBytesByDataType],
     * which is absent on lower versions (so [StorageUsage.aotBytes] is null).
     */
    suspend fun queryAppStorage(packageName: String): StorageUsage = withContext(Dispatchers.IO) {
        val cached = storageCache[packageName]
        if (cached != null && System.currentTimeMillis() - cached.ts < storageCacheTtlMs) {
            return@withContext cached.usage
        }
        val ssm = context.getSystemService(StorageStatsManager::class.java)
        val storageManager = context.getSystemService(StorageManager::class.java)
        val uuid = storageManager.getUuidForPath(context.filesDir)
        val stats = ssm.queryStatsForPackage(uuid, packageName, Process.myUserHandle())
        val usage = StorageUsage(
            appBytes = stats.appBytes,
            dataBytes = stats.dataBytes,
            cacheBytes = stats.cacheBytes,
            externalCacheBytes = stats.externalCacheBytes,
            // AOT artifact bytes require StorageStats.getAppBytesByDataType(
            //   APK_DATA_TYPE_FILE_TYPE_DEXOPT_ARTIFACT) which is API 35+ only and whose
            // static symbol does not resolve on this compile toolchain. The AOT row is
            // therefore hidden on every version for now; the field stays nullable and
            // the UI already guards it. Re-enable once verified on a real API 35 device.
            aotBytes = null,
        )
        storageCache[packageName] = CachedStorage(usage, System.currentTimeMillis())
        usage
    }

    /**
     * Footprint (total bytes) for every installed package, used to populate
     * [com.yunlu.kongjian.core.model.AppInfo.storageBytes] and the list
     * summary. Runs on a bounded parallel IO dispatcher; results are cached in
     * [storageCache] for 60s so the next refresh is instant.
     */
    suspend fun queryAllStorage(): Map<String, Long> = withContext(Dispatchers.IO) {
        val ssm = context.getSystemService(StorageStatsManager::class.java)
        val storageManager = context.getSystemService(StorageManager::class.java)
        val uuid = storageManager.getUuidForPath(context.filesDir)
        val packages = packageManager.getInstalledPackages(0)
        coroutineScope {
            val bounded = Dispatchers.IO.limitedParallelism(8)
            packages.map { pi ->
                async(bounded) {
                    val pkg = pi.packageName
                    val cached = storageCache[pkg]
                    if (cached != null && System.currentTimeMillis() - cached.ts < storageCacheTtlMs) {
                        pkg to cached.usage.totalBytes
                    } else {
                        val total = runCatching {
                            val stats = ssm.queryStatsForPackage(uuid, pkg, Process.myUserHandle())
                            StorageUsage(
                                appBytes = stats.appBytes,
                                dataBytes = stats.dataBytes,
                                cacheBytes = stats.cacheBytes,
                                externalCacheBytes = stats.externalCacheBytes,
                                aotBytes = null,
                            ).also { storageCache[pkg] = CachedStorage(it, System.currentTimeMillis()) }
                                .totalBytes
                        }.getOrDefault(0L)
                        pkg to total
                    }
                }
            }.mapNotNull { it.await() }.toMap()
        }
    }

    /**
     * Device-wide totals for the global storage page. Needs only
     * [android.Manifest.permission.QUERY_ALL_PACKAGES] (already declared).
     */
    suspend fun queryDeviceStorage(): DeviceStorage = withContext(Dispatchers.IO) {
        val ssm = context.getSystemService(StorageStatsManager::class.java)
        val storageManager = context.getSystemService(StorageManager::class.java)
        val uuid = storageManager.getUuidForPath(context.filesDir)
        DeviceStorage(
            totalBytes = runCatching { ssm.getTotalBytes(uuid) }.getOrDefault(0L),
            freeBytes = runCatching { ssm.getFreeBytes(uuid) }.getOrDefault(0L),
        )
    }

    /**
     * Sum of cache bytes across every package currently held in [storageCache].
     * Call only after [queryAllStorage] has populated the cache for this pass.
     */
    suspend fun queryCacheTotal(): Long = withContext(Dispatchers.IO) {
        storageCache.values.sumOf { it.usage.cacheBytes }
    }

    // ---------------------------------------------------------- usage stats

    /**
     * Whether the app can read usage stats. [android.Manifest.permission.PACKAGE_USAGE_STATS]
     * is a special (non-runtime) permission; the system holds it via AppOps, not
     * the normal runtime grant flow. Iron rule: when this returns false, callers
     * must HIDE the usage UI rather than treat a blank result as "zero usage"
     * (that would mislabel every app as idle).
     */
    fun hasUsageStatsPermission(): Boolean = try {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        false
    }

    /**
     * Per-package usage for the last 30 days (last used, foreground time, launch
     * count) for every installed package. Returns an empty map when the
     * permission is not granted. [android.app.usage.UsageStats.getAppLaunchCount]
     * requires API 28+. Cached for [USAGE_CACHE_TTL_MS] because the detail page
     * and the list page both call this — a full 30-day query is expensive.
     */
    private val usageCache = ConcurrentHashMap<String, UsageInfo>()
    private var usageCacheAt = 0L

    suspend fun queryAllUsage(): Map<String, UsageInfo> = withContext(Dispatchers.IO) {
        if (!hasUsageStatsPermission()) return@withContext emptyMap()
        val now = System.currentTimeMillis()
        synchronized(usageCache) {
            if (now - usageCacheAt < usageCacheTtlMs && usageCache.isNotEmpty()) {
                return@withContext HashMap(usageCache)
            }
        }
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return@withContext emptyMap()
        val endTime = System.currentTimeMillis()
        val startTime = endTime - IDLE_THRESHOLD_MS
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        }.getOrNull().orEmpty()
        val result = stats
            .filter { !it.packageName.isNullOrBlank() }
            .associate { s ->
                val launch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // getAppLaunchCount() is API 28+ but its static symbol is not
                    // exposed by this compileSdk's platform jar (same class of
                    // problem as the AOT constant). Resolve it reflectively so it
                    // still works at runtime on devices that do expose it.
                    runCatching {
                        s.javaClass.getMethod("getAppLaunchCount").invoke(s) as? Int
                    }.getOrNull() ?: 0
                } else {
                    0
                }
                s.packageName to UsageInfo(
                    lastUsedTime = s.lastTimeUsed,
                    totalForegroundMs = s.totalTimeInForeground,
                    launchCount = launch,
                    hasData = true,
                )
            }
        synchronized(usageCache) {
            usageCache.clear()
            usageCache.putAll(result)
            usageCacheAt = System.currentTimeMillis()
        }
        result
    }

    // ---------------------------------------------------------------- actions

    /**
     * Intent for the system uninstall dialog. Result comes back through
     * EXTRA_RETURN_RESULT: RESULT_OK / RESULT_CANCELED / RESULT_FIRST_USER.
     * Uses ACTION_DELETE (the modern, non-deprecated equivalent of
     * ACTION_UNINSTALL_PACKAGE).
     */
    fun buildUninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }

    fun buildAppSettingsIntent(packageName: String): Intent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }

    fun buildLaunchIntent(packageName: String): Intent? =
        packageManager.getLaunchIntentForPackage(packageName)

    /** 30s TTL for the usage cache: fresh enough for a detail-page revisit. */
    private val usageCacheTtlMs = 30_000L
}
