package com.dg.nightvision

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Small web server that streams the image live to the Quest browser over WiFi. */
class MjpegServer(private val port: Int) {
    @Volatile private var latest: ByteArray? = null
    @Volatile private var server: ServerSocket? = null
    private val clients = AtomicInteger(0)

    val hasClients: Boolean get() = clients.get() > 0

    fun update(jpg: ByteArray) { latest = jpg }

    fun start() {
        thread(isDaemon = true) {
            try {
                val ss = ServerSocket(port)
                server = ss
                while (!ss.isClosed) {
                    val s = ss.accept()
                    thread(isDaemon = true) { handle(s) }
                }
            } catch (_: Exception) { }
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) { }
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                val reader = s.getInputStream().bufferedReader()
                val req = reader.readLine() ?: return
                val path = req.split(" ").getOrNull(1) ?: "/"
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val out = s.getOutputStream()
                if (path == "/stream") {
                    out.write(("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                        "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray())
                    clients.incrementAndGet()
                    try {
                        var last: ByteArray? = null
                        while (true) {
                            val j = latest
                            if (j != null && j !== last) {
                                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${j.size}\r\n\r\n".toByteArray())
                                out.write(j)
                                out.write("\r\n".toByteArray())
                                out.flush()
                                last = j
                            }
                            Thread.sleep(15)
                        }
                    } finally {
                        clients.decrementAndGet()
                    }
                } else {
                    val body = PAGE.toByteArray()
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    out.write(body)
                    out.flush()
                }
            }
        } catch (_: Exception) { }
    }

    companion object {
        private const val PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DGNightVision</title>
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
img{width:100%;height:100%;object-fit:contain;display:block}</style></head>
<body><img src="/stream" onclick="document.documentElement.requestFullscreen&&document.documentElement.requestFullscreen()">
</body></html>"""
    }
}
