package me.him188.ani.utils.ktor

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.InternalAPI
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import me.him188.ani.utils.logging.logger

// Bangumi 系域名 ECH：通用插件 + 分平台实现。
// 受保护域名只走 ECH，失败抛异常绝不放行明文；非受保护域名原样走引擎。
// Android 用预编译库真握手；桌面/iOS 现阶段直通（桌面 ECH 排下一件）。

internal object BgmEchHosts {
    private val protectedHosts: Set<String> = setOf(
        "api.bgm.tv",
        "next.bgm.tv",
        "bgm.tv",
    )

    fun isProtected(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return protectedHosts.any { h == it || h.endsWith(".$it") }
    }
}

internal class BgmEchResult(
    val status: Int,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
)

expect object BgmEchFetch {
    // 非受保护域名或本平台不支持 → 返回 null，走正常链路。
    // 受保护但拿不到配置/握手失败 → 抛 IOException（fail-closed）。
    suspend fun fetchIfProtected(
        url: String,
        method: HttpMethod,
        headers: Map<String, String>,
        body: ByteArray?,
    ): BgmEchResult?
}

private val echLogger = logger("BgmEch")

@OptIn(InternalAPI::class)
internal fun HttpClient.installBgmEch() {
    val client = this
    plugin(HttpSend).intercept { request: HttpRequestBuilder ->
        val host = request.url.host.lowercase().trimEnd('.')
        if (!BgmEchHosts.isProtected(host)) {
            return@intercept execute(request)
        }
        val headers = request.headers.entries()
            .flatMap { (k, v) -> v.map { k to it } }
            .filterNot { (k, _) -> k.equals(HttpHeaders.Host, true) || k.equals(HttpHeaders.ContentLength, true) }
            .toMap()
        val bodyBytes = requestBodyBytes(request.body, host)
        val r = BgmEchFetch.fetchIfProtected(request.url.toString(), request.method, headers, bodyBytes)
            ?: throw IOException("ECH 未覆盖 $host，拒绝明文")
        echLogger.info { "ECH $host ${request.method.value} -> ${r.status}" }
        HttpClientCall(
            client,
            request.build(),
            HttpResponseData(
                HttpStatusCode(r.status, ""),
                GMTDate(),
                Headers.build {
                    r.headers.forEach { (k, v) -> append(k, v) }
                },
                HttpProtocolVersion.HTTP_1_1,
                ByteReadChannel(r.body),
                client.coroutineContext,
            ),
        )
    }
}

private suspend fun requestBodyBytes(content: OutgoingContent, host: String): ByteArray? {
    return when (content) {
        is OutgoingContent.NoContent -> null
        is OutgoingContent.ByteArrayContent -> content.bytes()
        is OutgoingContent.ReadChannelContent -> {
            content.readFrom().readRemaining().readByteArray()
        }
        else -> throw IOException("ECH 不支持该请求体类型($host)，拒绝明文")
    }
}
