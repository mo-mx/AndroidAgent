package ai.closepaw.rescue

import android.content.Context
import android.os.Build
import org.json.JSONObject

/**
 * Signed HTTP client for the rescue relay.
 *
 * Direct-Boot safe: endpoint/secret/device id come from [RescueConfig] (Device Protected
 * Storage), never from credential-encrypted storage, so this works before the first unlock.
 *
 * Security model:
 *  - transport: TLS. Certificate pinning (SHA-256 of the DER cert) for the self-signed LAN
 *    relay; runtime endpoint overrides use the system trust store.
 *  - request auth: HMAC-SHA256(secret, ts + "\n" + path + "\n" + body) in X-Rescue-Sig, with
 *    ts in X-Rescue-Ts (relay rejects skew > 120 s). The secret never travels on the wire.
 *  - no inbound listener: the phone only ever makes outbound requests.
 */
class RescueClient(private val ctx: Context) {

    data class Reply(val code: Int, val body: String)

    private val endpoint: String = RescueConfig.endpoint(ctx)
    private val secret: String = RescueConfig.secret(ctx)
    private val pin: String = BuildConfig.RESCUE_PIN

    val deviceId: String by lazy { RescueConfig.deviceId(ctx) }

    val configured: Boolean get() = endpoint.isNotBlank() && secret.isNotBlank()

    fun isUserUnlockedNow(): Boolean = isUserUnlocked(ctx)

    fun poll(body: JSONObject): Reply = post("/poll", body.toString())
    fun result(body: JSONObject): Reply = post("/result", body.toString())

    private fun post(path: String, body: String): Reply {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val sig = hmacHex(secret, "$ts\n$path\n$body")
        val url = java.net.URL(endpoint + path)
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 35_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Rescue-Device", deviceId)
            setRequestProperty("X-Rescue-Ts", ts)
            setRequestProperty("X-Rescue-Sig", sig)
        }
        if (conn is javax.net.ssl.HttpsURLConnection && pin.isNotBlank() && isPinnedEndpoint()) {
            conn.sslSocketFactory = PinnedTls.factory(pin)
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            Reply(code, text)
        } catch (t: Throwable) {
            Reply(-1, t.message ?: t.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    /** Pinning only applies to the build-time relay; runtime overrides use system trust. */
    private fun isPinnedEndpoint(): Boolean =
        endpoint == BuildConfig.RESCUE_ENDPOINT.trimEnd('/')

    private fun hmacHex(key: String, msg: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** True while the user has not unlocked since boot (Direct Boot window). */
        fun isUserUnlocked(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
            val um = ctx.getSystemService(android.os.UserManager::class.java) ?: return true
            return um.isUserUnlocked
        }
    }
}
