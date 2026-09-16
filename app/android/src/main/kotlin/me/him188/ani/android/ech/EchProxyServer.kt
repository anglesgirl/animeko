package me.him188.ani.android.ech

import android.util.Log
import android.webkit.CookieManager
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// 本地回环代理：WebView 只渲染，网络走 ECH。
// WebView 加载 http://127.0.0.1:PORT/https://bgm.tv/xxx → 本代理字节级读取请求（含 POST 体）→ EchHttp 转发 → 回写，HTML 中绝对地址重写为代理前缀，302 Location 同步重写，Set-Cookie 透传到 CookieManager。
object EchProxyServer {
    private const val TAG = "ECH-Proxy"
    private const val PREFIX = "/https://"
    private const val PREFIX_HTTP = "/http://"

    @Volatile
    var port: Int = -1
        private set

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    fun proxyUrl(target: String): String {
        val p = port
        check(p > 0) { "代理未启动" }
        return "http://127.0.0.1:$p/$target"
    }

    fun isRunning(): Boolean = running.get() && port > 0

    fun start() {
        if (running.getAndSet(true)) return
        try {
            val ss = ServerSocket(0)
            ss.reuseAddress = true
            port = ss.localPort
            serverSocket = ss
            Log.i(TAG, "listening on 127.0.0.1:$port")
            Thread({
                while (running.get()) {
                    try {
                        val s = ss.accept()
                        executor.execute { handle(s) }
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "accept 失败", e)
                    }
                }
            }, "ECH-Proxy-Accept").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            running.set(false)
            Log.e(TAG, "启动失败", e)
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        port = -1
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val inp: InputStream = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()

                // 读取请求头直到 \r\n\r\n
                val headerBuf = ByteArrayOutputStream()
                val tmp = ByteArray(8192)
                var headerEnd = -1
                var total = 0
                while (headerEnd == -1 && total < 65536) {
                    val n = inp.read(tmp)
                    if (n <= 0) return
                    headerBuf.write(tmp, 0, n)
                    total += n
                    val data = headerBuf.toByteArray()
                    headerEnd = indexOf(data, "\r\n\r\n".toByteArray())
                    if (headerEnd != -1) break
                }
                if (headerEnd == -1) return
                val headerBytes = headerBuf.toByteArray()
                val headerStr = String(headerBytes, 0, headerEnd, Charsets.ISO_8859_1)
                val lines = headerStr.split("\r\n")
                if (lines.isEmpty()) return
                val requestLine = lines[0]
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val rawPath = parts[1]

                // 目标地址：/https://xxx 或 /http://xxx
                val targetUrl = when {
                    rawPath.startsWith(PREFIX) || rawPath.startsWith(PREFIX_HTTP) -> rawPath.substring(1)
                    rawPath.startsWith("http://") || rawPath.startsWith("https://") -> rawPath
                    else -> {
                        // 相对路径：用 Referer 推断原域
                        val referer = lines.firstOrNull { it.startsWith("Referer:", ignoreCase = true) }
                            ?.substringAfter(":", "")?.trim()
                        if (referer != null && referer.contains("127.0.0.1")) {
                            val idx = referer.indexOf("https://")
                            val idx2 = referer.indexOf("http://")
                            val base = when {
                                idx != -1 -> referer.substring(idx)
                                idx2 != -1 -> referer.substring(idx2)
                                else -> null
                            }
                            if (base != null) {
                                val baseUrl = java.net.URL(base)
                                "${baseUrl.protocol}://${baseUrl.host}${if (baseUrl.port != -1 && baseUrl.port != baseUrl.defaultPort) ":${baseUrl.port}" else ""}$rawPath"
                            } else null
                        } else null
                    }
                } ?: run {
                    writeSimple(out, 400, "Bad Request: 无法解析目标")
                    return
                }

                // 解析头
                val reqHeaders = mutableMapOf<String, String>()
                for (i in 1 until lines.size) {
                    val l = lines[i]
                    if (l.isEmpty()) continue
                    val c = l.indexOf(':')
                    if (c > 0) reqHeaders[l.substring(0, c).trim()] = l.substring(c + 1).trim()
                }

                val contentLen = reqHeaders.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull() ?: 0
                val already = headerBytes.size - (headerEnd + 4)
                val body: ByteArray? = if (contentLen > 0) {
                    val buf = ByteArray(contentLen)
                    var copied = 0
                    if (already > 0) {
                        val take = minOf(already, contentLen)
                        System.arraycopy(headerBytes, headerEnd + 4, buf, 0, take)
                        copied = take
                    }
                    var off = copied
                    while (off < contentLen) {
                        val r = inp.read(buf, off, contentLen - off)
                        if (r <= 0) break
                        off += r
                    }
                    if (off < contentLen) buf.copyOf(off) else buf
                } else if (already > 0) {
                    // 无长度但有残留（chunked 暂不支持，按已有返回）
                    headerBytes.copyOfRange(headerEnd + 4, headerBytes.size)
                } else null

                // Cookie 同步：从 WebView 取
                val cookie = runCatching { CookieManager.getInstance().getCookie(targetUrl) }.getOrNull()
                if (!cookie.isNullOrBlank() && !reqHeaders.keys.any { it.equals("Cookie", true) }) {
                    reqHeaders["Cookie"] = cookie
                }

                // 转发
                val rb = Request.Builder().url(targetUrl)
                // 透传头，跳过由 OkHttp 自生的
                val skip = setOf("host", "content-length", "connection", "proxy-connection", "accept-encoding")
                reqHeaders.forEach { (k, v) ->
                    if (k.lowercase() !in skip) rb.header(k, v)
                }
                if (cookie != null && !reqHeaders.containsKey("Cookie")) {
                    // 已在上一步加
                }
                val ct = reqHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                when (method.uppercase()) {
                    "GET" -> rb.get()
                    "POST" -> rb.post((body ?: ByteArray(0)).toRequestBody(ct?.toMediaTypeOrNull()))
                    "HEAD" -> rb.head()
                    else -> rb.method(method, body?.toRequestBody(ct?.toMediaTypeOrNull()))
                }

                val resp = EchHttp.get().newCall(rb.build()).execute()
                val respBody = resp.body?.bytes() ?: ByteArray(0)
                var respBytes = respBody

                // Set-Cookie 同步到 WebView
                val setCookies = resp.headers("Set-Cookie")
                if (setCookies.isNotEmpty()) {
                    val cm = CookieManager.getInstance()
                    for (sc in setCookies) {
                        runCatching { cm.setCookie(targetUrl, sc) }
                    }
                    runCatching { cm.flush() }
                }

                // Location 重写
                val loc = resp.header("Location")
                val headersOut = mutableListOf<Pair<String, String>>()
                for (i in 0 until resp.headers.size) {
                    val n = resp.headers.name(i)
                    val v = resp.headers.value(i)
                    if (n.equals("Content-Length", true) || n.equals("Content-Encoding", true) || n.equals("Transfer-Encoding", true)) continue
                    if (n.equals("Location", true) && v != null) {
                        val newLoc = when {
                            v.startsWith("https://") || v.startsWith("http://") -> proxyUrl(v)
                            v.startsWith("/") -> {
                                val u = java.net.URL(targetUrl)
                                proxyUrl("${u.protocol}://${u.host}${if (u.port != -1 && u.port != u.defaultPort) ":${u.port}" else ""}$v")
                            }
                            else -> v
                        }
                        headersOut.add(n to newLoc)
                    } else {
                        headersOut.add(n to v)
                    }
                }

                // HTML 重写：绝对地址改为代理前缀
                val cType = resp.header("Content-Type") ?: ""
                if (cType.contains("text/html", true) && respBytes.isNotEmpty()) {
                    val html = String(respBytes, Charsets.UTF_8)
                    // 仅对 BGM 相关域重写，避免全量替换误伤
                    var outHtml = html
                    for (host in listOf("https://bgm.tv", "https://api.bgm.tv", "https://next.bgm.tv", "https://bangumi.tv")) {
                        outHtml = outHtml.replace(host, proxyUrl(host))
                    }
                    respBytes = outHtml.toByteArray(Charsets.UTF_8)
                }

                // 回写
                val sb = StringBuilder()
                sb.append("HTTP/1.1 ${resp.code} ${resp.message}\r\n")
                var hasCT = false
                for ((k, v) in headersOut) {
                    if (k.equals("Content-Type", true)) hasCT = true
                    sb.append("$k: $v\r\n")
                }
                if (!hasCT && cType.isNotBlank()) sb.append("Content-Type: $cType\r\n")
                sb.append("Content-Length: ${respBytes.size}\r\n")
                sb.append("Connection: close\r\n")
                sb.append("\r\n")
                out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                if (respBytes.isNotEmpty()) out.write(respBytes)
                out.flush()
                resp.close()
            } catch (e: Exception) {
                Log.w(TAG, "处理失败", e)
                runCatching {
                    val out = socket.getOutputStream()
                    writeSimple(out, 502, "ECH 代理失败: ${e.message}")
                }
            }
        }
    }

    private fun writeSimple(out: java.io.OutputStream, code: Int, msg: String) {
        val body = msg.toByteArray()
        val head = "HTTP/1.1 $code ${if (code == 502) "Bad Gateway" else "Error"}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun indexOf(data: ByteArray, pat: ByteArray): Int {
        outer@ for (i in 0..data.size - pat.size) {
            for (j in pat.indices) if (data[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }
}
