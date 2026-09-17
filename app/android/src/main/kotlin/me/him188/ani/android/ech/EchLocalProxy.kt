package me.him188.ani.android.ech

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 进程内本地代理（Kotlin，非 Go 外挂进程）：
 * WebView 直接打开 http://127.0.0.1:8888/... ，本代理用 Conscrypt+ECH 转发到 https://bgm.tv/...。
 *
 * 为什么这样设计：
 * - Go 代理是独立进程，系统会回收 → 不稳定；本代理跑在 App 进程内（线程池），不会被杀
 * - WebView 访问 127.0.0.1 视为本地站点，页面/验证码/表单/JS 全部浏览器原生处理，
 *   Discuz 的相对路径（/FollowTheRabbit、/signup/captcha?..）自动命中本地代理，零 JS 劫持
 * - 登录/验证码冲突（Discuz submit 刷新验证码）自然消失：浏览器就是"正常浏览器"
 * - cookie 在代理内维护（bgm.tv 域）；302 到 api.animeko.org 原样放行，WebView 原生跳转完成绑定
 */
object EchLocalProxy {
    const val PORT = 8888
    private const val TARGET_HOST = "bgm.tv"

    @Volatile
    private var server: ServerSocket? = null
    @Volatile
    private var running = false
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "ech-local-proxy").apply { isDaemon = true }
    }
    private val cookieStore = LinkedHashMap<String, String>()
    private val cookieLock = Any()

    fun start(): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket(PORT, 64, InetAddress.getByName("127.0.0.1"))
            server = ss
            running = true
            Thread({ acceptLoop(ss) }, "ech-local-proxy-accept").apply { isDaemon = true }.start()
            EchLog.log("本地代理启动 http://127.0.0.1:$PORT (进程内, Conscrypt ECH -> $TARGET_HOST)")
            true
        } catch (e: Exception) {
            EchLog.log("本地代理启动失败: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running && !ss.isClosed) {
            try {
                val sock = ss.accept()
                pool.execute { runCatching { handle(sock) } }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 30_000
            val input = s.getInputStream()
            val out = s.getOutputStream()
            val reqLine = readLine(input) ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0].uppercase()
            val rawPath = parts[1]
            if (rawPath.contains("..")) return
            val headers = mutableListOf<Pair<String, String>>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers.add(line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim())
            }
            val cl = headers.firstOrNull { it.first == "content-length" }?.second?.toIntOrNull() ?: 0
            val body = if (cl > 0) ByteArray(cl).also { readFully(input, it) } else ByteArray(0)

            val targetUrl = "https://$TARGET_HOST$rawPath"
            val rb = Request.Builder().url(targetUrl)
            headers.forEach { (k, v) ->
                when (k) {
                    "host", "content-length", "cookie", "connection", "accept-encoding", "transfer-encoding" -> {}
                    // Discuz 防 CSRF：Referer/Origin 必须是 bgm.tv 域。WebView 的 Referer 是
                    // http://127.0.0.1:8888/...（本地），必须重写回 https://bgm.tv/...，否则"来路不正确"
                    "referer" -> rb.header(k, rewriteReferer(v))
                    "origin" -> rb.header(k, v.replace("http://127.0.0.1:$PORT", "https://$TARGET_HOST").replace("http://127.0.0.1", "https://$TARGET_HOST"))
                    else -> runCatching { rb.header(k, v) }
                }
            }
            rb.header("Host", TARGET_HOST)
            val cookie = currentCookie()
            if (cookie.isNotBlank()) rb.header("Cookie", cookie)
            if (method == "POST" || method == "PUT") {
                val ct = headers.firstOrNull { it.first == "content-type" }?.second
                rb.method(method, body.toRequestBody(ct?.toMediaTypeOrNull()))
            } else {
                rb.get()
            }

            val resp = EchHttp.post().newCall(rb.build()).execute()
            resp.use { r ->
                for (sc in r.headers("Set-Cookie")) {
                    storeSetCookie(sc)
                }
                val code = r.code
                val respBody = r.body?.bytes() ?: ByteArray(0)
                val ct = r.header("Content-Type") ?: "text/html"
                val isHtml = ct.contains("html")
                var finalBody = respBody
                val finalHeaders = mutableListOf<Pair<String, String>>()
                r.headers.forEach { (k, v) ->
                    val lk = k.lowercase()
                    when (lk) {
                        "content-length", "transfer-encoding", "connection", "content-encoding" -> {}
                        "location" -> {
                            // 302：bgm 内部 → 重写为 127.0.0.1；外部(api.animeko.org) → 原样(WebView 原生跳)
                            finalHeaders.add("Location" to rewriteLocation(v))
                        }
                        "content-type" -> finalHeaders.add(k to v)
                        else -> finalHeaders.add(k to v)
                    }
                }
                if (isHtml) {
                    // 页面内 https://bgm.tv 绝对链接 → 127.0.0.1（相对路径天然命中本地，无需处理）
                    val html = String(respBody, Charsets.UTF_8)
                        .replace("https://$TARGET_HOST", "http://127.0.0.1:$PORT")
                        .replace("http://$TARGET_HOST", "http://127.0.0.1:$PORT")
                    finalBody = html.toByteArray(Charsets.UTF_8)
                    finalHeaders.removeAll { it.first == "Content-Type" }
                    finalHeaders.add("Content-Type" to "text/html; charset=utf-8")
                }
                finalHeaders.removeAll { it.first.equals("Content-Length", true) }
                finalHeaders.add("Content-Length" to finalBody.size.toString())
                val head = StringBuilder()
                head.append("HTTP/1.1 ").append(code).append(" ").append(r.message.ifBlank { if (code == 302) "Found" else "OK" }).append("\r\n")
                finalHeaders.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
                head.append("Connection: close\r\n\r\n")
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                if (method != "HEAD") out.write(finalBody)
                out.flush()
                EchLog.log("代理 $method $rawPath -> $code (${finalBody.size}B)")
            }
        }
    }

    private fun rewriteLocation(loc: String): String {
        return when {
            loc.startsWith("http://$TARGET_HOST") || loc.startsWith("https://$TARGET_HOST") ->
                loc.replaceFirst(Regex("https?://$TARGET_HOST"), "http://127.0.0.1:$PORT")
            loc.startsWith("/") -> loc  // 相对路径，WebView 按 127.0.0.1 解析
            else -> loc  // 外部域（api.animeko.org 等），原样放行
        }
    }

    private fun rewriteReferer(ref: String): String =
        ref.replace("http://127.0.0.1:$PORT", "https://$TARGET_HOST")
            .replace("http://127.0.0.1", "https://$TARGET_HOST")

    private fun storeSetCookie(sc: String) {
        val eq = sc.indexOf('=')
        if (eq <= 0) return
        val name = sc.substring(0, eq).trim()
        val value = sc.substring(eq + 1).substringBefore(';').trim()
        synchronized(cookieLock) { cookieStore[name] = value }
    }

    private fun currentCookie(): String = synchronized(cookieLock) {
        cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString("ISO-8859-1")
    }

    private fun readFully(input: InputStream, arr: ByteArray) {
        var off = 0
        while (off < arr.size) {
            val n = input.read(arr, off, arr.size - off)
            if (n == -1) break
            off += n
        }
    }
}
