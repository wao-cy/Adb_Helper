package com.adbhelper.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adbhelper.app.ui.viewmodels.MemoryInfo
import com.adbhelper.app.ui.viewmodels.ProcessInfo
import com.adbhelper.app.ui.viewmodels.ProcessManagerViewModel
import com.adbhelper.app.ui.viewmodels.ProcessSortBy
import kotlinx.coroutines.delay

@Composable
fun ProcessManagerPanel(
    isActive: Boolean,
    viewModel: ProcessManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 操作反馈 Snackbar
    LaunchedEffect(uiState.operationMessage) {
        uiState.operationMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            viewModel.clearMessage()
        }
    }

    // 自动刷新（仅 Tab 激活时）
    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        viewModel.loadProcesses()
        while (true) {
            delay(5000)
            viewModel.refreshProcesses()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.error != null && uiState.processes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Text(uiState.error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadProcesses(force = true) }) { Text("重试") }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 内存概览
                    MemoryOverviewCard(memoryInfo = uiState.memoryInfo)

                    // 搜索栏
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        placeholder = { Text("搜索应用名或包名…") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(Icons.Default.Close, "清除")
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.setSearchQuery(uiState.searchQuery) })
                    )

                    // 排序行
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ProcessSortBy.entries.forEach { sort ->
                            FilterChip(
                                selected = uiState.sortBy == sort,
                                onClick = { viewModel.setSortBy(sort) },
                                label = {
                                    val arrow = if (uiState.sortBy == sort) {
                                        if (uiState.sortAscending) " ↑" else " ↓"
                                    } else ""
                                    Text(sort.name + arrow, fontSize = 12.sp)
                                }
                            )
                        }
                    }

                    // 进程计数 + 刷新指示
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "共 ${uiState.filteredProcesses.size} 个应用进程",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (uiState.isRefreshing) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        }
                    }

                    HorizontalDivider()

                    if (uiState.filteredProcesses.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Search, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text("未找到应用进程", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item { ProcessListHeader() }
                            items(uiState.filteredProcesses, key = { it.pid }) { process ->
                                ProcessListItem(
                                    process = process,
                                    appName = viewModel.getAppDisplayName(process.name),
                                    onKill = { viewModel.requestKill(process) }
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                            }
                            item { Spacer(Modifier.height(80.dp)) }
                        }
                    }
                }
            }
        }
    }

    // Kill 确认对话框
    if (uiState.showKillConfirm && uiState.processToKill != null) {
        val p = uiState.processToKill!!
        val displayName = viewModel.getAppDisplayName(p.name) ?: p.name
        AlertDialog(
            onDismissRequest = { viewModel.cancelKill() },
            title = { Text("终止进程") },
            text = { Text("确定要终止进程 $displayName (PID: ${p.pid}) 吗？") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmKill() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { viewModel.cancelKill() }) { Text("取消") } }
        )
    }
}

@Composable
private fun ProcessListHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("PID", modifier = Modifier.width(48.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("应用名", modifier = Modifier.weight(1f).padding(start = 4.dp), fontSize = 11.sp,
            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("CPU", modifier = Modifier.width(40.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("MEM", modifier = Modifier.width(40.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(32.dp))
    }
}

@Composable
private fun ProcessListItem(
    process: ProcessInfo,
    appName: String?,
    onKill: () -> Unit
) {
    val primaryName = appName ?: process.name
    val secondaryName = if (appName != null) process.name else null

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // PID
        Text(
            text = process.pid.toString(),
            modifier = Modifier.width(48.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        // 应用名 + 包名
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = primaryName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (secondaryName != null) {
                Text(
                    text = secondaryName,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // CPU%
        Text(
            text = formatPercent(process.cpuPercent),
            modifier = Modifier.width(40.dp),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
        // MEM%
        Text(
            text = formatPercent(process.memPercent),
            modifier = Modifier.width(40.dp),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
        // Kill 按钮
        IconButton(onClick = onKill, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "终止",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun formatPercent(value: Float): String {
    return if (value >= 100f) "${value.toInt()}" else String.format("%.1f", value)
}

@Composable
private fun MemoryOverviewCard(memoryInfo: MemoryInfo?) {
    if (memoryInfo == null) return

    val usedPercent = memoryInfo.usedPercent / 100f
    val isHighUsage = memoryInfo.usedPercent > 85f
    val progressColor = when {
        memoryInfo.usedPercent > 90f -> MaterialTheme.colorScheme.error
        memoryInfo.usedPercent > 75f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(6.dp))
                Text("运行内存", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(8.dp))

            // 数值行：已用 / 总计
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "已用 ${formatMemorySize(memoryInfo.usedKb)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isHighUsage) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "可用 ${formatMemorySize(memoryInfo.availableKb)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))

            // 总容量 + 百分比
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "总计 ${formatMemorySize(memoryInfo.totalKb)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${memoryInfo.usedPercent.toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (isHighUsage) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            // 进度条
            LinearProgressIndicator(
                progress = { usedPercent },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

private fun formatMemorySize(kb: Long): String {
    return when {
        kb >= 1_048_576 -> String.format("%.1f GB", kb / 1_048_576.0)
        kb >= 1024 -> String.format("%.1f MB", kb / 1024.0)
        else -> "$kb KB"
    }
}
