package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.ChineseTime

/**
 * Deterministic Chinese → [SemanticTask] parser.
 *
 * The important structural difference from the previous code: the *verb* decides the action and
 * is matched first and with priority, the noun only decides the target. Cancelling therefore can
 * never be parsed into a creating action, because "取消/关闭/删除" are consumed as the verb before
 * "倒计时/闹钟" is ever looked at.
 *
 * It intentionally does not try to be a general NLU: what it cannot parse confidently comes back
 * with `ambiguity != null`, and the assistant asks a short question instead of guessing.
 */
object SemanticParser {

    // ── verb lexicons, in priority order (destructive intents first) ──
    private val cancelVerbs = listOf("取消", "撤销", "撤回", "停掉", "停止", "别计时", "不要计时", "关掉这个")
    private val deleteVerbs = listOf("删除", "删掉", "移除")
    private val closeVerbs = listOf("关闭", "关掉", "关了", "关一下", "断开")
    private val pauseVerbs = listOf("暂停")
    private val resumeVerbs = listOf("恢复", "继续")
    private val queryVerbs = listOf("查询", "查一下", "查查", "看看", "看一下", "有没有", "还剩", "剩多少",
        "多少", "什么", "几点", "多久", "是不是", "是否", "状态")
    private val createVerbs = listOf("设置", "设一个", "设个", "设", "定一个", "定个", "定", "建一个", "建",
        "添加", "新建", "开一个", "来个")
    private val openVerbs = listOf("打开", "启动", "运行", "进入", "唤起", "调出")
    private val enableVerbs = listOf("打开", "开启", "启用", "启动")
    private val disableVerbs = listOf("关闭", "关掉", "停用", "禁用")

    // ── target lexicons ──
    private val timerWords = listOf("倒计时", "计时器", "计时", "定时器")
    private val alarmWords = listOf("闹钟", "闹铃", "起床闹钟")
    private val appFeatureWords = listOf("扫一扫", "扫码", "付款码", "收付款", "朋友圈", "订单", "卡包", "收藏")
    private val settingsPageWords = listOf("设置页", "设置页面", "系统设置")

    private val lastActionWords = listOf("刚才", "刚刚", "上一个", "上一个操作", "这个操作", "它")

    /** verbs that mean "turn something on/off" rather than launch an app. */
    private fun actionOfVerb(text: String, target: Target): Action? {
        val t = text
        // Cancelling / deleting / closing always wins over creating.
        if (cancelVerbs.any { t.contains(it) }) return Action.CANCEL
        if (deleteVerbs.any { t.contains(it) }) return Action.DELETE
        if (pauseVerbs.any { t.contains(it) }) return Action.PAUSE
        if (resumeVerbs.any { t.contains(it) }) return Action.RESUME
        if (closeVerbs.any { t.contains(it) }) return Action.CLOSE
        if (createVerbs.any { t.contains(it) }) return Action.CREATE
        // Query words only count when they are not part of "打开了没" style questions
        if (queryVerbs.any { t.contains(it) }) return Action.QUERY
        if (openVerbs.any { t.contains(it) }) {
            return when (target) {
                Target.TORCH, Target.WIFI, Target.BLUETOOTH -> Action.ENABLE
                else -> Action.OPEN
            }
        }
        return null
    }

    private fun targetOf(text: String): Target {
        val t = text
        if (timerWords.any { t.contains(it) }) return Target.TIMER
        if (alarmWords.any { t.contains(it) }) return Target.ALARM
        if (t.contains("手电") || t.contains("闪光灯")) return Target.TORCH
        if (t.contains("wifi") || t.contains("wi-fi") || t.contains("无线")) return Target.WIFI
        if (t.contains("蓝牙")) return Target.BLUETOOTH
        if (t.contains("音量") || t.contains("静音")) return Target.VOLUME
        if (t.contains("电量") || t.contains("电池") || t.contains("多少电")) return Target.BATTERY
        if (t.contains("存储") || t.contains("空间") || t.contains("版本") || t.contains("锁屏") || t.contains("锁着"))
            return Target.DEVICE
        if (t.contains("网络") || t.contains("联网") || t.contains("有网")) return Target.NETWORK
        if (t.contains("相机") || t.contains("拍照") || t.contains("录像") || t.contains("录制")) return Target.CAMERA
        if (t.contains("电话") || t.contains("拨打") || t.contains("呼叫")) return Target.CALL
        if (settingsPageWords.any { t.contains(it) }) return Target.SETTINGS_PAGE
        if (t.contains("导航") || t.contains("地图") || t.contains("路线")) return Target.MAPS
        if (t.contains("搜索") || t.contains("搜一下")) return Target.WEB
        if (t.contains("几点") || t.contains("时间")) return Target.TIME
        return Target.APP
    }

