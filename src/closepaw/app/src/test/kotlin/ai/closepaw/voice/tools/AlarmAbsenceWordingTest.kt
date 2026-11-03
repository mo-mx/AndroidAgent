package ai.closepaw.voice.tools

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

/**
 * Review backlog slice: "no next alarm" is an AlarmManager observation; a failed lookup is not one,
 * and neither of them enumerates the alarms the user has created.
 *
 * Old `showAlarms` collapsed three different facts into one sentence:
 * 1. `nextAlarmClock` returned an alarm → "下一个闹钟是 X" (fine).
 * 2. `nextAlarmClock` returned null    → "现在没有设置闹钟" — an assertion about the alarm list,
 *    while the only thing observed was that no alarm is currently *enabled*. Disabled alarms are
 *    exactly the case the user hears about.
 * 3. The `AlarmManager` call threw     → `runCatching{...}.getOrNull()` produced null, i.e. the same
 *    "现在没有设置闹钟" — a read failure spoken as an empty phone.
 *
 * The rule pinned here: an unreadable probe is reported as unreadable (`verified=false`), a null
 * probe is spoken as "没有下一个已启用的闹钟", and the list is never claimed to be empty.
 */
class AlarmAbsenceWordingTest {
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
        every { ctx.getSystemService(Context.ALARM_SERVICE) } returns alarm
    }

    @After fun cleanup() = unmockkAll()

    /** Case 3: the system call failed. Nothing was observed, so nothing may be asserted. */
    @Test fun `an unreadable next alarm is not an empty alarm list`() {
        every { alarm.nextAlarmClock } throws IllegalStateException("alarm service unavailable")

        val r = IntentActions.showAlarms(ctx)

        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.speak).doesNotContain("没有设置闹钟")
        assertThat(r.speak).contains("没法确认")
        assertThat(r.detail).contains("nextKnown=false")
    }

    /** Case 2: null means "no alarm enabled right now", not "you have no alarms". */
    @Test fun `a null next alarm does not claim the user has no alarms`() {
        every { alarm.nextAlarmClock } returns null

        val r = IntentActions.showAlarms(ctx)

        assertThat(r.speak).doesNotContain("没有设置闹钟")
        assertThat(r.speak).contains("没有下一个已启用的闹钟")
        assertThat(r.verified).isEqualTo(true)
        assertThat(r.detail).contains("nextKnown=true")
    }

    /** Control: a real next alarm is still spoken as a system fact. */
    @Test fun `a present next alarm is still reported`() {
        val info = mockk<AlarmManager.AlarmClockInfo>()
        every { info.triggerTime } returns 1_760_000_000_000L
        every { alarm.nextAlarmClock } returns info

        val r = IntentActions.showAlarms(ctx)

        assertThat(r.speak).contains("下一个闹钟是")
        assertThat(r.verified).isEqualTo(true)
    }
}
