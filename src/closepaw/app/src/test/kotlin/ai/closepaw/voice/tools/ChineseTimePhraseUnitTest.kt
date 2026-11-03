package ai.closepaw.voice.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Round 35 — the shapes the round-33/34 review kept pointing at, still wrong at HEAD `1a77501`:
 *
 *   `半分钟`    → 1800 s  (an hour-vocabulary reading of "半"; the phrase means 30 s)
 *   `一个半小时` → 1800 s  (the hour regex matches the trailing `半小时` and drops `一个`)
 *   `1.5小时`   → 18000 s (the regex skips `1.` and treats `5小时` as the whole duration)
 *
 * These are not display bugs: the number becomes the `seconds` argument of a real
 * `AlarmClock.EXTRA_LENGTH`, and `IntentGate` re-runs this same parser to cross-check, so both
 * sides agree on the wrong value and the gate cannot catch it.
 *
 * The unit is the *consumed phrase*: an expression is measured against what a person means by it,
 * and an expression the parser cannot consume whole must return null rather than a suffix reading.
 *
 * Pure JVM — parameter parsing only. Whether Samsung's clock app accepts 30 s is untested here.
 */
class ChineseTimePhraseUnitTest {

    @Test
    fun `half a minute is thirty seconds, not half an hour`() {
        assertThat(ChineseTime.durationSeconds("半分钟")).isEqualTo(30)
        assertThat(ChineseTime.durationSeconds("设置半分钟的倒计时")).isEqualTo(30)
        assertThat(ChineseTime.durationSeconds("半分钟倒计时")).isEqualTo(30)
    }

    @Test
    fun `one and a half hours keeps the leading numeral`() {
        assertThat(ChineseTime.durationSeconds("一个半小时")).isEqualTo(5400)
        assertThat(ChineseTime.durationSeconds("设置一个半小时的倒计时")).isEqualTo(5400)
        assertThat(ChineseTime.durationSeconds("两个半小时")).isEqualTo(9000)
        // The trailing-"半" spelling of the same duration.
        assertThat(ChineseTime.durationSeconds("一小时半")).isEqualTo(5400)
        assertThat(ChineseTime.durationSeconds("两小时半")).isEqualTo(9000)
    }

    @Test
    fun `decimal hours are read as a decimal, not as a suffix`() {
        assertThat(ChineseTime.durationSeconds("1.5小时")).isEqualTo(5400)
        assertThat(ChineseTime.durationSeconds("0.5小时")).isEqualTo(1800)
        assertThat(ChineseTime.durationSeconds("设置1.5小时的倒计时")).isEqualTo(5400)
        assertThat(ChineseTime.durationSeconds("2.25小时")).isEqualTo(8100)
    }

    @Test
    fun `minutes plus half a minute is not dropped`() {
        assertThat(ChineseTime.durationSeconds("一分半")).isEqualTo(90)
        assertThat(ChineseTime.durationSeconds("三分钟半")).isEqualTo(210)
    }

    @Test
    fun `the shapes that already worked stay put`() {
        assertThat(ChineseTime.durationSeconds("五分钟")).isEqualTo(300)
        assertThat(ChineseTime.durationSeconds("半小时")).isEqualTo(1800)
        assertThat(ChineseTime.durationSeconds("一小时")).isEqualTo(3600)
        assertThat(ChineseTime.durationSeconds("一小时十分钟")).isEqualTo(4200)
        assertThat(ChineseTime.durationSeconds("一小时45分30秒")).isEqualTo(6330)
        assertThat(ChineseTime.durationSeconds("六十分钟")).isEqualTo(3600)
        assertThat(ChineseTime.durationSeconds("45秒")).isEqualTo(45)
        assertThat(ChineseTime.durationSeconds("零分钟")).isNull()
        assertThat(ChineseTime.durationSeconds("一会儿")).isNull()
    }
}
