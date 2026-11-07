package com.yunlu.kongjian.feature.appdetail

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yunlu.kongjian.R
import com.yunlu.kongjian.core.model.AppDetail
import com.yunlu.kongjian.core.model.PermissionInfo
import com.yunlu.kongjian.core.model.StorageUsage
import com.yunlu.kongjian.core.model.UsageInfo
import com.yunlu.kongjian.core.util.formatDateTime
import com.yunlu.kongjian.core.util.formatFileSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    onBack: () -> Unit,
    viewModel: AppDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val extractState by viewModel.extractState.collectAsStateWithLifecycle()
    val storageState by viewModel.storageState.collectAsStateWithLifecycle()
    val usageState by viewModel.usageState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showModeDialog by remember { mutableStateOf(false) }

    val uninstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val message = when (result.resultCode) {
            Activity.RESULT_OK -> context.getString(R.string.uninstall_done)
            Activity.RESULT_CANCELED -> context.getString(R.string.uninstall_canceled)
            else -> context.getString(R.string.uninstall_failed)
        }
        scope.launch { snackbarHostState.showSnackbar(message) }
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.load()
        }
    }

    // API 28 and below need WRITE_EXTERNAL_STORAGE to publish into Download/;
    // API 29+ uses MediaStore and requires nothing.
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.saveToDownloads()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.extract_storage_permission_needed)
                )
            }
        }
    }

    fun requestSaveToDownloads() {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.saveToDownloads()
        }
    }

    // Surface extraction / save outcomes as a snackbar, then clear the flag.
    LaunchedEffect(extractState.savedCount) {
        val count = extractState.savedCount
        if (count != null) {
            snackbarHostState.showSnackbar(context.getString(R.string.extract_saved, count))
            viewModel.consumeSavedNotice()
        }
    }
    // Resolve the localized text outside the effect: LaunchedEffect's block is
    // not a @Composable scope, so stringResource cannot be called inside it.
    val pendingError = extractState.error
    val pendingErrorText = if (pendingError != null) extractErrorText(pendingError) else null
    LaunchedEffect(pendingError) {
        if (pendingError != null && pendingErrorText != null) {
            snackbarHostState.showSnackbar(pendingErrorText)
            viewModel.consumeError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.detail?.info?.label ?: stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.notFound || state.detail == null -> {
                    Text(
                        text = stringResource(R.string.empty_list),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                else -> {
                    // `state.detail` is a delegated property (cannot smart-cast);
                    // copy into a local val and check explicitly.
                    val detail = state.detail
                    if (detail != null) {
                        DetailContent(
                            detail = detail,
                            storageUsage = storageState.usage,
                            storageLoading = storageState.isLoading,
                            usageState = usageState,
                            onOpenSettingsUsage = {
                                try {
                                    context.startActivity(viewModel.buildUsageSettingsIntent())
                                } catch (_: Exception) {
                                    // Some ROMs may not expose this settings page.
                                }
                            },
                            onOpen = {
                                val intent = viewModel.buildLaunchIntent()
                                if (intent != null) {
                                    try {
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.detail_cannot_launch)
                                            )
                                        }
                                    }
                                } else {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.detail_cannot_launch)
                                        )
                                    }
                                }
                            },
                            onAppInfo = {
                                try {
                                    context.startActivity(viewModel.buildAppSettingsIntent())
                                } catch (_: Exception) {
                                    // Some OEM ROMs may not resolve this intent.
                                }
                            },
                            onExtract = { showModeDialog = true },
                            onUninstall = {
                                if (!detail.info.isSystemApp) {
                                    uninstallLauncher.launch(viewModel.buildUninstallIntent())
                                } else {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.uninstall_system_notice)
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // ---- dialogs ------------------------------------------------------------

    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text(stringResource(R.string.extract_mode_title)) },
            text = {
                Column {
                    ModeChoice(
                        title = stringResource(R.string.extract_mode_full),
                        description = stringResource(R.string.extract_mode_full_desc),
                        onClick = {
                            showModeDialog = false
                            viewModel.extract(includeSplits = true)
                        },
                    )
                    HorizontalDivider()
                    ModeChoice(
                        title = stringResource(R.string.extract_mode_base),
                        description = stringResource(R.string.extract_mode_base_desc),
                        onClick = {
                            showModeDialog = false
                            viewModel.extract(includeSplits = false)
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showModeDialog = false }) {
                    Text(stringResource(R.string.extract_close))
                }
            },
        )
    }

    if (extractState.running) {
        AlertDialog(
            onDismissRequest = { /* extraction cannot be cancelled mid-copy */ },
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.extract_running))
                }
            },
        )
    }

    val result = extractState.result
    if (!extractState.running && result != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissExtractDialog() },
            title = {
                Text(
                    text = stringResource(
                        R.string.extract_done,
                        result.fileCount,
                        formatFileSize(context, result.totalBytes),
                    )
                )
            },
            text = {
                Column {
                    if (result.includeSplits && result.fileCount == 1) {
                        Text(
                            text = stringResource(R.string.extract_no_split_app),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        text = stringResource(R.string.extract_list_header),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    result.dir?.listFiles { f -> f.isFile }
                        ?.sortedBy { it.name }
                        ?.forEach { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = formatFileSize(context, file.length()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.extract_path_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = result.dir?.absolutePath ?: "-",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val title = context.getString(R.string.extract_share)
                        val intent = viewModel.buildShareIntent(title)
                        if (intent != null) {
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.extract_no_share)
                                    )
                                }
                            }
                        } else {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.extract_no_share)
                                )
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.extract_share))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { requestSaveToDownloads() },
                        enabled = !extractState.saving,
                    ) {
                        Text(stringResource(R.string.extract_save))
                    }
                    TextButton(onClick = { viewModel.dismissExtractDialog() }) {
                        Text(stringResource(R.string.extract_close))
                    }
                }
            },
        )
    }
}

