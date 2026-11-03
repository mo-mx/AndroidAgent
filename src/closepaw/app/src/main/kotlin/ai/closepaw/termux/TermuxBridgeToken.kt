package ai.closepaw.termux

import android.content.Context
import android.util.Log
import java.security.SecureRandom
import java.util.Base64

/**
 * Shared secret between the app and the Termux bridge daemon.
 *
 * The daemon runs a shell endpoint on `127.0.0.1:18422`. Loopback is shared by every app on the
 * device, so the endpoint cannot rely on the address for authorization: the token is what proves
 * the caller is this app. It is written into Termux-private storage (`~/.closepaw/token`, mode 600)
 * during deploy, which other apps cannot read, and it is persisted app-side so a daemon started in
 * an earlier process can still be reached.
 */
internal interface TermuxTokenStore {
    fun read(): String?
    fun write(token: String)
}

internal class PreferencesTokenStore(context: Context) : TermuxTokenStore {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String? =
        prefs.getString(KEY_TOKEN, null)?.takeIf(TermuxBridgeToken::isUsable)

    override fun write(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    private companion object {
        const val PREFS_NAME = "closepaw_bridge"
        const val KEY_TOKEN = "bridge_token"
    }
}

internal class InMemoryTokenStore(initial: String? = null) : TermuxTokenStore {
    private var token: String? = initial?.takeIf(TermuxBridgeToken::isUsable)

    override fun read(): String? = token

    override fun write(token: String) {
        this.token = token
    }
}

internal object TermuxBridgeToken {
    private const val TAG = "TermuxBridgeToken"
    private const val MIN_LENGTH = 32
    private const val TOKEN_BYTES = 32

    @Volatile
    private var current: String? = null

    /** The token the running process should present; null until a manager loaded or created one. */
    fun current(): String? = current

    fun publish(token: String) {
        if (isUsable(token)) current = token
    }

    fun clear() {
        current = null
    }

    /** Returns the stored token, creating and persisting one when the store has none. */
    fun ensure(store: TermuxTokenStore): String {
        val existing = store.read()?.takeIf(::isUsable)
        if (existing != null) {
            publish(existing)
            return existing
        }
        val created = generate()
        store.write(created)
        publish(created)
        return created
    }

    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * The token is interpolated into a Termux shell command and an HTTP header, so only the
     * base64url alphabet is accepted. A corrupted preference must not become shell syntax.
     */
    fun isUsable(token: String?): Boolean =
        token != null && token.length >= MIN_LENGTH && TOKEN_PATTERN.matches(token)

    private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]+$")

    /** The deploy step that installs the token next to the daemon, readable only by Termux. */
    fun tokenInstallCommand(token: String): String {
        if (!isUsable(token)) {
            Log.w(TAG, "Refusing to deploy a bridge token that is not base64url")
            throw IllegalArgumentException("bridge token is not base64url")
        }
        return "umask 077 && mkdir -p ~/.closepaw && " +
            "printf '%s' '$token' > ~/.closepaw/token && chmod 600 ~/.closepaw/token"
    }
}
