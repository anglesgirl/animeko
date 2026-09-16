package me.him188.ani.android.ech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

// 原生登录：不走 WebView，直接用 ECH OkHttp 完成 Bangumi(Discuz) 授权链。
// GET 授权页(取 formhash+cookie) → POST 登录 → 302 到确认页 → GET 确认页(取 formhash) → POST 确认 → 302 到 animeko callback → 完成绑定。
// 全程 ECH 加密（bgm.tv 域），错误信息直接抛给 UI 显示，不再依赖页面 JS 劫持。
object BangumiNativeLogin {
    private const val UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // 提取第一个 name=xx value=yy 的 hidden input（顺序兼容 name/value 前后）
    private fun extractHidden(input: String): Pair<String, String>? {
        val name = Regex("""name=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(input)?.groupValues?.get(1)
            ?: return null
        val value = Regex("""value=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(input)?.groupValues?.get(1) ?: ""
        return name to value
    }

    // 提取页面第一个 formhash
    private fun findFormhash(html: String): String? {
        for (m in Regex("""<input[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val tag = m.value
            if (!tag.contains("formhash", ignoreCase = true)) continue
            val kv = extractHidden(tag) ?: continue
            if (kv.first.equals("formhash", true) && kv.second.isNotBlank()) return kv.second
        }
        return null
    }

    // 提取第一个 form 的 action（优先含 oauth/authorize 的表单）
    private fun findFormAction(html: String, pageUrl: String): String? {
        val forms = Regex("""<form[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()
        if (forms.isEmpty()) return null
        val pref = forms.firstOrNull { it.contains("oauth/authorize", true) || it.contains("FollowTheRabbit", true) }
            ?: forms.first()
        val action = Regex("""action=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(pref)?.groupValues?.get(1)
            ?: return pageUrl
        return runCatching { pageUrl.toHttpUrl().resolve(action).toString() }.getOrElse { action }
    }

    /**
     * @return 成功后返回最终回调 URL（已请求），失败抛异常
     */
    suspend fun login(authorizeUrl: String, email: String, password: String, onStep: (String) -> Unit = {}): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                // 1. GET 授权页（ECH，自动跟随 302 到登录页），拿 formhash + session cookie
                onStep("获取登录页")
                EchLog.log("NativeLogin 1 GET $authorizeUrl")
                val resp1 = EchHttp.get().newCall(Request.Builder().url(authorizeUrl).header("User-Agent", UA).build()).execute()
                val html1 = resp1.body?.string() ?: ""
                val pageUrl1 = resp1.request.url.toString()
                val code1 = resp1.code
                resp1.close()
                if (code1 != 200) return@withContext Result.failure(IllegalStateException("获取登录页失败: HTTP $code1"))
                val formhash = findFormhash(html1)
                    ?: return@withContext Result.failure(IllegalStateException("登录页未找到 formhash（可能已被风控/验证码拦截）"))
                // 诊断：登录页是否要求验证码（Discuz seccode / reCAPTCHA）
                val seccode = Regex("""(seccode|seccodehash|captcha|recaptcha|geetest)""", RegexOption.IGNORE_CASE).find(html1)?.value
                EchLog.log("NativeLogin 1 OK url=$pageUrl1 formhash=$formhash htmlLen=${html1.length} seccode=$seccode")

                // 2. POST 登录（Discuz FollowTheRabbit）
                onStep("提交账号密码")
                val loginBody = "formhash=$formhash&referer=${enc(pageUrl1)}&email=${enc(email)}&password=${enc(password)}&cookietime=2592000"
                EchLog.log("NativeLogin 2 POST FollowTheRabbit body=${loginBody.take(200)}")
                val resp2 = EchHttp.post().newCall(
                    Request.Builder().url("https://bgm.tv/FollowTheRabbit")
                        .post(loginBody.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
                        .header("User-Agent", UA)
                        .header("Referer", pageUrl1)
                        .header("Origin", "https://bgm.tv")
                        .build()
                ).execute()
                val loc2 = resp2.header("Location")
                val code2 = resp2.code
                val body2 = resp2.body?.string() ?: ""
                resp2.close()
                if (code2 != 302 || loc2.isNullOrBlank()) {
                    // Discuz/Bangumi 错误提示常见结构：<div class="alert alert-error">..</div> / <div class="msg">..</div> / <div id="message">..</div>
                    val hint = Regex("""<(?:div|p|span|h\d)[^>]*(?:class|id)=["'][^"']*(?:alert|error|notice|flash|msg|message|tip)[^"']*["'][^>]*>\s*([^<]{1,120})""", RegexOption.IGNORE_CASE)
                        .find(body2)?.groupValues?.get(1)?.trim()
                    val hint2 = hint ?: Regex("""<p[^>]*>\s*([^<]{2,80}(?:失败|错误|不正确|错误|验证码|输入|错误信息)[^<]{0,80})\s*</p>""", RegexOption.IGNORE_CASE)
                        .find(body2)?.groupValues?.get(1)?.trim()
                    val msg = hint2 ?: "登录失败: HTTP $code2（可能账号密码错误或需要验证码）"
                    EchLog.log("NativeLogin 2 FAIL code=$code2 hint=$hint2 bodyLen=${body2.length} body=${body2.take(1500)}")
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                val confirmUrl = runCatching { "https://bgm.tv".toHttpUrl().resolve(loc2).toString() }.getOrElse { loc2 }
                EchLog.log("NativeLogin 2 OK -> $confirmUrl")

                // 3. GET 确认页
                onStep("读取授权确认页")
                val resp3 = EchHttp.get().newCall(Request.Builder().url(confirmUrl).header("User-Agent", UA).build()).execute()
                val html3 = resp3.body?.string() ?: ""
                val pageUrl3 = resp3.request.url.toString()
                val code3 = resp3.code
                resp3.close()
                if (code3 != 200) return@withContext Result.failure(IllegalStateException("授权确认页失败: HTTP $code3"))
                val confirmFormhash = findFormhash(html3)
                val formAction = findFormAction(html3, pageUrl3)
                EchLog.log("NativeLogin 3 OK url=$pageUrl3 formhash=$confirmFormhash action=$formAction htmlLen=${html3.length}")

                // 4. POST 确认（Discuz 确认表单：formhash + hidden 字段 + submit）
                onStep("确认授权")
                val hiddenFields = Regex("""<input[^>]*type=["']hidden["'][^>]*>""", RegexOption.IGNORE_CASE)
                    .findAll(html3).mapNotNull { extractHidden(it.value) }.filter { it.first.isNotBlank() }.toList()
                val confirmBody = buildString {
                    val seen = mutableSetOf<String>()
                    if (confirmFormhash != null && seen.add("formhash")) append("formhash=$confirmFormhash&")
                    for ((n, v) in hiddenFields) {
                        if (seen.add(n)) append("${enc(n)}=${enc(v)}&")
                    }
                    if (seen.add("submit")) append("submit=${enc("同意")}")
                }
                val actionUrl = formAction ?: pageUrl3
                EchLog.log("NativeLogin 4 POST $actionUrl body=${confirmBody.take(300)}")
                val resp4 = EchHttp.post().newCall(
                    Request.Builder().url(actionUrl)
                        .post(confirmBody.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
                        .header("User-Agent", UA)
                        .header("Referer", pageUrl3)
                        .header("Origin", "https://bgm.tv")
                        .build()
                ).execute()
                val loc4 = resp4.header("Location")
                val code4 = resp4.code
                val body4 = resp4.body?.string() ?: ""
                resp4.close()
                if (code4 != 302 || loc4.isNullOrBlank()) {
                    EchLog.log("NativeLogin 4 FAIL code=$code4 loc=$loc4 bodyLen=${body4.length} body=${body4.take(120)}")
                    return@withContext Result.failure(IllegalStateException("授权确认失败: HTTP $code4（${body4.take(120)}）"))
                }
                val cbUrl = runCatching { "https://bgm.tv".toHttpUrl().resolve(loc4).toString() }.getOrElse { loc4 }
                EchLog.log("NativeLogin 4 OK -> $cbUrl")

                // 5. 请求回调 URL（animeko.org 服务器完成绑定，App 现有 OAuthConfigurator 会通过轮询收到）
                onStep("完成授权")
                val resp5 = EchHttp.get().newCall(Request.Builder().url(cbUrl).header("User-Agent", UA).build()).execute()
                val code5 = resp5.code
                resp5.close()
                EchLog.log("NativeLogin 5 OK callback=$cbUrl code=$code5")
                Result.success(cbUrl)
            } catch (e: Exception) {
                EchLog.log("NativeLogin ERR ${e.javaClass.simpleName}: ${e.message}")
                Result.failure(e)
            }
        }
}
