package com.adbhelper.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.adbhelper.app.ui.viewmodels.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileManagerPanel(
    isActive: Boolean = true,
    onNavigateBack: () -> Unit = {},
    viewModel: FileManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showExitConfirm by remember { mutableStateOf(false) }

    // 仅在当前 Tab 可见时拦截返回键
    BackHandler(enabled = isActive) {
        if (uiState.currentPath != "/") {
            viewModel.navigateUp()
        } else {
            showExitConfirm = true
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            // 从 URI 获取原始文件名
            val originalName = getFileNameFromUri(context, it) ?: "upload_file"
            val tempFile = java.io.File(context.cacheDir, "upload_temp")
            try {
                context.contentResolver.openInputStream(it)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                viewModel.uploadFile(tempFile.absolutePath, remoteName = originalName)
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(Unit) { viewModel.loadFiles() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 路径栏
            FileManagerPathBar(
                currentPath = uiState.currentPath,
                showHidden = uiState.showHidden,
                sortBy = uiState.sortBy,
                sortAscending = uiState.sortAscending,
                onNavigateUp = { viewModel.navigateUp() },
                onNavigateTo = { viewModel.navigateTo(it) },
                onNewFolder = { viewModel.showNewFolderDialog() },
                onUpload = { filePickerLauncher.launch("*/*") },
                onToggleHidden = { viewModel.toggleHidden() },
                onSortBy = { viewModel.setSortBy(it) }
            )

            // 剪贴板提示
            if (uiState.clipboardFile != null) {
                ClipboardBar(
                    fileName = uiState.clipboardFile!!.name,
                    isCut = uiState.clipboardMode == ClipboardMode.CUT,
                    onPaste = { viewModel.paste() },
                    onCancel = { viewModel.selectFile(null) }
                )
            }

            // 文件列表
            FileManagerContent(
                isLoading = uiState.isLoading,
                error = uiState.error,
                files = viewModel.getDisplayFiles(),
                selectedPath = uiState.selectedFile?.path,
                hasSelection = uiState.selectedFile != null,
                onNavigate = { viewModel.navigateTo(it) },
                onShowDetail = { viewModel.showFileDetail(it) },
                onSelect = { viewModel.selectFile(it) },
                onDeselect = { viewModel.selectFile(null) },
                onRetry = { viewModel.loadFiles() }
            )

            // 传输进度弹窗（传输中 + 完成后都显示）
            uiState.transferState?.let { state ->
                TransferProgressDialog(
                    state = state,
                    onCancel = { viewModel.cancelTransfer() },
                    onDismiss = { viewModel.dismissTransferResult() }
                )
            }
        }

        // 底部选中操作栏
        if (uiState.selectedFile != null && !uiState.showFileDetail) {
            FileActionBottomBar(
                onCopy = { viewModel.copyToClipboard() },
                onCut = { viewModel.cutToClipboard() },
                onRename = { viewModel.showRenameDialog() },
                onDelete = { viewModel.confirmDelete() },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    // ========== 弹窗 ==========

    if (uiState.showFileDetail && uiState.selectedFile != null) {
        FileDetailDialog(
            file = uiState.selectedFile!!,
            onDismiss = { viewModel.dismissFileDetail() },
            onViewText = { viewModel.viewTextFile(uiState.selectedFile!!) },
            onDownload = { viewModel.downloadFile(uiState.selectedFile!!) },
            onCopyPath = { clipboardManager.setText(AnnotatedString(uiState.selectedFile!!.path)) }
        )
    }

    if (uiState.showFileViewer) {
        TextViewerDialog(
            fileName = uiState.selectedFile?.name ?: "",
            content = uiState.fileContent,
            isLoading = uiState.isLoadingContent,
            onDismiss = { viewModel.dismissFileViewer() }
        )
    }

    if (uiState.showRenameDialog && uiState.selectedFile != null) {
        RenameDialog(
            currentName = uiState.selectedFile!!.name,
            onConfirm = { viewModel.renameFile(it) },
            onDismiss = { viewModel.dismissRenameDialog() }
        )
    }

    if (uiState.showNewFolderDialog) {
        NewFolderDialog(
            onConfirm = { viewModel.createFolder(it) },
            onDismiss = { viewModel.dismissNewFolderDialog() }
        )
    }

    if (uiState.showDeleteConfirm && uiState.selectedFile != null) {
        val f = uiState.selectedFile!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirm() },
            title = { Text("确认删除") },
            text = { Text("确定要删除 \"${f.name}\" 吗？${if (f.isDirectory) "文件夹将递归删除。" else ""}") },
            confirmButton = { TextButton(onClick = { viewModel.deleteFile() }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { viewModel.dismissDeleteConfirm() }) { Text("取消") } }
        )
    }

    // 根目录按返回键 → 确认退出
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("退出文件管理") },
            text = { Text("确定要退出文件管理器吗？") },
            confirmButton = { TextButton(onClick = { showExitConfirm = false; onNavigateBack() }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showExitConfirm = false }) { Text("取消") } }
        )
    }
}

