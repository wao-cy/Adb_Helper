package com.adbhelper.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adbhelper.app.R
import com.adbhelper.app.ui.viewmodels.AppInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListItem(
    app: AppInfo,
    onForceStop: () -> Unit,
    onClearData: () -> Unit,
    onUninstall: () -> Unit,
    onLaunch: () -> Unit,
    onDownloadApk: () -> Unit,
    onDisable: () -> Unit,
    onEnable: () -> Unit,
    onShowDetail: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    var showUninstallConfirm by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    ListItem(
        headlineContent = {
            Text(
                text = app.appName.ifBlank { app.packageName },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                if (app.appName.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(app.packageName))
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.copy_package_name),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = app.apkPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(app.apkPath))
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.copy_apk_path),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        },
        leadingContent = {
            Icon(
                imageVector = if (app.isSystemApp) Icons.Default.Shield else Icons.Default.Android,
                contentDescription = null,
                tint = if (app.isSystemApp) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_app_info)) },
                        onClick = {
                            showMenu = false
                            onShowDetail()
                        },
                        leadingIcon = { Icon(Icons.Default.Info, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_launch)) },
                        onClick = {
                            showMenu = false
                            onLaunch()
                        },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_force_stop)) },
                        onClick = {
                            showMenu = false
                            onForceStop()
                        },
                        leadingIcon = { Icon(Icons.Default.Stop, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_clear_data)) },
                        onClick = {
                            showMenu = false
                            onClearData()
                        },
                        leadingIcon = { Icon(Icons.Default.DeleteSweep, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_download_apk)) },
                        onClick = {
                            showMenu = false
                            onDownloadApk()
                        },
                        leadingIcon = { Icon(Icons.Default.Download, null) }
                    )
                    if (app.isDisabled) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_enable)) },
                            onClick = {
                                showMenu = false
                                onEnable()
                            },
                            leadingIcon = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_disable)) },
                            onClick = {
                                showMenu = false
                                onDisable()
                            },
                            leadingIcon = { Icon(Icons.Default.Block, null) }
                        )
                    }
                    if (!app.isSystemApp) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_uninstall)) },
                            onClick = {
                                showMenu = false
                                showUninstallConfirm = true
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
        }
    )

    HorizontalDivider()

    // 卸载确认对话框（仅卸载保留确认弹窗）
    if (showUninstallConfirm) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text(stringResource(R.string.confirm_uninstall)) },
            text = { Text(stringResource(R.string.confirm_uninstall_msg, app.packageName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUninstallConfirm = false
                        onUninstall()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
