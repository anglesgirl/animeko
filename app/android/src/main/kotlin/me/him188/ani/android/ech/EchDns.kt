package me.him188.ani.android.ech

import okhttp3.Dns
import java.net.InetAddress

// 受保护域名只走 DoH，拿不到抛异常绝不回落系统。
internal object EchDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (!isBgmHost(hostname)) return Dns.SYSTEM.lookup(hostname)
        return EchDoh.fetchA(hostname.lowercase().trimEnd('.'))
    }
}

internal fun isBgmHost(hostname: String): Boolean {
    val h = hostname.lowercase().trimEnd('.')
    return h == "api.bgm.tv" || h.endsWith(".api.bgm.tv") ||
        h == "next.bgm.tv" || h.endsWith(".next.bgm.tv") ||
        h == "bgm.tv" || h.endsWith(".bgm.tv")
}
