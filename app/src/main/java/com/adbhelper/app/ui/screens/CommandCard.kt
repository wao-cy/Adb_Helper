package com.adbhelper.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adbhelper.app.R
import com.adbhelper.app.core.script.ScriptCommand

@Composable
fun CommandCard(
    index: Int,
    command: ScriptCommand,
    onUpdate: (ScriptCommand) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    if (command.command.startsWith("#")) {
        CommentRow(command.command, onRemove, onMoveUp, onMoveDown)
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Row {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.move_up), modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.move_down), modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Delete, stringResource(R.string.delete), modifier = Modifier.size(16.dp))
                    }
                }
            }

            OutlinedTextField(
                value = command.command,
                onValueChange = { onUpdate(command.copy(command = it)) },
                label = { Text(stringResource(R.string.adb_command)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = command.ignoreError,
                        onCheckedChange = { onUpdate(command.copy(ignoreError = it)) }
                    )
                    Text(stringResource(R.string.ignore_errors), style = MaterialTheme.typography.labelSmall)
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "${stringResource(R.string.timeout)}: ${command.timeout / 1000}s",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Suppress("SpellCheckingInspection")
@Composable
fun AddCommandDialog(
    onDismiss: () -> Unit,
    onAdd: (ScriptCommand) -> Unit
) {
    var command by remember { mutableStateOf("") }
    var ignoreError by remember { mutableStateOf(false) }
    var timeout by remember { mutableStateOf("30") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_command)) },
        text = {
            Column {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.adb_command)) },
                    placeholder = { Text("shell getprop") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = timeout,
                    onValueChange = { timeout = it },
                    label = { Text(stringResource(R.string.timeout_seconds)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = ignoreError,
                        onCheckedChange = { ignoreError = it }
                    )
                    Text(stringResource(R.string.ignore_errors))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(
                        ScriptCommand(
                            command = command,
                            ignoreError = ignoreError,
                            timeout = (timeout.toLongOrNull() ?: 30) * 1000
                        )
                    )
                },
                enabled = command.isNotBlank()
            ) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** 轻量注释行，用于卡片视图中渲染 # 开头的注释命令 */
@Composable
private fun CommentRow(
    text: String,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.FormatQuote,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text.removePrefix("#").trim(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onMoveUp, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.move_up), modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onMoveDown, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.move_down), modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Delete, stringResource(R.string.delete), modifier = Modifier.size(16.dp))
        }
    }
}
