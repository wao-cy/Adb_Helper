package com.adbhelper.app.core.adb

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ProcessPromptHelper"

data class PromptResult(
    val output: String,
    val promptFound: Boolean,
    val exitCode: Int
)

/**
 * 通用的进程交互工具：启动进程 → 并行读取 stdout/stderr → 检测提示符 → 写入输入 → 收集输出。
 */
object ProcessPromptHelper {

    /**
     * @param processBuilder     已配置好的 ProcessBuilder（redirectErrorStream 应为 false）
     * @param promptText         要检测的提示符文本（子串匹配）
     * @param input              检测到提示符后写入的文本
     * @param promptTimeoutMs    等待提示符的超时时间（毫秒）
     * @param responseTimeoutMs  写入输入后等待进程退出的超时时间（毫秒），默认 10 秒
     * @return PromptResult      包含收集到的输出、是否找到提示符、进程退出码
     */
    fun run(
        processBuilder: ProcessBuilder,
        promptText: String,
        input: String,
        promptTimeoutMs: Long,
        responseTimeoutMs: Long = 10_000L
    ): PromptResult {
        val process: Process
        try {
            process = processBuilder.start()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start process: ${e.message}")
            return PromptResult(output = "", promptFound = false, exitCode = -1)
        }

        val outputBuilder = StringBuilder()
        val promptLatch = CountDownLatch(1)
        val processExited = AtomicBoolean(false)
        val promptLength = promptText.length

        fun startStreamReader(stream: InputStream, label: String): Thread {
            return Thread {
                try {
                    val sb = StringBuilder()
                    var ch: Int
                    while (stream.read().also { ch = it } != -1) {
                        val c = ch.toChar()
                        sb.append(c)
                        // 仅在缓冲区长度 >= 提示符长度时才检测，且只搜尾部
                        if (!promptLatch.await(0, TimeUnit.MILLISECONDS)
                            && sb.length >= promptLength
                            && sb.indexOf(promptText, sb.length - promptLength * 2.coerceAtMost(sb.length)) >= 0
                        ) {
                            promptLatch.countDown()
                        }
                    }
                    synchronized(outputBuilder) {
                        if (sb.isNotEmpty()) {
                            Log.d(TAG, "$label: ${sb.toString().trim()}")
                            outputBuilder.appendLine("[$label] ${sb.toString().trim()}")
                        }
                    }
                } catch (_: IOException) {
                    // 进程被销毁时流会关闭
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        val stdoutThread = startStreamReader(process.inputStream, "stdout")
        val stderrThread = startStreamReader(process.errorStream, "stderr")

        val promptFound = promptLatch.await(promptTimeoutMs, TimeUnit.MILLISECONDS)

        if (promptFound) {
            Log.d(TAG, "Prompt found, writing input")
            try {
                val writer = process.outputStream.bufferedWriter()
                writer.write(input)
                writer.newLine()
                writer.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to write input: ${e.message}")
                process.destroy()
            }
        } else {
            Log.w(TAG, "Prompt not found within ${promptTimeoutMs}ms")
            process.destroy()
        }

        // 等待进程退出，带超时（API 24 兼容）
        val exitLatch = CountDownLatch(1)
        Thread({
            try { process.waitFor() } catch (_: Exception) {}
            processExited.set(true)
            exitLatch.countDown()
        }, "prompt-helper-wait").apply { isDaemon = true }.start()

        val exited = exitLatch.await(responseTimeoutMs, TimeUnit.MILLISECONDS)
        if (!exited) {
            Log.w(TAG, "Process did not exit within ${responseTimeoutMs}ms, destroying")
            process.destroy()
        }

        stdoutThread.join(2000)
        stderrThread.join(2000)

        val exitCode = if (processExited.get()) {
            try { process.exitValue() } catch (_: Exception) { -1 }
        } else {
            -1
        }

        val output = outputBuilder.toString()
        Log.d(TAG, "output: $output")
        Log.d(TAG, "exitValue: $exitCode")

        return PromptResult(
            output = output,
            promptFound = promptFound,
            exitCode = exitCode
        )
    }
}
