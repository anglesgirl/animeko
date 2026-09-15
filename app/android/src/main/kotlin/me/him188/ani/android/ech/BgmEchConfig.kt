package me.him188.ani.android.ech

// Bangumi 系域名 ECH 配置：第一件只加文件，不切链路。
// api/next.bgm.tv 公网无 ech=（同 CF 机房），靠 .so 内共享配置回落做真 ECH（源码已核：ecl: 全量握手，非空转）。

internal object BgmEchConfig {
    val protectedHosts: Set<String> = setOf(
        "api.bgm.tv",
        "next.bgm.tv",
        "bgm.tv",
    )

    const val DOH_URL = "https://1.1.1.1/dns-query"

    // DoH 地址本身就是 IP 字面量，无需 bootstrap
    const val DOH_RESOLVE = ""

    fun isProtected(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return protectedHosts.any { h == it || h.endsWith(".$it") }
    }
}
