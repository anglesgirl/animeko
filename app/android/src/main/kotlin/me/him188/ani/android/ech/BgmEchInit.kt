package me.him188.ani.android.ech

import android.content.Context
import android.util.Log
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import me.him188.ani.utils.ktor.BgmEchResult
import me.him188.ani.utils.ktor.BgmEchTransport
import me.him188.ani.utils.logging.logger
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Android 传输注入：在 Application.onCreate 调一次。
// Conscrypt 复用通道 + 自有 DoH，失败一律抛异常绝不放行明文。
object BgmEchInit {
    private const val TAG = "BGM-ECH"
    private val fileLogger = logger<BgmEchInit>()

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        Log.i(TAG, "install Conscrypt transport")
        fileLogger.info { "BGM-ECH install Conscrypt transport" }
        BgmEchTransport.fetchHandler = { url, method, headers, body ->
            fetch(url, method, headers, body)
        }
    }

    private suspend fun fetch(
        url: String,
        method: HttpMethod,
        headers: Map<String, String>,
        body: ByteArray?,
    ): BgmEchResult? = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        if (body == null && method == HttpMethod.Get) rb.get()
        else rb.method(method.value, body?.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
        val methodStr = method.value
        Log.i(TAG, "-> $methodStr $url")
        fileLogger.info { "BGM-ECH -> $methodStr $url" }
        try {
            EchHttp.get().newCall(rb.build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val hs = mutableListOf<Pair<String, String>>()
                for (i in 0 until resp.headers.size) hs.add(resp.headers.name(i) to resp.headers.value(i))
                Log.i(TAG, "<- ${resp.code} $url")
                fileLogger.info { "BGM-ECH <- ${resp.code} $url" }
                BgmEchResult(resp.code, hs, bytes)
            }
        } catch (e: Exception) {
            throw if (e is IOException) e else IOException("ECH 请求异常: ${e.message}")
        }
    }
}
