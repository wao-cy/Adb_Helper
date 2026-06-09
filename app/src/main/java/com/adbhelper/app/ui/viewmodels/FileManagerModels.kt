package com.adbhelper.app.ui.viewmodels

import android.os.Environment

data class RemoteFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val permissions: String = "",
    val owner: String = "",
    val group: String = "",
    val modifiedDate: String = "",
    val isHidden: Boolean = false,
    val isSymlink: Boolean = false,
    val linkTarget: String? = null
)

enum class SortBy { NAME, SIZE, DATE, TYPE }
enum class ClipboardMode { COPY, CUT }

data class FileManagerState(
    val isLoading: Boolean = true,
    val currentPath: String = Environment.getExternalStorageDirectory().path,
    val files: List<RemoteFile> = emptyList(),
    val sortBy: SortBy = SortBy.NAME,
    val sortAscending: Boolean = true,
    val showHidden: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    // 选中与操作
    val selectedFile: RemoteFile? = null,
    val showFileDetail: Boolean = false,
    val showFileViewer: Boolean = false,
    val fileContent: String? = null,
    val isLoadingContent: Boolean = false,
    val showRenameDialog: Boolean = false,
    val showNewFolderDialog: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    // 剪贴板
    val clipboardFile: RemoteFile? = null,
    val clipboardMode: ClipboardMode? = null,
    // 传输
    val transferState: TransferState? = null
)
