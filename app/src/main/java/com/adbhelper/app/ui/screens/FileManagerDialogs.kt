package com.adbhelper.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adbhelper.app.ui.viewmodels.RemoteFile

// ========== 文件详情弹窗 ==========

@Composable
fun FileDetailDialog(
    file: RemoteFile,
    onDismiss: () -> Unit,
    onViewText: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onCopyPath: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow("路径", file.path, trailing = {
                    IconButton(onClick = onCopyPath, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.ContentCopy, "复制路径", modifier = Modifier.size(14.dp))
                    }
                })
                DetailRow("类型", if (file.isDirectory) "文件夹" else fileTypeLabel(file.name))
                if (!file.isDirectory) DetailRow("大小", formatFileSize(file.size))
                DetailRow("权限", file.permissions)
                DetailRow("所有者", "${file.owner}:${file.group}")
                DetailRow("修改时间", file.modifiedDate)

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))

                // 操作按钮：重命名 + 查看/下载
                if (file.isDirectory) {
                    OutlinedButton(
                        onClick = onRename,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DriveFileRenameOutline, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp)); Text("重命名")
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onRename,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.DriveFileRenameOutline, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp)); Text("重命名")
                            }
                            if (isTextFile(file.name)) {
                                OutlinedButton(
                                    onClick = onViewText,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Visibility, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp)); Text("查看")
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp)); Text("下载")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        trailing?.invoke()
    }
}

// ========== 文本查看器弹窗 ==========

@Composable
fun TextViewerDialog(
    fileName: String,
    content: String?,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            when {
                isLoading -> Box(modifier = Modifier.height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> Box(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text = content ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

// ========== 重命名弹窗 ==========

@Composable
fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }, enabled = name.isNotBlank()) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ========== 新建文件夹弹窗 ==========

@Composable
fun NewFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件夹") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("文件夹名称") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }, enabled = name.isNotBlank()) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ========== 路径编辑弹窗 ==========

@Composable
fun PathEditDialog(
    currentPath: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var path by remember { mutableStateOf(currentPath) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到路径") },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                singleLine = true,
                placeholder = { Text("/path/to/directory") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (path.isNotBlank()) onConfirm(path.trim()) },
                enabled = path.isNotBlank()
            ) { Text("跳转") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
