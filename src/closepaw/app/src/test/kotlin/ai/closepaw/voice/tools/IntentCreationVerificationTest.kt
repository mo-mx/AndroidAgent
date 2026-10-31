package ai.closepaw.voice.tools

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

/** Real intent capability; only Android and accessibility boundaries are mocked. */
class IntentCreationVerificationTest {
    private val ctx = mockk<Context>()
    private val pm = mockk<PackageManager>()
    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { ctx.packageManager } returns pm
        every { pm.resolveActivity(any<Intent>(), any<Int>()) } returns mockk<ResolveInfo>()
        every { ctx.startActivity(any()) } just Runs
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Int>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
        mockkObject(UiAutomator)
        every { UiAutomator.foregroundPackage() } returns "com.sec.android.app.clockpackage"
        // Round 26: the timer tab is reached through the exact navigation control only; the loose
        // `clickText` matcher is no longer part of this path.
        every { UiAutomator.clickClockNavTab("定时器") } returns true
        // Round 27: countdown wording only counts when the same read proves the clock package and
        // the selected timer tab, so the fixture supplies page identity with its texts.
        // Existing unrelated timer remains unchanged after OEM ignores the new request.
        every { UiAutomator.readScreen(any()) } returns clockRead(
            listOf("1 个定时器正在运行", "剩余 09:58", "10 分钟 0 秒")
        )
    }
    @After fun cleanup() = unmockkAll()

    private fun clockRead(texts: List<String>) = UiAutomator.ScreenRead(
        texts = texts,
        nodesVisited = texts.size,
        rootPackage = "com.sec.android.app.clockpackage",
        serviceAvailable = true,
        selectedTabs = mapOf("定时器" to true),
    )

    @Test fun `existing same time alarm cannot verify an ignored creation`() {
        val time = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 8); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
        val alarm = mockk<android.app.AlarmManager.AlarmClockInfo>()
        every { alarm.triggerTime } returns time
        val manager = mockk<android.app.AlarmManager>()
        every { ctx.getSystemService(Context.ALARM_SERVICE) } returns manager
        every { manager.nextAlarmClock } returns alarm
        val result = IntentActions.setAlarm(ctx, 8, 0)
        assertThat(result.status).isEqualTo(ToolStatus.SUCCESS)
        // Delivered Intent, nothing read back about this alarm → unknown (null).
        assertThat(result.verified).isNull()
        assertThat(result.speak).doesNotContain("闹钟已经设")
        assertThat(result.speak).contains("无法确认")
        verify(exactly = 1) { ctx.startActivity(any()) }
    }

    @Test fun `earlier next alarm does not prove requested alarm absent or created`() {
        val time = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 7); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
        val alarm = mockk<android.app.AlarmManager.AlarmClockInfo>()
        every { alarm.triggerTime } returns time
        val manager = mockk<android.app.AlarmManager>()
        every { ctx.getSystemService(Context.ALARM_SERVICE) } returns manager
        every { manager.nextAlarmClock } returns alarm
        val result = IntentActions.setAlarm(ctx, 8, 0)
        assertThat(result.verified).isNull()
        assertThat(result.speak).contains("无法确认")
        verify(exactly = 1) { ctx.startActivity(any()) }
    }

    @Test fun `timer diagnostics never log private screen text`() {
        val secret = "PRIVATE_TIMER_LABEL_8d291"
        every { UiAutomator.readScreen(any()) } returns clockRead(listOf(secret, "剩余 09:58"))
        IntentActions.setTimer(ctx, 300)
        verify(exactly = 0) { Log.i(any(), match { it.contains(secret) }) }
    }

    @Test fun `old unrelated timer cannot verify new five minute creation`() {
        val result = IntentActions.setTimer(ctx, 300)
        assertThat(result.verified).isNull()
        assertThat(result.speak).doesNotContain("已经开始")
        assertThat(result.speak).contains("无法确认")
        verify(exactly = 1) { ctx.startActivity(any()) }
    }
}
