package me.him188.ani.utils.ktor

import android.content.Context
import com.liar.han1meplus.EchHttpClient
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.io.IOException

// Android 真实现：预编译库握手。库未加载 / 无配置 / 被拒一律抛异常，绝不放行明文。
actual object BgmEchFetch {
    // 自有网关池：对 bgm.tv 系直接给目标配置（CNAME 到自维护记录，含可用 ech=）。
    // 注意：网关地址嵌在公开 fork 里会被他人蹭用，后续件迁入设置页可配。
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

    fun ensureInit(context: Context): Boolean {
        EchHttpClient.init(context.applicationContext)
        return EchHttpClient.isLoaded
    }

    actual suspend fun fetchIfProtected(
        url: String,
        method: HttpMethod,
        headers: Map<String, String>,
        body: ByteArray?,
    ): BgmEchResult? = withContext(Dispatchers.IO) {
        if (!EchHttpClient.isLoaded) throw IOException("ECH 库未加载，拒绝明文")
        var lastError: IOException? = null
        // 地址池轮询：从上次可用的开始，一个不通换下一个
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
                    if (!isEchAccepted(resp.echStatus)) {
                        throw IOException("ECH 未生效(${resp.echStatus})，拒绝明文")
                    }
                    startIndex = idx
                    return@withContext BgmEchResult(
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

    private fun isEchAccepted(s: String): Boolean {
        return s.contains("accepted", true)
    }
}
