package com.adbhelper.app.ui.viewmodels

import androidx.compose.ui.graphics.ImageBitmap
import com.adbhelper.app.core.shell.TransferProgress

data class AppInfo(
    val packageName: String,
    val apkPath: String = "",
    val appName: String = "",
    val isSystemApp: Boolean = false,
    val isDisabled: Boolean = false
)

enum class AppFilter { ALL, THIRD_PARTY, SYSTEM }

data class LocalAppInfo(
    val packageName: String,
    val appName: String,
    val apkPath: String
)

data class TransferState(
    val direction: TransferDirection,
    val fileName: String,
    val fileSize: Long = 0L,
    val progress: TransferProgress = TransferProgress(),
    val resultMessage: String? = null,
    val isError: Boolean = false
)

enum class TransferDirection { PUSH, PULL }

data class AppDetail(
    val packageName: String = "",
    val versionName: String = "",
    val versionCode: String = "",
    val minSdkVersion: String = "",
    val targetSdkVersion: String = "",
    val firstInstallTime: String = "",
    val lastUpdateTime: String = "",
    val apkSize: String = "",
    val launchActivity: String = ""
)

data class AppManagerUiState(
    val isLoading: Boolean = true,
    val isLoadingNames: Boolean = false,
    val apps: List<AppInfo> = emptyList(),
    val filteredApps: List<AppInfo> = emptyList(),
    val filter: AppFilter = AppFilter.THIRD_PARTY,
    val searchQuery: String = "",
    val operationMessage: String? = null,
    val operationSuccess: Boolean = true,
    val error: String? = null,
    val localApps: List<LocalAppInfo> = emptyList(),
    val showPushDialog: Boolean = false,
    val showLocalAppPicker: Boolean = false,
    val transferState: TransferState? = null,
    val showPermissionWarning: Boolean = false,
    val appDetail: AppDetail? = null,
    val isLoadingDetail: Boolean = false,
    val appIcons: Map<String, ImageBitmap> = emptyMap(),
    val isLoadingIcons: Boolean = false
)
