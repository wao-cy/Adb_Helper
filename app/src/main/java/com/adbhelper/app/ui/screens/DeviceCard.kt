package com.adbhelper.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adbhelper.app.R
import com.adbhelper.app.core.adb.AdbDevice
import com.adbhelper.app.core.adb.DeviceState

@Composable
fun QuickActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(icon, contentDescription = label)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceCard(
    device: AdbDevice,
    isSelected: Boolean,
    hasRoot: Boolean,
    onSelect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (device.state) {
                    DeviceState.DEVICE -> Icons.Default.PhoneAndroid
                    DeviceState.OFFLINE -> Icons.Default.UsbOff
                    DeviceState.UNAUTHORIZED -> Icons.Default.Lock
                    DeviceState.UNKNOWN -> Icons.AutoMirrored.Filled.Help
                },
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = when (device.state) {
                    DeviceState.DEVICE -> MaterialTheme.colorScheme.primary
                    DeviceState.OFFLINE -> MaterialTheme.colorScheme.error
                    DeviceState.UNAUTHORIZED -> MaterialTheme.colorScheme.tertiary
                    DeviceState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.model ?: device.serial,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = device.serial,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (device.state) {
                            DeviceState.DEVICE -> stringResource(R.string.connected)
                            DeviceState.OFFLINE -> stringResource(R.string.offline)
                            DeviceState.UNAUTHORIZED -> stringResource(R.string.unauthorized_hint)
                            DeviceState.UNKNOWN -> stringResource(R.string.unknown)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (device.state) {
                            DeviceState.DEVICE -> MaterialTheme.colorScheme.primary
                            DeviceState.OFFLINE -> MaterialTheme.colorScheme.error
                            DeviceState.UNAUTHORIZED -> MaterialTheme.colorScheme.tertiary
                            DeviceState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (hasRoot && device.state == DeviceState.DEVICE) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.height(22.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Security,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = stringResource(R.string.root),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
            }

            IconButton(onClick = onDisconnect) {
                Icon(
                    Icons.Default.LinkOff,
                    contentDescription = stringResource(R.string.disconnect),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
