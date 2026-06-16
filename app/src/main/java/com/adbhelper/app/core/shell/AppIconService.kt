package com.adbhelper.app.core.shell

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.adbhelper.app.data.repositories.AppIconCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppIconService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shellExecutor: ShellExecutor,
    private val transferHelper: TransferHelper,
    private val appIconCache: AppIconCache
) {
    companion object {
        private const val TAG = "AppIconService"
    }

    /**
     * 从设备批量拉取应用图标，写入磁盘缓存 + LRU 缓存。
     * @return 包名 → ImageBitmap 映射（包含磁盘缓存命中）
     */
    suspend fun loadAppIcons(
        packages: List<String>,
        serial: String
    ): Map<String, ImageBitmap> = withContext(Dispatchers.IO) {
        loadDiskCache()

        val missApps = packages.filter { appIconCache.get(it) == null }

        if (missApps.isNotEmpty()) {
            Log.d(TAG, "${missApps.size} cache miss, resolving batch...")

            val localIconDir = File(context.cacheDir, "icons")
            localIconDir.mkdirs()
            missApps.forEach { File(localIconDir, "$it.png").delete() }

            ensureJarOnDevice("AppIconResolver.jar", serial)
            shellExecutor.execute("rm -rf /data/local/tmp/icons", serial)
            shellExecutor.execute("mkdir -p /data/local/tmp/icons", serial)

            val allPkgs = missApps.joinToString(" ") { it }
            val result = shellExecutor.execute(
                "CLASSPATH=/data/local/tmp/AppIconResolver.jar " +
                "app_process /data/local/tmp " +
                "com.adbhelper.app.tools.AppIconResolver /data/local/tmp/icons $allPkgs", serial)
            Log.d(TAG, "batch exit=${result.exitCode} " +
                "ok=${result.output.lines().count { it.startsWith("OK ") } } " +
                "fail=${result.output.lines().count { it.startsWith("FAIL ") } }")

            transferHelper.pull("/data/local/tmp/icons/.", localIconDir.absolutePath, serial)

            var loadedCount = 0
            localIconDir.listFiles()?.forEach { file ->
                val pkg = file.nameWithoutExtension
                if (appIconCache.get(pkg) == null) {
                    try {
                        val bytes = file.readBytes()
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) { appIconCache.put(pkg, bmp.asImageBitmap()); loadedCount++ }
                    } catch (_: Exception) {}
                }
            }

            shellExecutor.execute("rm -rf /data/local/tmp/icons", serial)
            Log.d(TAG, "loaded $loadedCount/${missApps.size}")
        }

        packages.mapNotNull { pkg ->
            appIconCache.get(pkg)?.let { pkg to it }
        }.toMap()
    }

    private suspend fun ensureJarOnDevice(jarName: String, serial: String) {
        val dexFile = File(context.cacheDir, jarName)
        context.assets.open(jarName).use { input ->
            dexFile.outputStream().use { output -> input.copyTo(output) }
        }
        val b64 = Base64.encodeToString(dexFile.readBytes(), Base64.NO_WRAP)
        shellExecutor.execute("echo '$b64' | base64 -d > /data/local/tmp/$jarName", serial)
        shellExecutor.execute("chmod 644 /data/local/tmp/$jarName", serial)
    }

    private suspend fun loadDiskCache() {
        val localIconDir = File(context.cacheDir, "icons")
        if (!localIconDir.exists()) return
        localIconDir.listFiles()?.forEach { file ->
            val pkg = file.nameWithoutExtension
            if (appIconCache.get(pkg) == null) {
                try {
                    val bytes = file.readBytes()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) appIconCache.put(pkg, bmp.asImageBitmap())
                } catch (_: Exception) {}
            }
        }
    }
}
