package com.adbhelper.app.ui.viewmodels

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adbhelper.app.core.adb.AdbManager
import com.adbhelper.app.core.adb.DeviceState
import com.adbhelper.app.core.adb.DeviceSession
import com.adbhelper.app.core.shell.ShellExecutor
import com.adbhelper.app.core.shell.TransferHelper
import java.io.File
import com.adbhelper.app.data.repositories.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AppManagerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val adbManager: AdbManager,
    private val shellExecutor: ShellExecutor,
    private val transferHelper: TransferHelper,
    private val settingsRepository: SettingsRepository,
    private val deviceSession: DeviceSession
) : ViewModel() {

    companion object {
        private const val TAG = "AppManagerViewModel"
    }

    private val _uiState = MutableStateFlow(AppManagerUiState())
    val uiState: StateFlow<AppManagerUiState> = _uiState.asStateFlow()

    private suspend fun getDeviceSerial(): String? {
        return deviceSession.selectedSerial.value
            ?: adbManager.getDevices().getOrNull()
                ?.firstOrNull { it.state == DeviceState.DEVICE }
                ?.serial
    }

    /** 传输相关逻辑委托给 handler，减轻 ViewModel 体量 */
    private val transferHandler = AppTransferHandler(
        context = context,
        shellExecutor = shellExecutor,
        transferHelper = transferHelper,
        settingsRepository = settingsRepository,
        uiState = _uiState,
        scope = viewModelScope,
        onAppsChanged = { loadApps(force = true) },
        onMessage = { msg, success -> showMessage(msg, success) },
        getDeviceSerial = { getDeviceSerial() }
    )

    init {
        loadApps()
    }

    // ========== 应用列表 ==========

    fun loadApps(force: Boolean = false) {
        if (!force && _uiState.value.apps.isNotEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val serial = getDeviceSerial()
                val result = shellExecutor.execute("pm list packages -f", serial)
                val disabledResult = shellExecutor.execute("pm list packages -d", serial)
                val disabledPackages = disabledResult.output.lines()
                    .filter { it.startsWith("package:") }
                    .map { it.removePrefix("package:").trim() }
                    .toSet()
                val apps = parsePackageList(result.output, disabledPackages)
                _uiState.value = _uiState.value.copy(isLoading = false, apps = apps, error = null)
                applyFilterAndSearch()
                loadAppNames()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    private suspend fun loadAppNames() = withContext(Dispatchers.IO) {
        _uiState.value = _uiState.value.copy(isLoadingNames = true)
        try {
            val serial = getDeviceSerial()
            val nameMap = mutableMapOf<String, String>()
            val currentApps = _uiState.value.apps

            // 方法一：app_process → PackageManager API（主方案，~87% 成功率）
            if (serial != null) {
                try {
                    // 仅首次需推送 jar 到设备
                    val dexFile = File(context.cacheDir, "AppNameResolver.jar")
                    val checkResult = shellExecutor.execute(
                        "ls /data/local/tmp/AppNameResolver.jar 2>/dev/null", serial)
                    if (checkResult.exitCode != 0) {
                        context.assets.open("AppNameResolver.jar").use { input ->
                            dexFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        val base64 = Base64.encodeToString(dexFile.readBytes(), Base64.NO_WRAP)
                        shellExecutor.execute(
                            "echo '$base64' | base64 -d > /data/local/tmp/AppNameResolver.jar",
                            serial)
                        shellExecutor.execute(
                            "chmod 644 /data/local/tmp/AppNameResolver.jar", serial)
                    }

                    // 一次性传所有包，app_process 内部逐包解析
                    val allPkgs = currentApps.map { it.packageName }.joinToString(" ")
                    val result = shellExecutor.execute(
                        "CLASSPATH=/data/local/tmp/AppNameResolver.jar " +
                        "app_process /data/local/tmp " +
                        "com.adbhelper.app.tools.AppNameResolver $allPkgs", serial)
                    for (line in result.output.lines()) {
                        val eq = line.indexOf('=')
                        if (eq > 0) {
                            val pkg = line.substring(0, eq)
                            val name = line.substring(eq + 1)
                            if (name.isNotBlank()) nameMap[pkg] = name
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "app_process failed", e)
                }
            }

            // 方法二：fallback — cmd overlay lookup
            val unnamedPkgs = currentApps.filter {
                !it.isSystemApp && (nameMap[it.packageName] ?: "").isBlank()
            }.map { it.packageName }
            if (unnamedPkgs.isNotEmpty() && serial != null) {
                for (pkg in unnamedPkgs) {
                    try {
                        val result = shellExecutor.execute("cmd overlay lookup $pkg $pkg:string/app_name", serial)
                        val output = result.output.trim()
                        if (output.isNotBlank()
                            && !output.contains("error", ignoreCase = true)
                            && !output.contains("find service", ignoreCase = true)
                        ) {
                            val name = Regex("""->\s*"(.+?)"""").find(output)?.groupValues?.get(1)
                                ?: output.substringAfter(" -> ", "")
                                    .trim()
                                    .removeSurrounding("\"")
                                    .ifBlank { null }
                                ?: output.lines().firstOrNull()?.trim().orEmpty()
                            if (name.isNotBlank()) nameMap[pkg] = name
                        }
                    } catch (_: Exception) {}
                }
            }

            val updatedApps = currentApps.map { app ->
                val appName = nameMap[app.packageName] ?: ""
                app.copy(appName = appName.ifBlank { app.packageName })
            }
            _uiState.value = _uiState.value.copy(apps = updatedApps, isLoadingNames = false)
            applyFilterAndSearch()
        } catch (_: Exception) {
            _uiState.value = _uiState.value.copy(isLoadingNames = false)
        }
    }

    private fun parsePackageList(output: String, disabledPackages: Set<String> = emptySet()): List<AppInfo> {
        val apps = mutableListOf<AppInfo>()
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("package:")) continue
            val withoutPrefix = trimmed.removePrefix("package:")
            val eqIndex = withoutPrefix.lastIndexOf('=')
            if (eqIndex <= 0) continue
            val apkPath = withoutPrefix.substring(0, eqIndex)
            val packageName = withoutPrefix.substring(eqIndex + 1)
            val isSystemApp = apkPath.startsWith("/system/") || apkPath.startsWith("/product/") ||
                    apkPath.startsWith("/vendor/") || apkPath.startsWith("/apex/")
            apps.add(AppInfo(
                packageName = packageName,
                apkPath = apkPath,
                isSystemApp = isSystemApp,
                isDisabled = packageName in disabledPackages
            ))
        }
        return apps.sortedBy { it.packageName.lowercase() }
    }

    // ========== 应用详情 ==========

    fun loadAppDetail(packageName: String, apkPath: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDetail = true, appDetail = null)
            try {
                val serial = getDeviceSerial()
                val dumpsys = shellExecutor.execute("dumpsys package $packageName", serial).output
                val detail = parseAppDetail(dumpsys, packageName, apkPath, serial)
                _uiState.value = _uiState.value.copy(isLoadingDetail = false, appDetail = detail)
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingDetail = false,
                    appDetail = AppDetail(packageName = packageName)
                )
            }
        }
    }

    fun dismissAppDetail() {
        _uiState.value = _uiState.value.copy(appDetail = null)
    }

    private suspend fun parseAppDetail(
        dumpsys: String, packageName: String, apkPath: String, serial: String?
    ): AppDetail {
        val versionName = Regex("versionName=(.+)").find(dumpsys)?.groupValues?.get(1)?.trim() ?: ""
        val versionCode = Regex("versionCode=(\\d+)").find(dumpsys)?.groupValues?.get(1)?.trim() ?: ""
        val minSdk = Regex("minSdk=(\\d+)").find(dumpsys)?.groupValues?.get(1)?.trim() ?: ""
        val targetSdk = Regex("targetSdk=(\\d+)").find(dumpsys)?.groupValues?.get(1)?.trim() ?: ""
        val firstInstall = Regex("firstInstallTime=(.+)").find(dumpsys)?.groupValues?.get(1)?.trim() ?: ""
        val lastUpdate = Regex("lastUpdateTime=(.+)").find(dumpsys)?.groupValues?.get(1)?.trim() ?: ""

        // 获取启动活动
        var launchActivity = ""
        val launcherRegex = Regex("android\\.intent\\.action\\.MAIN[\\s\\S]*?([a-zA-Z0-9_.]+/[a-zA-Z0-9_.]+)")
        val match = launcherRegex.find(dumpsys)
        if (match != null) {
            launchActivity = match.groupValues[1].trim()
        } else {
            // 回退：用 cmd package resolve-activity
            try {
                val resolve = shellExecutor.execute(
                    "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $packageName", serial
                )
                val lines = resolve.output.lines().filter { it.contains("/") }
                if (lines.size >= 2) launchActivity = lines.last().trim()
            } catch (_: Exception) {}
        }

        // 获取 APK 大小
        var apkSize = ""
        try {
            val lsResult = shellExecutor.execute("ls -la $apkPath", serial)
            val parts = lsResult.output.trim().split("\\s+".toRegex())
            if (parts.size >= 5) {
                val sizeBytes = parts[4].toLongOrNull()
                if (sizeBytes != null) {
                    apkSize = formatFileSize(sizeBytes)
                }
            }
        } catch (_: Exception) {}

        return AppDetail(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            minSdkVersion = minSdk,
            targetSdkVersion = targetSdk,
            firstInstallTime = firstInstall,
            lastUpdateTime = lastUpdate,
            apkSize = apkSize,
            launchActivity = launchActivity
        )
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    // ========== 筛选与搜索 ==========

    fun setFilter(filter: AppFilter) {
        _uiState.value = _uiState.value.copy(filter = filter)
        applyFilterAndSearch()
    }

    fun setSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        applyFilterAndSearch()
    }

    private fun applyFilterAndSearch() {
        val state = _uiState.value
        val filtered = state.apps.filter { app ->
            val matchFilter = when (state.filter) {
                AppFilter.ALL -> true
                AppFilter.THIRD_PARTY -> !app.isSystemApp
                AppFilter.SYSTEM -> app.isSystemApp
            }
            val matchSearch = state.searchQuery.isBlank() ||
                    app.packageName.contains(state.searchQuery, ignoreCase = true) ||
                    app.appName.contains(state.searchQuery, ignoreCase = true)
            matchFilter && matchSearch
        }
        _uiState.value = state.copy(filteredApps = filtered)
    }

    // ========== 应用操作 ==========

    fun forceStop(packageName: String) {
        viewModelScope.launch {
            try {
                val serial = getDeviceSerial()
                shellExecutor.forceStop(packageName, serial)
                showMessage("已停止 $packageName", true)
            } catch (e: Exception) { showMessage("停止失败: ${e.message}", false) }
        }
    }

    fun clearData(packageName: String) {
        viewModelScope.launch {
            try {
                val serial = getDeviceSerial()
                val result = shellExecutor.clearPackage(packageName, serial)
                val success = result.output.contains("Success", ignoreCase = true)
                showMessage(if (success) "已清除 $packageName 的数据" else "清除失败: 需要 Root 权限或设备管理员权限", success)
            } catch (e: Exception) { showMessage("清除失败: ${e.message}", false) }
        }
    }

    fun uninstall(packageName: String) {
        viewModelScope.launch {
            try {
                val serial = getDeviceSerial()
                val result = shellExecutor.uninstallPackage(packageName, serial)
                val success = result.output.contains("Success", ignoreCase = true)
                if (success) loadApps(force = true)
                showMessage(if (success) "已卸载 $packageName" else "卸载失败: ${result.output.trim()}", success)
            } catch (e: Exception) { showMessage("卸载失败: ${e.message}", false) }
        }
    }

    fun launchApp(packageName: String) {
        viewModelScope.launch {
            try {
                val serial = getDeviceSerial()
                val result = shellExecutor.execute("monkey -p $packageName -c android.intent.category.LAUNCHER 1 2>&1", serial)
                val success = !result.output.contains("Error", ignoreCase = true) &&
                        !result.output.contains("No activities", ignoreCase = true)
                showMessage(if (success) "已启动 $packageName" else "启动失败: 可能没有可启动的界面", success)
            } catch (e: Exception) { showMessage("启动失败: ${e.message}", false) }
        }
    }

    fun disableApp(packageName: String) {
        viewModelScope.launch {
            try {
                val serial = getDeviceSerial()
                val result = shellExecutor.disableApp(packageName, serial)
                val success = result.output.contains("disabled", ignoreCase = true) || result.output.isBlank()
                if (success) loadApps(force = true)
                showMessage(if (success) "已禁用 $packageName" else "禁用失败: ${result.output.trim()}", success)
            } catch (e: Exception) { showMessage("禁用失败: ${e.message}", false) }
        }
    }

    fun enableApp(packageName: String) {
        viewModelScope.launch {
            try {
                val serial = getDeviceSerial()
                val result = shellExecutor.enableApp(packageName, serial)
                val success = result.output.contains("enabled", ignoreCase = true) || result.output.isBlank()
                if (success) loadApps(force = true)
                showMessage(if (success) "已启用 $packageName" else "启用失败: ${result.output.trim()}", success)
            } catch (e: Exception) { showMessage("启用失败: ${e.message}", false) }
        }
    }

    fun downloadApk(packageName: String, apkPath: String) {
        transferHandler.downloadApk(packageName, apkPath)
    }

    // ========== 传输相关（委托给 handler） ==========

    fun showPushDialog() = transferHandler.showPushDialog()
    fun dismissPushDialog() = transferHandler.dismissPushDialog()
    fun showLocalAppPicker() = transferHandler.showLocalAppPicker()
    fun dismissLocalAppPicker() = transferHandler.dismissLocalAppPicker()
    fun dismissPermissionWarning() = transferHandler.dismissPermissionWarning()
    fun openAppSettings() = transferHandler.openAppSettings()
    fun pushLocalApp(localApp: LocalAppInfo) = transferHandler.pushLocalApp(localApp)
    fun handlePickedFile(uri: Uri?) = transferHandler.handlePickedFile(uri)
    fun cancelTransfer() = transferHandler.cancelTransfer()

    fun dismissTransferResult() {
        _uiState.value = _uiState.value.copy(transferState = null)
    }

    // ========== 消息 ==========

    private fun showMessage(message: String, success: Boolean) {
        _uiState.value = _uiState.value.copy(operationMessage = message, operationSuccess = success)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(operationMessage = null)
    }
}
