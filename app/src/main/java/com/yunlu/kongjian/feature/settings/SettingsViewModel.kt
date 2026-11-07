package com.yunlu.kongjian.feature.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yunlu.kongjian.data.ApkExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val extractor: ApkExtractor,
) : ViewModel() {

    private val _bundleSummary = MutableStateFlow(0 to 0L)
    /** extracted bundle count to total bytes on disk. */
    val bundleSummary: StateFlow<Pair<Int, Long>> = _bundleSummary.asStateFlow()

    private val _clearedCount = MutableStateFlow<Int?>(null)
    val clearedCount: StateFlow<Int?> = _clearedCount.asStateFlow()

    private val _openDirResult = MutableSharedFlow<OpenDirResult>(extraBufferCapacity = 1)
    val openDirResult: SharedFlow<OpenDirResult> = _openDirResult.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val bundles = extractor.listExtracted()
            val total = bundles.sumOf { dir ->
                dir.listFiles()?.sumOf { it.length() } ?: 0L
            }
            _bundleSummary.value = bundles.size to total
        }
    }

    fun clearExtracted() {
        viewModelScope.launch(Dispatchers.IO) {
            _clearedCount.value = extractor.clearAll()
            refresh()
        }
    }

    /**
     * Sync every extracted bundle into the public Download/云麓空间 folder,
     * then expose an Intent that opens that folder in the system file manager.
     */
    fun openExtractDir() {
        viewModelScope.launch(Dispatchers.IO) {
            val bundles = extractor.listExtracted()
            if (bundles.isEmpty()) {
                _openDirResult.emit(OpenDirResult.Empty)
                return@launch
            }
            var failed = false
            for (dir in bundles) {
                val result = extractor.saveToDownloads(dir)
                if (!result.success) failed = true
            }
            val intent = extractor.buildOpenDownloadsDirIntent()
            if (intent == null || failed) {
                _openDirResult.emit(OpenDirResult.NoIntent)
            } else {
                _openDirResult.emit(OpenDirResult.Ready(intent))
            }
        }
    }

    fun consumeNotice() {
        _clearedCount.value = null
    }
}

/** Outcome of "open extract dir". */
sealed interface OpenDirResult {
    data object Empty : OpenDirResult
    data object NoIntent : OpenDirResult
    data class Ready(val intent: Intent) : OpenDirResult
}