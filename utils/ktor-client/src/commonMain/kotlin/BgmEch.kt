/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.ktor

import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readRemaining
import kotlinx.io.IOException
import kotlinx.io.readByteArray

// Bangumi 系域名 ECH：通用插件 + 可插拔传输。
// 受保护域名只走平台注入的传输，失败抛异常绝不放行明文；非受保护域名原样走引擎。
// 传输由平台方注入；未注入的平台走正常链路。

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

class BgmEchResult(
    val status: Int,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
)

object BgmEchTransport {
    // 平台注入的传输实现；null = 本平台暂不支持，走正常链路。
    var fetchHandler: (suspend (url: String, method: HttpMethod, headers: Map<String, String>, body: ByteArray?) -> BgmEchResult?)? = null
}

@OptIn(InternalAPI::class)
internal fun HttpClient.installBgmEch() {
    val client = this
    plugin(HttpSend).intercept { request: HttpRequestBuilder ->
        val host = request.url.host.lowercase().trimEnd('.')
        if (!BgmEchHosts.isProtected(host)) {
            return@intercept execute(request)
        }
        val handler = BgmEchTransport.fetchHandler
            ?: return@intercept execute(request)
        val headers = request.headers.entries()
            .flatMap { (k, v) -> v.map { k to it } }
            .filterNot { (k, _) -> k.equals(HttpHeaders.Host, true) || k.equals(HttpHeaders.ContentLength, true) }
            .toMap()
        val bodyBytes = requestBodyBytes(
            request.body as? OutgoingContent
                ?: throw IOException("ECH 请求体不可读($host)，拒绝明文"),
            host,
        )
        val r = handler(request.url.toString(), request.method, headers, bodyBytes)
            ?: throw IOException("ECH 未覆盖 $host，拒绝明文")
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
        is OutgoingContent.ReadChannelContent -> content.readFrom().readRemaining().readByteArray()
        else -> throw IOException("ECH 不支持该请求体类型($host)，拒绝明文")
    }
}
