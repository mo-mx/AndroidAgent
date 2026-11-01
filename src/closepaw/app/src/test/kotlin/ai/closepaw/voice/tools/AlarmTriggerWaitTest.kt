package ai.closepaw.voice.tools

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Round 49, offline (source + JVM): the two facts `IntentActions.setAlarm` used to flatten while it
 * waited for the system to register the requested alarm.
 *
 * 1. **A bounded wait on the wall clock.** `deadline = System.currentTimeMillis() + 6000` is not a
 *    bound: a clock correction that moves *backwards* makes the six-second window longer (in the
 *    limit: unbounded), and a forward jump ends it early. Round 48 fixed exactly this in the
 *    bluetooth settle window (`LocalCapabilities.awaitState`); the alarm wait is the same class of
 *    exit and was left behind.
 * 2. **A failed read spoken as "nothing registered".** The loop read through `nextAlarm(ctx)`,
 *    which drops `NextAlarm.known` — so an `AlarmManager` that throws for six seconds produced the
 *    same `trigger == null` as a healthy system with no alarm, and the answer was "已经打开闹钟界面，
 *    可能还需要你按一下保存". That is an assertion about both the alarm registration and a save UI
 *    that was never observed (the intent is sent with `EXTRA_SKIP_UI = true`, so there may be no UI
 *    at all).
 *
 * The rules pinned here: the wait runs on the injected monotonic source and has a hard read cap; a
 * final read that failed is reported as unreadable, not as an empty registration.
 */
class AlarmTriggerWaitTest {
    private val ctx = mockk<Context>()
    private val pm = mockk<PackageManager>()
    private val alarm = mockk<AlarmManager>()

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { ctx.packageManager } returns pm
        every { pm.resolveActivity(any<Intent>(), any<Int>()) } returns mockk<ResolveInfo>()
        every { ctx.startActivity(any()) } just Runs
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Int>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>()) } answers { self as Intent }
        every { ctx.getSystemService(Context.ALARM_SERVICE) } returns alarm
    }

    @After fun cleanup() = unmockkAll()

    private fun triggerAt(hour: Int, minute: Int): AlarmManager.AlarmClockInfo {
        val info = mockk<AlarmManager.AlarmClockInfo>()
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        every { info.triggerTime } returns cal.timeInMillis
        return info
    }

    private val noAlarm = IntentActions.NextAlarm(null, true)
    private val unreadable = IntentActions.NextAlarm(null, false)

    /**
     * A clock correction that goes backwards must not stretch the window: the old loop compared two
     * wall-clock readings, so `now` never reaches the deadline and only the read cap can stop it.
     */
    @Test fun `a backwards clock jump cannot stretch the bounded wait`() {
        var t = 10_000L
        val wait = IntentActions.awaitNextAlarm(
            probe = { noAlarm },
            timeoutMs = 300,
            now = { t -= 1_000L; t },
            pause = {},
        )

        // 300 ms window / 300 ms step = 1 read + 2 spare = 3.
        assertThat(wait.reads).isEqualTo(3)
        assertThat(wait.why).isEqualTo("read-cap")
        assertThat(wait.trigger).isNull()
    }

    /** A jump forwards must end the wait instead of running it out against a stale deadline. */
    @Test fun `a forward clock jump ends the wait instead of running it out`() {
        var calls = 0
        val wait = IntentActions.awaitNextAlarm(
            probe = { noAlarm },
            timeoutMs = 300,
            now = { calls++; if (calls >= 2) 10_000_000L else 1_000L },
            pause = {},
        )

        assertThat(wait.reads).isEqualTo(1)
        assertThat(wait.why).isEqualTo("timeout")
    }

    /** Control: a trigger that appears on the third read is still found, and says so. */
    @Test fun `a trigger that arrives late is still found`() {
        var n = 0
        val info = triggerAt(7, 30)
        val wait = IntentActions.awaitNextAlarm(
            probe = {
                n++
                when (n) {
                    1, 2 -> noAlarm
                    else -> IntentActions.NextAlarm(info, true)
                }
            },
            timeoutMs = 3_000,
            now = { 1_000L + n * 300L },
            pause = {},
        )

        assertThat(wait.reads).isEqualTo(3)
        assertThat(wait.why).isEqualTo("found")
        assertThat(wait.trigger).isEqualTo(info.triggerTime)
        assertThat(wait.lastKnown).isTrue()
    }

    /**
     * End to end: an `AlarmManager` that throws for the whole window may not be spoken as "the alarm
     * screen is waiting for you to save".
     */
    @Test fun `an unreadable alarm manager is not spoken as a pending save`() {
        every { alarm.nextAlarmClock } throws IllegalStateException("alarm service unavailable")

        val r = IntentActions.setAlarm(ctx, 7, 30, alarmWaitMs = 300, now = { 1_000L }, pause = {})

        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.speak).contains("读不到")
        assertThat(r.speak).doesNotContain("按一下保存")
        assertThat(r.detail).contains("nextKnown=false")
        assertThat(r.detail).contains("read-failed")
    }

    /** Control: a readable "no alarm enabled" is still a system fact, and is still reported. */
    @Test fun `a readable null probe is reported as an observation`() {
        every { alarm.nextAlarmClock } returns null

        val r = IntentActions.setAlarm(ctx, 7, 30, alarmWaitMs = 300, now = { 1_000L }, pause = {})

        assertThat(r.speak).doesNotContain("按一下保存")
        assertThat(r.speak).contains("没能确认")
        assertThat(r.detail).contains("nextKnown=true")
        assertThat(r.verified).isNotEqualTo(true)
    }

    /** Control: a matching next alarm is still never claimed as this creation. */
    @Test fun `a matching next alarm is still not creation proof`() {
        every { alarm.nextAlarmClock } returns triggerAt(7, 30)

        val r = IntentActions.setAlarm(ctx, 7, 30, alarmWaitMs = 300, now = { 1_000L }, pause = {})

        assertThat(r.speak).contains("无法确认本次是否新增或更新")
        assertThat(r.verified).isNotEqualTo(true)
    }
}
