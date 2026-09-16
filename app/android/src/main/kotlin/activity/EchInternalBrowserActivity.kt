package me.him188.ani.android.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import me.him188.ani.android.ech.EchProxyServer

// 站内浏览器：外部 Bangumi 授权页改走 ECH 内部打开，失败不回落明文。
class EchInternalBrowserActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "url"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val url = req.url.toString()
                    // 代理前缀解包后再判域
                    val real = if (url.contains("127.0.0.1")) {
                        val idx = url.indexOf("https://")
                        val idx2 = url.indexOf("http://", url.indexOf("127.0.0.1"))
                        when {
                            idx != -1 -> url.substring(idx)
                            idx2 != -1 -> url.substring(idx2)
                            else -> url
                        }
                    } else url
                    // 非 BGM 域可放行外部，其余站内
                    return false
                }

                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                    // 已走代理的请求不二次拦截
                    if (req.url.host == "127.0.0.1") return null
                    val url = req.url.toString()
                    val host = req.url.host ?: return null
                    val isBgm = host == "bgm.tv" || host.endsWith(".bgm.tv") ||
                        host == "bangumi.tv" || host.endsWith(".bangumi.tv")
                    if (!isBgm) return null
                    // GET 类走 shouldInterceptRequest 直通 ECH
                    if (req.method != "GET" && req.method != "HEAD") return null
                    return try {
                        val headers = req.requestHeaders ?: emptyMap()
                        val cookie = CookieManager.getInstance().getCookie(url)
                        val allHeaders = if (!cookie.isNullOrBlank() && !headers.keys.any { it.equals("Cookie", true) }) {
                            headers + ("Cookie" to cookie)
                        } else headers
                        val rb = okhttp3.Request.Builder().url(url).get()
                        allHeaders.forEach { (k, v) -> if (!k.equals("Host", true)) rb.header(k, v) }
                        val resp = me.him188.ani.android.ech.EchHttp.get().newCall(rb.build()).execute()
                        val body = resp.body?.bytes() ?: ByteArray(0)
                        val ct = resp.header("Content-Type") ?: "text/html"
                        val mime = ct.substringBefore(";").trim().ifBlank { "text/html" }
                        val enc = Regex("charset=([^;\\s\"']+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1) ?: "utf-8"
                        // Set-Cookie 回写
                        for (sc in resp.headers("Set-Cookie")) {
                            runCatching { CookieManager.getInstance().setCookie(url, sc) }
                        }
                        runCatching { CookieManager.getInstance().flush() }
                        val stream = java.io.ByteArrayInputStream(body)
                        WebResourceResponse(mime, enc, resp.code, "OK", resp.headers.toMultimap().mapValues { it.value.firstOrNull() ?: "" }.filterKeys { !it.equals("Content-Type", true) }, stream).apply {
                            // 保留原始状态码
                        }
                    } catch (_: Exception) {
                        // fail-closed：BGM 域失败不回落，返回 502 让页面报错而非明文
                        WebResourceResponse("text/html", "utf-8", 502, "Bad Gateway", emptyMap(), java.io.ByteArrayInputStream("ECH 失败".toByteArray()))
                    }
                }
            }
        }
        setContentView(wv)
        // 走本地代理，POST 亦能带体
        val proxyUrl = if (EchProxyServer.isRunning()) EchProxyServer.proxyUrl(target) else target
        wv.loadUrl(proxyUrl)
    }
}
