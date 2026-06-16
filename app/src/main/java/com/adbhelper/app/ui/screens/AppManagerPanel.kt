package com.adbhelper.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adbhelper.app.R
import com.adbhelper.app.ui.viewmodels.AppFilter
import com.adbhelper.app.ui.viewmodels.AppManagerViewModel

/**
 * 应用管理面板，可嵌入 DeviceScreen 的 Tab 页中
 */
@Suppress("SpellCheckingInspection")
@Composable
fun AppManagerPanel(
    viewModel: AppManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.handlePickedFile(it) } }

    LaunchedEffect(uiState.operationMessage) {
        uiState.operationMessage?.let { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            viewModel.clearMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 搜索栏
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_packages)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Close, stringResource(R.string.clear))
                        }
                    }
                },
                singleLine = true
            )

            // 筛选 Tab
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                AppFilter.entries.forEachIndexed { index, filter ->
                    SegmentedButton(
                        selected = uiState.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = AppFilter.entries.size)
                    ) {
                        Text(
                            when (filter) {
                                AppFilter.ALL -> stringResource(R.string.filter_all)
                                AppFilter.THIRD_PARTY -> stringResource(R.string.filter_third_party)
                                AppFilter.SYSTEM -> stringResource(R.string.filter_system)
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            // 应用数量
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.apps_count, uiState.filteredApps.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // isLoadingNames 不再需要（名称随 AppListResolver 一起返回）
            }

            // 应用列表
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.loading_apps))
                    }
                }
            } else if (uiState.filteredApps.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.no_apps_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(items = uiState.filteredApps, key = { it.packageName }) { app ->
                        AppListItem(
                            app = app,
                            icon = uiState.appIcons[app.packageName],
                            onForceStop = { viewModel.forceStop(app.packageName) },
                            onClearData = { viewModel.clearData(app.packageName) },
                            onUninstall = { viewModel.uninstall(app.packageName) },
                            onLaunch = { viewModel.launchApp(app.packageName) },
                            onDownloadApk = { viewModel.downloadApk(app.packageName, app.apkPath) },
                            onDisable = { viewModel.disableApp(app.packageName) },
                            onEnable = { viewModel.enableApp(app.packageName) },
                            onShowDetail = { viewModel.loadAppDetail(app.packageName, app.apkPath) }
                        )
                    }
                }
            }
        }

        // 推送 FAB（传输中隐藏）
        if (uiState.transferState == null) {
            FloatingActionButton(
                onClick = { viewModel.showPushDialog() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                Icon(Icons.Default.FileUpload, stringResource(R.string.action_push_apk))
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
        // Push 选项对话框
    if (uiState.showPushDialog) {
        PushOptionsDialog(
            onSelectFile = { viewModel.dismissPushDialog(); filePickerLauncher.launch("application/vnd.android.package-archive") },
            onSelectInstalledApp = { viewModel.showLocalAppPicker() },
            onDismiss = { viewModel.dismissPushDialog() }
        )
    }

    // 本地应用选择对话框
    if (uiState.showLocalAppPicker) {
        LocalAppPickerDialog(
            apps = uiState.localApps,
            onSelect = { app -> viewModel.dismissLocalAppPicker(); viewModel.pushLocalApp(app) },
            onDismiss = { viewModel.dismissLocalAppPicker() }
        )
    }

    // 权限受限提示
    if (uiState.showPermissionWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPermissionWarning() },
            title = { Text(stringResource(R.string.permission_limited)) },
            text = { Text(stringResource(R.string.permission_limited_msg)) },
            confirmButton = { TextButton(onClick = { viewModel.openAppSettings() }) { Text(stringResource(R.string.go_to_settings)) } },
            dismissButton = { TextButton(onClick = { viewModel.dismissPermissionWarning() }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // 传输进度对话框
    uiState.transferState?.let { state ->
        TransferProgressDialog(state = state, onCancel = { viewModel.cancelTransfer() }, onDismiss = { viewModel.dismissTransferResult() })
    }

    // 应用详情对话框
    if (uiState.appDetail != null || uiState.isLoadingDetail) {
        AppDetailDialog(
            detail = uiState.appDetail,
            isLoading = uiState.isLoadingDetail,
            onDismiss = { viewModel.dismissAppDetail() }
        )
    }

}
