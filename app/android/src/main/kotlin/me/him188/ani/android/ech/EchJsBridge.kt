package me.him188.ani.android.ech

import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// 供 JS 调用的桥：表单 POST 走 ECH（不跟随 302，手动取 Location 交给页面继续跳转），
// 不碰页面结构，输入不卡。Cookie 由 EchHttp 的 CookieJar 统一与 WebView 同步。
class EchJsBridge(private val onNavigate: (String) -> Unit) {

    @JavascriptInterface
    fun postForm(url: String, body: String, contentType: String?): String {
        EchLog.log("postForm -> $url ct=$contentType bodyLen=${body.length} body=${body.take(200)}")
        return try {
            val ct = contentType ?: "application/x-www-form-urlencoded"
            val req = Request.Builder().url(url)
                .post(body.toByteArray(Charsets.UTF_8).toRequestBody(ct.toMediaTypeOrNull()))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36")
                .build()
            val resp = EchHttp.post().newCall(req).execute()
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
            EchLog.log("postForm <- $url code=$code loc=$loc respLen=${respBody.length} head=${respBody.take(120)}")
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
