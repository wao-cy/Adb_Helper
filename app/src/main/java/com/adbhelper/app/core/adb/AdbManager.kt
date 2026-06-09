package com.adbhelper.app.core.adb

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.joinAll
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AdbManager"

data class AdbDevice(
    val serial: String,
    val state: DeviceState,
    val model: String? = null,
    val product: String? = null,
    val device: String? = null,
    val hasRoot: Boolean = false
)

enum class DeviceState {
    DEVICE, OFFLINE, UNAUTHORIZED, UNKNOWN
}


@Singleton
class AdbManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private var adbProcess: Process? = null
    private var adbPath: String = ""
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var hasRoot: Boolean = false
        private set
    var isServerRunning: Boolean = false
        private set

    /**
     * 通过检测 ADB server 端口是否在监听来判断真实状态，不触发 auto-start
     */
    fun checkServerPort(): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", DEFAULT_ADB_PORT), 500)
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    init {
        setupAdb()
    }

    private fun setupAdb() {
        // 从 jniLibs 提取的 .so 文件在 nativeLibraryDir 中
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val adbFile = File(nativeLibDir, "libadb.so")

        Log.d(TAG, "nativeLibDir: $nativeLibDir")
        Log.d(TAG, "adbFile exists: ${adbFile.exists()}")

        if (adbFile.exists()) {
            adbFile.setExecutable(true, false)
            adbPath = adbFile.absolutePath
        } else {
            // 回退：从 assets 复制
            val filesAdb = File(context.filesDir, "adb")
            copyAssetToFile(filesAdb)
            filesAdb.setExecutable(true, false)
            adbPath = filesAdb.absolutePath
        }

        Log.d(TAG, "adbPath: $adbPath")
        Log.d(TAG, "adbFile canExecute: ${File(adbPath).canExecute()}")

        // 预创建 ADB 所需的目录
        val adbDir = File(context.filesDir, ".adb")
        if (!adbDir.exists()) {
            adbDir.mkdirs()
        }
        val androidDir = File(context.filesDir, ".android")
        if (!androidDir.exists()) {
            androidDir.mkdirs()
        }
        // ADB server 启动时需要写日志到 cache 目录
        if (!context.cacheDir.exists()) {
            context.cacheDir.mkdirs()
        }
    }

    private fun copyAssetToFile(targetFile: File) {
        try {
            context.assets.open("adb").use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun startServer(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // server 已在运行，直接复用
            if (checkServerPort()) {
                isServerRunning = true
                return@withContext Result.success("daemon already running")
            }
            // 确保 cache 目录存在（用户可能从设置清除了缓存）
            if (!context.cacheDir.exists()) {
                context.cacheDir.mkdirs()
            }
            // 清理旧的 ADB 持久化数据，避免启动时重连已离线的 TCP 设备导致卡顿
            cleanAdbPersistDir()
            val result = executeAdbCommand("start-server")
            isServerRunning = result.contains("daemon started successfully")
                    || result.isBlank()
                    || (!result.contains("error") && !result.contains("failed"))
            if (isServerRunning) Result.success(result)
            else Result.failure(Exception(result.trim()))
        } catch (e: Exception) {
            isServerRunning = false
            Result.failure(e)
        }
    }

    /**
     * 清理 ADB 持久化目录，让 server 启动时不尝试重连旧的 TCP 设备
     * adb_keys 会被删除，下次连接设备时需重新授权（仅首次）
     */
    private fun cleanAdbPersistDir() {
        try {
            val adbDir = File(context.filesDir, ".adb")
            if (adbDir.exists()) {
                adbDir.deleteRecursively()
            }
            adbDir.mkdirs()
        } catch (e: Exception) {
            Log.w(TAG, "cleanAdbPersistDir failed: ${e.message}")
        }
    }

    suspend fun killServer(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val result = executeAdbCommand("kill-server")
            isServerRunning = false
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 杀掉 ADB server，最多等 500ms，超时直接返回 */
    fun killServerWithTimeout() {
        try {
            val pb = createProcessBuilder("kill-server")
            pb.redirectErrorStream(true)
            val process = pb.start()
            // API 24 兼容：用线程 + Thread.sleep 替代 waitFor(timeout, unit)（需 API 26）
            val finished = java.util.concurrent.CountDownLatch(1)
            Thread { try { process.waitFor(); } catch (_: Exception) {} finally { finished.countDown() } }.start()
            finished.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            process.destroy()
        } catch (_: Exception) {}
    }

    suspend fun getDevices(): Result<List<AdbDevice>> = withContext(Dispatchers.IO) {
        try {
            val output = executeAdbCommand("devices", "-l")
            var devices = parseDevices(output)
            devices = devices.map { device ->
                if (device.state == DeviceState.DEVICE) {
                    device.copy(hasRoot = checkRoot(device.serial))
                } else {
                    device
                }
            }
            hasRoot = devices.any { it.hasRoot }
            Result.success(devices)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 检测指定设备是否有 root 权限
     */
    private suspend fun checkRoot(serial: String): Boolean {
        return try {
            val result = executeAdbCommand("shell", "su", "-c", "id", serial = serial)
            val rooted = result.contains("uid=0")
            Log.d(TAG, "checkRoot($serial): $rooted")
            if (rooted) {
                try { executeAdbCommand("remount", serial = serial) } catch (_: Exception) {}
            }
            rooted
        } catch (_: Exception) {
            false
        }
    }

    private fun parseDevices(output: String): List<AdbDevice> {
        val devices = mutableListOf<AdbDevice>()
        val lines = output.lines().drop(1) // Skip "List of devices attached"

        for (line in lines) {
            if (line.isBlank()) continue
            val trimmedLine = line.trim()
            // 跳过非设备行（如 daemon 信息、错误信息等）
            if (trimmedLine.startsWith("*") || trimmedLine.startsWith("adb")) continue

            val parts = trimmedLine.split("\\s+".toRegex())
            if (parts.size >= 2) {
                val serial = parts[0]
                val stateStr = parts[1]
                val state = when (stateStr) {
                    "device" -> DeviceState.DEVICE
                    "offline" -> DeviceState.OFFLINE
                    "unauthorized" -> DeviceState.UNAUTHORIZED
                    else -> DeviceState.UNKNOWN
                }
                val model = if (parts.size > 2 && parts[2].startsWith("model:")) {
                    parts[2].removePrefix("model:")
                } else null

                devices.add(AdbDevice(serial, state, model))
            }
        }
        return devices
    }

    suspend fun connect(ip: String, port: Int = 5555): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "connect to $ip:$port")
            val result = executeAdbCommand("connect", "$ip:$port")
            Log.d(TAG, "connect result: $result")
            val errorKeywords = listOf("cannot connect", "failed", "error", "no host", "refused", "unreachable", "timed out")
            val hasError = errorKeywords.any { result.contains(it, ignoreCase = true) }
            if (hasError) {
                Result.failure(Exception(result.trim()))
            } else {
                val serial = "$ip:$port"
                hasRoot = checkRoot(serial)
                Result.success(result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "connect error", e)
            Result.failure(e)
        }
    }

    suspend fun pair(ip: String, port: Int, pairingCode: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val processBuilder = createProcessBuilder("pair", "$ip:$port")
            processBuilder.redirectErrorStream(false)

            val result = ProcessPromptHelper.run(
                processBuilder = processBuilder,
                promptText = "Enter pairing code:",
                input = pairingCode,
                promptTimeoutMs = PAIR_TIMEOUT_MS
            )

            if (!result.promptFound) {
                Result.failure(Exception("Connection timed out"))
            } else if (result.output.contains("Successfully paired") || result.exitCode == 0) {
                Result.success(result.output)
            } else {
                Result.failure(Exception(result.output.trim()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "pair error", e)
            Result.failure(e)
        }
    }

    suspend fun disconnect(serial: String? = null): Result<String> = withContext(Dispatchers.IO) {
        try {
            val args = mutableListOf("disconnect")
            if (serial != null) args.add(serial)
            val result = executeAdbCommand(*args.toTypedArray())
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Suppress("unused")
    suspend fun setTcpIp(port: Int = 5555): Result<String> = withContext(Dispatchers.IO) {
        try {
            //noinspection SpellCheckingInspection
            val result = executeAdbCommand("tcpip", port.toString())
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 扫描局域网内可连接的 ADB 设备
     * 通过检测 5555 端口是否开放来判断
     */
    suspend fun scanLanDevices(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            // 获取本机 IP
            val localIp = getLocalIpAddress() ?: return@withContext Result.failure(Exception("无法获取本机 IP"))
            val subnet = localIp.substringBeforeLast(".")

            val found = mutableListOf<String>()
            val semaphore = Semaphore(SCAN_CONCURRENCY_LIMIT)
            val jobs = (1..254).map { i ->
                async {
                    semaphore.withPermit {
                        val ip = "$subnet.$i"
                        try {
                            val socket = java.net.Socket()
                            socket.connect(java.net.InetSocketAddress(ip, 5555), 200)
                            socket.close()
                            synchronized(found) { found.add(ip) }
                        } catch (_: Exception) {}
                    }
                }
            }
            jobs.joinAll()

            Log.d(TAG, "scanLanDevices: found ${found.size} devices")
            Result.success(found.sorted())
        } catch (e: Exception) {
            Log.e(TAG, "scanLanDevices error", e)
            Result.failure(e)
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                for (address in iface.inetAddresses) {
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun createProcessBuilder(vararg args: String): ProcessBuilder {
        val fullCommand = arrayOf(adbPath, "-P", DEFAULT_ADB_PORT.toString()) + args
        Log.d(TAG, "createProcessBuilder: ${fullCommand.joinToString(" ")}")

        val processBuilder = ProcessBuilder(*fullCommand)

        val adbDir = File(context.filesDir, ".adb")
        if (!adbDir.exists()) {
            adbDir.mkdirs()
        }
        val env = processBuilder.environment()
        env["HOME"] = context.filesDir.absolutePath
        env["ANDROID_ADB_KEYS_PATH"] = File(adbDir, "adb_keys").absolutePath
        env["USERPROFILE"] = context.filesDir.absolutePath
        // ADB daemon logs to TMPDIR; /tmp may not exist on older Android
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["TMP"] = context.cacheDir.absolutePath
        env["TEMP"] = context.cacheDir.absolutePath

        return processBuilder
    }

    fun executeCommand(vararg command: String): Process {
        val processBuilder = createProcessBuilder(*command)
        processBuilder.redirectErrorStream(true)
        return processBuilder.start()
    }

    companion object {
        const val DEFAULT_ADB_PORT = 5037
        private const val PAIR_TIMEOUT_MS = 8_000L
        private const val SCAN_CONCURRENCY_LIMIT = 50
    }

    suspend fun executeAdbCommand(vararg args: String, serial: String? = null): String = withContext(Dispatchers.IO) {
        val finalArgs = if (serial != null) arrayOf("-s", serial) + args else args
        val process = executeCommand(*finalArgs)
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        Log.d(TAG, "executeAdbCommand output: $output")
        output
    }

    @Suppress("unused")
    fun destroy() {
        scope.cancel()
        adbProcess?.destroy()
    }
}
