package com.adbhelper.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adbhelper.app.R
import com.adbhelper.app.core.shell.ShellExecutor
import com.adbhelper.app.ui.viewmodels.AppManagerViewModel
import com.adbhelper.app.ui.viewmodels.DeviceViewModel
import com.adbhelper.app.ui.viewmodels.FileManagerViewModel
import com.adbhelper.app.ui.viewmodels.ProcessManagerViewModel
import com.adbhelper.app.ui.viewmodels.RemoteControlViewModel
import com.adbhelper.app.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    onNavigateBack: () -> Unit,
    viewModel: DeviceViewModel = hiltViewModel(),
    appManagerViewModel: AppManagerViewModel = hiltViewModel(),
    fileManagerViewModel: FileManagerViewModel = hiltViewModel(),
    remoteControlViewModel: RemoteControlViewModel = hiltViewModel(),
    processManagerViewModel: ProcessManagerViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val defaultTabPage by settingsViewModel.settingsRepository.defaultTabFlow.collectAsState()
    val tabs = listOf("设备信息", "应用管理", "文件管理", "进程管理", "模拟遥控")
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val coroutineScope = rememberCoroutineScope()

    // 更多菜单
    var showMoreMenu by remember { mutableStateOf(false) }
    // 重启确认
    var showRebootConfirm by remember { mutableStateOf(false) }
    var showRebootFastbootConfirm by remember { mutableStateOf(false) }
    var showRebootRecoveryConfirm by remember { mutableStateOf(false) }
    // 清缓存确认
    var showClearCacheConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadDeviceInfoIfNeeded()
    }

    // 首次进入或默认 Tab 设置变更时跳转
    val safeDefaultTab = defaultTabPage.coerceIn(0, tabs.size - 1)
    LaunchedEffect(safeDefaultTab) {
        if (pagerState.currentPage != safeDefaultTab) {
            pagerState.animateScrollToPage(safeDefaultTab)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.device_info)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        when (pagerState.currentPage) {
                            0 -> viewModel.loadDeviceInfo()
                            1 -> appManagerViewModel.loadApps(force = true)
                            2 -> fileManagerViewModel.loadFiles(force = true)
                            3 -> processManagerViewModel.refreshProcesses()
                        }
                    }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                    }
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, "更多")
                    }
                    DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.screenshot)) },
                            onClick = { viewModel.takeScreenshot(); showMoreMenu = false },
                            leadingIcon = { Icon(Icons.Default.Screenshot, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.reboot)) },
                            onClick = { showMoreMenu = false; showRebootConfirm = true },
                            leadingIcon = { Icon(Icons.Default.RestartAlt, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("重启到 Fastboot") },
                            onClick = { showMoreMenu = false; showRebootFastbootConfirm = true },
                            leadingIcon = { Icon(Icons.Default.RestartAlt, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("重启到 Recovery") },
                            onClick = { showMoreMenu = false; showRebootRecoveryConfirm = true },
                            leadingIcon = { Icon(Icons.Default.RestartAlt, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.clear_cache)) },
                            onClick = { showMoreMenu = false; showClearCacheConfirm = true },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, null) }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // TabRow
            ScrollableTabRow(selectedTabIndex = pagerState.currentPage, edgePadding = 8.dp) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(title) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // HorizontalPager
            HorizontalPager(state = pagerState) { page ->
                when (page) {
                    0 -> DeviceInfoContent(uiState = uiState, viewModel = viewModel)
                    1 -> AppManagerPanel(viewModel = appManagerViewModel)
                    2 -> FileManagerPanel(isActive = pagerState.currentPage == 2, onNavigateBack = onNavigateBack, viewModel = fileManagerViewModel)
                    3 -> ProcessManagerPanel(
                        isActive = pagerState.currentPage == 3,
                        viewModel = processManagerViewModel
                    )
                    4 -> RemoteControlPanel(viewModel = remoteControlViewModel)
                }
            }
        }
    }

    // 截屏对话框
    if (uiState.showScreenshotDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissScreenshotDialog() },
            title = { Text(stringResource(R.string.screenshot)) },
            text = {
                Column {
                    Text(stringResource(R.string.screenshot_saved))
                    Text(text = uiState.screenshotPath, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.dismissScreenshotDialog() }) { Text(stringResource(R.string.ok)) } }
        )
    }

    // 重启确认
    if (showRebootConfirm) {
        AlertDialog(
            onDismissRequest = { showRebootConfirm = false },
            title = { Text(stringResource(R.string.reboot)) },
            text = { Text("确定要重启设备吗？") },
            confirmButton = { TextButton(onClick = { viewModel.reboot(); showRebootConfirm = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showRebootConfirm = false }) { Text("取消") } }
        )
    }

    if (showRebootFastbootConfirm) {
        AlertDialog(
            onDismissRequest = { showRebootFastbootConfirm = false },
            title = { Text("重启到 Fastboot") },
            text = { Text("确定要重启设备到 Fastboot 模式吗？") },
            confirmButton = { TextButton(onClick = { viewModel.reboot(ShellExecutor.RebootMode.FASTBOOT); showRebootFastbootConfirm = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showRebootFastbootConfirm = false }) { Text("取消") } }
        )
    }

    if (showRebootRecoveryConfirm) {
        AlertDialog(
            onDismissRequest = { showRebootRecoveryConfirm = false },
            title = { Text("重启到 Recovery") },
            text = { Text("确定要重启设备到 Recovery 模式吗？") },
            confirmButton = { TextButton(onClick = { viewModel.reboot(ShellExecutor.RebootMode.RECOVERY); showRebootRecoveryConfirm = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showRebootRecoveryConfirm = false }) { Text("取消") } }
        )
    }

    // 清缓存确认
    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text(stringResource(R.string.clear_cache)) },
            text = { Text("确定要清除缓存吗？") },
            confirmButton = { TextButton(onClick = { viewModel.clearCache(); showClearCacheConfirm = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showClearCacheConfirm = false }) { Text("取消") } }
        )
    }
}

