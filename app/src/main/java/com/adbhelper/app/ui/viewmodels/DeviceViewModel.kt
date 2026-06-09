package com.adbhelper.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adbhelper.app.core.adb.DeviceSession
import com.adbhelper.app.core.shell.ShellExecutor
import com.adbhelper.app.core.shell.ShellExecutor.RebootMode
import com.adbhelper.app.core.shell.TransferHelper
import com.adbhelper.app.data.repositories.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeviceUiState(
    val isLoading: Boolean = true,
    val deviceProperties: Map<String, String> = emptyMap(),
    val totalRam: String = "Unknown",
    val availableRam: String = "Unknown",
    val batteryLevel: Int = 0,
    val batteryStatus: String = "Unknown",
    val batteryHealth: String = "Unknown",
    val batteryTemperature: Int = 0,
    val batteryVoltage: Int = 0,
    val screenSize: String = "Unknown",
    val screenDensity: Int = 0,
    val resolution: String = "Unknown",
    val internalStorage: String = "Unknown",
    val externalStorage: String = "Unknown",
    val showScreenshotDialog: Boolean = false,
    val screenshotPath: String = "",
    val error: String? = null
)

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val shellExecutor: ShellExecutor,
    private val transferHelper: TransferHelper,
    private val settingsRepository: SettingsRepository,
    private val deviceSession: DeviceSession
) : ViewModel() {
    private val _uiState = MutableStateFlow(DeviceUiState())
    val uiState: StateFlow<DeviceUiState> = _uiState.asStateFlow()

    /** 记录当前已加载数据对应的设备 serial，用于判断是否需要重新加载 */
    private var loadedSerial: String? = null

    /** 进入页面时调用：同一设备且已有数据则跳过，设备变更时自动重新加载 */
    fun loadDeviceInfoIfNeeded() {
        val currentSerial = deviceSession.selectedSerial.value
        if (_uiState.value.deviceProperties.isNotEmpty()
            && !_uiState.value.isLoading
            && loadedSerial == currentSerial) return
        loadDeviceInfo()
    }

    fun loadDeviceInfo() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val serial = deviceSession.selectedSerial.value

                // Load device properties
                val props = shellExecutor.getAllProps(serial)
                val importantProps = mapOf(
                    "型号" to (props["ro.product.model"] ?: "Unknown"),
                    "品牌" to (props["ro.product.brand"] ?: "Unknown"),
                    "设备代号" to (props["ro.product.device"] ?: "Unknown"),
                    "Android 版本" to (props["ro.build.version.release"] ?: "Unknown"),
                    "SDK 版本" to (props["ro.build.version.sdk"] ?: "Unknown"),
                    "构建 ID" to (props["ro.build.display.id"] ?: "Unknown"),
                    "安全补丁" to (props["ro.build.version.security_patch"] ?: "Unknown"),
                    "CPU 架构" to (props["ro.product.cpu.abi"] ?: "Unknown"),
                    "硬件" to (props["ro.hardware"] ?: "Unknown")
                )

                // Load battery info
                val batteryOutput = shellExecutor.getBatteryInfo(serial)
                val batteryLevel = extractBatteryValue(batteryOutput, "level")?.toIntOrNull() ?: 0
                val batteryStatus = extractBatteryValue(batteryOutput, "status") ?: "Unknown"
                val batteryHealth = extractBatteryValue(batteryOutput, "health") ?: "Unknown"
                val batteryTemp = extractBatteryValue(batteryOutput, "temperature")?.toIntOrNull() ?: 0
                val batteryVoltage = extractBatteryValue(batteryOutput, "voltage")?.toIntOrNull() ?: 0

                // Load screen info
                val screenSizeOutput = shellExecutor.getScreenSize(serial)
                val screenSize = screenSizeOutput.lines()
                    .find { it.contains("Physical size") }
                    ?.substringAfter(":")?.trim() ?: "Unknown"
                val densityOutput = shellExecutor.execute("wm density", serial)
                val density = densityOutput.output.lines()
                    .find { it.contains("Physical density") }
                    ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0

                // Load RAM info
                val memInfoOutput = shellExecutor.getMemInfo(serial)
                val (totalRam, availableRam) = parseMemInfo(memInfoOutput)

                // Load storage info - 一次获取全量 df -h，分别匹配挂载点
                val dfOutput = shellExecutor.getDiskUsage(serial)
                val internalStorage = parseDfMount(dfOutput)
                val externalStorage = parseDfExternal(dfOutput)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    deviceProperties = importantProps,
                    totalRam = totalRam,
                    availableRam = availableRam,
                    batteryLevel = batteryLevel,
                    batteryStatus = batteryStatus,
                    batteryHealth = batteryHealth,
                    batteryTemperature = batteryTemp,
                    batteryVoltage = batteryVoltage,
                    screenSize = screenSize,
                    screenDensity = density,
                    resolution = screenSize,
                    internalStorage = internalStorage,
                    externalStorage = externalStorage,
                    error = null
                )
                loadedSerial = serial
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    private fun extractBatteryValue(output: String, key: String): String? {
        val regex = Regex("^\\s+$key:", RegexOption.IGNORE_CASE)
        return output.lines()
            .find { regex.containsMatchIn(it) }
            ?.substringAfter(":")?.trim()
    }

    /**
     * 解析 /proc/meminfo 输出，返回 (总内存, 可用内存) 的可读字符串
     * meminfo 单位为 kB，转换为 GB 显示
     */
    private fun parseMemInfo(output: String): Pair<String, String> {
        val map = mutableMapOf<String, Long>()
        for (line in output.lines()) {
            val parts = line.split(":")
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim().split("\\s+".toRegex())[0].toLongOrNull()
                if (value != null) map[key] = value
            }
        }
        val totalKb = map["MemTotal"] ?: 0L
        val availableKb = map["MemAvailable"] ?: (map["MemFree"] ?: 0L)
        return Pair(formatSize(totalKb), formatSize(availableKb))
    }

    private fun formatSize(kb: Long): String {
        return when {
            kb >= 1_048_576 -> String.format(Locale.ROOT, "%.1f GB", kb / 1_048_576.0)
            kb >= 1024 -> String.format(Locale.ROOT, "%.0f MB", kb / 1024.0)
            else -> "$kb KB"
        }
    }

    /**
     * 解析 df -h 全量输出，精确匹配挂载点
     * 返回如 "49G / 110G（可用 60G）" 或 "N/A"
     */
    private fun parseDfMount(output: String): String {
        for (line in output.lines()) {
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size >= 6 && parts.last() == "/data") {
                val size = parts[1]
                val used = parts[2]
                val avail = parts[3]
                return "$used / $size（可用 $avail）"
            }
        }
        return "N/A"
    }

    /**
     * 查找真正的外部 SD 卡：/storage/ 下非 /storage/emulated 的挂载点
     */
    private fun parseDfExternal(output: String): String {
        for (line in output.lines()) {
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size >= 6) {
                val mount = parts.last()
                if (mount.startsWith("/storage/") && mount != "/storage/emulated") {
                    val size = parts[1]
                    val used = parts[2]
                    val avail = parts[3]
                    return "$used / $size（可用 $avail）"
                }
            }
        }
        return "无 SD 卡"
    }

    @Suppress("SdCardPath")
    fun takeScreenshot() {
        viewModelScope.launch {
            try {
                val serial = deviceSession.selectedSerial.value
                val timestamp = System.currentTimeMillis()
                val remotePath = "/sdcard/screenshot_$timestamp.png"
                val localDir = settingsRepository.localSavePathFlow.value
                // 确保本地目录存在（清缓存后目录可能丢失）
                java.io.File(localDir).mkdirs()
                val localPath = "$localDir/screenshot_$timestamp.png"

                // 远程截屏
                shellExecutor.screenshot(remotePath, serial)

                // 拉取到本地
                val pullResult = transferHelper.pull(remotePath, localPath, serial)
                val success = pullResult.output.contains("pulled", ignoreCase = true)

                // 清理远程临时文件
                shellExecutor.execute("rm -f $remotePath", serial)

                _uiState.value = _uiState.value.copy(
                    showScreenshotDialog = true,
                    screenshotPath = if (success) localPath else "拉取失败: ${pullResult.output.trim()}"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun reboot(mode: RebootMode = RebootMode.NORMAL) {
        viewModelScope.launch {
            try {
                shellExecutor.reboot(mode, serial = deviceSession.selectedSerial.value)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            try {
                // Clear cache for common apps
                shellExecutor.execute("pm trim-caches 100M", deviceSession.selectedSerial.value)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun dismissScreenshotDialog() {
        _uiState.value = _uiState.value.copy(showScreenshotDialog = false)
    }
}
