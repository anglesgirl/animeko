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
            val loc = resp.header("Location")?.let {
                runCatching { resp.request.url.resolve(it).toString() }.getOrElse { it }
            }
            val respBody = resp.body?.string() ?: ""
            resp.close()
            if (loc != null && code in 300..399) {
                onNavigate(loc)
                """{"code":$code,"location":"$loc"}"""
            } else {
                // 返回页面内容，JS 侧替换 document
                """{"code":$code,"body":${org.json.JSONObject.quote(respBody)}}"""
            }
        } catch (e: Exception) {
            """{"code":502,"error":${org.json.JSONObject.quote(e.message ?: "fail")}}"""
        }
    }
}