@Composable
private fun ModeChoice(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun extractErrorText(code: String): String = when {
    code.startsWith("ERR_APP_GONE") -> stringResource(R.string.extract_err_gone)
    code.startsWith("ERR_NO_SOURCE") -> stringResource(R.string.extract_err_no_source)
    code.startsWith("ERR_MKDIR") -> stringResource(R.string.extract_err_mkdir)
    code.startsWith("ERR_EMPTY") -> stringResource(R.string.extract_err_empty)
    code.startsWith("ERR_SAVE") -> stringResource(R.string.extract_err_save)
    else -> stringResource(R.string.extract_err_copy)
}

@Composable
private fun DetailContent(
    detail: AppDetail,
    storageUsage: StorageUsage?,
    storageLoading: Boolean,
    usageState: UsageUiState,
    onOpenSettingsUsage: () -> Unit,
    onOpen: () -> Unit,
    onAppInfo: () -> Unit,
    onExtract: () -> Unit,
    onUninstall: () -> Unit,
) {
    val context = LocalContext.current
    val app = detail.info
    val isSystem = app.isSystemApp

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- Header ----
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (app.icon != null) {
                        Image(
                            bitmap = app.icon.asImageBitmap(),
                            contentDescription = app.label,
                            modifier = Modifier.size(64.dp),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isSystem) {
                                AssistChip(onClick = {}, label = { Text("系统应用") })
                            }
                            if (!app.enabled) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text(stringResource(R.string.detail_app_disabled)) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- Actions ----
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.detail_open))
                }
                OutlinedButton(onClick = onAppInfo, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Info, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.detail_app_info))
                }
                if (!isSystem) {
                    Button(
                        onClick = onUninstall,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.detail_uninstall))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onExtract, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.detail_extract))
            }
            if (isSystem) {
                Text(
                    text = stringResource(R.string.detail_system_app_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // ---- Storage (V3 B1) ----
        item {
            StorageCard(usage = storageUsage, isLoading = storageLoading)
        }

        // ---- Usage stats (V3 B2) ----
        item {
            UsageCard(state = usageState, onOpenSettings = onOpenSettingsUsage)
        }

        // ---- Basic info ----
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.detail_section_basic),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    InfoRow(stringResource(R.string.detail_field_package), app.packageName, monospace = true)
                    InfoRow(
                        stringResource(R.string.detail_field_version),
                        "${app.versionName} (${app.versionCode})",
                    )
                    InfoRow(
                        stringResource(R.string.detail_field_sdk),
                        "min ${app.minSdk} / target ${app.targetSdk}",
                    )
                    InfoRow(
                        stringResource(R.string.detail_field_first_install),
                        formatDateTime(app.firstInstallTime),
                    )
                    InfoRow(
                        stringResource(R.string.detail_field_last_update),
                        formatDateTime(app.lastUpdateTime),
                    )
                    InfoRow(
                        stringResource(R.string.detail_field_installer),
                        detail.installerPackage ?: "-",
                    )
                    InfoRow(
                        stringResource(R.string.detail_field_apk_size),
                        formatFileSize(context, app.apkSizeBytes),
                    )
                    InfoRow(
                        stringResource(R.string.detail_field_source_path),
                        detail.sourceDir,
                        monospace = true,
                    )
                }
            }
        }

        // ---- Permissions ----
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.detail_section_permissions),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (detail.permissions.isEmpty()) {
                        Text(
                            text = stringResource(R.string.detail_no_permissions),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        items(detail.permissions, key = { it.name }) { permission ->
            PermissionRow(permission)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

@Composable
private fun PermissionRow(permission: PermissionInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = permission.name.removePrefix("android.permission."),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        SuggestionChip(
            onClick = {},
            label = {
                Text(
                    text = stringResource(
                        if (permission.granted) R.string.detail_permission_granted
                        else R.string.detail_permission_denied
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
        )
        if (permission.dangerous) {
            Spacer(Modifier.width(6.dp))
            SuggestionChip(
                onClick = {},
                label = {
                    Text(
                        text = stringResource(R.string.detail_permission_dangerous),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )
        }
    }
}

@Composable
private fun StorageCard(usage: StorageUsage?, isLoading: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.detail_section_storage),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            when {
                isLoading && usage == null -> {
                    Text(
                        text = stringResource(R.string.storage_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                usage != null -> {
                    StorageRow(stringResource(R.string.storage_apk), usage.appBytes)
                    StorageRow(stringResource(R.string.storage_data), usage.dataBytes)
                    StorageRow(stringResource(R.string.storage_cache), usage.cacheBytes)
                    StorageRow(stringResource(R.string.storage_external), usage.externalCacheBytes)
                    if (usage.aotBytes != null) {
                        StorageRow(stringResource(R.string.storage_aot), usage.aotBytes)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    StorageRow(stringResource(R.string.storage_total), usage.totalBytes, bold = true)
                }

                else -> {
                    Text(
                        text = stringResource(R.string.storage_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageCard(state: UsageUiState, onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.usage_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            when {
                // Iron rule: without the permission we never show usage data.
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }

                !state.permissionGranted -> {
                    Text(
                        text = stringResource(R.string.usage_no_permission),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.usage_open_settings))
                    }
                }

                state.usage == null -> {
                    Text(
                        text = stringResource(R.string.usage_no_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    val u = state.usage ?: return@Column
                    val totalMin = u.totalForegroundMs / 60_000L
                    val durText = if (totalMin >= 60) {
                        "${totalMin / 60} 小时 ${totalMin % 60} 分"
                    } else {
                        "${totalMin} 分"
                    }
                    UsageRow(stringResource(R.string.usage_last_used), formatDateTime(u.lastUsedTime))
                    UsageRow(stringResource(R.string.usage_foreground), durText)
                    UsageRow(stringResource(R.string.usage_launch_count), "${u.launchCount} 次")
                }
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StorageRow(label: String, value: Long, bold: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatFileSize(LocalContext.current, value),
            style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        )
    }
}