package ai.closepaw.voice.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import java.text.Collator
import java.util.Locale

/**
 * Installed-app discovery and launching.
 *
 * Android 11+ hides other packages unless the app declares visibility, so the manifest carries
 * `<queries>` for launcher intents (plus QUERY_ALL_PACKAGES in debug builds). Everything else —
 * names, package names, Chinese aliases — is resolved at runtime; nothing is hard-coded, so
 * "打开计算器" works through the same path as "打开微信".
 *
 * Alias table lives in `files/app_aliases.json` so a user can map 微信 → com.tencent.mm without a
 * rebuild.
 */
object AppRegistry {
    private const val TAG = "AppRegistry"

    data class App(val label: String, val packageName: String, val aliases: List<String>)

    @Volatile private var cache: List<App> = emptyList()
    @Volatile private var cachedAt = 0L
    private const val TTL_MS = 60_000L

    fun apps(ctx: Context, force: Boolean = false): List<App> {
        val now = System.currentTimeMillis()
        if (!force && cache.isNotEmpty() && now - cachedAt < TTL_MS) return cache
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())
        val aliases = aliasTable(ctx)
        val list = resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == "ai.closepaw") return@mapNotNull null
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            App(label, pkg, aliases[label] ?: aliases[pkg] ?: emptyList())
        }.distinctBy { it.packageName }
        cache = list
        cachedAt = now
        Log.i(TAG, "discovered ${list.size} launchable apps")
        return list
    }

    /** Match by exact label, then alias, then substring — Chinese and English both supported. */
    fun find(ctx: Context, spoken: String): App? {
        val q = normalize(spoken)
        if (q.isBlank()) return null
        val all = apps(ctx)
        val byExact = all.firstOrNull { normalize(it.label) == q }
        if (byExact != null) return byExact
        val byAlias = all.firstOrNull { a -> a.aliases.any { normalize(it) == q } }
        if (byAlias != null) return byAlias
        // "微信" vs "WeChat", "计算器" vs "Calculator": compare both label and package tail
        val byContains = all.firstOrNull { normalize(it.label).contains(q) || q.contains(normalize(it.label)) }
        if (byContains != null) return byContains
        return all.firstOrNull { it.packageName.lowercase().contains(q) }
    }

    fun launch(ctx: Context, app: App): ToolResult {
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(app.packageName)
            ?: pm.getLeanbackLaunchIntentForPackage(app.packageName)
        if (intent == null) {
            return ToolResult.unavailable("app", "${app.label}没有可以启动的入口", app.packageName)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            // Real verification happens a moment later: does that package own the foreground?
            val ok = UiAutomator.waitForPackage(ctx, app.packageName, 2500)
            ToolResult(
                if (ok) ToolStatus.SUCCESS else ToolStatus.TIMEOUT,
                "app",
                if (ok) "已打开${app.label}" else "已经请求打开${app.label}，但没确认到它到了前台",
                "pkg=${app.packageName} foreground=$ok",
                verified = ok,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "launch failed", t)
            ToolResult.error("app", "打不开${app.label}", t.javaClass.simpleName)
        }
    }

    fun listSpeech(ctx: Context): String {
        val names = apps(ctx).map { it.label }.sortedWith(Collator.getInstance(Locale.CHINA))
        return if (names.isEmpty()) "没有扫描到可以打开的应用" else "可以打开：" + names.take(12).joinToString("、")
    }

    private fun normalize(s: String): String =
        s.trim().lowercase().replace(" ", "").removeSuffix("app").removeSuffix("应用")

    private fun aliasTable(ctx: Context): Map<String, List<String>> {
        val f = listOfNotNull(
            java.io.File(ctx.filesDir, "app_aliases.json"),
            ctx.getExternalFilesDir(null)?.let { java.io.File(it, "app_aliases.json") },
        ).firstOrNull { it.exists() } ?: return emptyMap()
        return runCatching {
            val root = org.json.JSONObject(f.readText())
            root.keys().asSequence().associateWith { k ->
                val v = root.get(k)
                if (v is org.json.JSONArray) (0 until v.length()).map { v.getString(it) } else listOf(v.toString())
            }
        }.getOrElse { emptyMap() }
    }
}
