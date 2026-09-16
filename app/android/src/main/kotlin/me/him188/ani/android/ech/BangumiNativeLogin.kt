package me.him188.ani.android.ech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

// 原生登录：不走 WebView，直接用 ECH OkHttp 完成 Bangumi(Discuz) 授权链。
// 字段已按真实成功请求对齐：
//   登录 POST https://bgm.tv/FollowTheRabbit
//     body: formhash + referer + dreferer + email + password + captcha_challenge_field(图形验证码,必填) + cookietime
//   授权确认 POST https://bgm.tv/oauth/authorize?client_id=..&response_type=code&state=..  (POST 到 authorize URL 本身)
//     body: formhash + redirect_uri + client_id + submit=授权
//   302 → https://api.animeko.org/v2/users/bangumi/oauth/callback?code=..&state=..  → GET 完成绑定
// 全程 ECH 加密（bgm.tv 域），错误信息直接抛给 UI 显示。
object BangumiNativeLogin {
    private const val UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36"

    data class Prepare(
        val formhash: String,
        val pageUrl: String,          // 登录页最终 URL（作 referer）
        val captchaImage: ByteArray?, // null = 页面无需验证码（可能已登录）
        val clientId: String,
        val redirectUri: String,
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // 提取页面第一个 formhash
    private fun findFormhash(html: String): String? {
        for (m in Regex("""<input[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)) {
            val tag = m.value
            if (!tag.contains("formhash", ignoreCase = true)) continue
            val name = Regex("""name=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
                ?: continue
            if (!name.equals("formhash", true)) continue
            val value = Regex("""value=["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /**
     * 阶段一：GET 授权页（ECH），提取 formhash + 下载验证码图片 + 解析 client_id/redirect_uri。
     */
    suspend fun prepare(authorizeUrl: String, onStep: (String) -> Unit = {}): Result<Prepare> =
        withContext(Dispatchers.IO) {
            try {
                onStep("获取登录页")
                EchLog.log("NativeLogin prepare GET $authorizeUrl")
                val resp1 = EchHttp.get().newCall(Request.Builder().url(authorizeUrl).header("User-Agent", UA).build()).execute()
                val html1 = resp1.body?.string() ?: ""
                val pageUrl = resp1.request.url.toString()
                resp1.close()
                if (html1.isBlank()) return@withContext Result.failure(IllegalStateException("登录页为空"))

                val formhash = findFormhash(html1)
                    ?: return@withContext Result.failure(IllegalStateException("页面未找到 formhash"))
                EchLog.log("NativeLogin prepare OK url=$pageUrl formhash=$formhash htmlLen=${html1.length}")

                // 需要登录（页面含验证码/登录表单）才下载验证码图；已登录（确认页）跳过
                val needCaptcha = html1.contains("captcha_challenge_field", ignoreCase = true) ||
                    html1.contains("loginForm", ignoreCase = true)
                val captchaImage: ByteArray? = if (needCaptcha) {
                    try {
                        val ts = System.currentTimeMillis()
                        val n = (1..6).random()
                        val imgUrl = "https://bgm.tv/signup/captcha?$ts$n"
                        EchLog.log("NativeLogin captcha GET $imgUrl")
                        val imgResp = EchHttp.get().newCall(Request.Builder().url(imgUrl).header("User-Agent", UA).build()).execute()
                        val b = imgResp.body?.bytes()
                        imgResp.close()
                        EchLog.log("NativeLogin captcha OK size=${b?.size}")
                        b
                    } catch (e: Exception) {
                        EchLog.log("NativeLogin captcha ERR ${e.message}")
                        null
                    }
                } else null

                val q = runCatching { authorizeUrl.toHttpUrl() }.getOrNull()
                val clientId = q?.queryParameter("client_id") ?: ""
                val redirectUri = q?.queryParameter("redirect_uri") ?: ""
                Result.success(Prepare(formhash, pageUrl, captchaImage, clientId, redirectUri))
            } catch (e: Exception) {
                EchLog.log("NativeLogin prepare ERR ${e.javaClass.simpleName}: ${e.message}")
                Result.failure(e)
            }
        }

    /**
     * 阶段二：登录 + 授权确认 + 回调（一条龙，字段对齐真实成功请求）。
     * @param email 账号；password 密码；captcha 验证码（页面需要时必填）
     */
    suspend fun submit(
        p: Prepare,
        email: String,
        password: String,
        captcha: String,
        onStep: (String) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 1. 登录（未登录才需要；若页面无验证码说明已登录，直接跳确认）
            val needLogin = p.captchaImage != null
            var loginLoc: String? = null
            if (needLogin) {
                onStep("提交账号密码")
                val loginBody = "formhash=${p.formhash}&referer=${enc(p.pageUrl)}&dreferer=&email=${enc(email)}&password=${enc(password)}&captcha_challenge_field=${enc(captcha)}&cookietime=2592000"
                EchLog.log("NativeLogin 登录 POST FollowTheRabbit body=${loginBody.take(400)}")
                val resp2 = EchHttp.post().newCall(
                    Request.Builder().url("https://bgm.tv/FollowTheRabbit")
                        .post(loginBody.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
                        .header("User-Agent", UA)
                        .header("Referer", p.pageUrl)
                        .header("Origin", "https://bgm.tv")
                        .build()
                ).execute()
                val loc2 = resp2.header("Location")
                val code2 = resp2.code
                val body2 = resp2.body?.string() ?: ""
                resp2.close()
                if (code2 != 302 || loc2.isNullOrBlank()) {
                    val hint = Regex("""<(?:div|p|span|h\d)[^>]*(?:class|id)=["'][^"']*(?:alert|error|notice|flash|msg|message|tip)[^"']*["'][^>]*>\s*([^<]{1,120})""", RegexOption.IGNORE_CASE)
                        .find(body2)?.groupValues?.get(1)?.trim()
                    val msg = hint ?: "登录失败: HTTP $code2（账号密码错误或验证码不对）"
                    EchLog.log("NativeLogin 登录 FAIL code=$code2 hint=$hint body=${body2.take(1200)}")
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                loginLoc = loc2
                EchLog.log("NativeLogin 登录 OK -> $loc2")
            }

            // 2. GET 授权确认页（登录态下直接显示）
            onStep("读取授权确认页")
            val confirmUrl = if (loginLoc != null) {
                runCatching { "https://bgm.tv".toHttpUrl().resolve(loginLoc).toString() }.getOrElse { loginLoc!! }
            } else p.pageUrl
            val resp3 = EchHttp.get().newCall(Request.Builder().url(confirmUrl).header("User-Agent", UA).build()).execute()
            val html3 = resp3.body?.string() ?: ""
            val pageUrl3 = resp3.request.url.toString()
            resp3.close()
            val confirmFormhash = findFormhash(html3) ?: p.formhash
            EchLog.log("NativeLogin 确认页 OK url=$pageUrl3 formhash=$confirmFormhash htmlLen=${html3.length}")

            // 3. POST 授权确认（对齐真实请求：formhash+redirect_uri+client_id+submit=授权）
            onStep("确认授权")
            val confirmBody = "formhash=${confirmFormhash}&redirect_uri=${enc(p.redirectUri)}&client_id=${p.clientId}&submit=${enc("授权")}"
            EchLog.log("NativeLogin 确认 POST $pageUrl3 body=${confirmBody.take(300)}")
            val resp4 = EchHttp.post().newCall(
                Request.Builder().url(pageUrl3)
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
                EchLog.log("NativeLogin 确认 FAIL code=$code4 loc=$loc4 body=${body4.take(300)}")
                return@withContext Result.failure(IllegalStateException("授权确认失败: HTTP $code4"))
            }
            val cbUrl = runCatching { "https://bgm.tv".toHttpUrl().resolve(loc4).toString() }.getOrElse { loc4 }
            EchLog.log("NativeLogin 确认 OK -> $cbUrl")

            // 4. GET 回调（animeko 服务器完成绑定，App 轮询收到）
            onStep("完成授权")
            val resp5 = EchHttp.get().newCall(Request.Builder().url(cbUrl).header("User-Agent", UA).build()).execute()
            val code5 = resp5.code
            resp5.close()
            EchLog.log("NativeLogin 回调 OK code=$code5 url=$cbUrl")
            Result.success(cbUrl)
        } catch (e: Exception) {
            EchLog.log("NativeLogin submit ERR ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
        }
    }
}
