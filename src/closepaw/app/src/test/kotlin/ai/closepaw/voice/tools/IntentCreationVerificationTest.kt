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
        every { UiAutomator.clickText("定时器") } returns true
        // Existing unrelated timer remains unchanged after OEM ignores the new request.
        every { UiAutomator.screenTexts(any()) } returns listOf("1 个定时器正在运行", "剩余 09:58", "10 分钟 0 秒")
    }
    @After fun cleanup() = unmockkAll()

    @Test fun `existing same time alarm cannot verify an ignored creation`() {
        val time = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 8); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
        val alarm = mockk<android.app.AlarmManager.AlarmClockInfo>()
        every { alarm.triggerTime } returns time
        val manager = mockk<android.app.AlarmManager>()
        every { ctx.getSystemService(Context.ALARM_SERVICE) } returns manager
        every { manager.nextAlarmClock } returns alarm
        val result = IntentActions.setAlarm(ctx, 8, 0)
        assertThat(result.verified).isFalse()
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
        assertThat(result.verified).isFalse()
        assertThat(result.speak).contains("无法确认")
        verify(exactly = 1) { ctx.startActivity(any()) }
    }

    @Test fun `old unrelated timer cannot verify new five minute creation`() {
        val result = IntentActions.setTimer(ctx, 300)
        assertThat(result.verified).isFalse()
        assertThat(result.speak).doesNotContain("已经开始")
        assertThat(result.speak).contains("无法确认")
        verify(exactly = 1) { ctx.startActivity(any()) }
    }
}
