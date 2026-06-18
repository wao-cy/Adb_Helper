package com.adbhelper.app.ui.viewmodels

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adbhelper.app.core.adb.AdbManager
import com.adbhelper.app.core.adb.DeviceState
import com.adbhelper.app.core.adb.DeviceSession
import com.adbhelper.app.core.shell.AppIconService
import com.adbhelper.app.core.shell.ShellExecutor
import com.adbhelper.app.core.shell.TransferHelper
import com.adbhelper.app.data.repositories.AppNameStore
import com.adbhelper.app.data.repositories.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppManagerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val adbManager: AdbManager,
    private val shellExecutor: ShellExecutor,
    private val transferHelper: TransferHelper,
    private val settingsRepository: SettingsRepository,
    private val appNameStore: AppNameStore,
    private val appIconService: AppIconService,
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
            val t0 = System.currentTimeMillis()
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val serial = getDeviceSerial()
                if (serial == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false,
                        error = "No device connected")
                    return@launch
                }

                ensureJarOnDevice("AppListResolver.jar", serial)
                Log.d(TAG, "[t] ensureJar: ${System.currentTimeMillis() - t0}ms")

                val resolveNames = settingsRepository.resolveNamesFlow.value
                val extraArgs = if (!resolveNames) " --no-labels" else ""

                val result = shellExecutor.execute(
                    "CLASSPATH=/data/local/tmp/AppListResolver.jar " +
                    "app_process /data/local/tmp " +
                    "com.adbhelper.app.tools.AppListResolver$extraArgs", serial)
                Log.d(TAG, "[t] app_process list: ${System.currentTimeMillis() - t0}ms")

                Log.d(TAG, "[loadApps] exitCode=${result.exitCode}, " +
                    "outputLines=${result.output.lines().size}, " +
                    "firstLine=${result.output.lines().firstOrNull().orEmpty().take(100)}")

                if (result.exitCode != 0 || result.output.contains("ERROR") || result.output.contains("FATAL")) {
                    val errLine = result.output.lines().firstOrNull {
                        it.startsWith("ERROR") || it.startsWith("FATAL")
                    }
                    throw Exception(errLine ?: "AppListResolver failed (exit=$result.exitCode)")
                }

                val apps = parseAppListResult(result.output)

                val sysCount = apps.count { it.isSystemApp }
                val thirdCount = apps.count { !it.isSystemApp }
                Log.d(TAG, "[loadApps] parsed=${apps.size} (sys=$sysCount, third=$thirdCount), " +
                    "sample=${apps.take(3).map { "${it.packageName}=${it.appName}" }}")

                if (resolveNames) {
                    appNameStore.update(apps.associate { it.packageName to it.appName })
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false, apps = apps, error = null
                )
                applyFilterAndSearch()

                // 并行加载图标：不阻塞列表展示
                if (settingsRepository.fetchIconsFlow.value) {
                    viewModelScope.launch { loadAppIcons(t0) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[loadApps] error", e)
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    private suspend fun ensureJarOnDevice(jarName: String, serial: String) {
        val dexFile = File(context.cacheDir, jarName)
        context.assets.open(jarName).use { input ->
            dexFile.outputStream().use { output -> input.copyTo(output) }
        }
        val b64 = Base64.encodeToString(dexFile.readBytes(), Base64.NO_WRAP)
        shellExecutor.execute("echo '$b64' | base64 -d > /data/local/tmp/$jarName", serial)
        shellExecutor.execute("chmod 644 /data/local/tmp/$jarName", serial)
    }

    private suspend fun loadAppIcons(t0: Long = 0) {
        if (!settingsRepository.fetchIconsFlow.value) return
        _uiState.value = _uiState.value.copy(isLoadingIcons = true)
        try {
            val serial = getDeviceSerial() ?: return
            val currentApps = _uiState.value.apps
            if (currentApps.isEmpty()) return

            val icons = appIconService.loadAppIcons(
                packages = currentApps.map { it.packageName },
                serial = serial
            )

            if (t0 > 0) Log.d(TAG, "[t] total load: ${System.currentTimeMillis() - t0}ms")
            _uiState.value = _uiState.value.copy(appIcons = icons, isLoadingIcons = false)
            Log.d(TAG, "[icons] displayed ${icons.size} icons")
        } catch (e: Exception) {
            Log.e(TAG, "[icons] loadAppIcons failed", e)
            _uiState.value = _uiState.value.copy(isLoadingIcons = false)
        }
    }

    private fun parseAppListResult(output: String): List<AppInfo> {
        val apps = output.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("ERROR") && !it.startsWith("FATAL") }
            .mapNotNull { line ->
                val parts = line.split("\t", limit = 5)
                if (parts.size < 5) {
                    Log.d(TAG, "skip[${parts.size}] ${line.take(100)}")
                    return@mapNotNull null
                }
                val (pkg, sourceDir, isSystem, isDisabled, label) = parts
                AppInfo(
                    packageName = pkg,
                    apkPath = sourceDir,
                    appName = if (label == "?" || label.isBlank()) pkg else label,
                    isSystemApp = isSystem == "1",
                    isDisabled = isDisabled == "1"
                )
            }
            .sortedBy { it.packageName.lowercase() }
        Log.d(TAG, "[parse] total=${output.lines().size}, parsed=${apps.size}")
        return apps
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

        var launchActivity = ""
        val launcherRegex = Regex("android\\.intent\\.action\\.MAIN[\\s\\S]*?([a-zA-Z0-9_.]+/[a-zA-Z0-9_.]+)")
        val match = launcherRegex.find(dumpsys)
        if (match != null) {
            launchActivity = match.groupValues[1].trim()
        } else {
            try {
                val resolve = shellExecutor.execute(
                    "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $packageName", serial
                )
                val lines = resolve.output.lines().filter { it.contains("/") }
                if (lines.size >= 2) launchActivity = lines.last().trim()
            } catch (_: Exception) {}
        }

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
