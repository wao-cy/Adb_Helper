package com.adbhelper.app.core.shell

import android.content.Context
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppIconServerClient @Inject constructor(
    private val shellExecutor: ShellExecutor,
    private val adbManager: com.adbhelper.app.core.adb.AdbManager,
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AppIconServerClient"
        private const val LOCAL_PORT = 27300
        private const val READ_TIMEOUT_MS = 15_000
        private const val CMD_ICON_REQUEST = 0x01
    }

    private var serverStarted = false

    suspend fun startServer(serial: String) {
        if (serverStarted && tcpReachable()) return

        ensureJarOnDevice("AppIconResolver.jar", serial)
        Log.d(TAG, "JAR pushed, starting daemon...")

        // Use unique socket name to avoid "Address already in use" from stale daemon
        val socketName = "adbhelper_${System.currentTimeMillis()}"
        val logFile = "/data/local/tmp/icon_daemon.log"

        // Remove old forward if any
        try {
            adbManager.executeAdbCommand("forward", "--remove", "tcp:$LOCAL_PORT", serial = serial)
        } catch (_: Exception) {}

        // Start daemon
        shellExecutor.execute(
            "CLASSPATH=/data/local/tmp/AppIconResolver.jar " +
            "app_process /data/local/tmp " +
            "com.adbhelper.app.tools.AppIconResolver --daemon $socketName " +
            ">/dev/null 2>$logFile &", serial)
        Log.d(TAG, "daemon started with socket $socketName")

        delay(800)

        // Set up forward
        try {
            adbManager.executeAdbCommand("forward", "--remove", "tcp:$LOCAL_PORT", serial = serial)
        } catch (_: Exception) {}
        adbManager.executeAdbCommand(
            "forward", "tcp:$LOCAL_PORT", "localabstract:$socketName", serial = serial)
        Log.d(TAG, "forward tcp:$LOCAL_PORT -> $socketName")

        // Wait for readiness
        var ready = false
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (tcpReachable() && checkDaemonReachable()) {
                ready = true
                break
            }
            delay(300)
        }

        if (!ready) {
            try {
                val log = shellExecutor.execute("cat $logFile 2>/dev/null || echo '(empty)'", serial)
                Log.e(TAG, "===== daemon log =====")
                for (line in log.output.lines()) Log.e(TAG, "  $line")
            } catch (_: Exception) {}
            serverStarted = false
            throw RuntimeException("AppIconResolver daemon did not start")
        }

        serverStarted = true
    }

    suspend fun requestIconBatch(
        packages: List<String>,
        serial: String
    ): Map<String, ByteArray?> = withContext(Dispatchers.IO) {
        if (packages.isEmpty()) return@withContext emptyMap()
        if (!serverStarted) startServer(serial)

        val socket = Socket()
        socket.soTimeout = READ_TIMEOUT_MS
        socket.setTcpNoDelay(true)
        try {
            socket.connect(InetSocketAddress("127.0.0.1", LOCAL_PORT), READ_TIMEOUT_MS)
            val dos = DataOutputStream(socket.getOutputStream())
            val dis = DataInputStream(socket.getInputStream())
            val results = LinkedHashMap<String, ByteArray?>()

            for (pkg in packages) {
                val pkgBytes = pkg.toByteArray(Charsets.UTF_8)
                dos.writeByte(CMD_ICON_REQUEST)
                dos.writeShort(pkgBytes.size)
                dos.write(pkgBytes)
                dos.flush()

                val status = dis.readByte()
                val iconLen = dis.readInt()
                if (status == 0.toByte() && iconLen > 0) {
                    val data = ByteArray(iconLen)
                    dis.readFully(data)
                    results[pkg] = data
                } else {
                    results[pkg] = null
                }
            }

            dos.writeByte(CMD_ICON_REQUEST)
            dos.writeShort(0)
            dos.flush()

            results
        } catch (e: Exception) {
            Log.w(TAG, "requestIconBatch failed", e)
            serverStarted = false
            throw e
        } finally {
            if (!socket.isClosed) socket.close()
        }
    }

    suspend fun requestIcon(pkg: String, serial: String): ByteArray? {
        val results = requestIconBatch(listOf(pkg), serial)
        return results[pkg]
    }

    private fun tcpReachable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", LOCAL_PORT), 1000); true }
    } catch (_: Exception) { false }

    private fun checkDaemonReachable(): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", LOCAL_PORT), 1000)
            s.soTimeout = 2000
            val dos = DataOutputStream(s.getOutputStream())
            val dis = DataInputStream(s.getInputStream())
            dos.writeByte(CMD_ICON_REQUEST); dos.writeShort(1); dos.write(0); dos.flush()
            dis.readByte(); dis.readInt()
            dos.writeByte(CMD_ICON_REQUEST); dos.writeShort(0); dos.flush()
            true
        }
    } catch (_: Exception) { false }

    private suspend fun ensureJarOnDevice(jarName: String, serial: String) {
        val dexFile = File(context.cacheDir, jarName)
        context.assets.open(jarName).use { input ->
            dexFile.outputStream().use { output -> input.copyTo(output) }
        }
        val b64 = Base64.encodeToString(dexFile.readBytes(), Base64.NO_WRAP)
        shellExecutor.execute("echo '$b64' | base64 -d > /data/local/tmp/$jarName", serial)
        shellExecutor.execute("chmod 644 /data/local/tmp/$jarName", serial)
    }
}
