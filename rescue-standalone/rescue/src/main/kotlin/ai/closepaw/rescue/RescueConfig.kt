package ai.closepaw.rescue

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File
import java.util.UUID

/**
 * Configuration that must survive into Direct Boot (before the user's first unlock).
 *
 * Everything here lives in **Device Protected Storage** (`createDeviceProtectedStorageContext`)
 * which is readable before unlock. Credential-encrypted storage (normal `filesDir`, shared
 * prefs, `Settings.Secure`) is NOT available pre-unlock, so nothing on the rescue path may
 * depend on it.
 *
 * What is stored: relay endpoint, a stable device id, and an optional secret override.
 * What is deliberately NOT stored: PIN/pattern, tokens, contacts, message content — the
 * rescue link only needs to know who it is and where to call home.
 */
object RescueConfig {
    private const val F_DEVICE_ID = "device_id.txt"
    private const val F_ENDPOINT = "endpoint.txt"
    private const val F_SECRET = "secret.txt"

    fun dpDir(ctx: Context): File {
        val dp = ctx.createDeviceProtectedStorageContext()
        return dp.filesDir
    }

    fun isDirectBootAvailable(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    fun endpoint(ctx: Context): String {
        val f = File(dpDir(ctx), F_ENDPOINT)
        val override = runCatching { if (f.exists()) f.readText().trim() else "" }.getOrDefault("")
        return (override.ifBlank { BuildConfig.RESCUE_ENDPOINT }).trimEnd('/')
    }

    fun secret(ctx: Context): String {
        val f = File(dpDir(ctx), F_SECRET)
        val override = runCatching { if (f.exists()) f.readText().trim() else "" }.getOrDefault("")
        return override.ifBlank { BuildConfig.RESCUE_SECRET }
    }

    /**
     * Stable device id. Generated once and kept in DP so it is identical before and after
     * unlock. On first run (post-install, usually unlocked) we seed it from ANDROID_ID so the
     * relay keeps recognising the same device as before Direct Boot support.
     */
    fun deviceId(ctx: Context): String {
        val f = File(dpDir(ctx), F_DEVICE_ID)
        val existing = runCatching { if (f.exists()) f.readText().trim() else "" }.getOrDefault("")
        if (existing.isNotBlank()) return existing

        val seeded = runCatching {
            val raw = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            if (raw.isNullOrBlank()) "" else "${Build.MODEL.replace(' ', '_')}-${raw.takeLast(8)}"
        }.getOrDefault("")
        val id = seeded.ifBlank { "rescue-${UUID.randomUUID().toString().take(8)}" }
        runCatching { f.writeText(id) }
        return id
    }

    /** Called on normal (unlocked) launch: seeds DP with the current build defaults. */
    fun ensureSeeded(ctx: Context) {
        val dir = dpDir(ctx)
        runCatching { dir.mkdirs() }
        val idFile = File(dir, F_DEVICE_ID)
        if (!idFile.exists()) deviceId(ctx)
        if (BuildConfig.RESCUE_ENDPOINT.isNotBlank()) {
            val ep = File(dir, F_ENDPOINT)
            if (!ep.exists()) ep.writeText(BuildConfig.RESCUE_ENDPOINT)
        }
    }

    fun describe(ctx: Context): String = buildString {
        append("endpoint=").append(endpoint(ctx).ifBlank { "<unset>" })
        append(", device=").append(deviceId(ctx))
        append(", dpDir=").append(dpDir(ctx).absolutePath)
    }
}
