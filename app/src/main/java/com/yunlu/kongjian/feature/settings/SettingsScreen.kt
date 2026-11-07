package com.yunlu.kongjian.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yunlu.kongjian.BuildConfig
import com.yunlu.kongjian.R
import com.yunlu.kongjian.core.util.formatFileSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val summary by viewModel.bundleSummary.collectAsStateWithLifecycle()
    val cleared by viewModel.clearedCount.collectAsStateWithLifecycle()

    LaunchedEffect(cleared) {
        val count = cleared
        if (count != null) {
            snackbarHostState.showSnackbar(
                context.getString(R.string.settings_clear_extracted_done, count)
            )
            viewModel.consumeNotice()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.openDirResult.collect { result ->
            when (result) {
                is OpenDirResult.Empty -> snackbarHostState.showSnackbar(
                    context.getString(R.string.settings_open_dir_empty)
                )
                is OpenDirResult.NoIntent -> snackbarHostState.showSnackbar(
                    context.getString(R.string.settings_open_dir_failed)
                )
                is OpenDirResult.Ready -> {
                    val intent = result.intent
                    runCatching {
                        context.startActivity(intent)
                    }.onFailure {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.settings_open_dir_failed)
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(16.dp)),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_about),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${stringResource(R.string.settings_version)}: ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_extracted_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    val (count, bytes) = summary
                    Text(
                        text = if (count == 0) {
                            stringResource(R.string.settings_extracted_empty)
                        } else {
                            stringResource(
                                R.string.settings_extracted_summary,
                                count,
                                formatFileSize(context, bytes),
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (count > 0) {
                        Spacer(Modifier.height(12.dp))
                        Row {
                            androidx.compose.material3.TextButton(
                                onClick = { viewModel.openExtractDir() },
                            ) {
                                Text(stringResource(R.string.settings_open_extract_dir))
                            }
                            androidx.compose.material3.TextButton(
                                onClick = { viewModel.clearExtracted() },
                            ) {
                                Text(stringResource(R.string.settings_extracted_action))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_privacy),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_privacy_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_permission_note),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_permission_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}