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

    /** "十分钟" / "半小时" / "一个半小时" / "45秒" → seconds. */
    fun durationSeconds(text: String): Int? {
        val t = text.replace(" ", "")
        var total = 0
        var matched = false

        val hour = Regex("(半|\\d+|[零〇一二两三四五六七八九十百]+)(个)?(小时|钟头)").find(t)
        if (hour != null) {
            val v = hour.groupValues[1]
            total += if (v == "半") 1800 else (number(v) ?: return null) * 3600
            matched = true
        }
        val min = Regex("(半|\\d+|[零〇一二两三四五六七八九十百]+)(个)?(分钟|分)").find(t)
        if (min != null) {
            val v = min.groupValues[1]
            total += if (v == "半") 30 else (number(v) ?: return null)
            total *= 60
            matched = true
        }
        val sec = Regex("(\\d+|[零〇一二两三四五六七八九十百]+)秒").find(t)
        if (sec != null) {
            total += number(sec.groupValues[1]) ?: return null
            matched = true
        }
        return if (matched && total > 0) total else null
    }

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
