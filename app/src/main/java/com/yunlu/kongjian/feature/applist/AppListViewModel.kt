package com.yunlu.kongjian.feature.applist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yunlu.kongjian.core.model.AppInfo
import com.yunlu.kongjian.core.model.SortMode
import com.yunlu.kongjian.core.model.applyFilter
import com.yunlu.kongjian.data.ApkExtractor
import com.yunlu.kongjian.data.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppListUiState(
    val isLoading: Boolean = true,
    val query: String = "",
    val sortMode: SortMode = SortMode.NAME,
    val idleOnly: Boolean = false,
    val usageAvailable: Boolean = false,
    val userApps: List<AppInfo> = emptyList(),
    val systemApps: List<AppInfo> = emptyList(),
)

/** Aggregate on-disk footprint totals shown at the top of the list. */
data class StorageSummary(
    val userTotalBytes: Long,
    val systemTotalBytes: Long,
    val cacheTotalBytes: Long,
)

/** State of a batch-extract run. */
sealed interface BatchExtractState {
    data object Idle : BatchExtractState
    data class Running(val done: Int, val total: Int) : BatchExtractState
    data class Done(val success: Int, val failed: Int, val totalBytes: Long) : BatchExtractState
}

@HiltViewModel
class AppListViewModel @Inject constructor(
    private val repository: AppRepository,
    private val apkExtractor: ApkExtractor,
) : ViewModel() {

    private val allApps = MutableStateFlow<List<AppInfo>>(emptyList())
    private val query = MutableStateFlow("")
    private val sortMode = MutableStateFlow(SortMode.NAME)
    private val isLoading = MutableStateFlow(true)

    private val _batchExtractState = MutableStateFlow<BatchExtractState>(BatchExtractState.Idle)
    val batchExtractState: StateFlow<BatchExtractState> = _batchExtractState

    private val _batchExtractMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val batchExtractMessage: SharedFlow<String> = _batchExtractMessage

    private val _storageSummary = MutableStateFlow(StorageSummary(0, 0, 0))
    val storageSummary: StateFlow<StorageSummary> = _storageSummary

    private val _usageAvailable = MutableStateFlow(false)
    val usageAvailable: StateFlow<Boolean> = _usageAvailable

    private val _idleOnly = MutableStateFlow(false)

    val uiState: StateFlow<AppListUiState> =
        combine(allApps, query, sortMode, isLoading, _idleOnly) { apps, q, sort, loading, idle ->
            AppListUiState(
                isLoading = loading,
                query = q,
                sortMode = sort,
                idleOnly = idle,
                usageAvailable = _usageAvailable.value,
                userApps = apps.applyFilter(includeSystem = false, query = q, sort = sort, idleOnly = idle),
                systemApps = apps.applyFilter(includeSystem = true, query = q, sort = sort, idleOnly = idle),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppListUiState(),
        )

    init {
        // Re-query whenever the installed-app set changes (install / uninstall / update).
        viewModelScope.launch {
            repository.appChanges.collect { refresh() }
        }
        refresh()
    }

    private var refreshJob: Job? = null

    fun refresh() {
        // Cancel any in-flight refresh so a slow query can never leave
        // isLoading stuck at true (that would freeze the UI on the spinner).
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            isLoading.value = true
            allApps.value = runCatching { repository.loadApps() }.getOrDefault(emptyList())
            isLoading.value = false
            // Fill on-disk footprints in the background; the list is already
            // visible and only re-sorts once storageBytes is populated.
            runCatching {
                val footprints = repository.queryAllStorage()
                val cacheTotal = repository.queryCacheTotal()
                // V3 B2: fill usage stats in the SAME pass so the list only
                // re-orders once (merging in two steps would re-sort twice).
                _usageAvailable.value = repository.hasUsageStatsPermission()
                val usageMap = repository.queryAllUsage()
                allApps.value = allApps.value.map { app ->
                    app.copy(
                        storageBytes = footprints[app.packageName] ?: 0L,
                        usage = usageMap[app.packageName],
                    )
                }
                var userTotal = 0L
                var systemTotal = 0L
                allApps.value.forEach { app ->
                    if (app.isSystemApp) systemTotal += app.storageBytes
                    else userTotal += app.storageBytes
                }
                _storageSummary.value = StorageSummary(userTotal, systemTotal, cacheTotal)
            }
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onSortModeChange(mode: SortMode) {
        sortMode.value = mode
    }

    fun onToggleIdleOnly() {
        _idleOnly.value = !_idleOnly.value
    }

    fun buildUninstallIntent(packageName: String) =
        repository.buildUninstallIntent(packageName)

    // ---------------------------------------------------- batch extract APKs

    fun extractPackages(packages: Set<String>) {
        if (packages.isEmpty() || _batchExtractState.value is BatchExtractState.Running) return
        viewModelScope.launch {
            var success = 0
            var failed = 0
            var totalBytes = 0L
            _batchExtractState.value = BatchExtractState.Running(0, packages.size)
            packages.forEachIndexed { index, pkg ->
                val result = runCatching {
                    apkExtractor.extract(pkg, includeSplits = true)
                }.getOrNull()
                if (result?.success == true) {
                    success++
                    totalBytes += result.totalBytes
                } else {
                    failed++
                }
                _batchExtractState.value = BatchExtractState.Running(index + 1, packages.size)
            }
            _batchExtractState.value = BatchExtractState.Done(success, failed, totalBytes)
            val msg = if (failed == 0) {
                "已提取 $success 个应用（${totalBytes / 1024 / 1024} MB），可在「设置」页查看并分享"
            } else {
                "提取完成：成功 $success 个，失败 $failed 个"
            }
            _batchExtractMessage.emit(msg)
        }
    }
}
