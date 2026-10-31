package ai.closepaw.voice.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The duration parser is the only place a spoken length becomes the `seconds` argument a clock tool
 * receives, so a wrong number here is a wrong tool argument that no later gate can catch: the gate
 * compares the utterance against the parser's own output, and both are wrong together.
 *
 * Round 33 found exactly that shape in the hour+minute case. `durationSeconds` parsed the hour part
 * first (setting `total` to whole seconds), then the minute part added *minutes* into the same
 * accumulator and multiplied the whole accumulator by 60:
 *
 *   "一小时十分钟"  → (3600 + 10) * 60 = 216600 s   (requested 4200 s — a 51-hour timer)
 *   "1小时30分"     → (3600 + 30) * 60 = 217800 s
 *
 * Hour-only, minute-only and second-only shapes were already correct, so nothing in the existing
 * suites noticed. These tests pin the mixed shapes and keep the working ones as controls.
 *
 * Pure JVM. This is a parameter-parsing contract, not device evidence: whether Samsung's clock
 * accepts 4200 s at all is untested.
 */
class ChineseTimeDurationTest {

    @Test
    fun `minute only stays minutes`() {
        assertThat(ChineseTime.durationSeconds("五分钟")).isEqualTo(300)
        assertThat(ChineseTime.durationSeconds("十分钟")).isEqualTo(600)
        assertThat(ChineseTime.durationSeconds("45秒")).isEqualTo(45)
        assertThat(ChineseTime.durationSeconds("半小时")).isEqualTo(1800)
        // "一个半小时" is *not* pinned here: today it resolves to 1800 s, i.e. half an hour, while
        // the phrase means one and a half hours (5400 s). That is a separate semantics change and it
        // needs a decision, so this test deliberately does not enshrine the current value either way.
        assertThat(ChineseTime.durationSeconds("一小时")).isEqualTo(3600)
    }

    @Test
    fun `hours plus minutes is a sum, not a rescaling of the hours`() {
        assertThat(ChineseTime.durationSeconds("一小时十分钟")).isEqualTo(4200)
        assertThat(ChineseTime.durationSeconds("设置一小时十分钟的倒计时")).isEqualTo(4200)
        assertThat(ChineseTime.durationSeconds("1小时30分")).isEqualTo(5400)
        assertThat(ChineseTime.durationSeconds("两小时十五分钟")).isEqualTo(8100)
        assertThat(ChineseTime.durationSeconds("一小时45分30秒")).isEqualTo(6330)
    }

    @Test
    fun `hours alone are not multiplied by a stray minute word`() {
        // "分钟" appears in the utterance, but as part of the same hour phrase: no separate minute
        // value to add, so the result must stay one hour.
        assertThat(ChineseTime.durationSeconds("六十分钟")).isEqualTo(3600)
        assertThat(ChineseTime.durationSeconds("三十分钟")).isEqualTo(1800)
    }

    @Test
    fun `an utterance with no duration yields null instead of a guess`() {
        assertThat(ChineseTime.durationSeconds("开始倒计时")).isNull()
        assertThat(ChineseTime.durationSeconds("一会儿")).isNull()
        assertThat(ChineseTime.durationSeconds("零分钟")).isNull()
    }
}
