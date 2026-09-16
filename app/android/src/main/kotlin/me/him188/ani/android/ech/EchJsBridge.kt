package me.him188.ani.android.ech

import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// 供 JS 调用的桥：表单 POST 走 ECH，不碰页面结构，输入不卡。
class EchJsBridge(private val onNavigate: (String) -> Unit) {

    @JavascriptInterface
    fun postForm(url: String, body: String, contentType: String?): String {
        return try {
            val ct = contentType ?: "application/x-www-form-urlencoded"
            val cookie = CookieManager.getInstance().getCookie(url)
            val req = Request.Builder().url(url)
                .post(body.toByteArray(Charsets.UTF_8).toRequestBody(ct.toMediaTypeOrNull()))
                .apply {
                    if (!cookie.isNullOrBlank()) header("Cookie", cookie)
                    header("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36")
                }.build()
            val resp = EchHttp.get().newCall(req).execute()
            for (sc in resp.headers("Set-Cookie")) {
                runCatching { CookieManager.getInstance().setCookie(url, sc) }
            }
            runCatching { CookieManager.getInstance().flush() }
            val loc = resp.header("Location")
            val code = resp.code
            val respBody = resp.body?.string() ?: ""
            resp.close()
            if (loc != null && (code in 300..399)) {
                // 通知外层跳转，JS 侧收到后 window.location
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
