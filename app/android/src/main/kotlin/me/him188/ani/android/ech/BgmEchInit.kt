package me.him188.ani.android.ech

import android.content.Context
import com.liar.han1meplus.EchHttpClient
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import me.him188.ani.utils.ktor.BgmEchResult
import me.him188.ani.utils.ktor.BgmEchTransport

// Android 传输注入：在 Application.onCreate 调一次。
// 网关池：一个不通换下一个；库未加载/无配置/被拒一律抛异常，绝不放行明文。
object BgmEchInit {
    private data class DohEndpoint(val url: String, val resolve: String)

    private val pool = listOf(
        DohEndpoint(
            "https://tgxjjdszvu.cloudflare-gateway.com/dns-query",
            "tgxjjdszvu.cloudflare-gateway.com:443:162.159.36.20,162.159.36.5",
        ),
        DohEndpoint(
            "https://pieqllv9i7.cloudflare-gateway.com/dns-query",
            "pieqllv9i7.cloudflare-gateway.com:443:162.159.36.20,162.159.36.5",
        ),
        DohEndpoint(
            "https://m2b4x7vw98.cloudflare-gateway.com/dns-query",
            "m2b4x7vw98.cloudflare-gateway.com:443:162.159.36.20,162.159.36.5",
        ),
    )

    @Volatile
    private var startIndex = 0

    @Volatile
    private var installed = false

    // 原生库多路并发进会崩（启动 burst），这里串行砌墙
    private val nativeMutex = Mutex()

    fun install(context: Context) {
        EchHttpClient.init(context.applicationContext)
        if (installed) return
        installed = true
        BgmEchTransport.fetchHandler = { url, method, headers, body ->
            fetch(url, method, headers, body)
        }
    }

    private suspend fun fetch(
        url: String,
        method: HttpMethod,
        headers: Map<String, String>,
        body: ByteArray?,
    ): BgmEchResult? = withContext(Dispatchers.IO) {
        if (!EchHttpClient.isLoaded) throw IOException("ECH 库未加载，拒绝明文")
        // 原生调用串行化：并发进库会 native 崩
        nativeMutex.withLock {
            poolLoop(url, method, headers, body)
        }
    }

    private suspend fun poolLoop(
        url: String,
        method: HttpMethod,
        headers: Map<String, String>,
        body: ByteArray?,
    ): BgmEchResult? {
        var lastError: IOException? = null
        for (offset in pool.indices) {
            val idx = (startIndex + offset) % pool.size
            val ep = pool[idx]
            repeat(2) {
                try {
                    val resp = EchHttpClient.execute(method.value, url, headers, body, ep.url, ep.resolve)
                    if (isEchRejected(resp.echStatus)) {
                        lastError = IOException("ECH 被拒(${resp.echStatus})")
                        delay(300)
                        return@repeat
                    }
                    if (!resp.echStatus.contains("accepted", true)) {
                        throw IOException("ECH 未生效(${resp.echStatus})，拒绝明文")
                    }
                    startIndex = idx
                    return BgmEchResult(
                        resp.statusCode,
                        resp.headers.flatMap { (k, v) -> v.map { k to it } },
                        resp.body,
                    )
                } catch (e: Exception) {
                    lastError = if (e is IOException) e else IOException("ECH 请求异常: ${e.message}")
                    delay(300)
                }
            }
        }
        throw lastError ?: IOException("ECH 请求失败，拒绝明文")
    }

    private fun isEchRejected(s: String): Boolean {
        return s.contains("REJECTED", true) ||
            s.contains("ECH was not offered", true) ||
            s.contains("ech was disabled", true) ||
            s.contains("failed", true) && s.contains("ech", true)
    }
}
