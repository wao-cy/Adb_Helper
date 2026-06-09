package com.adbhelper.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adbhelper.app.R
import com.adbhelper.app.ui.viewmodels.HomeViewModel
import com.adbhelper.app.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToDevice: () -> Unit,
    onNavigateToShell: () -> Unit,
    onNavigateToScripts: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showConnectDialog by remember { mutableStateOf(false) }
    var lastBackPressTime by rememberSaveable { mutableLongStateOf(0L) }
    var lastNavigationTime by rememberSaveable { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val exitHint = stringResource(R.string.press_back_again_to_exit)
    @Suppress("SpellCheckingInspection")
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            snackbarMessage = null
        }
    }

    fun navigateDebounced(action: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastNavigationTime > 500) {
            lastNavigationTime = now
            action()
        }
    }

    BackHandler {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime < 2000) {
            viewModel.shutdown()
            (context as? android.app.Activity)?.finish()
            android.os.Process.killProcess(android.os.Process.myPid())
        } else {
            lastBackPressTime = currentTime
            snackbarMessage = exitHint
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { viewModel.refreshDevices() }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                    }
                    IconButton(onClick = { navigateDebounced(onNavigateToSettings) }) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                @Suppress("AssignedValueIsNeverRead")
                showConnectDialog = true
            }) {
                Icon(Icons.Default.Add, stringResource(R.string.connect_device))
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ADB Server Status
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.adb_server),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (uiState.isServerRestarting) stringResource(R.string.restarting) else stringResource(R.string.running),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (uiState.isServerRestarting) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                    FilledTonalButton(
                        onClick = { viewModel.restartAdbServer() },
                        enabled = !uiState.isServerRestarting
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.restart))
                    }
                }
            }

            // Quick Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                QuickActionButton(
                    icon = Icons.Default.PhoneAndroid,
                    label = stringResource(R.string.device),
                    onClick = {
                        if (uiState.selectedDevice != null) {
                            navigateDebounced(onNavigateToDevice)
                        } else {
                            snackbarMessage = context.getString(R.string.please_select_device)
                        }
                    }
                )
                QuickActionButton(
                    icon = Icons.Default.Terminal,
                    label = stringResource(R.string.shell),
                    onClick = { navigateDebounced(onNavigateToShell) }
                )
                QuickActionButton(
                    icon = Icons.Default.Code,
                    label = stringResource(R.string.scripts),
                    onClick = { navigateDebounced(onNavigateToScripts) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Connected Devices
            Text(
                text = stringResource(R.string.connected_devices),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.connectedDevices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.UsbOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.no_devices),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.connect_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.connectedDevices) { device ->
                        DeviceCard(
                            device = device,
                            isSelected = device == uiState.selectedDevice,
                            hasRoot = device.hasRoot,
                            onSelect = { viewModel.selectDevice(device) },
                            onDisconnect = { viewModel.disconnectDevice(device.serial) }
                        )
                    }
                }
            }
        }

        // Error Snackbar
        uiState.error?.let { error ->
            LaunchedEffect(error) {
                snackbarHostState.showSnackbar(
                    message = error,
                    duration = SnackbarDuration.Short
                )
                viewModel.clearError()
            }
        }

        // Success Message Snackbar
        uiState.message?.let { message ->
            LaunchedEffect(message) {
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Short
                )
                viewModel.clearMessage()
            }
        }

        // Connect Dialog
        if (showConnectDialog) {
            ConnectDeviceDialog(
                onDismiss = { showConnectDialog = false },
                onConnect = { ip, port, onResult ->
                    viewModel.connectDevice(ip, port) { success, message ->
                        if (success) {
                            settingsViewModel.addConnectHistory("$ip:$port")
                            showConnectDialog = false
                        }
                        onResult(success, message)
                    }
                },
                onPair = { ip, port, pairingCode, onResult ->
                    viewModel.pairDevice(ip, port, pairingCode, onResult)
                },
                lanDevices = uiState.lanDevices,
                isScanningLan = uiState.isScanningLan,
                onScanLan = { viewModel.scanLanDevices() },
                onLanDeviceClick = { ip ->
                    viewModel.connectDevice(ip, 5555) { success, _ ->
                        if (success) {
                            settingsViewModel.addConnectHistory("$ip:5555")
                            showConnectDialog = false
                        }
                    }
                },
                connectHistory = settingsViewModel.settingsRepository.connectHistoryFlow.collectAsState(),
                onDeleteHistory = { settingsViewModel.removeConnectHistory(it) }
            )
        }
    }
}