    fun parse(fullText: String): List<SemanticTask> {
        val clauses = splitClauses(fullText)
        val out = ArrayList<SemanticTask>()
        for (raw in clauses) {
            var t = parseClause(raw)
            // "如果…" conditions ride along with the preceding task instead of becoming a task.
            if (t == null) continue
            // "打开手电筒，然后关闭" — the second clause has a verb but no object; it refers to
            // the previous object, not to "an app called 关闭".
            val bare = t.target == Target.APP && t.params["name"]?.let { n ->
                n in setOf("关闭", "打开", "关掉", "开", "关", "它", "这个", "那个")
            } == true
            if (bare) {
                val prev = out.lastOrNull()
                if (prev != null && prev.target != Target.APP) {
                    val a = when (t.action) {
                        Action.CLOSE -> Action.DISABLE
                        Action.OPEN -> Action.ENABLE
                        else -> t.action
                    }
                    t = t.copy(target = prev.target, action = a, params = prev.params,
                        expectedState = expectedStateOf(a, prev.target, prev.params))
                }
            }
            out.add(t)
        }
        return if (out.isEmpty()) {
            listOf(
                SemanticTask(Action.UNKNOWN, Target.UNKNOWN, raw = fullText,
                    ambiguity = "没听明白要做什么")
            )
        } else out
    }

