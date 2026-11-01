package ai.closepaw.voice.tools

/**
 * Minimal Chinese number/date parsing for voice commands.
 *
 * Kept deliberately small: it parses the shapes people actually say to a phone assistant —
 * "十分钟", "半小时", "一个半小时", "7点半", "八点二十", "下午三点" — instead of pretending to be a
 * general NLU. Anything it cannot parse returns null and the caller asks again rather than
 * guessing a wrong duration.
 */
object ChineseTime {
    private val digits = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    /** Chinese or Arabic numeral, decimals allowed ("1.5"). Shared by the duration patterns. */
    private const val NUM = "(?:\\d+(?:\\.\\d+)?|[零〇一二两三四五六七八九十百]+)"

    /** Parses 0–99 written in Chinese or Arabic digits. */
    fun number(s: String): Int? {
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }
        var total = 0
        var section = 0
        var seen = false
        for (ch in s) {
            when {
                digits.containsKey(ch) -> {
                    section = digits[ch]!!
                    seen = true
                }
                ch == '十' -> {
                    total += (if (section == 0) 1 else section) * 10
                    section = 0
                    seen = true
                }
                ch == '百' -> {
                    total += (if (section == 0) 1 else section) * 100
                    section = 0
                    seen = true
                }
                ch == ' ' || ch == '个' -> {}
                else -> return null
            }
        }
        return if (seen) total + section else null
    }

    /**
     * "十分钟" / "半小时" / "一个半小时" / "1.5小时" / "45秒" → seconds.
     *
     * The match is by *consumed phrase*, not by first fragment: whatever the pattern eats is
     * removed before the next unit is looked for, so `一小时十分钟` is 4200 s and nothing is counted
     * twice. A numeral that cannot be read fails the whole utterance (`null` → callers ask again)
     * instead of falling back to a suffix reading.
     *
     * Round 35 — three shapes were being read as a different duration than the words mean:
     *   `半分钟`    → 1800 s, because 半 in the minute slot was read as "half *of an hour*"
     *   `一个半小时` → 1800 s, because the hour pattern matched the trailing `半小时`
     *   `1.5小时`   → 18000 s, because `\d+` matched the `5` after the decimal point
     * Each value becomes `AlarmClock.EXTRA_LENGTH`, and `IntentGate` cross-checks against this same
     * function, so a wrong parse was invisible to the gate. Hence: the compound shapes below are
     * tried before the plain ones, and numbers may be decimal.
     */
    fun durationSeconds(text: String): Int? {
        // Wall-clock shapes (「7点30分」) share digits and the 分 unit with durations, and the duration
        // patterns would otherwise eat the "30分" of a time. Remove them before looking for a
        // duration.
        var work = text.replace(" ", "").replace(CLOCK_TIME_IN_TEXT, "")
        var total = 0.0
        var matched = false
        var badNumeral = false

        /** Consumes the first match of [re] and adds `secondsOf(match)` to the total. */
        fun take(re: Regex, secondsOf: (MatchResult) -> Double?): Boolean {
            val m = re.find(work) ?: return false
            val secs = secondsOf(m)
            if (secs == null) {
                badNumeral = true
                return false
            }
            work = work.removeRange(m.range)
            total += secs
            matched = true
            return true
        }

        // 一个半小时 / 两个半小时 — must be tried before the plain hour shape, which would otherwise
        // match the trailing 半小时 and drop the leading numeral.
        take(Regex("($NUM)个?半个?(?:小时|钟头)")) { m -> num(m.groupValues[1])?.let { (it + 0.5) * 3600 } }
        // 半小时 on its own.
        take(Regex("半(?:个)?(?:小时|钟头)")) { 1800.0 }
        // 一小时半 / 两小时半 — trailing 半 means another half hour.
        take(Regex("($NUM)个?(?:小时|钟头)半")) { m -> num(m.groupValues[1])?.let { it * 3600 + 1800 } }
        // 一小时 / 1.5小时 / 一个钟头.
        take(Regex("($NUM)个?(?:小时|钟头)")) { m -> num(m.groupValues[1])?.let { it * 3600 } }
        // 一个半分钟 is 90 s: 「一个」+「半分钟」, and it must be tried before the bare 半分钟 shape,
        // which would otherwise eat 半分钟 and leave 一个 unread.
        take(Regex("($NUM)个?半(?:分钟|分)")) { m -> num(m.groupValues[1])?.let { it * 60 + 30 } }
        // 半分钟 is 30 s, not half an hour: it must be tried before the N分钟 shape.
        take(Regex("半(?:个)?(?:分钟|分)")) { 30.0 }
        // 一分半 / 三分钟半.
        take(Regex("($NUM)个?(?:分钟|分)半")) { m -> num(m.groupValues[1])?.let { it * 60 + 30 } }
        // 十分钟 / 六十分.
        take(Regex("($NUM)个?(?:分钟|分)")) { m -> num(m.groupValues[1])?.let { it * 60 } }
        // 45秒.
        take(Regex("($NUM)秒")) { m -> num(m.groupValues[1]) }

        // Every duration word in the utterance must have been consumed and must describe one value.
        // 「五分钟或者十分钟」 and 「十分钟和一分钟」 are choices, not a duration: picking the first
        // match hands the system a number the user never settled on, and `IntentGate` re-parses with
        // the same function, so nothing downstream would catch it. Wall-clock times (「7点30分」)
        // are removed first so they are not mistaken for a second duration.
        val leftover = work
        if (matched && !badNumeral && total > 0 && RESIDUAL_DURATION.containsMatchIn(leftover)) {
            return null
        }

        return if (matched && !badNumeral && total > 0) Math.round(total).toInt() else null
    }

    /** A wall-clock shape, which shares digits/units with durations but is not one. */
    private val CLOCK_TIME_IN_TEXT = Regex("($NUM)点(半|一刻|三刻|$NUM)?")

    /** Any duration phrase still sitting in the text after the first one was consumed. */
    private val RESIDUAL_DURATION = Regex("($NUM)个?半?(?:小时|钟头|分钟|分|秒)")

    /** Arabic or Chinese numeral, decimals included ("1.5"), for [durationSeconds]. */
    private fun num(raw: String): Double? = raw.toDoubleOrNull() ?: number(raw)?.toDouble()

    /** "7点半" / "八点二十" / "下午三点" → (hour24, minute). */
    fun clockTime(text: String): Pair<Int, Int>? {
        val t = text.replace(" ", "")
        val m = Regex("([零〇一二两三四五六七八九十\\d]{1,3})点(半|一刻|三刻|[零〇一二三四五六七八九十\\d]{1,3})?").find(t)
            ?: return null
        var hour = number(m.groupValues[1]) ?: return null
        val tail = m.groupValues[2]
        val minute = when {
            tail == "半" -> 30
            tail == "一刻" -> 15
            tail == "三刻" -> 45
            tail.isNotEmpty() -> number(tail) ?: 0
            else -> 0
        }
        val afternoon = t.contains("下午") || t.contains("傍晚") || t.contains("晚上")
        val morning = t.contains("早上") || t.contains("上午") || t.contains("凌晨")
        if (afternoon && hour in 1..11) hour += 12
        if (morning && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    /** "今天/明天" → day offset. */
    fun dayOffset(text: String): Int = when {
        text.contains("后天") -> 2
        text.contains("明天") -> 1
        else -> 0
    }
}
