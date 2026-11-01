package ai.closepaw.voice.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Review `6551563018` P1 — the choice-conjunction guard only looked at *leftover* durations, so two
 * alternatives in **different units** were consumed as a compound duration and summed:
 *
 *  - 「设置一小时或者十分钟的倒计时」 → 4200 s (should be refused: the user has not chosen);
 *  - 「设置五分钟或者半分钟的倒计时」 → 330 s (same).
 *
 * `一小时十分钟` is a real compound duration (4200 s) and must keep working — the difference is the
 * conjunction between the two phrases, not the number of phrases.
 */
class ChineseTimeChoiceUnitTest {

    // ── the defect: a choice across units ──────────────────────────────────────────────────────

    @Test
    fun `an hour or ten minutes is not a duration`() {
        assertThat(ChineseTime.durationSeconds("设置一小时或者十分钟的倒计时")).isNull()
    }

    @Test
    fun `five minutes or half a minute is not a duration`() {
        assertThat(ChineseTime.durationSeconds("设置五分钟或者半分钟的倒计时")).isNull()
    }

    @Test
    fun `还是 is a choice too`() {
        assertThat(ChineseTime.durationSeconds("设置一小时还是十分钟的倒计时")).isNull()
    }

    @Test
    fun `a bare 或 between units is a choice too`() {
        assertThat(ChineseTime.durationSeconds("设置一小时或十分钟的倒计时")).isNull()
    }

    @Test
    fun `a choice inside a longer sentence is still a choice`() {
        assertThat(ChineseTime.durationSeconds("帮我设置明天7点30分一小时或者十分钟的倒计时")).isNull()
    }

    // ── controls: compound durations must not be mistaken for choices ──────────────────────────

    @Test
    fun `an hour plus ten minutes is still a compound duration`() {
        assertThat(ChineseTime.durationSeconds("设置一小时十分钟的倒计时")).isEqualTo(4200)
    }

    @Test
    fun `an hour and a half is still a compound duration`() {
        assertThat(ChineseTime.durationSeconds("设置一个半小时的倒计时")).isEqualTo(5400)
    }

    @Test
    fun `a single duration is unaffected`() {
        assertThat(ChineseTime.durationSeconds("设置五分钟的倒计时")).isEqualTo(300)
    }

    @Test
    fun `a single hour is unaffected`() {
        assertThat(ChineseTime.durationSeconds("设置一小时的倒计时")).isEqualTo(3600)
    }
}