/**
 * Tab 0: 设备信息内容（从原 DeviceScreen 提取）
 */
@Composable
private fun DeviceInfoContent(
    uiState: com.adbhelper.app.ui.viewmodels.DeviceUiState,
    viewModel: DeviceViewModel
) {
    if (uiState.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else if (uiState.error != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Text(text = stringResource(R.string.device_connection_failed), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(text = uiState.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { viewModel.loadDeviceInfo() }) { Text(stringResource(R.string.retry)) }
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            // Device Properties
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(text = stringResource(R.string.device_properties), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    uiState.deviceProperties.forEach { (key, value) -> PropertyRow(key, value) }
                }
            }

            // Memory Info
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(text = stringResource(R.string.memory), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    PropertyRow(stringResource(R.string.total_ram), uiState.totalRam)
                    PropertyRow(stringResource(R.string.available_ram), uiState.availableRam)
                }
            }

            // Storage Info
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(text = stringResource(R.string.storage), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    PropertyRow(stringResource(R.string.internal_storage), uiState.internalStorage)
                    PropertyRow(stringResource(R.string.external_storage), uiState.externalStorage)
                }
            }

            // Battery Info
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(text = stringResource(R.string.battery), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    PropertyRow(stringResource(R.string.battery_level), "${uiState.batteryLevel}%")
                    PropertyRow(stringResource(R.string.battery_status), uiState.batteryStatus)
                    PropertyRow(stringResource(R.string.battery_health), uiState.batteryHealth)
                    PropertyRow(stringResource(R.string.battery_temperature), "${uiState.batteryTemperature / 10.0}°C")
                    PropertyRow(stringResource(R.string.battery_voltage), "${uiState.batteryVoltage}mV")
                }
            }

            // Display Info
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(text = stringResource(R.string.display), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    PropertyRow(stringResource(R.string.screen_size), uiState.screenSize)
                    PropertyRow(stringResource(R.string.screen_density), "${uiState.screenDensity} dpi")
                    PropertyRow(stringResource(R.string.resolution), uiState.resolution)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
