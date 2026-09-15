package me.him188.ani.android.ech

import org.conscrypt.Conscrypt
import org.conscrypt.DomainEncryptionMode
import org.conscrypt.NetworkSecurityPolicy
import org.conscrypt.metrics.CertificateTransparencyVerificationReason
import java.security.Security
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// Conscrypt ECH：策略 REQUIRED（无配置抛异常，绝不空转）+ 逐 host 注入配置。
internal object ConscryptEch {
    fun ensureProvider() {
        if (Security.getProvider("Conscrypt") == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
    }

    fun systemTrustManager(): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as java.security.KeyStore?)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    class BgmPolicy : NetworkSecurityPolicy {
        override fun isCertificateTransparencyVerificationRequired(hostname: String) = false
        override fun getCertificateTransparencyVerificationReason(hostname: String) =
            CertificateTransparencyVerificationReason.UNKNOWN

        override fun getDomainEncryptionMode(hostname: String): DomainEncryptionMode {
            val h = hostname.lowercase().trimEnd('.')
            val prot = h == "api.bgm.tv" || h.endsWith(".api.bgm.tv") ||
                h == "next.bgm.tv" || h.endsWith(".next.bgm.tv") ||
                h == "bgm.tv" || h.endsWith(".bgm.tv")
            return if (prot) DomainEncryptionMode.REQUIRED else DomainEncryptionMode.DISABLED
        }
    }

    class PolicyTrustManager(
        private val delegate: X509TrustManager,
    ) : X509TrustManager {
        private val policy = BgmPolicy()
        fun getNetworkSecurityPolicy(): NetworkSecurityPolicy = policy

        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) =
            delegate.checkClientTrusted(c, a)

        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) =
            delegate.checkServerTrusted(c, a)

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }
}
