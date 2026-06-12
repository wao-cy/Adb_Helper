package com.adbhelper.app.ui.screens

import android.content.Intent
import android.net.Uri

import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adbhelper.app.R
import com.adbhelper.app.data.repositories.SettingsRepository
import com.adbhelper.app.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ADB Settings
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.adb_settings),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Local save path
                    Text(stringResource(R.string.local_save_path))
                    Spacer(modifier = Modifier.height(8.dp))
                    LocalSavePathSelector(
                        currentPath = uiState.localSavePath,
                        onPathSelected = { viewModel.updateLocalSavePath(it) }
                    )
                }
            }

            // Appearance
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.appearance),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Dark mode
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.dark_mode))
                        Switch(
                            checked = uiState.darkMode,
                            onCheckedChange = { viewModel.updateDarkMode(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Keep screen on
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.keep_screen_on))
                        Switch(
                            checked = uiState.keepScreenOn,
                            onCheckedChange = { viewModel.updateKeepScreenOn(it) }
                        )
                    }
                }
            }

            // 默认设备 Tab
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.default_device_tab),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    val tabNames = listOf("设备信息", "应用管理", "文件管理", "模拟遥控")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tabNames.forEachIndexed { index, name ->
                            FilterChip(
                                selected = uiState.defaultTab == index,
                                onClick = { viewModel.updateDefaultTab(index) },
                                label = { Text(name, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }

            // Storage Management
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.storage_management),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Cache size display
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.cache_size))
                        Text(
                            text = SettingsRepository.formatCacheSize(uiState.cacheSize),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Clear cache button
                    var showClearCacheDialog by remember { mutableStateOf(false) }

                    Button(
                        onClick = { showClearCacheDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState.cacheSize > 0
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.clear_app_cache))
                    }

                    if (showClearCacheDialog) {
                        AlertDialog(
                            onDismissRequest = { showClearCacheDialog = false },
                            title = { Text(stringResource(R.string.confirm_clear_cache)) },
                            text = { Text(stringResource(R.string.confirm_clear_cache_msg)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    viewModel.clearAppCache()
                                    showClearCacheDialog = false
                                }) {
                                    Text(stringResource(R.string.ok))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showClearCacheDialog = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            }
                        )
                    }
                }
            }

            // About
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                onClick = onNavigateToAbout
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.about),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Icon(Icons.Default.ChevronRight, stringResource(R.string.about))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalSavePathSelector(
    currentPath: String,
    onPathSelected: (String) -> Unit
) {
    val ctx = LocalContext.current

    // SAF OpenDocumentTree 选择器
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                ctx.contentResolver.takePersistableUriPermission(it, flags)
            } catch (_: Exception) {}
            onPathSelected(safUriToFilePath(it) ?: it.toString())
        }
    }

    Column {
        // 当前路径输入 + 选择按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = currentPath,
                onValueChange = onPathSelected,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.local_save_path_hint)) },
                singleLine = true
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = {
                val initialUri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary"
                )
                folderPickerLauncher.launch(initialUri)
            }) {
                Icon(Icons.Default.FolderOpen, stringResource(R.string.select_folder))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 快捷选择
        Text(
            "快捷选择",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))

        val presets = buildList {
            val appDir = ctx.getExternalFilesDir(null)?.absolutePath
            if (appDir != null) add(appDir to "应用目录")
            add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path to "Downloads")
            add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).path to "Documents")
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            presets.forEach { (path, label) ->
                FilterChip(
                    selected = currentPath == path,
                    onClick = { onPathSelected(path) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

/** 将 SAF tree URI 转为文件路径，用于直接 adb pull */
private fun safUriToFilePath(uri: Uri): String? {
    return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        if (docId.startsWith("primary:")) {
            "/storage/emulated/0/${docId.removePrefix("primary:")}"
        } else null
    } catch (_: Exception) { null }
}
