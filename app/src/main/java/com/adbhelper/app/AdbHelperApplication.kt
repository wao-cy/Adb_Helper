package com.adbhelper.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AdbHelperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 初始化ADB
        initializeAdb()
    }

    private fun initializeAdb() {
        // 复制内置的ADB可执行文件到应用私有目录
        // 在实际实现中，这里会复制assets中的adb二进制文件
    }
}