// ========== 子组件 ==========

@Composable
private fun FileManagerPathBar(
    currentPath: String,
    showHidden: Boolean,
    sortBy: SortBy,
    sortAscending: Boolean,
    onNavigateUp: () -> Unit,
    onNavigateTo: (String) -> Unit,
    onNewFolder: () -> Unit,
    onUpload: () -> Unit,
    onToggleHidden: () -> Unit,
    onSortBy: (SortBy) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showSortSubmenu by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onNavigateUp, enabled = currentPath != "/") {
            Icon(Icons.Default.ArrowUpward, contentDescription = "返回上级")
        }
        // 路径可点击，打开编辑弹窗
        Text(
            text = currentPath,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
                .clickable { showPathDialog = true }
        )
        IconButton(onClick = onNewFolder) {
            Icon(Icons.Default.CreateNewFolder, contentDescription = "新建文件夹")
        }
        IconButton(onClick = onUpload) {
            Icon(Icons.Default.FileUpload, contentDescription = "上传文件")
        }
        IconButton(onClick = { showMenu = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多")
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false; showSortSubmenu = false }) {
            DropdownMenuItem(
                text = { Text("排序") },
                onClick = { showSortSubmenu = !showSortSubmenu },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }
            )
            if (showSortSubmenu) {
                SortBy.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = {
                            Text(when (sort) {
                                SortBy.NAME -> "按名称"; SortBy.SIZE -> "按大小"
                                SortBy.DATE -> "按日期"; SortBy.TYPE -> "按类型"
                            })
                        },
                        onClick = { onSortBy(sort); showMenu = false },
                        leadingIcon = {
                            if (sortBy == sort) {
                                Icon(
                                    if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                    null, modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (showHidden) "隐藏隐藏文件" else "显示隐藏文件") },
                onClick = { onToggleHidden(); showMenu = false },
                leadingIcon = { Icon(if (showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) }
            )
        }
    }

    // 路径编辑弹窗
    if (showPathDialog) {
        PathEditDialog(
            currentPath = currentPath,
            onConfirm = { onNavigateTo(it) },
            onDismiss = { showPathDialog = false }
        )
    }
}

@Composable
private fun ClipboardBar(
    fileName: String,
    isCut: Boolean,
    onPaste: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (isCut) Icons.Default.ContentCut else Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "${if (isCut) "剪切" else "复制"}: $fileName",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onPaste) { Text("粘贴") }
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileManagerContent(
    isLoading: Boolean,
    error: String?,
    files: List<RemoteFile>,
    selectedPath: String?,
    hasSelection: Boolean,
    onNavigate: (String) -> Unit,
    onShowDetail: (RemoteFile) -> Unit,
    onSelect: (RemoteFile) -> Unit,
    onDeselect: () -> Unit,
    onRetry: () -> Unit
) {
    when {
        isLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        error != null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRetry) { Text("重试") }
                }
            }
        }
        files.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("空文件夹", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        else -> {
            // 选中时底部留出空间给操作栏
            val bottomPadding = if (hasSelection) 56.dp else 0.dp
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = files, key = { it.path }) { file ->
                    FileListItem(
                        file = file,
                        isSelected = selectedPath == file.path,
                        onClick = {
                            when {
                                // 已选中 → 取消选中
                                selectedPath == file.path -> onDeselect()
                                // 目录或符号链接 → 进入
                                file.isDirectory || file.isSymlink -> onNavigate(file.path)
                                // 文件 → 显示详情
                                else -> onShowDetail(file)
                            }
                        },
                        onLongClick = { onSelect(file) }
                    )
                }
                // 底部占位，防止被操作栏遮挡
                item { Spacer(Modifier.height(bottomPadding)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItem(
    file: RemoteFile,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    file.isDirectory -> Icons.Default.Folder
                    file.isSymlink -> Icons.Default.Link
                    else -> fileIcon(file.name)
                },
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = when {
                    file.isDirectory -> MaterialTheme.colorScheme.primary
                    file.isSymlink -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // 符号链接显示目标路径
                if (file.isSymlink && file.linkTarget != null) {
                    Text(
                        "→ ${file.linkTarget}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row {
                    Text(file.modifiedDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!file.isDirectory) {
                        Spacer(Modifier.width(8.dp))
                        Text(formatFileSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileActionBottomBar(
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 8.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("复制")
            }
            TextButton(onClick = onCut) {
                Icon(Icons.Default.ContentCut, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("剪切")
            }
            TextButton(onClick = onRename) {
                Icon(Icons.Default.DriveFileRenameOutline, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp)); Text("重命名")
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp)); Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
