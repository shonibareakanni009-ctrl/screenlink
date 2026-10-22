package com.screenlink.share

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Tiny HTTP server on the phone so the Chromebook can POST taps/swipes.
 * POST /tap  body: {"x":0.5,"y":0.3}  (normalized 0..1)
 * POST /swipe body: {"x1":0.5,"y1":0.8,"x2":0.5,"y2":0.2}
 */
class ControlServer(private val port: Int = 8081) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null

    fun start() {
        if (running.getAndSet(true)) return
        thread(name = "screenlink-control") {
            try {
                server = ServerSocket(port)
                Log.i("ScreenLink", "Control server on $port")
                while (running.get()) {
                    val socket = try { server?.accept() } catch (_: Exception) { null } ?: break
                    thread { handle(socket) }
                }
            } catch (e: Exception) {
                Log.w("ScreenLink", "Control server stopped: ${e.message}")
            } finally {
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(socket: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val path = parts.getOrNull(1) ?: "/"
            var contentLength = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var got = 0
                while (got < contentLength) {
                    val n = input.read(buf, got, contentLength - got)
                    if (n < 0) break
                    got += n
                }
                String(buf, 0, got)
            } else ""

            var ok = false
            var msg = "ok"
            if (method == "OPTIONS") {
                respond(socket, 204, "")
                return
            }
            if (method == "POST" && path.startsWith("/tap")) {
                val j = JSONObject(if (body.isBlank()) "{}" else body)
                val x = j.optDouble("x", -1.0).toFloat()
                val y = j.optDouble("y", -1.0).toFloat()
                ok = TapAccessibilityService.tapNormalized(x, y)
                msg = if (ok) "tapped" else "accessibility_off_or_failed"
            } else if (method == "POST" && path.startsWith("/swipe")) {
                val j = JSONObject(if (body.isBlank()) "{}" else body)
                ok = TapAccessibilityService.swipeNormalized(
                    j.optDouble("x1", 0.5).toFloat(),
                    j.optDouble("y1", 0.8).toFloat(),
                    j.optDouble("x2", 0.5).toFloat(),
                    j.optDouble("y2", 0.2).toFloat()
                )
                msg = if (ok) "swiped" else "accessibility_off_or_failed"
            } else if (method == "GET") {
                respond(socket, 200, "ScreenLink control")
                return
            } else {
                respond(socket, 404, "not found")
                return
            }
            respond(socket, if (ok) 200 else 503, msg)
        } catch (e: Exception) {
            try { respond(socket, 500, e.message ?: "error") } catch (_: Exception) {}
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun respond(socket: Socket, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"
            204 -> "204 No Content"
            404 -> "404 Not Found"
            503 -> "503 Service Unavailable"
            else -> "$code Error"
        }
        val head = "HTTP/1.1 $status\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n\r\n"
        val out: OutputStream = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }
}
