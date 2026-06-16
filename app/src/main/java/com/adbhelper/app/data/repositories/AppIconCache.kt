package com.adbhelper.app.data.repositories

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LRU 图标缓存，由 AppManagerViewModel 在图标解析完成后写入。
 *
 * 后续加载只解析缓存未命中的应用图标，大幅减少跨解析器的重复工作。
 * 进程级单例，App 重启后清空（内存级缓存）。
 */
@Singleton
class AppIconCache @Inject constructor() {

    companion object {
        /** 最多缓存 500 个图标，每个 72x72 ARGB_8888 约 20KB，总计约 10MB */
        private const val MAX_ICONS = 500
    }

    private val lruCache = object : LruCache<String, ImageBitmap>(MAX_ICONS) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = 1
    }

    fun get(pkg: String): ImageBitmap? = lruCache.get(pkg)

    fun put(pkg: String, icon: ImageBitmap) {
        lruCache.put(pkg, icon)
    }

    fun putAll(icons: Map<String, ImageBitmap>) {
        icons.forEach { (pkg, icon) -> lruCache.put(pkg, icon) }
    }

    fun evictAll() {
        lruCache.evictAll()
    }

    /** 全量快照，供 ViewModel 组装当前显示的 icon map */
    fun snapshot(): Map<String, ImageBitmap> = lruCache.snapshot()
}
