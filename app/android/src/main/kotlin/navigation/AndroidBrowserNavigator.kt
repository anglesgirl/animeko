/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.android.navigation

import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import me.him188.ani.app.navigation.BrowserNavigator
import me.him188.ani.app.navigation.OpenBrowserResult
import me.him188.ani.app.navigation.QQ_GROUP_JOIN_LINK
import me.him188.ani.app.platform.Context
import me.him188.ani.utils.logging.logger

class AndroidBrowserNavigator : BrowserNavigator {
    private val logger = logger<AndroidBrowserNavigator>()

    private fun isBgmUrl(url: String): Boolean {
        val h = runCatching { url.toUri().host?.lowercase()?.trimEnd('.') }.getOrNull() ?: return false
        return h == "bgm.tv" || h.endsWith(".bgm.tv") || h == "bangumi.tv" || h.endsWith(".bangumi.tv") ||
            h == "api.bgm.tv" || h.endsWith(".api.bgm.tv") || h == "next.bgm.tv" || h.endsWith(".next.bgm.tv")
    }

    override fun openBrowser(context: Context, url: String): OpenBrowserResult {
        // BGM 相关走站内 ECH 浏览器：用户在 WebView 里完成登录（含验证码），
        // POST 由 JS 劫持走 ECH 通道；授权完成后 302 回调 animeko 服务器自动绑定。
        // 外部浏览器 SNI 明文会被墙，不能直接用。
        if (isBgmUrl(url)) {
            try {
                val i = Intent(context, me.him188.ani.android.activity.EchInternalBrowserActivity::class.java).apply {
                    putExtra(me.him188.ani.android.activity.EchInternalBrowserActivity.EXTRA_URL, url)
                }
                context.startActivity(i)
                return OpenBrowserResult.Success
            } catch (e: Exception) {
                logger.warn("ECH 内置浏览器打开失败，回落外部", e)
            }
        }
        val lastEx: Exception

        try {
            launchChromeTab(context, url)
            return OpenBrowserResult.Success
        } catch (ex: Exception) {
            lastEx = ex
        }

        try {
            view(url, context)
            return OpenBrowserResult.Success
        } catch (ex: Exception) {
            val finalEx = ex.apply { addSuppressed(lastEx) }
            logger.warn("Failed to open browser", finalEx)
            return OpenBrowserResult.Failure(finalEx, url)
        }
    }

    private fun launchChromeTab(context: Context, url: String) {
        val intent = CustomTabsIntent.Builder().build()
        intent.launchUrl(context, url.toUri())
    }

    override fun intentActionView(context: Context, url: String): OpenBrowserResult {
        try {
            view(url, context)
            return OpenBrowserResult.Success
        } catch (ex: Exception) {
            logger.warn("Failed to open browser", ex)
            return OpenBrowserResult.Failure(ex, url)
        }
    }

    override fun intentOpenVideo(context: Context, url: String): OpenBrowserResult {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW)
                .apply { setDataAndType(url.toUri(), "video/*") }
            context.startActivity(Intent.createChooser(browserIntent, "选择播放器"))
            return OpenBrowserResult.Success
        } catch (ex: Exception) {
            logger.warn("Failed to open video", ex)
            return OpenBrowserResult.Failure(ex, url)
        }
    }

    private fun view(url: String, context: Context) {
        val browserIntent = Intent(Intent.ACTION_VIEW).apply {
            setData(url.toUri())
        }
        context.startActivity(browserIntent)
    }

    override fun openJoinGroup(context: Context): OpenBrowserResult {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW)
                .apply { setData(QQ_GROUP.toUri()) }
            context.startActivity(browserIntent)
            return OpenBrowserResult.Success
        } catch (ex: Exception) {
            logger.warn("Failed to open QQ", ex)
            return OpenBrowserResult.Failure(ex, QQ_GROUP_JOIN_LINK)
        }
    }
}

// https://qun.qq.com/#/handy-tool/join-group
private const val QQ_GROUP =
    "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D" + "oiWgOz87g6x4Eskej1Ja0bKWYyZR_dPO"
 