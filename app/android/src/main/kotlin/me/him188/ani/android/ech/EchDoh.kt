package me.him188.ani.android.ech

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

// DoH 取 A 记录与 ECH 配置（dns-json），带缓存。拿不到一律抛异常。
internal object EchDoh {
    data class Endpoint(val url: String)

    val pool = listOf(
        Endpoint("https://tgxjjdszvu.cloudflare-gateway.com/dns-query"),
        Endpoint("https://pieqllv9i7.cloudflare-gateway.com/dns-query"),
        Endpoint("https://m2b4x7vw98.cloudflare-gateway.com/dns-query"),
    )

    private data class Entry<out T>(val value: T, val expiresAt: Long)
    private val aCache = ConcurrentHashMap<String, Entry<List<InetAddress>>>()
    private val echCache = ConcurrentHashMap<String, Entry<ByteArray>>()

    private fun get(dohUrl: String, name: String, qtype: String): JSONObject? {
        val c = (URL("$dohUrl?name=$name&type=$qtype").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/dns-json")
            setRequestProperty("User-Agent", "Ani-ECH/1.0")
        }
        return try {
            if (c.responseCode != 200) return null
            JSONObject(c.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    fun fetchA(host: String): List<InetAddress> {
        aCache[host]?.let { if (System.currentTimeMillis() < it.expiresAt) return it.value }
        var lastErr: Exception? = null
        for (ep in pool) {
            try {
                val json = get(ep.url, host, "A")
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
                return out
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: UnknownHostException("DoH全灭")
    }

    fun fetchEch(host: String): ByteArray {
        echCache[host]?.let { if (System.currentTimeMillis() < it.expiresAt) return it.value }
        var lastErr: Exception? = null
        for (ep in pool) {
            try {
                val json = get(ep.url, host, "HTTPS") ?: continue
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
                        return raw
                    }
                }
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: UnknownHostException("无ECH配置")
    }
}
