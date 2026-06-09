package com.adbhelper.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.adbhelper.app.core.adb.AdbManager
import com.adbhelper.app.core.adb.DeviceSession
import com.adbhelper.app.core.shell.ShellExecutor
import com.adbhelper.app.core.shell.TransferHelper
import com.adbhelper.app.data.repositories.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class FileManagerViewModel @Inject constructor(
    private val shellExecutor: ShellExecutor,
    private val transferHelper: TransferHelper,
    private val settingsRepository: SettingsRepository,
    private val deviceSession: DeviceSession,
    private val adbManager: AdbManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileManagerState())
    val uiState: StateFlow<FileManagerState> = _uiState.asStateFlow()

    private var transferJob: Job? = null

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
                val files = parseLsOutput(output, path)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentPath = path,
                    files = files,
                    selectedFile = null
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

    // ========== 选中与详情 ==========

    fun selectFile(file: RemoteFile?) {
        _uiState.value = _uiState.value.copy(selectedFile = file)
    }

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

    fun copyToClipboard() {
        val file = _uiState.value.selectedFile ?: return
        _uiState.value = _uiState.value.copy(
            clipboardFile = file,
            clipboardMode = ClipboardMode.COPY,
            selectedFile = null,
            showFileDetail = false
        )
        showMessage("已复制: ${file.name}")
    }

    fun cutToClipboard() {
        val file = _uiState.value.selectedFile ?: return
        _uiState.value = _uiState.value.copy(
            clipboardFile = file,
            clipboardMode = ClipboardMode.CUT,
            selectedFile = null,
            showFileDetail = false
        )
        showMessage("已剪切: ${file.name}")
    }

    fun paste() {
        val clipFile = _uiState.value.clipboardFile ?: return
        val mode = _uiState.value.clipboardMode ?: return
        val destDir = _uiState.value.currentPath

        // 防止粘贴到自身目录内：目标路径是源路径的子目录
        val normalizedSource = clipFile.path.trimEnd('/')
        val normalizedDest = destDir.trimEnd('/')
        if (clipFile.isDirectory
            && (normalizedDest == normalizedSource || normalizedDest.startsWith("$normalizedSource/"))) {
            showMessage("不能粘贴到自身目录内")
            return
        }

        // 同目录复制时自动加后缀，避免 cp "a" "a" 失败
        val sameDir = mode == ClipboardMode.COPY
                && normalizedDest == clipFile.path.substringBeforeLast('/').trimEnd('/')

        viewModelScope.launch {
            try {
                val destPath = if (sameDir) {
                    generateCopyName(destDir, clipFile.name, clipFile.isDirectory)
                } else {
                    "$destDir/${clipFile.name}"
                }
                when (mode) {
                    ClipboardMode.COPY -> {
                        val cmd = if (clipFile.isDirectory) "cp -r \"${clipFile.path}\" \"$destPath\""
                        else "cp \"${clipFile.path}\" \"$destPath\""
                        execShell(cmd)
                        showMessage("已粘贴: ${File(destPath).name}")
                    }
                    ClipboardMode.CUT -> {
                        execShell("mv \"${clipFile.path}\" \"$destPath\"")
                        showMessage("已移动: ${clipFile.name}")
                    }
                }
                _uiState.value = _uiState.value.copy(clipboardFile = null, clipboardMode = null)
                loadFiles()
            } catch (e: Exception) {
                showMessage("粘贴失败: ${e.message}")
            }
        }
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

                val success = _uiState.value.transferState?.progress?.error == null
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
        _uiState.value = _uiState.value.copy(transferState = null)
    }

    // ========== 消息 ==========

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun showMessage(msg: String) {
        _uiState.value = _uiState.value.copy(message = msg)
    }

    // ========== ls 解析 ==========

    private suspend fun parseLsOutput(output: String, basePath: String): List<RemoteFile> {
        val files = mutableListOf<RemoteFile>()
        // 权限首字符：d=目录, l=符号链接, -=文件
        val regex = Regex(
            """^([dl\-][rwxsSt\-]{9})\s+(\d+)\s+(\S+)\s+(\S+)\s+(\d+)\s+(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}|\w{3}\s+\d+\s+[\d:]+)\s+(.+)$"""
        )

        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            if (trimmed.startsWith("total")) continue

            val match = regex.find(trimmed) ?: continue
            val perms = match.groupValues[1]
            val owner = match.groupValues[3]
            val group = match.groupValues[4]
            val size = match.groupValues[5].toLongOrNull() ?: 0
            val date = match.groupValues[6]
            val rawName = match.groupValues[7].trim()

            // 符号链接：解析 "name -> target"
            val isLink = perms.startsWith("l")
            val name: String
            val linkTarget: String?
            if (isLink) {
                val arrowIdx = rawName.indexOf(" -> ")
                if (arrowIdx >= 0) {
                    name = rawName.substring(0, arrowIdx).trim()
                    linkTarget = rawName.substring(arrowIdx + 4).trim()
                } else {
                    name = rawName.trim()
                    linkTarget = null
                }
            } else {
                name = rawName.trim()
                linkTarget = null
            }

            // 跳过 . 和 ..
            if (name == "." || name == "..") continue

            val isDir = perms.startsWith("d")
            val fullPath = "${basePath.trimEnd('/')}/$name"

            files.add(
                RemoteFile(
                    name = name,
                    path = fullPath,
                    isDirectory = isDir,
                    size = size,
                    permissions = perms.substring(1),
                    owner = owner,
                    group = group,
                    modifiedDate = date,
                    isHidden = name.startsWith("."),
                    isSymlink = isLink,
                    linkTarget = linkTarget
                )
            )
        }

        // 批量解析符号链接目标是否为目录
        resolveSymlinkTypes(files)
        return files
    }

    /**
     * 批量判断符号链接目标是文件还是目录。
     * 用一条 shell 命令批量 stat 所有链接，避免逐个执行的开销。
     * 对于无权限的链接，默认视为目录（可导航）。
     */
    private suspend fun resolveSymlinkTypes(files: MutableList<RemoteFile>) {
        val symlinks = files.filter { it.isSymlink && it.linkTarget != null }
        if (symlinks.isEmpty()) return

        try {
            // 用 stat -c '%F' 批量查询链接目标类型
            val paths = symlinks.joinToString(" ") { "\"${it.path}\"" }
            val output = execShell("stat -c '%F' $paths 2>/dev/null").output
            val types = output.lines().map { it.trim().lowercase() }

            symlinks.forEachIndexed { index, symlink ->
                if (index < types.size) {
                    val type = types[index]
                    val targetIsDir = type.contains("directory")
                    // 替换为已解析类型的副本
                    val i = files.indexOf(symlink)
                    if (i >= 0) {
                        files[i] = symlink.copy(isDirectory = targetIsDir)
                    }
                }
            }
        } catch (_: Exception) {
            // stat 失败时，根据常见目录路径做启发式判断
            val knownDirPrefixes = listOf(
                "/system/", "/product/", "/vendor/", "/data/", "/storage/",
                "/mnt/", "/proc/", "/sys/", "/dev/"
            )
            val knownFileExts = listOf(".txt", ".conf", ".rc", ".xml", ".json", ".prop", ".cfg")
            for (symlink in symlinks) {
                val target = symlink.linkTarget ?: continue
                val isLikelyDir = knownDirPrefixes.any { target.startsWith(it) && !knownFileExts.any { ext -> target.endsWith(ext) } }
                val i = files.indexOf(symlink)
                if (i >= 0) {
                    files[i] = symlink.copy(isDirectory = isLikelyDir)
                }
            }
        }
    }
}
