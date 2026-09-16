package me.him188.ani.android.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

// 站内浏览器：Bangumi 授权页内部打开，避免外部浏览器 SNI 明文。
// 仅 GET 走 ECH 拦截，POST 表单让页面自己提交（原外部浏览器同款行为，不引入本地代理）。
class EchInternalBrowserActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "url"
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(me.him188.ani.android.ech.EchJsBridge { url ->
                runOnUiThread { loadUrl(url) }
            }, "EchBridge")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    // 劫提交与 fetch/XHR，不碰输入
                    view.evaluateJavascript("""
                        (function(){
                          if(window.__echHooked) return; window.__echHooked=true;
                          const toAbs=(u)=>{ try{return new URL(u, location.href).href;}catch(_){return u;}};
                          const isBgm=(u)=> u.indexOf('bgm.tv')!=-1 || u.indexOf('bangumi.tv')!=-1;
                          const origFetch=window.fetch;
                          window.fetch=function(input, init){
                            try{
                              const u=typeof input==='string'? input : input.url;
                              const abs=toAbs(u);
                              const m=(init&&init.method||'GET').toUpperCase();
                              if(m==='POST' && isBgm(abs)){
                                const body=init&&init.body? (typeof init.body==='string'? init.body : '') : '';
                                const ct=init&&init.headers? (init.headers['Content-Type']||init.headers['content-type']||'') : '';
                                const ret=EchBridge.postForm(abs, body, ct);
                                try{ const j=JSON.parse(ret); if(j.location){ location.href=j.location; return Promise.resolve(new Response('',{status:j.code})); } return Promise.resolve(new Response(j.body||'',{status:j.code, headers:{'Content-Type':'text/html'}})); }catch(_){}
                              }
                            }catch(_){}
                            return origFetch.apply(this, arguments);
                          };
                          const origOpen=XMLHttpRequest.prototype.open, origSend=XMLHttpRequest.prototype.send;
                          XMLHttpRequest.prototype.open=function(m,u){ this._echM=m; this._echU=toAbs(u); return origOpen.apply(this, arguments); };
                          XMLHttpRequest.prototype.send=function(b){
                            if(this._echM==='POST' && isBgm(this._echU)){
                              const body=b? String(b): '';
                              const ret=EchBridge.postForm(this._echU, body, '');
                              try{ const j=JSON.parse(ret); if(j.location){ location.href=j.location; return; } }catch(_){}
                              Object.defineProperty(this,'readyState',{value:4}); Object.defineProperty(this,'status',{value:200});
                              this.dispatchEvent(new Event('load')); this.dispatchEvent(new Event('loadend')); return;
                            }
                            return origSend.apply(this, arguments);
                          };
                          const origSubmit=HTMLFormElement.prototype.submit;
                          HTMLFormElement.prototype.submit=function(){
                            try{
                              const a=this.action||location.href;
                              const abs=toAbs(a);
                              if(isBgm(abs)){
                                const fd=new FormData(this);
                                const ps=new URLSearchParams(fd).toString();
                                const ct=this.enctype||'application/x-www-form-urlencoded';
                                const ret=EchBridge.postForm(abs, ps, ct);
                                try{ const j=JSON.parse(ret); if(j.location){ location.href=j.location; return; } if(j.body!=undefined){ document.open(); document.write(j.body); document.close(); return; } }catch(_){}
                                return;
                              }
                            }catch(_){}
                            return origSubmit.apply(this, arguments);
                          };
                          document.addEventListener('submit', function(e){
                            const f=e.target; if(!(f instanceof HTMLFormElement)) return;
                            const a=f.action||location.href;
                            if(a.indexOf('bgm.tv')==-1 && a.indexOf('bangumi.tv')==-1) return;
                            e.preventDefault();
                            const fd=new FormData(f);
                            const ps=new URLSearchParams(fd).toString();
                            const ct=f.enctype||'application/x-www-form-urlencoded';
                            const abs=toAbs(a);
                            const ret=EchBridge.postForm(abs, ps, ct);
                            try{ const j=JSON.parse(ret); if(j.location){ location.href=j.location; return; } if(j.body!=undefined){ document.open(); document.write(j.body); document.close(); } }catch(_){}
                          }, true);
                        })();
                    """.trimIndent(), null)
                }

                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                    val url = req.url.toString()
                    val host = req.url.host ?: return null
                    val isBgm = host == "bgm.tv" || host.endsWith(".bgm.tv") ||
                        host == "bangumi.tv" || host.endsWith(".bangumi.tv")
                    if (!isBgm) return null
                    if (req.method != "GET" && req.method != "HEAD") return null
                    return try {
                        val headers = req.requestHeaders ?: emptyMap()
                        val cookie = CookieManager.getInstance().getCookie(url)
                        val allHeaders = if (!cookie.isNullOrBlank() && !headers.keys.any { it.equals("Cookie", true) }) {
                            headers + ("Cookie" to cookie)
                        } else headers
                        val rb = okhttp3.Request.Builder().url(url).get()
                        allHeaders.forEach { (k, v) -> if (!k.equals("Host", true)) rb.header(k, v) }
                        val resp = me.him188.ani.android.ech.EchHttp.get().newCall(rb.build()).execute()
                        val body = resp.body?.bytes() ?: ByteArray(0)
                        val ct = resp.header("Content-Type") ?: "text/html"
                        val mime = ct.substringBefore(";").trim().ifBlank { "text/html" }
                        val enc = Regex("charset=([^;\\s\"']+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1) ?: "utf-8"
                        for (sc in resp.headers("Set-Cookie")) {
                            runCatching { CookieManager.getInstance().setCookie(url, sc) }
                        }
                        runCatching { CookieManager.getInstance().flush() }
                        WebResourceResponse(mime, enc, resp.code, "OK", resp.headers.toMultimap().mapValues { it.value.firstOrNull() ?: "" }.filterKeys { !it.equals("Content-Type", true) }, java.io.ByteArrayInputStream(body))
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
        setContentView(wv)
        wv.loadUrl(target)
    }
}
