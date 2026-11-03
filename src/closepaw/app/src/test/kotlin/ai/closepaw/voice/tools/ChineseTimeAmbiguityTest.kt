package ai.closepaw.voice.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Review `6551213132` P1 — a duration parser that consumes the *first* phrase and ignores what is
 * left can hand the system a duration the user never asked for, and `IntentGate` cannot catch it
 * because it re-parses the same utterance with the same function.
 *
 * Two shapes were wrong:
 *  - 「五分钟或者十分钟」 → 300 s, with 「或者十分钟」 left unread (the user has not chosen yet);
 *  - 「一个半分钟」        → 30 s, with 「一个」 left unread (the intended value is 90 s).
 *
 * The rule pinned here: every duration word in the utterance must be consumed and must describe one
 * value; otherwise the parse fails and the caller asks again.
 */
class ChineseTimeAmbiguityTest {

    @Test
    fun `two alternative durations are not one duration`() {
        assertThat(ChineseTime.durationSeconds("设置五分钟或者十分钟的倒计时")).isNull()
    }

    @Test
    fun `a second duration joined by 和 is not silently dropped`() {
        assertThat(ChineseTime.durationSeconds("设置十分钟和一分钟的倒计时")).isNull()
    }

    @Test
    fun `two durations side by side are not silently truncated`() {
        assertThat(ChineseTime.durationSeconds("设置五分钟十分钟的倒计时")).isNull()
    }

    @Test
    fun `one and a half minutes is ninety seconds`() {
        assertThat(ChineseTime.durationSeconds("设置一个半分钟的倒计时")).isEqualTo(90)
    }

    @Test
    fun `two and a half minutes is one hundred and fifty seconds`() {
        assertThat(ChineseTime.durationSeconds("设置两个半分钟的倒计时")).isEqualTo(150)
    }

    // ── controls: the shapes that must keep parsing ────────────────────────────────────────────

    @Test
    fun `a single duration still parses`() {
        assertThat(ChineseTime.durationSeconds("设置五分钟的倒计时")).isEqualTo(300)
    }

    @Test
    fun `an hour plus minutes still parses`() {
        assertThat(ChineseTime.durationSeconds("设置一小时十分钟的倒计时")).isEqualTo(4200)
    }

    @Test
    fun `a compound half hour still parses`() {
        assertThat(ChineseTime.durationSeconds("设置一个半小时的倒计时")).isEqualTo(5400)
    }

    @Test
    fun `a clock time in the same utterance is not a second duration`() {
        // 「7点30分」 is a wall-clock time, not another duration; the 5 minutes must still win.
        assertThat(ChineseTime.durationSeconds("设置明天7点30分五分钟的倒计时")).isEqualTo(300)
    }

    @Test
    fun `a duration label read back from the clock still parses`() {
        assertThat(ChineseTime.durationSeconds("初始时长 5 分钟")).isEqualTo(300)
    }
}
