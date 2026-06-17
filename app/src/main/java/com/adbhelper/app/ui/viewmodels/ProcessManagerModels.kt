package com.adbhelper.app.ui.viewmodels

data class ProcessInfo(
    val pid: Int,
    val ppid: Int,
    val user: String,
    val name: String,
    val cpuPercent: Float,
    val memPercent: Float
)

data class MemoryInfo(
    val totalKb: Long,
    val availableKb: Long
) {
    val usedKb: Long get() = totalKb - availableKb
    val usedPercent: Float get() = if (totalKb > 0) (usedKb.toFloat() / totalKb) * 100f else 0f
}

enum class ProcessSortBy {
    PID, NAME, CPU, MEM
}

data class ProcessManagerUiState(
    val isLoading: Boolean = true,
    val processes: List<ProcessInfo> = emptyList(),
    val filteredProcesses: List<ProcessInfo> = emptyList(),
    val searchQuery: String = "",
    val sortBy: ProcessSortBy = ProcessSortBy.MEM,
    val sortAscending: Boolean = false,
    val showKillConfirm: Boolean = false,
    val processToKill: ProcessInfo? = null,
    val operationMessage: String? = null,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val memoryInfo: MemoryInfo? = null
)
