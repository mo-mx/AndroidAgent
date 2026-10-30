package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.AppRegistry
import ai.closepaw.voice.tools.ToolResult
import ai.closepaw.voice.tools.ToolStatus
import ai.closepaw.voice.tools.UiAutomator
import android.content.Context
import android.util.Log

/**
 * App resolution and in-app navigation.
 *
 * Fixes two reported failures:
 *  - "打开应用商店" picked Google Play because the old matcher returned the first substring hit.
 *    Resolution is now: explicit alias → exact label → alias table → store preference → ask when
 *    several apps match equally well.
 *  - "打开微信扫一扫" only launched WeChat and still reported success. A feature request is now a
 *    multi-step plan (launch → wait for the UI → locate the control → click → verify the screen
 *    changed), and it only reports success if the last step is verified.
 */
object AppNavigator {
    private const val TAG = "AppNavigator"

    /** Things people say that are not the launcher label. Order matters: first match wins. */
    private val preferred: LinkedHashMap<String, String> = linkedMapOf(
        // Most specific first: "play商店" contains "商店", so the generic key must come last.
        "play商店" to "com.android.vending",
        "谷歌商店" to "com.android.vending",
        "谷歌市场" to "com.android.vending",
        "google play" to "com.android.vending",
        "googleplay" to "com.android.vending",
        "三星商店" to "com.sec.android.app.samsungapps",
        "galaxy store" to "com.sec.android.app.samsungapps",
        "应用商店" to "com.sec.android.app.samsungapps",
        "应用市场" to "com.sec.android.app.samsungapps",
        "软件商店" to "com.sec.android.app.samsungapps",
        "商店" to "com.sec.android.app.samsungapps",
        "微信扫一扫" to "com.tencent.mm",
        "支付宝" to "com.eg.android.AlipayGphone",
    )

    data class Resolution(val app: AppRegistry.App?, val ambiguity: String? = null, val why: String = "")

    fun resolve(ctx: Context, spoken: String): Resolution {
        val q = spoken.trim().lowercase().replace(" ", "")
        if (q.isBlank()) return Resolution(null, "要打开哪个应用？", "empty")

        // 1) explicit preference table (this is what makes 应用商店 → Galaxy Store deterministic)
        preferred.entries.firstOrNull { q.contains(it.key) }?.let { (key, pkg) ->
            val all = AppRegistry.apps(ctx)
            val app = all.firstOrNull { it.packageName == pkg }
            if (app != null) return Resolution(app, why = "preferred:$key→$pkg")
            Log.w(TAG, "preferred package $pkg for '$key' not installed")
        }

        // 2) exact label / alias, via the registry
        val all = AppRegistry.apps(ctx)
        val exact = all.filter {
            it.label.lowercase().replace(" ", "") == q ||
                it.aliases.any { a -> a.lowercase().replace(" ", "") == q }
        }
        if (exact.size == 1) return Resolution(exact[0], why = "exact-label")
        if (exact.size > 1) {
            // Same visible name in two profiles (dual messenger / work profile): ask.
            val labels = exact.joinToString("、") { "${it.label}(${it.packageName})" }
            return Resolution(null, "有多个同名应用：$labels，要打开哪一个？", "duplicate-label")
        }

        // 3) substring, but only when a single candidate remains
        val partial = all.filter {
            it.label.lowercase().contains(q) || q.contains(it.label.lowercase())
        }
        if (partial.size == 1) return Resolution(partial[0], why = "partial-label")
        if (partial.size > 1) {
            val names = partial.joinToString("、") { it.label }
            return Resolution(null, "匹配到多个应用：$names，要打开哪一个？", "multiple")
        }

        // 4) package-name match
        val byPkg = all.firstOrNull { it.packageName.lowercase().contains(q) }
        if (byPkg != null) return Resolution(byPkg, why = "package")

        return Resolution(null, "没有找到「$spoken」这个应用", "not-found")
    }

    /** Launch an app and confirm it reached the foreground. */
    fun open(ctx: Context, spoken: String): ToolResult {
        val r = resolve(ctx, spoken)
        r.ambiguity?.let {
            return ToolResult(ToolStatus.DEVICE_UNAVAILABLE, "app", it, r.why)
        }
        val app = r.app ?: return ToolResult.unavailable("app", "没有找到这个应用", r.why)
        return openResolved(ctx, app, r.why)
    }

    /**
     * Launch an already-resolved app. Split out so the semantic gate can compare the concrete
     * package before the launch happens instead of re-resolving (and possibly re-deciding) here.
     */
    fun openResolved(ctx: Context, app: AppRegistry.App, why: String): ToolResult {
        val res = AppRegistry.launch(ctx, app)
        if (res.ok) ActionMemory.record(Action.OPEN, Target.APP, mapOf("pkg" to app.packageName), res.speak)
        return ToolResult(res.status, "app", res.speak, "${res.detail} ($why)", res.verified)
    }

    /**
     * Multi-step: launch → wait for UI → find the feature control → click → verify the screen
     * changed. Reports failure if any step cannot be verified; never claims success after only
     * the launch.
     */
    fun openFeature(ctx: Context, appName: String, feature: String): ToolResult {
        val r = resolve(ctx, appName)
        r.ambiguity?.let { return ToolResult(ToolStatus.DEVICE_UNAVAILABLE, "app_feature", it, r.why) }
        val app = r.app
            ?: return ToolResult.unavailable("app_feature", "没有找到「$appName」", r.why)
        return openFeatureResolved(ctx, app, feature, r.why)
    }

    /** Same multi-step flow, for an app the caller already resolved. */
    fun openFeatureResolved(
        ctx: Context,
        app: AppRegistry.App,
        feature: String,
        why: String,
    ): ToolResult {
        val launch = AppRegistry.launch(ctx, app)
        if (!launch.ok) {
            return ToolResult(
                ToolStatus.TIMEOUT, "app_feature",
                "打不开${app.label}，所以到不了$feature", "${launch.detail} ($why)", false
            )
        }

        val before = UiAutomator.screenTexts(120).toSet()
        val control = UiAutomator.findNode(feature, 4000)
            ?: return ToolResult(
                ToolStatus.DEVICE_UNAVAILABLE, "app_feature",
                "打开了${app.label}，但界面上没有找到$feature",
                "app=${app.packageName} control-not-found", false
            )

        val clicked = UiAutomator.click(control)
        Log.i(TAG, "openFeature $feature clicked=$clicked")
        if (!clicked) {
            return ToolResult.error("app_feature", "找到了${feature}但点不动", "click failed")
        }

        // Verification: the page must actually change, and we must still be inside the app.
        Thread.sleep(1200)
        val after = UiAutomator.screenTexts(120).toSet()
        val fg = UiAutomator.foregroundPackage()
        val changed = (after - before).size >= 2
        val stillInside = fg == app.packageName
        return when {
            changed && stillInside -> ToolResult.ok(
                "app_feature", "已经进入${app.label}的$feature", verified = true,
                detail = "fg=$fg newTexts=${(after - before).take(4)}"
            )
            stillInside -> ToolResult(
                ToolStatus.TIMEOUT, "app_feature",
                "点了$feature，但界面没有明显变化，可能没进到那个页面",
                "no screen change", false
            )
            else -> ToolResult.error(
                "app_feature", "点${feature}之后跳出了${app.label}", "fg=$fg"
            )
        }
    }
}
