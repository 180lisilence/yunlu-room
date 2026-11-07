package com.yunlu.kongjian.feature.applist

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yunlu.kongjian.R
import com.yunlu.kongjian.core.model.AppInfo
import com.yunlu.kongjian.core.model.SortMode
import com.yunlu.kongjian.core.util.formatDateTime
import com.yunlu.kongjian.core.util.formatFileSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    onAppClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStorage: () -> Unit,
    viewModel: AppListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val storageSummary by viewModel.storageSummary.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var tabIndex by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var itemMenuFor by remember { mutableStateOf<String?>(null) }

    // ---- Batch uninstall queue -------------------------------------------
    // The system only shows one uninstall dialog at a time, so a queue drives
    // them sequentially. `tick` (re)starts the driver effect; `index` advances
    // on each result callback.
    var pendingUninstall by remember { mutableStateOf<List<String>>(emptyList()) }
    var uninstallIndex by remember { mutableIntStateOf(-1) }
    var uninstallTick by remember { mutableIntStateOf(0) }

    val uninstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val message = when (result.resultCode) {
            Activity.RESULT_OK -> context.getString(R.string.uninstall_done)
            Activity.RESULT_CANCELED -> context.getString(R.string.uninstall_canceled)
            else -> context.getString(R.string.uninstall_failed)
        }
        scope.launch { snackbarHostState.showSnackbar(message) }
        viewModel.refresh()
        uninstallIndex += 1
    }

    // ---- Batch APK extract -----------------------------------------------
    val batchExtractState by viewModel.batchExtractState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.batchExtractMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
            selected = emptySet()
        }
    }

    LaunchedEffect(uninstallTick, uninstallIndex) {
        if (uninstallIndex in pendingUninstall.indices) {
            uninstallLauncher.launch(
                viewModel.buildUninstallIntent(pendingUninstall[uninstallIndex])
            )
        } else if (pendingUninstall.isNotEmpty() && uninstallIndex >= pendingUninstall.size) {
            // Queue finished.
            pendingUninstall = emptyList()
            uninstallIndex = -1
            selected = emptySet()
        }
    }

    fun startUninstall(packageNames: List<String>) {
        val filtered = packageNames.filterNot { it == context.packageName }
        if (filtered.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.uninstall_self_error)) }
            return
        }
        if (filtered.size > 1) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.uninstall_batch_notice)) }
        }
        pendingUninstall = filtered
        uninstallIndex = 0
        uninstallTick += 1
    }

    val currentList = if (tabIndex == 0) state.userApps else state.systemApps
    val selectionActive = selected.isNotEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionActive) {
                val extracting = batchExtractState is BatchExtractState.Running
                TopAppBar(
                    title = {
                        if (extracting) {
                            val running = batchExtractState as BatchExtractState.Running
                            Text("提取中 ${running.done}/${running.total}")
                        } else {
                            Text(stringResource(R.string.selection_count, selected.size))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_exit_selection),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { selected = currentList.map { it.packageName }.toSet() }) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = stringResource(R.string.action_select_all),
                            )
                        }
                        androidx.compose.material3.IconButton(
                            onClick = { viewModel.extractPackages(selected) },
                            enabled = !extracting,
                        ) {
                            androidx.compose.material3.Text("提取")
                        }
                        IconButton(onClick = { startUninstall(selected.toList()) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_uninstall_selected),
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.List,
                                    contentDescription = stringResource(R.string.sort_label),
                                )
                            }
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false },
                            ) {
                                SortMode.entries.forEach { mode ->
                                    // B2: usage-based sorts are only offered when the
                                    // permission is granted (iron rule: never surface
                                    // them as "zero usage" when there is no data).
                                    if (mode == SortMode.LAST_USED ||
                                        mode == SortMode.USAGE_DURATION ||
                                        mode == SortMode.LAUNCH_COUNT
                                    ) {
                                        if (!state.usageAvailable) return@forEach
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    when (mode) {
                                                        SortMode.NAME -> R.string.sort_by_name
                                                        SortMode.SIZE -> R.string.sort_by_size
                                                        SortMode.INSTALL_TIME -> R.string.sort_by_install_time
                                                        SortMode.STORAGE -> R.string.sort_by_storage
                                                        SortMode.LAST_USED -> R.string.sort_by_last_used
                                                        SortMode.USAGE_DURATION -> R.string.sort_by_usage_duration
                                                        SortMode.LAUNCH_COUNT -> R.string.sort_by_launch_count
                                                    }
                                                )
                                            )
                                        },
                                        onClick = {
                                            viewModel.onSortModeChange(mode)
                                            sortMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.settings_title),
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            StorageSummaryCard(
                summary = storageSummary,
                onClick = onOpenStorage,
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )

            if (state.usageAvailable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = state.idleOnly,
                        onClick = viewModel::onToggleIdleOnly,
                        label = { Text(stringResource(R.string.filter_idle_only)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.filter_idle_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0; selected = emptySet() },
                    text = { Text("${stringResource(R.string.tab_user_apps)} ${state.userApps.size}") },
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1; selected = emptySet() },
                    text = { Text("${stringResource(R.string.tab_system_apps)} ${state.systemApps.size}") },
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading && currentList.isEmpty() -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    currentList.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.empty_list),
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }

                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(currentList, key = { it.packageName }) { app ->
                                AppRow(
                                    app = app,
                                    selected = app.packageName in selected,
                                    selectionActive = selectionActive,
                                    onClick = {
                                        if (selectionActive) {
                                            selected = if (app.packageName in selected) {
                                                selected - app.packageName
                                            } else {
                                                selected + app.packageName
                                            }
                                        } else {
                                            onAppClick(app.packageName)
                                        }
                                    },
                                    onLongClick = { selected = selected + app.packageName },
                                    menuExpanded = itemMenuFor == app.packageName,
                                    onMenuDismiss = { itemMenuFor = null },
                                    onMenuOpen = { itemMenuFor = app.packageName },
                                    onUninstall = {
                                        itemMenuFor = null
                                        if (app.isSystemApp) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    context.getString(R.string.uninstall_system_notice)
                                                )
                                            }
                                        } else {
                                            startUninstall(listOf(app.packageName))
                                        }
                                    },
                                    onDetail = {
                                        itemMenuFor = null
                                        onAppClick(app.packageName)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppInfo,
    selected: Boolean,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menuExpanded: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onUninstall: () -> Unit,
    onDetail: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sizeText = if (app.storageBytes > 0) {
                    formatFileSize(context, app.storageBytes)
                } else {
                    formatFileSize(context, app.apkSizeBytes)
                }
                val usage = app.usage
                val lastUsedText = if (usage != null && usage.hasData && usage.lastUsedTime > 0) {
                    " · 最近 ${formatDateTime(usage.lastUsedTime)}"
                } else {
                    ""
                }
                Text(
                    text = "$sizeText · v${app.versionName} · ${formatDateTime(app.firstInstallTime)}$lastUsedText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!selectionActive) {
                Box {
                    IconButton(onClick = onMenuOpen) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = onMenuDismiss) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_app_detail)) },
                            onClick = onDetail,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_uninstall)) },
                            onClick = onUninstall,
                        )
                    }
                }
            } else if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun AppIcon(app: AppInfo, size: Dp = 44.dp) {
    val bitmap = app.icon
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = app.label,
            modifier = Modifier.size(size),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                ),
        )
    }
}

@Composable
private fun StorageSummaryCard(
    summary: StorageSummary,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryItem(
                label = stringResource(R.string.storage_user_apps),
                value = formatFileSize(context, summary.userTotalBytes),
            )
            SummaryItem(
                label = stringResource(R.string.storage_system_apps),
                value = formatFileSize(context, summary.systemTotalBytes),
            )
            SummaryItem(
                label = stringResource(R.string.storage_cache),
                value = formatFileSize(context, summary.cacheTotalBytes),
            )
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
