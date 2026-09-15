package me.him188.ani.android.ech

import org.conscrypt.Conscrypt
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

// 逐 host 注入 ECH 配置的套接字工厂（OkHttp 走带 host 的重载）。
internal class EchSocketFactory(
    private val delegate: SSLSocketFactory,
    private val configFor: (String) -> ByteArray,
) : SSLSocketFactory() {
    private fun inject(s: Socket, host: String?): Socket {
        if (s is SSLSocket && host != null) {
            Conscrypt.setEchConfigList(s, configFor(host))
        }
        return s
    }

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        inject(delegate.createSocket(s, host, port, autoClose), host)

    override fun createSocket(host: String, port: Int): Socket =
        inject(delegate.createSocket(host, port), host)

    override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket =
        inject(delegate.createSocket(h, p, l, lp), h)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        delegate.createSocket(host, port)

    override fun createSocket(a: InetAddress, p: Int, l: InetAddress, lp: Int): Socket =
        delegate.createSocket(a, p, l, lp)

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
}
