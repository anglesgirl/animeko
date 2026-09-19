/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.ech

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.app.ui.foundation.AniImageH3
import me.him188.ani.app.ui.foundation.imageContentTypeOf
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * quiche（HTTP/3 + ECH）的 JNI 入口。Rust 工程在 `native-h3/`，CI 用 cargo-ndk 编成 `libani_h3.so`。
 *
 * 定位：**只服务可缓存、不带会话的静态图片 GET**（Sketch 图片加载链路）。
 * 有状态请求（登录 / POST / Cookie）一律仍走 [EchHttp] 的 TCP+ECH 链路 —— 那条也是这里失败时的兜底。
 *
 * 策略（用户定调）：**默认所有域名都先试 H3**，不写死白名单；失败一次就把该域名记入**负缓存**
 * （落盘 [H3_STATE_PREFS]，[H3_FAIL_TTL_MS] 内直接走 H2/TCP+ECH，不再白试）；成功后清掉负缓存，
 * TTL 过期会再试一次（服务端可能后来才启用 H3）。
 *
 * 实测事实（无需写死，会被负缓存自动学会）：
 *   - api.bgm.tv / lain.bgm.tv —— CF 侧未启用 H3，握手失败 → 24h 内直接回落 H2
 *   - i.pximg.net —— 支持 H3
 *
 * 任何失败都返回 null（fail-closed，调用方原样回落既有 HTTP 栈）。
 */
internal object AniEchH3 {
    private const val TAG = "ANI-ECH-H3"

    @Volatile
    private var loaded = false

    /** 最近一次 JNI 返回的 JSON（失败时用来定位原因）。 */
    @Volatile
    private var lastJson: String = ""

    @Volatile
    private var appContext: Context? = null

