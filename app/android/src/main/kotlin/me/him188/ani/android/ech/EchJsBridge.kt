package me.him188.ani.android.ech

import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// 供 JS 调用的桥：表单 POST 走 ECH（不跟随 302，手动取 Location 交给页面继续跳转）。
// 只处理 bgm.tv/bangumi.tv 系（Kotlin 侧双重校验，杜绝 GA 等第三方 POST 被劫持）。
// 补 Referer/Origin 头：Bangumi(Discuz) 登录 POST 防 CSRF 校验必需，缺了会直接返回登录页。
class EchJsBridge(private val onNavigate: (String) -> Unit) {

    @JavascriptInterface
    fun postForm(url: String, body: String, contentType: String?, referer: String?): String {
        val host = runCatching { HttpUrl.get(url).host }.getOrNull()
        val isBgm = host == "bgm.tv" || host?.endsWith(".bgm.tv") == true ||
            host == "bangumi.tv" || host?.endsWith(".bangumi.tv") == true
        if (!isBgm) {
            EchLog.log("postForm SKIP 非bgm域名 $url")
            return """{"code":502,"error":"not-bgm-host"}"""
        }
        val cookie = CookieManager.getInstance().getCookie(url)
        EchLog.log("postForm -> $url ct=$contentType bodyLen=${body.length} cookie=${cookie?.take(100)} body=${body.take(150)}")
        return try {
            val ct = contentType ?: "application/x-www-form-urlencoded"
            val rb = Request.Builder().url(url)
                .post(body.toByteArray(Charsets.UTF_8).toRequestBody(ct.toMediaTypeOrNull()))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36")
            // Discuz 防 CSRF：Referer/Origin 必须带上
            if (!referer.isNullOrBlank()) {
                rb.header("Referer", referer)
                runCatching {
                    val r = HttpUrl.get(referer)
                    rb.header("Origin", "${r.scheme}://${r.host}")
                }
            }
            val resp = EchHttp.post().newCall(rb.build()).execute()
            for (sc in resp.headers("Set-Cookie")) {
                runCatching { CookieManager.getInstance().setCookie(url, sc) }
            }
            runCatching { CookieManager.getInstance().flush() }
            val code = resp.code
            // 302 的 Location 可能是相对路径，解析成绝对 URL 再交给 JS/onNavigate
            val loc: String? = resp.header("Location")?.let { locStr ->
                runCatching { resp.request.url.resolve(locStr).toString() }.getOrElse { locStr }
            }
            val respBody = resp.body?.string() ?: ""
            resp.close()
            EchLog.log("postForm <- $url code=$code loc=$loc respLen=${respBody.length} head=${respBody.take(500)}")
            if (loc != null && code in 300..399) {
                onNavigate(loc)
                """{"code":$code,"location":"$loc"}"""
            } else {
                // 返回页面内容，JS 侧替换 document
                """{"code":$code,"body":${org.json.JSONObject.quote(respBody)}}"""
            }
        } catch (e: Exception) {
            EchLog.log("postForm ERR $url: ${e.javaClass.simpleName}: ${e.message}")
            """{"code":502,"error":${org.json.JSONObject.quote(e.message ?: "fail")}}"""
        }
    }
}