    private fun splitClauses(text: String): List<String> {
        var s = text.replace(" ", "").replace("　", "")
        for (c in listOf("然后", "接着", "随后", "再", "并且", "同时", "顺便", "另外")) s = s.replace(c, "|")
        val sb = StringBuilder()
        for (ch in s) sb.append(if (ch in charArrayOf('，', ',', '。', '；', ';', '、', '\n', '！', '？', '?')) '|' else ch)
        return sb.toString().split('|').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseClause(raw: String): SemanticTask? {
        val t = raw

        // A conditional that stands alone still states an object to *report on*: treat it as a
        // query so nothing is modified ("如果蓝牙已经打开就不要修改" must not toggle Bluetooth).
        if (t.contains("如果") && (t.contains("就") || t.contains("的话"))) {
            val tg = targetOf(t)
            if (tg == Target.UNKNOWN || tg == Target.APP) return null
            return SemanticTask(
                Action.QUERY, tg,
                conditions = listOf(t.substringAfter("如果")),
                expectedState = "reported:$tg", confidence = 0.8, raw = t,
            )
        }
        // "取消刚才的操作"
        if (t.contains("刚才") || t.contains("刚刚") || t == "撤销" || t.contains("这个操作")) {
            if (t.contains("取消") || t.contains("撤销") || t.contains("撤回")) {
                return SemanticTask(
                    Action.CANCEL, Target.LAST_ACTION, expectedState = "undo_last",
                    confidence = 0.9, raw = t
                )
            }
        }

        val target = targetOf(t)
        var action = actionOfVerb(t, target)

        val params = HashMap<String, String>()
        ChineseTime.durationSeconds(t)?.let { params["seconds"] = it.toString() }
        ChineseTime.clockTime(t)?.let { (h, m) ->
            params["hour"] = h.toString(); params["minute"] = m.toString()
        }
        Regex("(\\d{1,3})\\s*%").find(t)?.let { params["percent"] = it.groupValues[1] }

        // app / feature splitting happens in the executor (needs PackageManager), but obvious
        // feature words let us pick the right target here.
        val featureHit = appFeatureWords.firstOrNull { t.contains(it) }
        val effectiveTarget = when {
            target == Target.TIMER || target == Target.ALARM -> target
            featureHit != null && (t.contains("打开") || t.contains("进入")) -> Target.APP_FEATURE
            else -> target
        }
        if (featureHit != null && effectiveTarget == Target.APP_FEATURE) {
            params["feature"] = featureHit
            params["name"] = t.substringAfter("打开").substringAfter("进入")
                .substringBefore(featureHit).trim()
        }

        // Target-specific implicit actions (a noun plus a duration means "create")
        if (action == null) {
            action = when (effectiveTarget) {
                Target.TIMER -> if (params["seconds"] != null) Action.CREATE else Action.QUERY
                Target.ALARM -> if (params["hour"] != null) Action.CREATE else Action.QUERY
                Target.VOLUME -> if (params["percent"] != null) Action.SET else Action.QUERY
                Target.TORCH, Target.WIFI, Target.BLUETOOTH -> Action.QUERY
                // Everything below opens/dials/captures something: declaring it QUERY here made a
                // mutating tool look like a read, which is exactly what the semantic gate refuses.
                Target.APP, Target.APP_FEATURE, Target.CALL, Target.CAMERA,
                Target.SETTINGS_PAGE, Target.MAPS, Target.WEB -> Action.OPEN
                else -> Action.QUERY
            }
        }

        // resolve "关闭手电筒" (CLOSE) into DISABLE for switches
        val normalised = when {
            effectiveTarget == Target.TORCH || effectiveTarget == Target.WIFI || effectiveTarget == Target.BLUETOOTH ->
                when (action) {
                    Action.CLOSE -> Action.DISABLE
                    Action.OPEN -> Action.ENABLE
                    else -> action
                }
            else -> action
        }

        // The executor needs the raw object name; keep "应用" so "应用商店" stays distinguishable
        // from a bare "商店" (they resolve to different stores).
        if (params["name"].isNullOrBlank()) {
            val stripped = t
                .replace(Regex("^(帮我|请|麻烦你?)"), "")
                .replace(Regex("^(打开|启动|运行|进入|唤起|调出|导航到|导航去|导航|搜索|搜一下|查一下)"), "")
                .replace(Regex("(一下|这个|那个|页面|设置页)$"), "")
                .trim()
            when (effectiveTarget) {
                Target.APP, Target.APP_FEATURE, Target.MAPS, Target.WEB,
                Target.SETTINGS_PAGE, Target.CALL ->
                    if (stripped.isNotBlank()) params["name"] = stripped
                else -> {}
            }
        }

        var ambiguity: String? = null
        var confidence = 0.95
        // A cancel/close with no identifiable object needs a question.
        if (normalised in setOf(Action.CANCEL, Action.CLOSE, Action.DELETE) &&
            effectiveTarget == Target.APP && params["name"].isNullOrBlank() && !t.contains("应用")
        ) {
            ambiguity = "要取消哪个？"
            confidence = 0.4
        }
        // "取消五分钟倒计时" without any prior timer — the executor decides whether one exists.

        return SemanticTask(
            action = normalised,
            target = effectiveTarget,
            params = params,
            conditions = if (t.contains("如果")) listOf(t.substringAfter("如果")) else emptyList(),
            expectedState = expectedStateOf(normalised, effectiveTarget, params),
            confidence = confidence,
            ambiguity = ambiguity,
            raw = t,
        )
    }

    private fun expectedStateOf(a: Action, t: Target, p: Map<String, String>): String = when {
        a == Action.CREATE && t == Target.TIMER -> "timer_running:${p["seconds"]}"
        a == Action.CREATE && t == Target.ALARM -> "alarm_exists:${p["hour"]}${p["minute"]}"
        a in setOf(Action.CANCEL, Action.CLOSE, Action.DELETE) && t == Target.TIMER -> "no_running_timer"
        a in setOf(Action.CANCEL, Action.CLOSE, Action.DELETE) && t == Target.ALARM -> "no_such_alarm"
        a == Action.ENABLE -> "on:$t"
        a == Action.DISABLE -> "off:$t"
        a == Action.QUERY -> "reported:$t"
        a == Action.OPEN && t == Target.APP_FEATURE -> "feature_visible"
        else -> ""
    }

    /** Text form used by the executor when it must ask a question. */
    fun describeForUser(task: SemanticTask): String =
        "${task.action.name}/${task.target.name}" + task.params.entries.joinToString { "${it.key}=${it.value}" }
}
