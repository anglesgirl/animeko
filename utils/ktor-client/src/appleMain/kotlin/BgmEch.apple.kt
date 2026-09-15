package me.him188.ani.utils.ktor

import io.ktor.http.HttpMethod

// iOS 直通：iOS 走 Go 代理路线，不在此处处理。
actual object BgmEchFetch {
    actual suspend fun fetchIfProtected(
        url: String,
        method: HttpMethod,
        headers: Map<String, String>,
        body: ByteArray?,
    ): BgmEchResult? = null
}
