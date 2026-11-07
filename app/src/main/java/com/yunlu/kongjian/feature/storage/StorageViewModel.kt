package com.yunlu.kongjian.feature.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yunlu.kongjian.data.AppRepository
import com.yunlu.kongjian.core.model.DeviceStorage
import com.yunlu.kongjian.feature.applist.StorageSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the global storage page (V3 B1). Aggregates device totals and the
 * per-app footprint summary by re-using [AppRepository] (its 60s storage cache
 * makes this cheap when the list already populated it).
 */
@HiltViewModel
class StorageViewModel @Inject constructor(
    private val repository: AppRepository,
) : ViewModel() {

    private val _device = MutableStateFlow<DeviceStorage?>(null)
    val device: StateFlow<DeviceStorage?> = _device.asStateFlow()

    private val _summary = MutableStateFlow(StorageSummary(0, 0, 0))
    val summary: StateFlow<StorageSummary> = _summary.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _device.value = runCatching { repository.queryDeviceStorage() }.getOrNull()
            val apps = runCatching { repository.loadApps() }.getOrDefault(emptyList())
            val footprints = runCatching { repository.queryAllStorage() }.getOrDefault(emptyMap())
            var userTotal = 0L
            var systemTotal = 0L
            apps.forEach { app ->
                val total = footprints[app.packageName] ?: 0L
                if (app.isSystemApp) systemTotal += total else userTotal += total
            }
            val cacheTotal = runCatching { repository.queryCacheTotal() }.getOrDefault(0L)
            _summary.value = StorageSummary(userTotal, systemTotal, cacheTotal)
            _isLoading.value = false
        }
    }
}
