package com.adbhelper.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adbhelper.app.core.adb.DeviceSession
import com.adbhelper.app.core.shell.ShellExecutor
import com.adbhelper.app.data.repositories.AppNameStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProcessManagerViewModel @Inject constructor(
    private val shellExecutor: ShellExecutor,
    private val deviceSession: DeviceSession,
    private val appNameStore: AppNameStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProcessManagerUiState())
    val uiState: StateFlow<ProcessManagerUiState> = _uiState.asStateFlow()

    private val serial: String?
        get() = deviceSession.selectedSerial.value

    /** 对外暴露，供 Panel UI 查询应用友好名 */
    fun getAppDisplayName(packageName: String): String? =
        appNameStore.getAppName(packageName)

    fun loadProcesses(force: Boolean = false) {
        val state = _uiState.value
        if (!force && state.processes.isNotEmpty()) return

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            try {
                val psResult = shellExecutor.getProcessList(serial)
                val memOutput = shellExecutor.getMemInfo(serial)

                val memoryInfo = parseMemoryInfo(memOutput)
                if (psResult.exitCode == 0) {
                    val allProcesses = parseProcessList(psResult.output)
                    val processes = allProcesses.filter { isAppProcess(it) }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        processes = processes,
                        filteredProcesses = applyFilterAndSort(processes, _uiState.value.searchQuery, _uiState.value.sortBy, _uiState.value.sortAscending),
                        memoryInfo = memoryInfo
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false, memoryInfo = memoryInfo, error = "获取进程列表失败: ${psResult.output.trim().take(100)}")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "错误: ${e.message?.take(100) ?: "未知"}")
            }
        }
    }

    fun refreshProcesses() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
            try {
                val psResult = shellExecutor.getProcessList(serial)
                val memOutput = shellExecutor.getMemInfo(serial)

                val memoryInfo = parseMemoryInfo(memOutput)
                if (psResult.exitCode == 0) {
                    val allProcesses = parseProcessList(psResult.output)
                    val processes = allProcesses.filter { isAppProcess(it) }
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        isLoading = false,
                        processes = processes,
                        filteredProcesses = applyFilterAndSort(processes, _uiState.value.searchQuery, _uiState.value.sortBy, _uiState.value.sortAscending),
                        memoryInfo = memoryInfo
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isRefreshing = false, isLoading = false, memoryInfo = memoryInfo, error = "获取进程列表失败: ${psResult.output.trim().take(100)}")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isRefreshing = false, isLoading = false, error = "错误: ${e.message?.take(100) ?: "未知"}")
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        applyFilterAndSort()
    }

    fun setSortBy(sortBy: ProcessSortBy) {
        val state = _uiState.value
        if (state.sortBy == sortBy) {
            _uiState.value = state.copy(sortAscending = !state.sortAscending)
        } else {
            _uiState.value = state.copy(sortBy = sortBy, sortAscending = true)
        }
        applyFilterAndSort()
    }

    fun requestKill(process: ProcessInfo) {
        _uiState.value = _uiState.value.copy(showKillConfirm = true, processToKill = process)
    }

    fun confirmKill() {
        val process = _uiState.value.processToKill ?: return
        viewModelScope.launch {
            try {
                val result = shellExecutor.killProcess(process.pid, serial)
                if (result.exitCode == 0) {
                    _uiState.value = _uiState.value.copy(
                        showKillConfirm = false,
                        processToKill = null,
                        operationMessage = "已终止进程 ${process.name} (PID: ${process.pid})"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        showKillConfirm = false,
                        processToKill = null,
                        operationMessage = "终止失败: ${result.output.trim().take(50)}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    showKillConfirm = false,
                    processToKill = null,
                    operationMessage = "终止失败: ${e.message?.take(50) ?: "未知"}"
                )
            }
            refreshProcesses()
        }
    }

    fun cancelKill() {
        _uiState.value = _uiState.value.copy(showKillConfirm = false, processToKill = null)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(operationMessage = null)
    }

    // ========== 内部方法 ==========

    private fun parseMemoryInfo(raw: String): MemoryInfo? {
        val totalKb = Regex("MemTotal:\\s+(\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val availKb = Regex("MemAvailable:\\s+(\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return MemoryInfo(totalKb = totalKb, availableKb = availKb)
    }

    /**
     * 判断是否为 Android 应用进程：
     * 1. 包名在 AppNameStore 中（由 AppManagerViewModel 解析后写入）
     * 2. 回退启发式：user 以 u0_a/u1_a 开头且进程名包含英文句点（包名特征）
     */
    private fun isAppProcess(process: ProcessInfo): Boolean {
        if (appNameStore.isKnownPackage(process.name)) return true
        val userOk = process.user.startsWith("u0_a") || process.user.startsWith("u1_a")
        val nameHasDot = process.name.contains(".")
        return userOk && nameHasDot
    }

    private fun parseProcessList(output: String): List<ProcessInfo> {
        val lines = output.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val processes = mutableListOf<ProcessInfo>()
        val firstLine = lines[0].trim()

        val hasPctCpuMem = firstLine.contains("%CPU") || firstLine.contains("%MEM")
        val isToyboxOFormat = hasPctCpuMem
        val isToolboxFormat = firstLine.startsWith("USER") && !isToyboxOFormat

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("PID") || trimmed.startsWith("pid") ||
                trimmed.startsWith("USER") || trimmed.startsWith("user")) continue

            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 4) continue

            if (isToyboxOFormat) {
                val pid = parts[0].toIntOrNull() ?: continue
                val ppid = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val user = parts.getOrNull(2) ?: ""
                val cpuIdx = parts.size - 2
                val memIdx = parts.size - 1
                val name = if (cpuIdx > 3) parts.subList(3, cpuIdx).joinToString(" ") else parts.getOrNull(3) ?: ""
                val cpu = parts.getOrNull(cpuIdx)?.replace("%", "")?.toFloatOrNull() ?: 0f
                val mem = parts.getOrNull(memIdx)?.replace("%", "")?.toFloatOrNull() ?: 0f
                processes.add(ProcessInfo(pid = pid, ppid = ppid, user = user, name = name, cpuPercent = cpu, memPercent = mem))
            } else if (isToolboxFormat) {
                val pid = parts.getOrNull(1)?.toIntOrNull() ?: continue
                val ppid = parts.getOrNull(2)?.toIntOrNull() ?: 0
                val user = parts[0]
                val name = parts.last()
                processes.add(ProcessInfo(pid = pid, ppid = ppid, user = user, name = name, cpuPercent = 0f, memPercent = 0f))
            } else {
                val pid = parts[0].toIntOrNull() ?: continue
                val ppid = parts.getOrNull(2)?.toIntOrNull() ?: 0
                val user = parts.getOrNull(1) ?: ""
                val name = parts.last()
                processes.add(ProcessInfo(pid = pid, ppid = ppid, user = user, name = name, cpuPercent = 0f, memPercent = 0f))
            }
        }
        return processes
    }

    private fun applyFilterAndSort() {
        val state = _uiState.value
        val filtered = applyFilterAndSort(state.processes, state.searchQuery, state.sortBy, state.sortAscending)
        _uiState.value = state.copy(filteredProcesses = filtered)
    }

    private fun applyFilterAndSort(
        processes: List<ProcessInfo>,
        query: String,
        sortBy: ProcessSortBy,
        ascending: Boolean
    ): List<ProcessInfo> {
        val filtered = if (query.isBlank()) processes
        else processes.filter {
            val displayName = appNameStore.getAppName(it.name) ?: it.name
            it.name.contains(query, ignoreCase = true) ||
            displayName.contains(query, ignoreCase = true)
        }

        val comparator: Comparator<ProcessInfo> = when (sortBy) {
            ProcessSortBy.PID -> compareBy { it.pid }
            ProcessSortBy.NAME -> compareBy { it.name }
            ProcessSortBy.CPU -> compareBy { it.cpuPercent }
            ProcessSortBy.MEM -> compareBy { it.memPercent }
        }
        return if (ascending) filtered.sortedWith(comparator) else filtered.sortedWith(comparator.reversed())
    }
}
