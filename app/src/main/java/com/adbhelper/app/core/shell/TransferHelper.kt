package com.adbhelper.app.core.shell

import com.adbhelper.app.core.adb.AdbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class TransferProgress(
    val percent: Int = 0,        // 0-100
    val transferred: Long = 0,   // 已传输字节
    val total: Long = 0,         // 总字节
    val speed: String = "",      // 传输速度文本，如 "1.2 MB/s"
    val isComplete: Boolean = false,
    val error: String? = null
)

@Singleton
class TransferHelper @Inject constructor(
    private val adbManager: AdbManager
) {
    suspend fun pull(remotePath: String, localPath: String, serial: String? = null): ShellResult = withContext(Dispatchers.IO) {
        ensureLocalDir(localPath)
        val args = buildArgs(serial, "pull", remotePath, localPath)
        val process = adbManager.executeCommand(*args)
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        ShellResult(command = "pull $remotePath $localPath", output = output, exitCode = exitCode)
    }

    suspend fun pushStreaming(
        localPath: String,
        remotePath: String,
        serial: String? = null,
        onProgress: (TransferProgress) -> Unit
    ): ShellResult = streamTransfer(
        args = buildArgs(serial, "push", localPath, remotePath),
        totalSize = File(localPath).length(),
        onProgress = onProgress,
        label = "push $localPath $remotePath"
    )

    suspend fun pullStreaming(
        remotePath: String,
        localPath: String,
        serial: String? = null,
        onProgress: (TransferProgress) -> Unit
    ): ShellResult {
        ensureLocalDir(localPath)
        return streamTransfer(
            args = buildArgs(serial, "pull", remotePath, localPath),
            totalSize = 0L,
            onProgress = onProgress,
            label = "pull $remotePath $localPath"
        )
    }

    private suspend fun streamTransfer(
        args: Array<String>,
        totalSize: Long,
        onProgress: (TransferProgress) -> Unit,
        label: String
    ): ShellResult = withContext(Dispatchers.IO) {
        val process = adbManager.executeCommand(*args)
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        val resultText = output.trim().ifBlank { if (exitCode == 0) "transfer completed" else "transfer failed (exit $exitCode)" }

        onProgress(TransferProgress(
            percent = 100, transferred = totalSize, total = totalSize,
            isComplete = true, error = if (exitCode != 0) resultText else null
        ))

        ShellResult(command = label, output = resultText, exitCode = exitCode)
    }

    private fun buildArgs(serial: String?, vararg parts: String): Array<String> {
        val args = mutableListOf<String>()
        if (serial != null) args.addAll(listOf("-s", serial))
        args.addAll(parts)
        return args.toTypedArray()
    }

    private fun ensureLocalDir(localPath: String) {
        val localDir = File(localPath).parentFile ?: return
        if (localDir.exists()) return
        localDir.mkdirs()
        if (!localDir.exists()) {
            try {
                Runtime.getRuntime().exec(arrayOf("mkdir", "-p", localDir.absolutePath)).waitFor()
            } catch (_: Exception) {}
        }
    }
}
