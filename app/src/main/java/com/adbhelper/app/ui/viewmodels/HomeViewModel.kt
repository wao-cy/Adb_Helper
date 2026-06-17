package com.adbhelper.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adbhelper.app.core.adb.AdbDevice
import com.adbhelper.app.core.adb.AdbManager
import com.adbhelper.app.core.adb.DeviceSession
//import com.adbhelper.app.core.adb.DeviceState
import dagger.hilt.android.lifecycle.HiltViewModel
//import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = false,
    val adbServerRunning: Boolean = true,
    val isServerRestarting: Boolean = false,
    val connectedDevices: List<AdbDevice> = emptyList(),
    val selectedDevice: AdbDevice? = null,
    val lanDevices: List<String> = emptyList(),
    val isScanningLan: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val adbManager: AdbManager,
    private val deviceSession: DeviceSession
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            adbManager.startServer()
            // 立即刷新设备
            doRefreshDevices()
            // 第二次刷新：等无线设备完成重连
            kotlinx.coroutines.delay(3000)
            doRefreshDevices()
        }
    }

    fun restartAdbServer() {
        if (_uiState.value.isServerRestarting) return
        _uiState.value = _uiState.value.copy(isServerRestarting = true)
        viewModelScope.launch {
            try {
                adbManager.killServer()
                val result = adbManager.startServer()
                result.fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(
                            isServerRestarting = false,
                            message = "ADB server restarted"
                        )
                        // 立即刷新：USB 设备秒连
                        doRefreshDevices()
                        // 延迟刷新：等无线设备重连
                        kotlinx.coroutines.delay(2000)
                        doRefreshDevices()
                    },
                    onFailure = { e ->
                        _uiState.value = _uiState.value.copy(
                            isServerRestarting = false,
                            error = "Failed to restart ADB server: ${e.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isServerRestarting = false,
                    error = "Failed to restart ADB server: ${e.message}"
                )
            }
        }
    }

    fun refreshDevices() {
        viewModelScope.launch {
            doRefreshDevices(showLoading = true)
        }
    }

    private suspend fun doRefreshDevices(showLoading: Boolean = false) {
        if (showLoading) _uiState.value = _uiState.value.copy(isLoading = true)
        try {
            val result = adbManager.getDevices()
            result.fold(
                onSuccess = { devices ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        connectedDevices = devices,
                        error = null
                    )
                    // 无选中设备时自动选中第一台已连接设备
                    if (_uiState.value.selectedDevice == null) {
                        devices.firstOrNull { it.state == com.adbhelper.app.core.adb.DeviceState.DEVICE }
                            ?.let { selectDevice(it) }
                    }
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Failed to get devices: ${e.message}"
                    )
                }
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = e.message
            )
        }
    }

    fun selectDevice(device: AdbDevice) {
        _uiState.value = _uiState.value.copy(selectedDevice = device)
        deviceSession.select(device.serial)
    }

    fun connectDevice(ip: String, port: Int = 5555, onResult: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val result = adbManager.connect(ip, port)
                result.fold(
                    onSuccess = {
                        onResult?.invoke(true, "Connected successfully")
                        doRefreshDevices()
                        delay(1000) // 等待设备稳定后二次刷新，避免显示 offline
                        doRefreshDevices()
                        // 自动选中刚连接的设备
                        val serial = "$ip:$port"
                        val connected = _uiState.value.connectedDevices.find { it.serial == serial }
                        if (connected != null) {
                            _uiState.value = _uiState.value.copy(selectedDevice = connected)
                            deviceSession.select(connected.serial)
                        }
                    },
                    onFailure = { e ->
                        val msg = e.message ?: "Connection failed"
                        onResult?.invoke(false, msg)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = msg
                        )
                    }
                )
            } catch (e: Exception) {
                val msg = e.message ?: "Connection failed"
                onResult?.invoke(false, msg)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = msg
                )
            }
        }
    }

    fun disconnectDevice(serial: String) {
        viewModelScope.launch {
            try {
                adbManager.disconnect(serial)
                // 清除选中状态
                if (_uiState.value.selectedDevice?.serial == serial) {
                    _uiState.value = _uiState.value.copy(selectedDevice = null)
                    deviceSession.select(null)
                }
                kotlinx.coroutines.delay(300)
                refreshDevices()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun pairDevice(ip: String, port: Int, pairingCode: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val result = adbManager.pair(ip, port, pairingCode)
                result.fold(
                    onSuccess = { output ->
                        onResult(true, output)
                    },
                    onFailure = { e ->
                        onResult(false, e.message ?: "Pairing failed")
                    }
                )
            } catch (e: Exception) {
                onResult(false, e.message ?: "Pairing failed")
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** 退出应用前清理：杀 ADB server，最多等 500ms */
    fun shutdown() {
        try {
            adbManager.killServerWithTimeout()
        } catch (_: Exception) {}
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun scanLanDevices() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanningLan = true, lanDevices = emptyList())
            val result = adbManager.scanLanDevices()
            result.fold(
                onSuccess = { devices ->
                    _uiState.value = _uiState.value.copy(
                        isScanningLan = false,
                        lanDevices = devices
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isScanningLan = false,
                        error = "扫描失败: ${e.message}"
                    )
                }
            )
        }
    }

}
