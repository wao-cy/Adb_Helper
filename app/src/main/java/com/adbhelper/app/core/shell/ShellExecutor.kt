package com.adbhelper.app.core.shell

import android.os.Environment
import com.adbhelper.app.core.adb.AdbManager
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

data class ShellResult(
    val command: String,
    val output: String,
    val exitCode: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Singleton
class ShellExecutor @Inject constructor(
    private val adbManager: AdbManager
) {
    suspend fun execute(
        command: String,
        serial: String? = null
    ): ShellResult = withContext(Dispatchers.IO) {
        val args = mutableListOf<String>()
        if (serial != null) args.addAll(listOf("-s", serial))
        val cleanCommand = if (command.startsWith("shell ")) command.removePrefix("shell ") else command
        args.addAll(listOf("shell", cleanCommand))

        val process = adbManager.executeCommand(*args.toTypedArray())
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        ShellResult(command = command, output = output, exitCode = exitCode)
    }

    private suspend fun tryCommands(commands: List<String>, serial: String? = null): ShellResult {
        var lastResult: ShellResult? = null
        for (cmd in commands) {
            try {
                val result = execute(cmd, serial)
                if (result.exitCode == 0 && !result.output.contains("Exception", ignoreCase = true)) return result
                lastResult = result
            } catch (e: Exception) {
                android.util.Log.d("ShellExecutor", "$cmd → exception: ${e.message}")
            }
        }
        return lastResult ?: throw IllegalStateException("All commands failed")
    }

    // ========== 常用命令封装 ==========

    suspend fun getAllProps(serial: String? = null): Map<String, String> {
        val output = execute("getprop", serial).output
        val props = mutableMapOf<String, String>()
        val regex = Regex("\\[([^]]+)]\\s*:\\s*\\[([^]]*)]")
        output.lines().forEach { line ->
            regex.find(line)?.let { props[it.groupValues[1]] = it.groupValues[2] }
        }
        return props
    }

    suspend fun screenshot(path: String? = null, serial: String? = null): ShellResult {
        val remotePath = path ?: "${Environment.getExternalStorageDirectory().path}/screenshot.png"
        return execute("screencap -p $remotePath", serial)
    }

    suspend fun forceStop(packageName: String, serial: String? = null): ShellResult =
        execute("am force-stop $packageName", serial)

    suspend fun clearPackage(packageName: String, serial: String? = null): ShellResult =
        tryCommands(listOf("pm clear $packageName", "pm clear --user 0 $packageName", "su -c pm clear $packageName"), serial)

    suspend fun uninstallPackage(packageName: String, serial: String? = null): ShellResult =
        tryCommands(listOf("pm uninstall $packageName", "pm uninstall --user 0 $packageName", "su -c pm uninstall $packageName"), serial)

    suspend fun disableApp(packageName: String, serial: String? = null): ShellResult =
        tryCommands(listOf("pm disable-user --user 0 $packageName", "pm disable $packageName", "su -c pm disable-user --user 0 $packageName"), serial)

    suspend fun enableApp(packageName: String, serial: String? = null): ShellResult =
        tryCommands(listOf("pm enable $packageName", "su -c pm enable $packageName"), serial)

    suspend fun installPackage(apkPath: String, serial: String? = null): ShellResult =
        execute("pm install $apkPath", serial)

    suspend fun reboot(mode: RebootMode = RebootMode.NORMAL, serial: String? = null): ShellResult {
        val args = when (mode) {
            RebootMode.NORMAL -> arrayOf("reboot")
            RebootMode.BOOTLOADER -> arrayOf("reboot", "bootloader")
            RebootMode.RECOVERY -> arrayOf("reboot", "recovery")
            RebootMode.FASTBOOT -> arrayOf("reboot", "fastboot")
        }
        val output = adbManager.executeAdbCommand(*args, serial = serial)
        return ShellResult(command = args.joinToString(" "), output = output, exitCode = 0)
    }

    suspend fun getBatteryInfo(serial: String? = null): String = execute("dumpsys battery", serial).output
    suspend fun getDiskUsage(serial: String? = null): String = execute("df -h", serial).output
    suspend fun getScreenSize(serial: String? = null): String = execute("wm size", serial).output
    suspend fun getMemInfo(serial: String? = null): String = execute("cat /proc/meminfo", serial).output

    enum class RebootMode { NORMAL, BOOTLOADER, RECOVERY, FASTBOOT }
}
