package me.him188.ani.android.ech

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

// 共享客户端：连接复用，握手一次，后续请求走复用通道。
// GET/HEAD 自动跟随重定向（登录页/确认页链），POST 不跟随（手动取 Location 继续走 ECH）。
// CookieJar 与 WebView 的 CookieManager 双向同步，保证页面与 ECH 请求会话一致。
internal object EchHttp {
    @Volatile
    private var client: OkHttpClient? = null
    @Volatile
    private var postClient: OkHttpClient? = null

    fun get(): OkHttpClient {
        client?.let { return it }
        return synchronized(this) {
            client ?: build(followRedirects = true).also { client = it }
        }
    }

    fun post(): OkHttpClient {
        postClient?.let { return it }
        return synchronized(this) {
            postClient ?: build(followRedirects = false).also { postClient = it }
        }
    }

    /**
     * ECH 自愈重试：Conscrypt 把「ECH 被拒」报成 SSLException（部分机型包成普通 IOException），
     * 此时按同一份配置再试也是白试 —— 丢掉缓存、重新取一份配置与地址，然后重试一次。
     */
    private object EchRetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            return try {
                chain.proceed(req)
            } catch (e: IOException) {
                if (!looksLikeEchFailure(e)) throw e
                EchLog.log("ECH 失败，自愈重试一次 ${req.url.host}: ${e.message}")
                EchDoh.invalidate(req.url.host)
                chain.proceed(req)
            }
        }

        private fun looksLikeEchFailure(e: IOException): Boolean {
            if (e is SSLException) return true
            val text = (e.message ?: "") + "｜" + (e.cause?.message ?: "")
            return text.contains("ECH", true) || text.contains("SSL", true)
        }
    }

    private fun build(followRedirects: Boolean): OkHttpClient {
        ConscryptEch.ensureProvider()
        val tm = ConscryptEch.PolicyTrustManager(ConscryptEch.systemTrustManager())
        val ctx = SSLContext.getInstance("TLSv1.3", "Conscrypt")
        ctx.init(null, arrayOf(tm), null)
        val factory = EchSocketFactory(ctx.socketFactory) { host ->
            EchDoh.fetchEch(host.lowercase().trimEnd('.'))
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(factory, tm)
            .dns(EchDns)
            .cookieJar(CookieManagerJar)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(EchRetryInterceptor)
            .build()
    }

    /** 与 WebView CookieManager 双向同步：ECH 请求的 Set-Cookie 写回 WebView，WebView 的 cookie 随请求带上。 */
    private object CookieManagerJar : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                runCatching { CookieManager.getInstance().setCookie(url.toString(), c.toString()) }
            }
            runCatching { CookieManager.getInstance().flush() }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val raw = runCatching { CookieManager.getInstance().getCookie(url.toString()) }.getOrNull()
                ?: return emptyList()
            return raw.split(";").mapNotNull { s ->
                val kv = s.trim().split("=", limit = 2)
                if (kv.size != 2) return@mapNotNull null
                runCatching {
                    Cookie.Builder().name(kv[0]).value(kv[1]).domain(url.host).path("/").build()
                }.getOrNull()
            }
        }
    }
}
