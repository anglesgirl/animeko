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

    override fun openBrowser(context: Context, url: String): OpenBrowserResult {
        // 统一走系统浏览器（Custom Tab）：
        // Bangumi OAuth 采用 animeko 官方后端模式——App 从 api.animeko.org 申请授权链接，
        // 浏览器打开 bgm.tv 由用户/浏览器原生完成登录+验证码+授权，302 回调 api.animeko.org 由后端绑定，
        // App 轮询 api.animeko.org 拿结果。全程 App 不直连 bgm.tv，不需要 ECH 站内浏览器。
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
 