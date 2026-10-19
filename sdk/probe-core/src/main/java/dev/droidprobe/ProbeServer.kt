package dev.droidprobe

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal class ProbeServer(
    private val app: Application,
    private val core: ProbeCore,
    private val maxConnections: Int = 4,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicInteger()

    fun start() {
        val name = "${app.packageName}.probe"
        val server = try {
            LocalServerSocket(name)
        } catch (error: IOException) {
            Log.w(Probe.TAG, "Cannot listen on @$name", error)
            return
        }
        thread(name = "droidprobe-accept", isDaemon = true) {
            var backoffMs = 0L
            while (true) {
                val socket = try {
                    server.accept()
                } catch (error: IOException) {
                    backoffMs = (backoffMs * 2).coerceIn(ACCEPT_BACKOFF_MIN_MS, ACCEPT_BACKOFF_MAX_MS)
                    Log.w(Probe.TAG, "accept failed; retrying in ${backoffMs}ms", error)
                    Thread.sleep(backoffMs)
                    continue
                }
                backoffMs = 0
                accept(socket)
            }
        }
    }

    private fun accept(socket: LocalSocket) {
        // adb forward connects as adbd (shell or root), which holds DUMP; other apps do not.
        val allowed = runCatching {
            val peer = socket.peerCredentials
            app.checkPermission(Manifest.permission.DUMP, peer.pid, peer.uid) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!allowed) {
            closeQuietly(socket)
            return
        }
        if (active.incrementAndGet() > maxConnections) {
            active.decrementAndGet()
            closeQuietly(socket)
            return
        }
        scope.launch {
            try {
                Dispatcher(core).serve(SocketTransport(socket))
            } catch (error: Throwable) {
                Log.w(Probe.TAG, "connection failed", error)
            } finally {
                active.decrementAndGet()
                closeQuietly(socket)
            }
        }
    }

    private class SocketTransport(private val socket: LocalSocket) : Transport {
        private val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        private val writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)

        override suspend fun readLine(): String? = runInterruptible { reader.readLine() }

        override suspend fun writeLine(line: String) {
            try {
                runInterruptible {
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                }
            } catch (error: IOException) {
                closeQuietly(socket)
                throw error
            }
        }
    }

    private companion object {
        const val ACCEPT_BACKOFF_MIN_MS = 200L
        const val ACCEPT_BACKOFF_MAX_MS = 2_000L

        fun closeQuietly(socket: LocalSocket) {
            runCatching { socket.close() }
        }
    }
}
