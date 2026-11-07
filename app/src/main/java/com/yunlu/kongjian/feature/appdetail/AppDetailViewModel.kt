package com.yunlu.kongjian.feature.appdetail

import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yunlu.kongjian.core.model.AppDetail
import com.yunlu.kongjian.core.model.StorageUsage
import com.yunlu.kongjian.core.model.UsageInfo
import com.yunlu.kongjian.data.ApkExtractResult
import com.yunlu.kongjian.data.ApkExtractor
import com.yunlu.kongjian.data.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppDetailUiState(
    val isLoading: Boolean = true,
    val detail: AppDetail? = null,
    val notFound: Boolean = false,
)

/** Per-package storage breakdown shown on the detail page (V3 B1). */
data class StorageUiState(
    val isLoading: Boolean = false,
    val usage: StorageUsage? = null,
)

/** V3 B2: per-package usage stats shown on the detail page. */
data class UsageUiState(
    val isLoading: Boolean = false,
    val usage: UsageInfo? = null,
    /** False => feature hidden entirely; true + null usage => show "no data". */
    val permissionGranted: Boolean = false,
)

/**
 * Extraction is kept separate from [AppDetailUiState] so that a long-running
 * copy never re-triggers the detail reload, and so the UI can show progress
 * independently of the page content.
 */
data class ExtractUiState(
    val running: Boolean = false,
    val saving: Boolean = false,
    val result: ApkExtractResult? = null,
    val savedCount: Int? = null,
    /** Raw failure code (e.g. ERR_NO_SOURCE); the UI maps it to localized text. */
    val error: String? = null,
) {
    val showDialog: Boolean get() = running || result != null
}

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AppRepository,
    private val extractor: ApkExtractor,
) : ViewModel() {

    private val packageName: String = savedStateHandle["packageName"] ?: ""

    private val _uiState = MutableStateFlow(AppDetailUiState())
    val uiState: StateFlow<AppDetailUiState> = _uiState.asStateFlow()

    private val _extractState = MutableStateFlow(ExtractUiState())
    val extractState: StateFlow<ExtractUiState> = _extractState.asStateFlow()

    private val _storageState = MutableStateFlow(StorageUiState())
    val storageState: StateFlow<StorageUiState> = _storageState.asStateFlow()

    private val _usageState = MutableStateFlow(UsageUiState())
    val usageState: StateFlow<UsageUiState> = _usageState.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            // If this app gets uninstalled/updated while we're looking at it.
            repository.appChanges.collect { load() }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = AppDetailUiState(isLoading = true)
            val detail = repository.loadAppDetail(packageName)
            _uiState.value = if (detail == null) {
                AppDetailUiState(isLoading = false, notFound = true)
            } else {
                AppDetailUiState(isLoading = false, detail = detail)
            }
            // Storage breakdown is queried separately so the page content is
            // not blocked on the (Binder) StorageStatsManager call.
            _storageState.value = StorageUiState(isLoading = true)
            val storage = runCatching { repository.queryAppStorage(packageName) }.getOrNull()
            _storageState.value = StorageUiState(isLoading = false, usage = storage)
            // V3 B2: usage stats are permission-gated. When the permission is
            // absent we surface nothing (iron rule); when present but the app
            // has no record we show "no data" rather than "idle".
            _usageState.value = UsageUiState(isLoading = true)
            val perm = repository.hasUsageStatsPermission()
            if (perm) {
                val all = runCatching { repository.queryAllUsage() }.getOrNull().orEmpty()
                _usageState.value = UsageUiState(isLoading = false, permissionGranted = true, usage = all[packageName])
            } else {
                _usageState.value = UsageUiState(isLoading = false, permissionGranted = false)
            }
        }
    }

    fun buildUninstallIntent() = repository.buildUninstallIntent(packageName)

    fun buildAppSettingsIntent() = repository.buildAppSettingsIntent(packageName)

    fun buildLaunchIntent() = repository.buildLaunchIntent(packageName)

    fun buildUsageSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    // ------------------------------------------------------------- apk extraction

    fun extract(includeSplits: Boolean) {
        if (_extractState.value.running) return
        viewModelScope.launch {
            _extractState.value = ExtractUiState(running = true)
            val result = extractor.extract(packageName, includeSplits)
            _extractState.value = ExtractUiState(result = result, error = result.error)
        }
    }

    fun saveToDownloads() {
        val dir = _extractState.value.result?.dir ?: return
        if (_extractState.value.saving) return
        viewModelScope.launch {
            _extractState.value = _extractState.value.copy(saving = true, error = null)
            val saveResult = extractor.saveToDownloads(dir)
            _extractState.value = _extractState.value.copy(
                saving = false,
                savedCount = saveResult.count.takeIf { saveResult.success },
                error = saveResult.error,
            )
        }
    }

    fun buildShareIntent(chooserTitle: String): Intent? {
        val dir = _extractState.value.result?.dir ?: return null
        return extractor.buildShareIntent(dir, chooserTitle)
    }

    /** True when this app ships configuration splits (base-only won't install). */
    fun hasSplitApks(): Boolean {
        val dir = _extractState.value.result?.dir ?: return false
        return dir.listFiles { f -> f.name.endsWith(".apk") }?.size ?: 0 > 1
    }

    fun dismissExtractDialog() {
        _extractState.value = ExtractUiState()
    }

    fun consumeSavedNotice() {
        _extractState.value = _extractState.value.copy(savedCount = null)
    }

    fun consumeError() {
        _extractState.value = _extractState.value.copy(error = null)
    }

    /** Internal state touch-up used when the screen needs to force-refresh. */
    internal fun refreshExtractState(transform: (ExtractUiState) -> ExtractUiState) {
        _extractState.update(transform)
    }
}