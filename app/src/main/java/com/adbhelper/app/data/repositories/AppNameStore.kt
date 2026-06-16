package com.adbhelper.app.data.repositories

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用名缓存，由 AppManagerViewModel 在解析完成后写入，
 * ProcessManagerViewModel 读取用于显示友好应用名和过滤 App 进程。
 */
@Singleton
class AppNameStore @Inject constructor() {

    @Volatile
    private var names: Map<String, String> = emptyMap()

    fun update(names: Map<String, String>) {
        this.names = names
    }

    /** 获取应用友好名，没有则返回 null */
    fun getAppName(packageName: String): String? = names[packageName]

    /** 该包名是否在已安装应用列表中 */
    fun isKnownPackage(packageName: String): Boolean = packageName in names

    /** 所有已知包名集合 */
    fun knownPackages(): Set<String> = names.keys
}
