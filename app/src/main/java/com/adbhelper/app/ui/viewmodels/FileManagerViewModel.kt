package com.adbhelper.app.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adbhelper.app.core.adb.AdbManager
import dagger.hilt.android.qualifiers.ApplicationContext
import com.adbhelper.app.core.adb.DeviceSession
import com.adbhelper.app.core.shell.ShellExecutor
import com.adbhelper.app.core.shell.ShellResult
import com.adbhelper.app.core.shell.TransferHelper
import com.adbhelper.app.core.shell.TransferProgress
import com.adbhelper.app.core.shell.LsOutputParser
import com.adbhelper.app.data.repositories.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class FileManagerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shellExecutor: ShellExecutor,
    private val transferHelper: TransferHelper,
    private val settingsRepository: SettingsRepository,
    private val deviceSession: DeviceSession,
    private val adbManager: AdbManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileManagerState())
    val uiState: StateFlow<FileManagerState> = _uiState.asStateFlow()

    private var transferJob: Job? = null
    private var downloadJob: Job? = null

    /** 按设备 serial 缓存 root 状态，避免多设备间相互影响 */
    private val rootCache = mutableMapOf<String, Boolean>()

    /** 检测当前设备是否有 root 权限（结果缓存） */
    private suspend fun hasDeviceRoot(): Boolean {
        val serial = deviceSession.selectedSerial.value ?: return false
        rootCache[serial]?.let { return it }
        // 通过 su 执行 id 命令检测
        val result = shellExecutor.execute("su -c id", serial)
        val hasRoot = result.output.contains("uid=0")
        rootCache[serial] = hasRoot
        return hasRoot
    }

    /** 有 root 权限时用 su 提权执行，否则原样执行 */
    private suspend fun execShell(command: String): com.adbhelper.app.core.shell.ShellResult {
        val serial = deviceSession.selectedSerial.value
        return if (hasDeviceRoot()) {
            val escaped = command.replace("\"", "\\\"")
            shellExecutor.execute("su -c \"$escaped\"", serial)
        } else {
            shellExecutor.execute(command, serial)
        }
    }

    fun loadFiles(path: String = _uiState.value.currentPath) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // 尾部加 / 确保符号链接（如 /sdcard）能列出实际目录内容
                val lsPath = if (path.endsWith("/")) path else "$path/"
                val output = execShell("ls -la \"$lsPath\"").output
                val files = LsOutputParser.parse(output, path).toMutableList()
                LsOutputParser.resolveSymlinkTypes(files) { execShell(it) }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentPath = path,
                    files = files,
                    selectedFile = null,
                    isMultiSelectMode = false,
                    selectedPaths = emptySet()
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    fun navigateTo(path: String) {
        loadFiles(path)
    }

    fun navigateUp() {
        val current = _uiState.value.currentPath
        if (current == "/") return
        val parent = current.substringBeforeLast('/').ifEmpty { "/" }
        loadFiles(parent)
    }

    // ========== 排序与过滤 ==========

    fun setSortBy(sortBy: SortBy) {
        val state = _uiState.value
        val newAscending = if (state.sortBy == sortBy) !state.sortAscending else true
        _uiState.value = state.copy(sortBy = sortBy, sortAscending = newAscending)
    }

    fun toggleHidden() {
        _uiState.value = _uiState.value.copy(showHidden = !_uiState.value.showHidden)
    }

    fun getDisplayFiles(): List<RemoteFile> {
        val state = _uiState.value
        val filtered = if (state.showHidden) state.files else state.files.filter { !it.isHidden }
        // 通用排序函数：同组内再按名称兜底
        val comparator: Comparator<RemoteFile> = when (state.sortBy) {
            SortBy.NAME -> compareBy { it.name.lowercase() }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.DATE -> compareBy { it.modifiedDate }
            SortBy.TYPE -> compareBy<RemoteFile> { it.name.substringAfterLast('.', "").lowercase() }.thenBy { it.name.lowercase() }
        }
        val sorted = if (state.sortAscending) filtered.sortedWith(comparator) else filtered.sortedWith(comparator.reversed())
        // 目录 + 符号链接一组在前，文件在后，各自遵循用户排序
        val navigable = sorted.filter { it.isDirectory || it.isSymlink }
        val files = sorted.filter { !it.isDirectory && !it.isSymlink }
        return navigable + files
    }

    // ========== 多选 ==========

    /** 进入多选模式并选中首个文件 */
    fun enterMultiSelect(file: RemoteFile) {
        _uiState.value = _uiState.value.copy(
            isMultiSelectMode = true,
            selectedPaths = setOf(file.path)
        )
    }

    /** 在多选模式下切换单个文件选中状态 */
    fun toggleSelection(file: RemoteFile) {
        val current = _uiState.value.selectedPaths.toMutableSet()
        if (file.path in current) current.remove(file.path) else current.add(file.path)
        _uiState.value = _uiState.value.copy(selectedPaths = current)
    }

    /** 退出多选模式 */
    fun exitMultiSelect() {
        _uiState.value = _uiState.value.copy(
            isMultiSelectMode = false,
            selectedPaths = emptySet()
        )
    }

    /** 全选当前可见文件 */
    fun selectAll() {
        val allPaths = getDisplayFiles().map { it.path }.toSet()
        _uiState.value = _uiState.value.copy(selectedPaths = allPaths)
    }

    /** 取消全选 */
    fun deselectAll() {
        _uiState.value = _uiState.value.copy(selectedPaths = emptySet())
    }

    private fun getSelectedFiles(): List<RemoteFile> {
        val selectedPaths = _uiState.value.selectedPaths
        return getDisplayFiles().filter { it.path in selectedPaths }
    }

    // ========== 单文件详情 ==========

    fun showFileDetail(file: RemoteFile) {
        _uiState.value = _uiState.value.copy(selectedFile = file, showFileDetail = true)
    }

    fun dismissFileDetail() {
        _uiState.value = _uiState.value.copy(showFileDetail = false)
    }

    // ========== 文件操作 ==========

    fun confirmDelete() {
        _uiState.value = _uiState.value.copy(showDeleteConfirm = true)
    }

    fun dismissDeleteConfirm() {
        _uiState.value = _uiState.value.copy(showDeleteConfirm = false)
    }

    fun deleteFile() {
        val file = _uiState.value.selectedFile ?: return
        _uiState.value = _uiState.value.copy(showDeleteConfirm = false, showFileDetail = false)
        viewModelScope.launch {
            try {
                val cmd = if (file.isDirectory) "rm -rf \"${file.path}\"" else "rm \"${file.path}\""
                execShell(cmd)
                showMessage("已删除: ${file.name}")
                loadFiles()
            } catch (e: Exception) {
                showMessage("删除失败: ${e.message}")
            }
        }
    }

    // ========== 批量删除 ==========

    fun confirmBatchDelete() {
        _uiState.value = _uiState.value.copy(showBatchDeleteConfirm = true)
    }

    fun dismissBatchDeleteConfirm() {
        _uiState.value = _uiState.value.copy(showBatchDeleteConfirm = false)
    }

    fun batchDelete() {
        val files = getSelectedFiles()
        if (files.isEmpty()) return
        _uiState.value = _uiState.value.copy(showBatchDeleteConfirm = false)
        viewModelScope.launch {
            try {
                var success = 0; var fail = 0
                for (file in files) {
                    try {
                        val cmd = if (file.isDirectory) "rm -rf \"${file.path}\"" else "rm \"${file.path}\""
                        execShell(cmd)
                        success++
                    } catch (_: Exception) { fail++ }
                }
                showMessage("删除完成: $success 成功, $fail 失败")
                exitMultiSelect()
                loadFiles()
            } catch (e: Exception) {
                showMessage("批量删除失败: ${e.message}")
            }
        }
    }

    fun showRenameDialog() {
        _uiState.value = _uiState.value.copy(showFileDetail = false, showRenameDialog = true)
    }

    fun dismissRenameDialog() {
        _uiState.value = _uiState.value.copy(showRenameDialog = false)
    }

    fun renameFile(newName: String) {
        val file = _uiState.value.selectedFile ?: return
        val parent = file.path.substringBeforeLast('/')
        val newPath = "$parent/$newName"
        _uiState.value = _uiState.value.copy(showRenameDialog = false)
        viewModelScope.launch {
            try {
                execShell("mv \"${file.path}\" \"$newPath\"")
                showMessage("已重命名为: $newName")
                loadFiles()
            } catch (e: Exception) {
                showMessage("重命名失败: ${e.message}")
            }
        }
    }

    fun showNewFolderDialog() {
        _uiState.value = _uiState.value.copy(showNewFolderDialog = true)
    }

    fun dismissNewFolderDialog() {
        _uiState.value = _uiState.value.copy(showNewFolderDialog = false)
    }

    fun createFolder(name: String) {
        _uiState.value = _uiState.value.copy(showNewFolderDialog = false)
        viewModelScope.launch {
            try {
                val path = "${_uiState.value.currentPath}/$name"
                execShell("mkdir \"$path\"")
                showMessage("已创建文件夹: $name")
                loadFiles()
            } catch (e: Exception) {
                showMessage("创建失败: ${e.message}")
            }
        }
    }

    // ========== 复制/剪切/粘贴 ==========

    fun clearClipboard() {
        _uiState.value = _uiState.value.copy(clipboardFiles = emptyList(), clipboardMode = null)
    }

    /** 将多选的文件复制到剪贴板 */
    fun copySelectedToClipboard() {
        val files = getSelectedFiles()
        if (files.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            clipboardFiles = files,
            clipboardMode = ClipboardMode.COPY,
            isMultiSelectMode = false,
            selectedPaths = emptySet()
        )
        showMessage("已复制 ${files.size} 个文件")
    }

    /** 将多选的文件剪切到剪贴板 */
    fun cutSelectedToClipboard() {
        val files = getSelectedFiles()
        if (files.isEmpty()) return
        _uiState.value = _uiState.value.copy(
            clipboardFiles = files,
            clipboardMode = ClipboardMode.CUT,
            isMultiSelectMode = false,
            selectedPaths = emptySet()
        )
        showMessage("已剪切 ${files.size} 个文件")
    }

    /** 粘贴剪贴板中的所有文件 */
    fun paste() {
        val clipFiles = _uiState.value.clipboardFiles
        if (clipFiles.isEmpty()) return
        val mode = _uiState.value.clipboardMode ?: return
        val destDir = _uiState.value.currentPath

        viewModelScope.launch {
            try {
                var success = 0; var fail = 0
                for (clipFile in clipFiles) {
                    try {
                        pasteSingleFile(clipFile, mode, destDir)
                        success++
                    } catch (_: Exception) { fail++ }
                }
                _uiState.value = _uiState.value.copy(clipboardFiles = emptyList(), clipboardMode = null)
                showMessage("粘贴完成: $success 成功${if (fail > 0) ", $fail 失败" else ""}")
                loadFiles()
            } catch (e: Exception) {
                showMessage("粘贴失败: ${e.message}")
            }
        }
    }

    private suspend fun pasteSingleFile(clipFile: RemoteFile, mode: ClipboardMode, destDir: String) {
        val normalizedSource = clipFile.path.trimEnd('/')
        val normalizedDest = destDir.trimEnd('/')

        // 防止粘贴到自身目录内
        if (clipFile.isDirectory
            && (normalizedDest == normalizedSource || normalizedDest.startsWith("$normalizedSource/"))) {
            return
        }

        val sameDir = mode == ClipboardMode.COPY
                && normalizedDest == clipFile.path.substringBeforeLast('/').trimEnd('/')

        val destPath = if (sameDir) {
            generateCopyName(destDir, clipFile.name, clipFile.isDirectory)
        } else {
            checkRemoteNameConflict(destDir, clipFile.name)
        }

        when (mode) {
            ClipboardMode.COPY -> {
                val cmd = if (clipFile.isDirectory) "cp -r \"${clipFile.path}\" \"$destPath\""
                else "cp \"${clipFile.path}\" \"$destPath\""
                execShell(cmd)
            }
            ClipboardMode.CUT -> {
                execShell("mv \"${clipFile.path}\" \"$destPath\"")
            }
        }
    }

    /** 目标目录重名检测，自动加 (copy) 后缀 */
    private suspend fun checkRemoteNameConflict(dir: String, name: String): String {
        val existing = try {
            execShell("ls \"$dir\"").output.lines().map { it.trim() }.toSet()
        } catch (_: Exception) { emptySet() }
        return if (name in existing) generateCopyName(dir, name, false) else "$dir/$name"
    }

    /**
     * 同目录复制时生成不冲突的名称：
     * file.txt → file (copy).txt
     * file (copy).txt → file (copy 2).txt
     * folder → folder (copy)
     */
    private suspend fun generateCopyName(destDir: String, name: String, isDir: Boolean): String {
        val baseName: String
        val ext: String
        if (isDir) {
            baseName = name
            ext = ""
        } else {
            val dotIdx = name.lastIndexOf('.')
            if (dotIdx > 0) {
                baseName = name.substring(0, dotIdx)
                ext = name.substring(dotIdx)
            } else {
                baseName = name
                ext = ""
            }
        }

        // 获取当前目录下已有的文件名集合
        val existingNames = try {
            execShell("ls \"$destDir\"").output.lines().map { it.trim() }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val candidate1 = "$baseName (copy)$ext"
        if (candidate1 !in existingNames) return "$destDir/$candidate1"

        var i = 2
        while (true) {
            val candidate = "$baseName (copy $i)$ext"
            if (candidate !in existingNames) return "$destDir/$candidate"
            i++
        }
    }

    /**
     * 本地路径去重：file.txt → file (copy).txt → file (copy 2).txt
     */
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

    /**
     * 远程路径去重：通过 ls 检查设备上是否已有同名文件
     */
    private suspend fun generateUniqueRemoteName(dir: String, name: String): String {
        val existing = try {
            execShell("ls \"$dir\"").output.lines().map { it.trim() }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        if (name !in existing) return name

        val dotIdx = name.lastIndexOf('.')
        val baseName = if (dotIdx > 0) name.substring(0, dotIdx) else name
        val ext = if (dotIdx > 0) name.substring(dotIdx) else ""

        val candidate1 = "$baseName (copy)$ext"
        if (candidate1 !in existing) return candidate1

        var i = 2
        while (true) {
            val candidate = "$baseName (copy $i)$ext"
            if (candidate !in existing) return candidate
            i++
        }
    }

    // ========== 文本查看 ==========

    fun viewTextFile(file: RemoteFile) {
        if (file.size > 1_048_576) {
            showMessage("文件过大（>1MB），无法查看")
            return
        }
        _uiState.value = _uiState.value.copy(
            showFileDetail = false,
            showFileViewer = true,
            isLoadingContent = true,
            fileContent = null
        )
        viewModelScope.launch {
            try {
                val content = execShell("cat \"${file.path}\"").output
                _uiState.value = _uiState.value.copy(isLoadingContent = false, fileContent = content)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingContent = false,
                    fileContent = "读取失败: ${e.message}"
                )
            }
        }
    }

    fun dismissFileViewer() {
        _uiState.value = _uiState.value.copy(showFileViewer = false, fileContent = null)
    }

    // ========== 上传/下载 ==========

    fun downloadFile(file: RemoteFile) {
        if (file.isDirectory) return
        transferJob?.cancel()
        _uiState.value = _uiState.value.copy(
            showFileDetail = false,
            transferState = TransferState(direction = TransferDirection.PULL, fileName = file.name, fileSize = file.size)
        )
        transferJob = viewModelScope.launch {
            try {
                val serial = deviceSession.selectedSerial.value
                val localDir = settingsRepository.localSavePathFlow.value
                File(localDir).mkdirs()
                val localPath = generateUniqueLocalPath(localDir, file.name)
                transferHelper.pullStreaming(file.path, localPath, serial) { progress ->
                    _uiState.value = _uiState.value.copy(
                        transferState = _uiState.value.transferState?.copy(progress = progress)
                    )
                }
                _uiState.value = _uiState.value.copy(
                    transferState = _uiState.value.transferState?.copy(resultMessage = "已下载到:\n$localPath")
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                _uiState.value = _uiState.value.copy(transferState = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    transferState = _uiState.value.transferState?.copy(resultMessage = "下载失败: ${e.message}", isError = true)
                )
            }
        }
    }

    /** 批量下载选中的文件（跳过目录） */
    fun downloadSelectedFiles() {
        val files = getSelectedFiles().filter { !it.isDirectory }
        if (files.isEmpty()) {
            showMessage(if (getSelectedFiles().all { it.isDirectory }) "暂不支持下载文件夹" else "没有可下载的文件")
            return
        }
        downloadJob?.cancel()
        val total = files.size
        _uiState.value = _uiState.value.copy(
            transferState = TransferState(direction = TransferDirection.PULL, fileName = files[0].name, fileSize = 0)
        )
        downloadJob = viewModelScope.launch {
            try {
                val serial = deviceSession.selectedSerial.value
                val localDir = settingsRepository.localSavePathFlow.value
                File(localDir).mkdirs()

                for ((index, file) in files.withIndex()) {
                    if (!isActive) break
                    val localPath = generateUniqueLocalPath(localDir, file.name)
                    _uiState.value = _uiState.value.copy(
                        transferState = _uiState.value.transferState?.copy(
                            fileName = "[$index/$total] ${file.name}",
                            progress = TransferProgress(percent = 0, total = file.size)
                        )
                    )
                    transferHelper.pullStreaming(file.path, localPath, serial) { progress ->
                        _uiState.value = _uiState.value.copy(
                            transferState = _uiState.value.transferState?.copy(progress = progress)
                        )
                    }
                }
                exitMultiSelect()
                _uiState.value = _uiState.value.copy(
                    transferState = _uiState.value.transferState?.copy(
                        resultMessage = "已下载 $total 个文件到:\n$localDir"
                    )
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                _uiState.value = _uiState.value.copy(transferState = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    transferState = _uiState.value.transferState?.copy(
                        resultMessage = "下载失败: ${e.message}", isError = true
                    )
                )
            }
        }
    }

    fun uploadFile(localPath: String, remoteName: String? = null) {
        transferJob?.cancel()
        val fileName = remoteName ?: File(localPath).name
        val fileSize = File(localPath).length()
        _uiState.value = _uiState.value.copy(
            transferState = TransferState(direction = TransferDirection.PUSH, fileName = fileName, fileSize = fileSize)
        )
        transferJob = viewModelScope.launch {
            try {
                val serial = deviceSession.selectedSerial.value
                val uniqueName = generateUniqueRemoteName(_uiState.value.currentPath, fileName)
                val remotePath = "${_uiState.value.currentPath}/$uniqueName"

                transferHelper.pushStreaming(localPath, remotePath, serial) { progress ->
                    _uiState.value = _uiState.value.copy(
                        transferState = _uiState.value.transferState?.copy(progress = progress)
                    )
                }

                val success = _uiState.value.transferState?.progress?.error == null
                if (success) {
                    _uiState.value = _uiState.value.copy(
                        transferState = _uiState.value.transferState?.copy(resultMessage = "已上传: $uniqueName")
                    )
                    loadFiles()
                } else {
                    _uiState.value = _uiState.value.copy(
                        transferState = _uiState.value.transferState?.copy(resultMessage = "上传失败", isError = true)
                    )
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                _uiState.value = _uiState.value.copy(transferState = null)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    transferState = _uiState.value.transferState?.copy(resultMessage = "上传失败: ${e.message}", isError = true)
                )
            }
        }
    }

    fun dismissTransferResult() {
        _uiState.value = _uiState.value.copy(transferState = null)
    }

    fun cancelTransfer() {
        transferJob?.cancel()
        transferJob = null
        downloadJob?.cancel()
        downloadJob = null
        _uiState.value = _uiState.value.copy(transferState = null)
    }

    // ========== 消息 ==========

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun showMessage(msg: String) {
        _uiState.value = _uiState.value.copy(message = msg)
    }
}
