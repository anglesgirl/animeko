package me.him188.ani.android.ech

import android.util.Base64
import me.him188.ani.android.BuildConfig
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// DoH 取 A 记录与 ECH 配置（dns-json），带缓存。拿不到一律抛异常。
// 节点池来自 BuildConfig（local.properties 的 echDohPool，逗号分隔），
// 缺省用 Cloudflare Gateway（大陆可直连、返回 ech= 配置）。
// 网关域名用内置 IP 直连，不依赖系统 DNS（避免 DoH 解析自身被污染的死循环）；
// SNI 仍为域名，证书校验不受影响。
internal object EchDoh {
    private const val TAG = "ECH-DOH"

    // Cloudflare Gateway 内置 IP（用户提供，用于解析网关域名，绕过系统 DNS 污染）
    private val GATEWAY_IPS: List<InetAddress> = listOf(
        "172.64.36.1", "172.64.36.2",
        "2a06:98c1:54::72:a4b3",
    ).mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }

    // bgm.tv（CNAME→research.cloudflare.com）的内置 ECH 配置兜底，DoH 全挂时可用
    private val FALLBACK_ECH: ByteArray? = runCatching {
        Base64.decode(
            "AEX+DQBBoQAgACD+e2S6IetnuhaljaMiPjyI4bJnjDikRM91us119cr5JAAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA=",
            Base64.DEFAULT,
        )
    }.getOrNull()

    private val pool: List<String> by lazy {
        BuildConfig.ECH_DOH_POOL.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    // Cloudflare Gateway 域名直接用内置 IP，不依赖系统 DNS（避免 DoH 解析自身被污染的死循环）
                    if (hostname == "bkbq2r7nr6.cloudflare-gateway.com") return GATEWAY_IPS
                    return try { Dns.SYSTEM.lookup(hostname) } catch (e: UnknownHostException) { emptyList() }
                }
            })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private data class Entry<out T>(val value: T, val expiresAt: Long)
    private val aCache = ConcurrentHashMap<String, Entry<List<InetAddress>>>()
    private val echCache = ConcurrentHashMap<String, Entry<ByteArray>>()

    private companion object {
        /** CF 官方活值来源：会随 CF 轮换自动更新，对任何 CF 边缘域名有效 */
        const val LIVE_SOURCE_HOST = "cloudflare-ech.com"

        /** 配置缓存 30 分钟（CF 的 ECH 密钥轮换周期远长于此，到期重新拉取即可） */
        const val CACHE_TTL_MS = 30 * 60 * 1000L
    }

    /** CF 官方 ECH 活值的单独缓存 */
    @Volatile
    private var officialCache: Entry<ByteArray>? = null

    private fun get(dohUrl: String, name: String, qtype: String): JSONObject? {
        val req = Request.Builder().url("$dohUrl?name=$name&type=$qtype")
            .header("Accept", "application/dns-json")
            .header("User-Agent", "Ani-ECH/1.0")
            .build()
        return try {
            val resp = dohClient.newCall(req).execute()
            val json = if (resp.code == 200) runCatching { JSONObject(resp.body?.string() ?: "") }.getOrNull() else null
            resp.close()
            if (json == null) EchLog.log("DoH GET fail $dohUrl name=$name type=$qtype code=${resp.code}")
            json
        } catch (e: Exception) {
            EchLog.log("DoH GET ERR $dohUrl name=$name type=$qtype: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    fun fetchA(host: String): List<InetAddress> {
        aCache[host]?.let { if (System.currentTimeMillis() < it.expiresAt) return it.value }
        var lastErr: Exception? = null
        for (url in pool) {
            try {
                val json = get(url, host, "A")
                    ?: throw UnknownHostException("DoH无响应")
                val ans = json.optJSONArray("Answer")
                    ?: throw UnknownHostException("DoH无A记录")
                val out = mutableListOf<InetAddress>()
                for (i in 0 until ans.length()) {
                    val o = ans.getJSONObject(i)
                    if (o.optInt("type", 0) != 1) continue
                    try {
                        out.add(InetAddress.getByName(o.optString("data", "")))
                    } catch (_: Exception) {
                    }
                }
                if (out.isEmpty()) throw UnknownHostException("DoH无有效IP")
                aCache[host] = Entry(out, System.currentTimeMillis() + 60_000)
                EchLog.log("DoH A OK $host -> ${out.joinToString { it.hostAddress }}")
                return out
            } catch (e: Exception) {
                lastErr = e
                EchLog.log("DoH A try fail $url $host: ${e.message}")
            }
        }
        EchLog.log("DoH A ALL FAIL $host")
        throw lastErr ?: UnknownHostException("DoH全灭")
    }

    /**
     * 取目标域的 ECH 配置。
     *
     * 先取 CF 官方 [LIVE_SOURCE_HOST] 的**活值**：域名自己的 `ech=` 记录常常过期或被服务端拒绝
     * （被拒时握手直接失败），而官方值对任何 CF 边缘域名都有效，且会随 CF 轮换自动更新。
     * 官方取不到时才退回域名自己的记录。
     */
    fun fetchEch(host: String): ByteArray {
        echCache[host]?.let { if (System.currentTimeMillis() < it.expiresAt) return it.value }

        officialEch()?.let { official ->
            echCache[host] = Entry(official, System.currentTimeMillis() + CACHE_TTL_MS)
            return official
        }

        return fetchEchRecord(host).also {
            echCache[host] = Entry(it, System.currentTimeMillis() + CACHE_TTL_MS)
        }
    }

    /** CF 官方域名 cloudflare-ech.com 的活值，单独缓存，避免每个域名都去查一次。 */
    private fun officialEch(): ByteArray? {
        officialCache?.let { if (System.currentTimeMillis() < it.expiresAt) return it.value }
        return runCatching { fetchEchRecord(LIVE_SOURCE_HOST) }
            .getOrNull()
            ?.also { officialCache = Entry(it, System.currentTimeMillis() + CACHE_TTL_MS) }
    }

    private fun fetchEchRecord(host: String): ByteArray {
        var lastErr: Exception? = null
        for (url in pool) {
            try {
                val json = get(url, host, "HTTPS") ?: continue
                val ans = json.optJSONArray("Answer") ?: continue
                for (i in 0 until ans.length()) {
                    val data = ans.getJSONObject(i).optString("data", "")
                    val j = data.indexOf("ech=")
                    if (j < 0) continue
                    var b64 = data.substring(j + 4).trim()
                    val sp = b64.indexOf(' ')
                    if (sp > 0) b64 = b64.substring(0, sp)
                    val raw = Base64.decode(b64, Base64.DEFAULT)
                    if (raw.size > 8) {
                        echCache[host] = Entry(raw, System.currentTimeMillis() + 1_800_000)
                        EchLog.log("DoH ECH OK $host (len=${raw.size}) via $url")
                        return raw
                    }
                }
                EchLog.log("DoH ECH no ech= in $url for $host")
            } catch (e: Exception) {
                lastErr = e
                EchLog.log("DoH ECH try fail $url $host: ${e.message}")
            }
        }
        // DoH 全挂时用内置配置兜底（仅 bgm.tv 系，其 CNAME 到 research.cloudflare.com）
        val isBgm = host == "bgm.tv" || host.endsWith(".bgm.tv") ||
            host == "bangumi.tv" || host.endsWith(".bangumi.tv")
        if (isBgm && FALLBACK_ECH != null) {
            echCache[host] = Entry(FALLBACK_ECH, System.currentTimeMillis() + 3_600_000)
            EchLog.log("DoH ECH use FALLBACK for $host")
            return FALLBACK_ECH
        }
        EchLog.log("DoH ECH ALL FAIL $host")
        throw lastErr ?: UnknownHostException("无ECH配置")
    }
}
