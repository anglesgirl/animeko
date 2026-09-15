package me.him188.ani.android.ech

import com.liar.han1meplus.EchHttpClient
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

// 受保护域名经 ECH 库发请求；失败抛异常，绝不放行明文（fail-closed）。
// 本件只加文件不接线：接线（Ktor 引擎挂载 + Application 初始化）是下一件。
internal class BgmEchInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (!BgmEchConfig.isProtected(host)) return chain.proceed(request)
        if (!EchHttpClient.isLoaded) {
            throw IOException("ECH 未就绪($host)，拒绝明文")
        }

        val headerMap = LinkedHashMap<String, String>()
        for (i in 0 until request.headers.size) {
            val name = request.headers.name(i)
            if (name.equals("Host", true) || name.equals("Content-Length", true)) continue
            headerMap[name] = request.headers.value(i)
        }
        val bodyBytes: ByteArray? = request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readByteArray()
        }

        var lastError: IOException? = null
        repeat(2) { attempt ->
            try {
                val resp = EchHttpClient.execute(
                    request.method, request.url.toString(),
                    headerMap, bodyBytes,
                    BgmEchConfig.DOH_URL, BgmEchConfig.DOH_RESOLVE,
                )
                if (isEchRejected(resp.echStatus)) {
                    lastError = IOException("ECH 被拒(${resp.echStatus})")
                    Thread.sleep(300)
                    return@repeat
                }
                return buildResponse(chain, resp.statusCode, resp.headers, resp.body)
            } catch (e: IOException) {
                lastError = e
                Thread.sleep(300)
            }
        }
        throw lastError ?: IOException("ECH 请求失败($host)")
    }

    private fun isEchRejected(echStatus: String): Boolean {
        return echStatus.contains("REJECTED", true)
    }

    private fun buildResponse(
        chain: Interceptor.Chain,
        code: Int,
        headers: Map<String, List<String>>,
        body: ByteArray,
    ): Response {
        val builder = Headers.Builder()
        for ((name, values) in headers) {
            for (v in values) {
                runCatching { builder.add(name, v) }
            }
        }
        val hs = builder.build()
        val contentType = hs["Content-Type"]?.toMediaTypeOrNull()
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .headers(hs)
            .body(body.toResponseBody(contentType))
            .build()
    }
}
