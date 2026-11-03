package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.AppRegistry
import ai.closepaw.voice.tools.ToolResult
import ai.closepaw.voice.tools.ToolStatus
import ai.closepaw.voice.tools.UiAutomator
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

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
        // Mutation memory is owned by SemanticRunner, never by nested capabilities.
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

        val before = UiAutomator.readScreen(READ_LIMIT)
        // "No accessibility service" and "no such control on screen" are different facts, and the
        // second is a claim about the app's UI. With no service, readScreen returns an empty
        // projection of nothing — the old code turned that into "界面上没有找到X".
        if (!before.serviceAvailable) {
            return ToolResult(
                ToolStatus.DEVICE_UNAVAILABLE, "app_feature",
                "无障碍服务不可用，看不到${app.label}的界面，没法找$feature",
                "app=${app.packageName} a11y=unavailable ($why)", false
            )
        }
        // The launch is verified *before* this read, so the read can be somebody else's window: a
        // permission sheet, a system dialog, another app. Binding a control from that tree and
        // clicking it is a side effect the foreground check after the click cannot undo.
        if (before.rootPackage != app.packageName) {
            return ToolResult(
                ToolStatus.DEVICE_UNAVAILABLE, "app_feature",
                "打开的窗口不是${app.label}的界面，没有点$feature",
                "app=${app.packageName} window=not-target(${before.rootPackage}) ($why)", false
            )
        }
        // Control identity is bound per window: same package for the root *and* the label node, and
        // the clicked node is the label itself or its nearest clickable ancestor in that package —
        // no generic ancestor climb, no coordinate tap.
        // Round 40 (review 6551941593, item 2): an utterance like 「打开微信扫码」 names the
        // feature, not the literal label on screen. Every label that may stand for it is accepted.
        val featureLabels = AppFeatureAliases.labelsFor(feature)
        val lookup = UiAutomator.featureControl(app.packageName, featureLabels, 4000)
        val control = lookup.control
        if (control == null) {
            val clipped = before.textsTruncated || before.nodesTruncated
            val windowLost = lookup.why?.startsWith("window-not-target") == true
            val ambiguous = lookup.why?.startsWith("ambiguous") == true
            val candidatesTruncated = lookup.why == "candidates-truncated"
            return when {
                windowLost -> ToolResult(
                    ToolStatus.DEVICE_UNAVAILABLE, "app_feature",
                    "点之前${app.label}的界面被另一个窗口盖住了，没有点$feature",
                    "app=${app.packageName} window=${lookup.why} ($why)", false
                )
                // More than one same-package control answers to the name: the name does not identify
                // a target, so picking one would be a guess (review 6551563018).
                ambiguous -> ToolResult(
                    ToolStatus.DEVICE_UNAVAILABLE, "app_feature",
                    "打开了${app.label}，但界面上有多个都像${feature}的地方，没法确定点哪一个，没有点",
                    "app=${app.packageName} control-ambiguous(${lookup.why}) read=${readFacts(before)} ($why)",
                    false
                )
                // The candidate walk hit its budget: uniqueness cannot be decided from a partial
                // scan, so this is "could not tell", not "not there".
                candidatesTruncated -> ToolResult(
                    ToolStatus.DEVICE_UNAVAILABLE, "app_feature",
                    "打开了${app.label}，但界面太大没数完，没法确认${feature}在哪，没有点",
                    "app=${app.packageName} candidates-truncated read=${readFacts(before)} ($why)",
                    false
                )
                // A clipped read is a sample: "not in the part I read" is not "not there".
                clipped -> ToolResult(
                    ToolStatus.DEVICE_UNAVAILABLE, "app_feature",
                    "打开了${app.label}，但屏幕读取被截断，没法确认有没有$feature",
                    "app=${app.packageName} control-not-found before-truncated " +
                        "read=${readFacts(before)} ($why)", false
                )
                else -> ToolResult(
                    ToolStatus.DEVICE_UNAVAILABLE, "app_feature",
                    "打开了${app.label}，但界面上没有找到$feature",
                    "app=${app.packageName} control-not-found read=${readFacts(before)} ($why)", false
                )
            }
        }

        // Round 40 (review 6551941593, item 3): between the scan and the click an async popup or a
        // page jump may replace the window, and Android gives no proof that `performAction` on a
        // node from the *earlier* read would fail instead of hitting whatever now occupies that
        // spot. So the control is re-resolved against a fresh read right at the action boundary and
        // the fresh node is the one clicked; if the fresh read no longer identifies exactly one
        // control, nothing is clicked.
        val fresh = UiAutomator.featureControlOnce(app.packageName, featureLabels)
        val target = fresh.control
        if (target == null) {
            return ToolResult(
                ToolStatus.DEVICE_UNAVAILABLE, "app_feature",
                "点之前${app.label}的界面又变了，没有点$feature",
                "app=${app.packageName} beyond-scan=${fresh.why} " +
                    "scan=${control.label.trim()} ($why)", false
            )
        }

        // Round 42 (review 6552469328, item 2): a fresh node in the same package is not the same
        // *target*. A page jump inside the app keeps the package and can still leave exactly one
        // control with the same name, so package + count are not identity. The fresh control is
        // bound to the first one by window id, resource id/class and the page's structural
        // signature; when that cannot be established, nothing is clicked.
        val identityMiss = UiAutomator.clickIdentityMiss(control, lookup.window, target, fresh.window)
        if (identityMiss != null) {
            return ToolResult(
                ToolStatus.DEVICE_UNAVAILABLE, "app_feature",
                "点之前${app.label}的界面换了，没法确认还是刚才那个$feature，没有点",
                "app=${app.packageName} beyond-scan-identity=$identityMiss " +
                    "scan=${control.label.trim()} fresh=${target.label.trim()} ($why)", false
            )
        }

        // Only the node the fresh lookup bound to; `UiAutomator.click` may climb parents or tap
        // coordinates, which is exactly the blind click this refuses to do.
        val clicked = target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "openFeature $feature clicked=$clicked label=${target.label}")
        if (!clicked) {
            return ToolResult.error("app_feature", "找到了${feature}但点不动", "click failed")
        }

        // Verification: the page must actually change, and we must still be inside the app.
        Thread.sleep(1200)
        val after = UiAutomator.readScreen(READ_LIMIT)
        val fg = UiAutomator.foregroundPackage()
        // Both reads are bounded samples of a tree, not the screen. `after - before` over two
        // samples is not evidence of change (the text may have been there but unsampled), and an
        // unchanged sample is not evidence of no change (the change may be in the part the walk
        // never reached). So a read that hit its budget, or one that lost the service, cannot
        // carry either verdict — the click is reported, the conclusion is not.
        val beforeTexts = before.texts.toSet()
        val afterTexts = after.texts.toSet()
        val incomplete = incompleteWhy(before, after)
        val changed = (afterTexts - beforeTexts).size >= 2
        val stillInside = fg == app.packageName
        return when {
            !stillInside -> ToolResult.error(
                "app_feature", "点${feature}之后跳出了${app.label}", "fg=$fg"
            )
            incomplete != null -> ToolResult(
                ToolStatus.SUCCESS, "app_feature",
                "点了${app.label}的$feature，但屏幕读取不完整，没法确认是否进了那个页面，请看一眼",
                "fg=$fg read=incomplete($incomplete) before=${readFacts(before)} after=${readFacts(after)}",
                null
            )
            changed -> ToolResult.ok(
                "app_feature",
                // Text churn is not page identity: a permission sheet, a toast or an error dialog
                // in the same package also adds two strings. The click really happened, so the
                // status stays SUCCESS, but nothing here proves arrival — verified = null and the
                // sentence says so instead of announcing "已经进入".
                "点了${app.label}的$feature，界面有变化，但没能确认已经进入那个页面，请看一眼",
                verified = null,
                detail = "fg=$fg newTexts=${(afterTexts - beforeTexts).take(4)}"
            )
            else -> ToolResult(
                ToolStatus.TIMEOUT, "app_feature",
                "点了$feature，但界面没有明显变化，可能没进到那个页面",
                "no screen change ${readFacts(before)} ${readFacts(after)}", false
            )
        }
    }

    /** Text budget for one feature-open read; large enough for a home screen, not the whole list. */
    private const val READ_LIMIT = 120

    /**
     * Why the pair of reads cannot support a change verdict, or null when both are complete.
     *
     * Named reasons so the trace says which boundary was hit instead of collapsing everything
     * into "unreadable".
     */
    private fun incompleteWhy(before: UiAutomator.ScreenRead, after: UiAutomator.ScreenRead): String? {
        if (before.textsTruncated || before.nodesTruncated) return "before-truncated"
        if (!after.serviceAvailable) return "after-a11y-down"
        if (after.textsTruncated || after.nodesTruncated) return "after-truncated"
        return null
    }

    private fun readFacts(r: UiAutomator.ScreenRead) =
        "texts=${r.texts.size} nodes=${r.nodesVisited} truncated=${r.textsTruncated || r.nodesTruncated}" +
            " pkg=${r.rootPackage}"
}
