package com.adbhelper.app.ui.screens

import androidx.compose.foundation.clickable
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
import com.adbhelper.app.R

@Composable
fun ConnectDeviceDialog(
    onDismiss: () -> Unit,
    onConnect: (String, Int, (Boolean, String) -> Unit) -> Unit,
    onPair: (String, Int, String, (Boolean, String) -> Unit) -> Unit,
    lanDevices: List<String> = emptyList(),
    isScanningLan: Boolean = false,
    onScanLan: () -> Unit = {},
    onLanDeviceClick: (String) -> Unit = {},
    connectHistory: State<List<String>> = mutableStateOf(emptyList()),
    onDeleteHistory: (String) -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var connectIp by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("5555") }
    var pairIp by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var isPairing by remember { mutableStateOf(false) }
    var pairResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var connectResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var showHistoryDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wireless_debugging)) },
        text = {
            Column {
                // Tab Row
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0; pairResult = null; connectResult = null },
                        text = { Text(stringResource(R.string.connect)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1; pairResult = null; connectResult = null },
                        text = { Text(stringResource(R.string.pair)) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = {
                            selectedTab = 2
                            pairResult = null
                            connectResult = null
                            if (lanDevices.isEmpty() && !isScanningLan) onScanLan()
                        },
                        text = { Text(stringResource(R.string.scan_lan)) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> {
                        // Connect Tab - 操作说明
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.connect_instructions),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // IP 地址输入 + 历史下拉
                        Box {
                            OutlinedTextField(
                                value = connectIp,
                                onValueChange = {
                                    connectIp = it
                                    showHistoryDropdown = it.isEmpty() && connectHistory.value.isNotEmpty()
                                },
                                label = { Text(stringResource(R.string.ip_address)) },
                                placeholder = { Text("192.168.1.100") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    if (connectHistory.value.isNotEmpty()) {
                                        IconButton(onClick = {
                                            showHistoryDropdown = !showHistoryDropdown
                                        }) {
                                            Icon(Icons.Default.History, stringResource(R.string.history))
                                        }
                                    }
                                }
                            )

                            // 历史记录下拉
                            if (showHistoryDropdown && connectHistory.value.isNotEmpty()) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 56.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        connectHistory.value.take(5).forEach { entry ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clickable {
                                                            val parts = entry.split(":")
                                                            connectIp = parts[0]
                                                            if (parts.size > 1) connectPort = parts[1]
                                                            showHistoryDropdown = false
                                                        },
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        Icons.Default.History,
                                                        null,
                                                        modifier = Modifier.size(16.dp),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = entry,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { onDeleteHistory(entry) },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Close,
                                                        stringResource(R.string.delete),
                                                        modifier = Modifier.size(16.dp),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = connectPort,
                            onValueChange = { connectPort = it },
                            label = { Text(stringResource(R.string.port)) },
                            placeholder = { Text("5555") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        connectResult?.let { (success, message) ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (success) stringResource(R.string.connect_success) else message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }

                        if (isConnecting) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    1 -> {
                        // Pair Tab
                        Text(
                            text = stringResource(R.string.pair_instructions),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pairIp,
                            onValueChange = { pairIp = it },
                            label = { Text(stringResource(R.string.pair_ip_port)) },
                            placeholder = { Text("192.168.1.100:37123") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pairingCode,
                            onValueChange = { pairingCode = it },
                            label = { Text(stringResource(R.string.pairing_code)) },
                            placeholder = { Text("123456") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        pairResult?.let { (success, message) ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (success) stringResource(R.string.pair_success) else stringResource(R.string.pair_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            if (!success) {
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        if (isPairing) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    2 -> {
                        // LAN Scan Tab
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.lan_devices),
                                style = MaterialTheme.typography.titleSmall
                            )
                            IconButton(
                                onClick = onScanLan,
                                enabled = !isScanningLan
                            ) {
                                Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                            }
                        }

                        if (isScanningLan) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.scanning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (lanDevices.isEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.no_lan_devices),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                                items(lanDevices) { ip ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onLanDeviceClick(ip) }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.PhoneAndroid,
                                            null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = ip,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = ":5555",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (selectedTab) {
                0 -> {
                    TextButton(
                        onClick = {
                            val parts = connectIp.split(":")
                            val ip: String
                            val portInt: Int
                            if (parts.size == 2) {
                                ip = parts[0]
                                portInt = parts[1].toIntOrNull() ?: connectPort.toIntOrNull() ?: 5555
                            } else {
                                ip = connectIp
                                portInt = connectPort.toIntOrNull() ?: 5555
                            }
                            isConnecting = true
                            onConnect(ip, portInt) { success, message ->
                                isConnecting = false
                                @Suppress("AssignedValueIsNeverRead")
                                connectResult = Pair(success, message)
                            }
                        },
                        enabled = connectIp.isNotBlank() && !isConnecting
                    ) {
                        Text(stringResource(R.string.connect))
                    }
                }
                1 -> {
                    TextButton(
                        onClick = {
                            val parts = pairIp.split(":")
                            if (parts.size == 2) {
                                val ip = parts[0]
                                val port = parts[1].toIntOrNull()
                                if (port != null && pairingCode.isNotBlank()) {
                                    isPairing = true
                                    onPair(ip, port, pairingCode) { success, message ->
                                        isPairing = false
                                        @Suppress("AssignedValueIsNeverRead")
                                        pairResult = Pair(success, message)
                                    }
                                }
                            }
                        },
                        enabled = pairIp.isNotBlank() && pairingCode.isNotBlank() && !isPairing
                    ) {
                        Text(stringResource(R.string.pair))
                    }
                }
                2 -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
