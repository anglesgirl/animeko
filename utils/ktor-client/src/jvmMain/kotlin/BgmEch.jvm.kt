package me.him188.ani.utils.ktor

import io.ktor.http.HttpMethod

// 桌面 JVM 直通：桌面 ECH 排下一件，本件不断桌面链路。
actual object BgmEchFetch {
    actual suspend fun fetchIfProtected(
        url: String,
        method: HttpMethod,
        headers: Map<String, String>,
        body: ByteArray?,
    ): BgmEchResult? = null
}