    /**
     * 在 `Application.onCreate` 调一次：接住 appContext，并把 H3 钩子挂到图片加载链路上。
     * 挂载失败不影响任何既有功能（钩子为空时 Sketch 原样走 Ktor/OkHttp）。
     */
    fun install(context: Context) {
        appContext = context.applicationContext
        AniImageH3.h3Loader = { url -> fetchImageBytes(url) }
        EchLog.log("AniEchH3 installed, h3 enabled for static image GET")
    }

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("ani_h3")
            loaded = true
            true
        } catch (t: Throwable) {
            EchLog.log("H3 native 库未加载：${t.message}")
            false
        }
    }

    // ---------------- H3 可用性记忆（落盘） ----------------

    private const val H3_STATE_PREFS = "ech_h3_state"

    /** 负缓存时长：服务端可能后来才启用 H3，过期后自动再试一次。 */
    private const val H3_FAIL_TTL_MS = 24 * 60 * 60 * 1000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(H3_STATE_PREFS, Context.MODE_PRIVATE)

    /** 是否该先试 H3：只要没被负缓存拦下就算可用（不写死白名单）。 */
    fun shouldTryH3(context: Context, host: String): Boolean {
        val until = runCatching { prefs(context).getLong("bad:$host", 0L) }.getOrDefault(0L)
        return System.currentTimeMillis() >= until
    }

    private fun rememberH3(context: Context, host: String, ok: Boolean, why: String = "") {
        runCatching {
            prefs(context).edit()
                .putLong("bad:$host", if (ok) 0L else System.currentTimeMillis() + H3_FAIL_TTL_MS)
                .apply()
        }
        if (ok) {
            EchLog.log("H3 可用，已记住: $host")
        } else {
            EchLog.log("H3 不通，已记负缓存 ${H3_FAIL_TTL_MS / 3600000}h，改走 H2(TCP+ECH): $host ($why)")
        }
    }

    /** 系统 CA 导出成单个 PEM（Rust 侧 load_verify_locations_from_file 读它），只做一次。 */
    private fun caBundlePath(context: Context): String {
        val f = File(context.cacheDir, "ani-system-ca.pem")
        if (f.exists() && f.length() > 1024) return f.absolutePath
        return try {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
            val sb = StringBuilder()
            val aliases = ks.aliases()
            while (aliases.hasMoreElements()) {
                val a = aliases.nextElement()
                val cert = ks.getCertificate(a) as? X509Certificate ?: continue
                sb.append("-----BEGIN CERTIFICATE-----\n")
                sb.append(Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
                sb.append("\n-----END CERTIFICATE-----\n")
            }
            f.writeText(sb.toString())
            f.absolutePath
        } catch (t: Throwable) {
            EchLog.log("系统 CA 导出失败：${t.message}")
            ""
        }
    }

    /** 防盗链 Referer：只有确实要求的站点才加。 */
    private val REFERERS = listOf(
        "pximg.net" to "https://www.pixiv.net/",
    )

    private fun refererFor(host: String): String? =
        REFERERS.firstOrNull { host == it.first || host.endsWith("." + it.first) }?.second

    /**
     * 图片魔数校验：不是图片一律判失败（回落既有链路），
     * 绝不把 HTML / 拦截页交给解码器（否则画面会直接黑掉）。
     */
    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        return imageContentTypeOf(bytes) != null
    }

    /**
     * 钩子实现：受保护/静态图片域的 H3+ECH 拉取。
     *
     * 非 https / 无 host / 负缓存命中 / ECH 与地址取数失败 / 握手失败 / 非图片响应
     * 一律返回 null —— 调用方（Sketch）原样回落既有 HTTP 栈，用户无感。
     *
     * JNI 里是阻塞的 QUIC 收发（最长 ~25s），显式丢到 IO 线程，绝不占调用方线程。
     */
    private suspend fun fetchImageBytes(url: String): ByteArray? =
        withContext(Dispatchers.IO) { fetchImageBytesBlocking(url) }

    private fun fetchImageBytesBlocking(url: String): ByteArray? {
        if (!url.startsWith("https://")) return null
        val context = appContext ?: return null
        val uri = try {
            URI(url)
        } catch (_: Throwable) {
            return null
        }
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        if (!shouldTryH3(context, host)) return null

        val ip = runCatching { EchDoh.fetchA(host).firstOrNull()?.hostAddress }.getOrNull()
        if (ip.isNullOrEmpty()) {
            rememberH3(context, host, false, "DoH 未解析出 IP")
            return null
        }
        // ECH 策略与 TCP 链路保持一致（ConscryptEch.BgmPolicy 只对 bgm 域强制 ECH，其余 DISABLED）：
        // 该发 ECH 的域必须带 ECH，不该发的域绝不硬塞（对不支持 ECH 的服务端会直接把握手打死）。
        val ech = if (isBgmHost(host)) {
            runCatching { EchDoh.fetchEch(host) }.getOrNull()
        } else {
            null
        }
        val pathWithQuery = buildString {
            append(uri.rawPath?.ifEmpty { "/" } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        // 必须保留原扩展名：avif/webp 等由扩展名影响解码器选择，统一叫 .bin 会黑屏。
        val ext = (uri.rawPath ?: "").substringAfterLast('.', "").take(5)
            .filter { it.isLetterOrDigit() }
            .ifEmpty { "bin" }
        val out = File(context.cacheDir, "h3-" + System.nanoTime() + "." + ext)
        val file = fetchToFile(context, host, ip, ech, pathWithQuery, refererFor(host), out)
        if (file == null) {
            rememberH3(context, host, false, "取回失败（ech=${ech?.size ?: 0}B, ip=$ip）")
            out.delete()
            return null
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
        file.delete()
        if (bytes == null || !looksLikeImage(bytes)) {
            rememberH3(context, host, false, "返回的不是图片或读取失败（${bytes?.size ?: 0}B）")
            return null
        }
        rememberH3(context, host, true)
        return bytes
    }

    /**
     * 走 H3+ECH 拉一个静态资源并落盘。
     * @return 成功返回文件；**任何失败都返回 null**（调用方回落 TCP+ECH 链路，保持 fail-closed）
     */
    private fun fetchToFile(
        context: Context,
        host: String,
        ip: String,
        ech: ByteArray?,
        pathWithQuery: String,
        referer: String?,
        out: File,
    ): File? {
        if (!ensureLoaded()) return null
        val echB64 = ech?.takeIf { it.isNotEmpty() }?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: ""
        val json = try {
            h3Fetch(host, ip, echB64, pathWithQuery, referer ?: "", caBundlePath(context), out.absolutePath)
        } catch (t: Throwable) {
            EchLog.log("H3 调用异常：${t.message}")
            return null
        }
        val saved = try {
            lastJson = json
            JSONObject(json).optString("saved_to", "")
        } catch (_: Throwable) {
            ""
        }
        return if (saved.isNotEmpty() && !saved.startsWith("ERR:") && out.exists() && out.length() > 0) out else null
    }

    external fun h3Fetch(
        host: String,
        peerIp: String,
        echB64: String,
        path: String,
        referer: String,
        caPath: String,
        outFile: String,
    ): String
}
