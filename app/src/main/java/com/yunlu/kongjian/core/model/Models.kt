package com.yunlu.kongjian.core.model

import android.graphics.Bitmap

/** Sort modes for the app list. */
enum class SortMode {
    NAME,
    SIZE,
    INSTALL_TIME,
    /** V3 B1: sort by on-disk footprint (APK + data + cache + external + AOT). */
    STORAGE,
    /** V3 B2: usage-based sorts (only meaningful when PACKAGE_USAGE_STATS is granted). */
    LAST_USED,
    USAGE_DURATION,
    LAUNCH_COUNT,
}

/**
 * An installed application, as shown in the list.
 *
 * Icons are pre-rasterized to a small Bitmap on a background dispatcher so the
 * Compose list never touches Drawables on the main thread.
 *
 * [storageBytes] is the on-disk footprint filled in asynchronously by
 * [com.yunlu.kongjian.data.AppRepository.queryAllStorage]; it is 0 until that
 * pass completes (the list never blocks on it).
 *
 * [usage] is the usage statistics filled in asynchronously by
 * [com.yunlu.kongjian.data.AppRepository.queryAllUsage]; it is null until that
 * pass completes or when the permission is not granted.
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val enabled: Boolean,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val apkSizeBytes: Long,
    val targetSdk: Int,
    val minSdk: Int,
    val storageBytes: Long = 0L,
    /** V3 B2: usage stats; null until filled or when the permission is absent. */
    val usage: UsageInfo? = null,
)

/** A single requested permission of an app. */
data class PermissionInfo(
    val name: String,
    val granted: Boolean,
    val dangerous: Boolean,
)

/** Full detail of an app (loaded lazily when the detail page opens). */
data class AppDetail(
    val info: AppInfo,
    val installerPackage: String?,
    val sourceDir: String,
    val permissions: List<PermissionInfo>,
)

/**
 * Per-package on-disk storage breakdown, queried via [android.app.usage.StorageStatsManager].
 *
 * All fields are raw byte counts. [aotBytes] is only populated on API 35+; on
 * lower versions it is `null` and the UI must not render that row.
 */
data class StorageUsage(
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
    val externalCacheBytes: Long,
    /** API 35+ only; null on lower versions. */
    val aotBytes: Long?,
) {
    /** Total on-disk footprint used by sort/summary math. */
    val totalBytes: Long
        get() = appBytes + dataBytes + cacheBytes + externalCacheBytes + (aotBytes ?: 0L)
}

/** Device-wide storage totals for the global storage page. */
data class DeviceStorage(
    val totalBytes: Long,
    val freeBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
}

/**
 * V3 B2: per-app usage statistics.
 *
 * [hasData] distinguishes "no permission / no records" from "app genuinely
 * unused". The UI must never treat a missing record as "idle" — that would
 * flag every app as idle, which is the worst kind of false injury.
 *
 * [lastUsedTime] is epoch millis (0 = never used in the queried window);
 * [totalForegroundMs] and [launchCount] come from UsageStatsManager.
 */
data class UsageInfo(
    val lastUsedTime: Long,
    val totalForegroundMs: Long,
    val launchCount: Int,
    val hasData: Boolean,
)

/** Idle threshold: a continuous 30-day gap with no foreground use. */
const val IDLE_THRESHOLD_MS: Long = 30L * 24 * 60 * 60 * 1000

/** True when the app is idle (never used, or unused for [IDLE_THRESHOLD_MS]). */
fun UsageInfo?.isIdle(now: Long = System.currentTimeMillis()): Boolean =
    this != null && hasData && (lastUsedTime == 0L || now - lastUsedTime > IDLE_THRESHOLD_MS)

/**
 * Approximate dangerous-permission set used for highlighting in the detail
 * page. Deliberately conservative: unknown permissions are treated as
 * non-dangerous rather than raising false alarms.
 */
val DANGEROUS_PERMISSIONS: Set<String> = setOf(
    "android.permission.READ_CALENDAR",
    "android.permission.WRITE_CALENDAR",
    "android.permission.READ_CALL_LOG",
    "android.permission.WRITE_CALL_LOG",
    "android.permission.PROCESS_OUTGOING_CALLS",
    "android.permission.CAMERA",
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.GET_ACCOUNTS",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.RECORD_AUDIO",
    "android.permission.READ_PHONE_STATE",
    "android.permission.READ_PHONE_NUMBERS",
    "android.permission.CALL_PHONE",
    "android.permission.ANSWER_PHONE_CALLS",
    "android.permission.ADD_VOICEMAIL",
    "android.permission.USE_SIP",
    "android.permission.ACCEPT_HANDOVER",
    "android.permission.BODY_SENSORS",
    "android.permission.SEND_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_WAP_PUSH",
    "android.permission.RECEIVE_MMS",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.ACCESS_MEDIA_LOCATION",
    "android.permission.ACTIVITY_RECOGNITION",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_CONNECT",
)

/** Filter + sort an app list for display. */
fun List<AppInfo>.applyFilter(
    includeSystem: Boolean,
    query: String,
    sort: SortMode,
    idleOnly: Boolean = false,
): List<AppInfo> {
    val now = System.currentTimeMillis()
    return asSequence()
        .filter { it.isSystemApp == includeSystem }
        .filter {
            query.isBlank() ||
                it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
        .filter { !idleOnly || it.usage.isIdle(now) }
        .let { seq ->
            when (sort) {
                SortMode.NAME -> seq.sortedBy { it.label.lowercase() }
                SortMode.SIZE -> seq.sortedByDescending { it.apkSizeBytes }
                SortMode.INSTALL_TIME -> seq.sortedByDescending { it.firstInstallTime }
                SortMode.STORAGE -> seq.sortedByDescending { it.storageBytes }
                SortMode.LAST_USED -> seq.sortedByDescending { it.usage?.lastUsedTime ?: 0L }
                SortMode.USAGE_DURATION -> seq.sortedByDescending { it.usage?.totalForegroundMs ?: 0L }
                SortMode.LAUNCH_COUNT -> seq.sortedByDescending { it.usage?.launchCount ?: 0 }
            }
        }
        .toList()
}
