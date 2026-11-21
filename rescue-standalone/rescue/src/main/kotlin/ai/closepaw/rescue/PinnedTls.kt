package ai.closepaw.rescue

import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Certificate pinning for the self-signed LAN relay.
 *
 * Accepts exactly one certificate whose SHA-256 (hex, lowercase, no colons) equals the pin
 * baked into the build. No global trust anchors are consulted, so a hostile CA cannot MITM
 * the relay; rotating the relay certificate requires rebuilding the app on purpose.
 */
object PinnedTls {
    private var cached: SSLSocketFactory? = null
    private var cachedPin: String = ""

    fun factory(pinHex: String): SSLSocketFactory {
        val norm = pinHex.replace(":", "").lowercase()
        synchronized(this) {
            if (cached != null && cachedPin == norm) return cached!!
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val cert = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("no cert")
                    val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    val hex = digest.joinToString("") { "%02x".format(it) }
                    if (hex != norm) throw java.security.cert.CertificateException("pin mismatch: $hex")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(tm), java.security.SecureRandom())
            cached = ctx.socketFactory
            cachedPin = norm
            return ctx.socketFactory
        }
    }
}
