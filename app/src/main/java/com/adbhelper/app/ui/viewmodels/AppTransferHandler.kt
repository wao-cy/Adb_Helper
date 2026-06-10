package com.adbhelper.app.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.adbhelper.app.core.shell.ShellExecutor
import com.adbhelper.app.core.shell.TransferHelper
import com.adbhelper.app.core.shell.TransferProgress
import com.adbhelper.app.data.repositories.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 处理 APK 推送、文件选择、本地应用选取等传输相关逻辑。
 * 从 AppManagerViewModel 中拆出，减轻 ViewModel 体量。
 */
class AppTransferHandler(
    private val context: Context,
    private val shellExecutor: ShellExecutor,
    private val transferHelper: TransferHelper,
    private val settingsRepository: SettingsRepository,
    private val uiState: MutableStateFlow<AppManagerUiState>,
    private val scope: CoroutineScope,
    private val onAppsChanged: () -> Unit,
    private val onMessage: (String, Boolean) -> Unit,
    private val getDeviceSerial: suspend () -> String?
) {
    private var transferJob: Job? = null

    // ========== 对话框控制 ==========

    fun showPushDialog() {
        uiState.value = uiState.value.copy(showPushDialog = true)
    }

    fun dismissPushDialog() {
        uiState.value = uiState.value.copy(showPushDialog = false, showLocalAppPicker = false)
    }

    fun showLocalAppPicker() {
        uiState.value = uiState.value.copy(showPushDialog = false)
        loadLocalApps()
    }

    fun dismissLocalAppPicker() {
        uiState.value = uiState.value.copy(showLocalAppPicker = false)
    }

    fun dismissPermissionWarning() {
        uiState.value = uiState.value.copy(showPermissionWarning = false)
    }

    fun openAppSettings() {
        uiState.value = uiState.value.copy(showPermissionWarning = false)
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }

    // ========== 本地应用列表 ==========

    private fun loadLocalApps() {
        scope.launch {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
            val apps = packages.mapNotNull { pkgInfo ->
                val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
                val label = appInfo.loadLabel(pm).toString()
                val apkPath = appInfo.sourceDir
                if (apkPath.isNotBlank()) {
                    LocalAppInfo(
                        packageName = pkgInfo.packageName,
                        appName = label.ifBlank { pkgInfo.packageName },
                        apkPath = apkPath
                    )
                } else null
            }.sortedBy { it.appName.lowercase() }

            val selfPackage = context.packageName
            val nonSelfApps = apps.filter { it.packageName != selfPackage }
            val permissionLimited = nonSelfApps.isEmpty()

            uiState.value = uiState.value.copy(
                localApps = apps,
                showPermissionWarning = permissionLimited,
                showLocalAppPicker = !permissionLimited
            )
        }
    }

    // ========== 传输操作 ==========

    fun downloadApk(packageName: String, apkPath: String) {
        transferJob?.cancel()
        val fileName = "$packageName.apk"
        uiState.value = uiState.value.copy(
            transferState = TransferState(direction = TransferDirection.PULL, fileName = fileName)
        )
        transferJob = scope.launch {
            try {
                val serial = getDeviceSerial()
                val localDir = settingsRepository.localSavePathFlow.value
                File(localDir).mkdirs()
                val localPath = generateUniqueLocalPath(localDir, fileName)
                transferHelper.pullStreaming(apkPath, localPath, serial) { progress ->
                    uiState.value = uiState.value.copy(
                        transferState = uiState.value.transferState?.copy(progress = progress)
                    )
                }
                uiState.value = uiState.value.copy(
                    transferState = uiState.value.transferState?.copy(resultMessage = "已保存到:\n$localPath")
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                uiState.value = uiState.value.copy(transferState = null)
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    transferState = uiState.value.transferState?.copy(resultMessage = "下载失败: ${e.message}", isError = true)
                )
            }
        }
    }

    fun cancelTransfer() {
        transferJob?.cancel()
        transferJob = null
        uiState.value = uiState.value.copy(transferState = null)
    }

    fun pushLocalApp(localApp: LocalAppInfo) {
        pushApk(localApp.apkPath)
    }

    fun pushApk(localApkPath: String) {
        transferJob?.cancel()
        val fileName = File(localApkPath).name
        val fileSize = File(localApkPath).length()
        uiState.value = uiState.value.copy(
            transferState = TransferState(direction = TransferDirection.PUSH, fileName = fileName, fileSize = fileSize)
        )
        transferJob = scope.launch {
            try {
                val serial = getDeviceSerial()
                val remotePath = "/data/local/tmp/$fileName"

                val pushResult = transferHelper.pushStreaming(localApkPath, remotePath, serial) { progress ->
                    uiState.value = uiState.value.copy(
                        transferState = uiState.value.transferState?.copy(progress = progress)
                    )
                }

                if (pushResult.exitCode != 0) {
                    uiState.value = uiState.value.copy(
                        transferState = uiState.value.transferState?.copy(
                            resultMessage = "推送失败: ${pushResult.output.trim()}", isError = true
                        )
                    )
                    return@launch
                }

                uiState.value = uiState.value.copy(
                    transferState = uiState.value.transferState?.copy(
                        progress = TransferProgress(percent = 100, isComplete = false, speed = "安装中…")
                    )
                )

                val installResult = shellExecutor.installPackage(remotePath, serial)
                val installSuccess = installResult.output.contains("Success", ignoreCase = true)
                shellExecutor.execute("rm -f $remotePath", serial)

                if (installSuccess) {
                    uiState.value = uiState.value.copy(
                        transferState = uiState.value.transferState?.copy(resultMessage = "推送并安装成功: $fileName")
                    )
                    onAppsChanged()
                } else {
                    uiState.value = uiState.value.copy(
                        transferState = uiState.value.transferState?.copy(
                            resultMessage = "推送成功，但安装失败:\n${installResult.output.trim()}", isError = true
                        )
                    )
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                uiState.value = uiState.value.copy(transferState = null)
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    transferState = uiState.value.transferState?.copy(
                        resultMessage = "推送失败: ${e.message}", isError = true
                    )
                )
            }
        }
    }

    fun handlePickedFile(uri: Uri?) {
        uri ?: return
        scope.launch {
            try {
                val path = getPathFromUri(uri)
                if (path != null) {
                    pushApk(path)
                } else {
                    val tempFile = File(context.cacheDir, "picked_apk.apk")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    pushApk(tempFile.absolutePath)
                }
            } catch (e: Exception) {
                onMessage("读取文件失败: ${e.message}", false)
            }
        }
    }

    private fun generateUniqueLocalPath(dir: String, name: String): String {
        val file = File(dir, name)
        if (!file.exists()) return file.absolutePath

        val dotIdx = name.lastIndexOf('.')
        val baseName = if (dotIdx > 0) name.substring(0, dotIdx) else name
        val ext = if (dotIdx > 0) name.substring(dotIdx) else ""

        var candidate = File(dir, "$baseName (copy)$ext")
        if (!candidate.exists()) return candidate.absolutePath

        var i = 2
        while (true) {
            candidate = File(dir, "$baseName (copy $i)$ext")
            if (!candidate.exists()) return candidate.absolutePath
            i++
        }
    }

    private fun getPathFromUri(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        try {
            context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex("_data")
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
