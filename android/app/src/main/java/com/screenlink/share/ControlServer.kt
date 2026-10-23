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
 * HTTP control server on the phone. expectedToken can be updated as Chromebook rotates tokens.
 */
class ControlServer(private val port: Int = 8081, token: String = "") {
    @Volatile private var expectedToken: String = token
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null

    fun updateToken(token: String) {
        expectedToken = token
    }

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
            var reqToken = ""
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                if (line.startsWith("X-ScreenLink-Token:", true)) {
                    reqToken = line.substringAfter(":").trim()
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
            if (method == "POST" && expectedToken.isNotEmpty()) {
                val peek = try {
                    JSONObject(if (body.isBlank()) "{}" else body).optString("token", "")
                } catch (_: Exception) { "" }
                if (reqToken != expectedToken && peek != expectedToken) {
                    respond(socket, 403, "bad token")
                    return
                }
            }
            if (method == "POST" && path.startsWith("/tap")) {
                val j = JSONObject(if (body.isBlank()) "{}" else body)
                ok = TapAccessibilityService.tapNormalized(
                    j.optDouble("x", -1.0).toFloat(),
                    j.optDouble("y", -1.0).toFloat()
                )
                msg = if (ok) "tapped" else "accessibility_off_or_failed"
            } else if (method == "POST" && path.startsWith("/swipe")) {
                val j = JSONObject(if (body.isBlank()) "{}" else body)
                ok = TapAccessibilityService.swipeNormalized(
                    j.optDouble("x1", 0.5).toFloat(),
                    j.optDouble("y1", 0.8).toFloat(),
                    j.optDouble("x2", 0.5).toFloat(),
                    j.optDouble("y2", 0.2).toFloat(),
                    j.optLong("ms", 200)
                )
                msg = if (ok) "swiped" else "accessibility_off_or_failed"
            } else if (method == "POST" && path.startsWith("/text")) {
                val j = JSONObject(if (body.isBlank()) "{}" else body)
                val text = j.optString("text", "")
                ok = text.isNotEmpty() && TapAccessibilityService.typeText(text)
                msg = if (ok) "typed" else "accessibility_off_or_no_field"
            } else if (method == "POST" && path.startsWith("/key")) {
                val j = JSONObject(if (body.isBlank()) "{}" else body)
                val key = j.optString("key", "")
                ok = when (key) {
                    "Backspace", "Back", "Escape" -> TapAccessibilityService.globalBack()
                    "Home" -> TapAccessibilityService.globalHome()
                    "Enter" -> TapAccessibilityService.typeText("\n")
                    else -> if (key.length == 1) TapAccessibilityService.typeText(key) else false
                }
                msg = if (ok) "key" else "accessibility_off_or_failed"
            } else if (method == "POST" && path.startsWith("/back")) {
                ok = TapAccessibilityService.globalBack()
                msg = if (ok) "back" else "accessibility_off_or_failed"
            } else if (method == "POST" && path.startsWith("/home")) {
                ok = TapAccessibilityService.globalHome()
                msg = if (ok) "home" else "accessibility_off_or_failed"
            } else if (method == "POST" && path.startsWith("/recents")) {
                ok = TapAccessibilityService.globalRecents()
                msg = if (ok) "recents" else "accessibility_off_or_failed"
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
            403 -> "403 Forbidden"
            404 -> "404 Not Found"
            503 -> "503 Service Unavailable"
            else -> "$code Error"
        }
        val head = "HTTP/1.1 $status\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type, X-ScreenLink-Token\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n\r\n"
        val out: OutputStream = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }
}
