package me.him188.ani.android.ech

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

// 共享客户端：连接复用，握手一次，后续请求走复用通道。
internal object EchHttp {
    @Volatile
    private var client: OkHttpClient? = null

    fun get(): OkHttpClient {
        client?.let { return it }
        return synchronized(this) {
            client ?: build().also { client = it }
        }
    }

    private fun build(): OkHttpClient {
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
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
