package com.yunlu.kongjian.feature.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yunlu.kongjian.R
import com.yunlu.kongjian.core.model.DeviceStorage
import com.yunlu.kongjian.core.util.formatFileSize
import com.yunlu.kongjian.feature.applist.StorageSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    onBack: () -> Unit,
    viewModel: StorageViewModel = hiltViewModel(),
) {
    val device by viewModel.device.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.storage_page_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DeviceCard(device = device, isLoading = isLoading) }
            item { SegmentCard(device = device, summary = summary) }
            item { SummaryCard(summary = summary) }
        }
    }
}

@Composable
private fun DeviceCard(device: DeviceStorage?, isLoading: Boolean) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.storage_page_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            if (device != null && device.totalBytes > 0) {
                val usedRatio = (device.usedBytes.toFloat() / device.totalBytes).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { usedRatio },
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    DeviceStat(stringResource(R.string.storage_device_total), formatFileSize(context, device.totalBytes))
                    DeviceStat(stringResource(R.string.storage_device_used), formatFileSize(context, device.usedBytes))
                    DeviceStat(stringResource(R.string.storage_device_free), formatFileSize(context, device.freeBytes))
                }
            } else if (isLoading) {
                Text(
                    text = stringResource(R.string.storage_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.storage_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeviceStat(label: String, value: String) {
    Column {
        Text(text = value, style = MaterialTheme.typography.titleMedium)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SegmentCard(device: DeviceStorage?, summary: StorageSummary) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.storage_page_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            if (device != null && device.totalBytes > 0) {
                val total = device.totalBytes.toFloat()
                val userW = (summary.userTotalBytes.toFloat() / total)
                val systemW = (summary.systemTotalBytes.toFloat() / total)
                val otherW =
                    ((device.usedBytes - summary.userTotalBytes - summary.systemTotalBytes)
                        .coerceAtLeast(0L).toFloat() / total)
                val freeW = (device.freeBytes.toFloat() / total)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    if (userW > 0f) Box(Modifier.weight(userW).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                    if (systemW > 0f) Box(Modifier.weight(systemW).fillMaxHeight().background(MaterialTheme.colorScheme.tertiary))
                    if (otherW > 0f) Box(Modifier.weight(otherW).fillMaxHeight().background(MaterialTheme.colorScheme.secondary))
                    if (freeW > 0f) Box(Modifier.weight(freeW).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant))
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    LegendDot(MaterialTheme.colorScheme.primary, stringResource(R.string.storage_segment_user), formatFileSize(context, summary.userTotalBytes))
                    LegendDot(MaterialTheme.colorScheme.tertiary, stringResource(R.string.storage_segment_system), formatFileSize(context, summary.systemTotalBytes))
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    LegendDot(MaterialTheme.colorScheme.secondary, stringResource(R.string.storage_segment_other), formatFileSize(context, (device.usedBytes - summary.userTotalBytes - summary.systemTotalBytes).coerceAtLeast(0L)))
                    LegendDot(MaterialTheme.colorScheme.surfaceVariant, stringResource(R.string.storage_segment_free), formatFileSize(context, device.freeBytes))
                }
            } else {
                Text(
                    text = stringResource(R.string.storage_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryCard(summary: StorageSummary) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.detail_section_storage),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            StorageLine(stringResource(R.string.storage_user_apps), summary.userTotalBytes)
            StorageLine(stringResource(R.string.storage_system_apps), summary.systemTotalBytes)
            StorageLine(stringResource(R.string.storage_cache), summary.cacheTotalBytes)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            StorageLine(
                stringResource(R.string.storage_total),
                summary.userTotalBytes + summary.systemTotalBytes,
                bold = true,
            )
        }
    }
}

@Composable
private fun StorageLine(label: String, value: Long, bold: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
