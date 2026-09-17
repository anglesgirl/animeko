package me.him188.ani.android.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import me.him188.ani.android.ech.EchLocalProxy

// 站内登录浏览器：WebView 直接打开 http://127.0.0.1:8888/...（进程内 Conscrypt ECH 本地代理）。
// WebView 全原生运行页面（验证码/表单/JS 零劫持），Discuz 相对路径自动命中本地代理，
// 代理用 ECH 转发 bgm.tv；302 到 api.animeko.org 原样放行，WebView 原生跳转完成绑定。
class EchInternalBrowserActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "url"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        me.him188.ani.android.ech.EchLog.init(applicationContext)
        me.him188.ani.android.ech.EchLog.log("EchInternalBrowserActivity open: $target")
        if (!EchLocalProxy.start()) {
            me.him188.ani.android.ech.EchLog.log("本地代理启动失败，无法打开登录页")
            finish()
            return
        }
        // 把 https://bgm.tv 换成 http://127.0.0.1:8888（保留路径和 query）
        val localUrl = target
            .replace("https://bgm.tv", "http://127.0.0.1:${EchLocalProxy.PORT}")
            .replace("https://bangumi.tv", "http://127.0.0.1:${EchLocalProxy.PORT}")
        me.him188.ani.android.ech.EchLog.log("加载本地代理地址: $localUrl")
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url?.toString() ?: return false
                    // 跳到 api.animeko.org = 授权完成（302 放行原生）；其余(127.0.0.1)照常
                    if (u.contains("api.animeko.org")) {
                        me.him188.ani.android.ech.EchLog.log("授权回调: $u")
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    me.him188.ani.android.ech.EchLog.log("onPageFinished: $url")
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    me.him188.ani.android.ech.EchLog.log("WebView 错误 ${error.errorCode}: ${error.description}")
                }
            }
            loadUrl(localUrl)
        }
        setContentView(wv)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { EchLocalProxy.stop() }
    }
}
