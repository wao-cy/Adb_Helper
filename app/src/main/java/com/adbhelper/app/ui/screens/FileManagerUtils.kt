package com.adbhelper.app.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

internal fun fileIcon(name: String): ImageVector = when {
    name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
            name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp") -> Icons.Default.Image
    name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") ||
            name.endsWith(".mov") -> Icons.Default.VideoFile
    name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg") ||
            name.endsWith(".flac") -> Icons.Default.AudioFile
    name.endsWith(".zip") || name.endsWith(".tar") || name.endsWith(".gz") ||
            name.endsWith(".rar") || name.endsWith(".7z") -> Icons.Default.FolderZip
    name.endsWith(".apk") -> Icons.Default.Android
    name.endsWith(".pdf") -> Icons.Default.PictureAsPdf
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

internal fun fileTypeLabel(name: String): String = when {
    name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
            name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp") -> "图片"
    name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") ||
            name.endsWith(".mov") -> "视频"
    name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg") ||
            name.endsWith(".flac") -> "音频"
    name.endsWith(".zip") || name.endsWith(".tar") || name.endsWith(".gz") ||
            name.endsWith(".rar") || name.endsWith(".7z") -> "压缩包"
    name.endsWith(".apk") -> "APK 安装包"
    name.endsWith(".pdf") -> "PDF 文档"
    name.endsWith(".txt") || name.endsWith(".log") || name.endsWith(".md") -> "文本文件"
    name.endsWith(".xml") || name.endsWith(".json") || name.endsWith(".yaml") ||
            name.endsWith(".yml") -> "数据文件"
    name.endsWith(".db") || name.endsWith(".sqlite") -> "数据库"
    name.endsWith(".so") -> "共享库"
    else -> "文件"
}

internal fun isTextFile(name: String): Boolean = name.let { n ->
    n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".xml") ||
            n.endsWith(".json") || n.endsWith(".conf") || n.endsWith(".cfg") ||
            n.endsWith(".prop") || n.endsWith(".sh") || n.endsWith(".py") ||
            n.endsWith(".md") || n.endsWith(".html") || n.endsWith(".csv") ||
            n.endsWith(".ini") || n.endsWith(".yaml") || n.endsWith(".yml") ||
            n.endsWith(".properties") || n.endsWith(".kt") || n.endsWith(".java") ||
            n.endsWith(".gradle") || n.endsWith(".kts")
}

internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

/** 从 Content URI 获取原始文件名 */
internal fun getFileNameFromUri(context: Context, uri: Uri): String? {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
    }
    return uri.lastPathSegment
}
