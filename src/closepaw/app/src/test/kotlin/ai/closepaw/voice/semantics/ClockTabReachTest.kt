package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.*
import android.content.Context
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

/**
 * Review `6539752681` P2: `openClockTab` used `clickText(tab) || waitForText(tab, 800)`, so a failed
 * tap still counted as "reached" whenever the tab label remained visible. The clock app keeps the
 * bottom tab labels on every tab, so a failed switch could leave the reader on another page and its
 * texts (world-clock times are also `H:mm`) could be reported as the alarm list.
 *
 * Required distinction: nav control present != click delivered != target business page reached.
 * Nothing here taps coordinates or falls back to blind points.
 */
class ClockTabReachTest {
    private val ctx = mockk<Context>()
    private val clockPkg = "com.sec.android.app.clockpackage"

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(UiAutomator, IntentActions)
        every { UiAutomator.foregroundPackage() } returns clockPkg
        every { IntentActions.nextAlarm(ctx) } returns null
    }

    @After fun cleanup() = unmockkAll()

    @Test fun `click failure with only the tab label visible is not a reached tab`() {
        every { UiAutomator.clickText("闹钟") } returns false
        every { UiAutomator.waitForText("闹钟", any()) } returns true
        every { UiAutomator.tabSelected("闹钟") } returns null
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00", "9:00", "北京"), nodesVisited = 40, rootPackage = clockPkg, serviceAvailable = true)

        val r = ClockControl.queryAlarms(ctx)

        assertThat(r.verified).isFalse()
        assertThat(r.speak).contains("无法读取闹钟列表")
        assertThat(r.speak).doesNotContain("当前可见列表")
        assertThat(r.detail).contains("tab=click-failed-label-only")
    }

    @Test fun `timer query with only a visible label never reports zero timers`() {
        every { UiAutomator.clickText("定时器") } returns false
        every { UiAutomator.waitForText("定时器", any()) } returns true
        every { UiAutomator.tabSelected("定时器") } returns null
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("添加定时器"), nodesVisited = 12, rootPackage = clockPkg, serviceAvailable = true)

        val r = ClockControl.queryTimers(ctx)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).doesNotContain("现在没有正在运行的倒计时")
        assertThat(r.detail).contains("tab=click-failed-label-only")
    }

    @Test fun `a delivered click on a tab that reports not selected is not a reached tab`() {
        every { UiAutomator.clickText("闹钟") } returns true
        every { UiAutomator.tabSelected("闹钟") } returns false
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00"), nodesVisited = 40, rootPackage = clockPkg, serviceAvailable = true)

        val r = ClockControl.queryAlarms(ctx)

        assertThat(r.verified).isFalse()
        assertThat(r.speak).contains("无法读取闹钟列表")
        assertThat(r.detail).contains("tab=clicked-not-selected")
    }

    @Test fun `an already selected tab counts as reached without a click`() {
        every { UiAutomator.clickText("闹钟") } returns false
        every { UiAutomator.waitForText("闹钟", any()) } returns true
        every { UiAutomator.tabSelected("闹钟") } returns true
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00"), nodesVisited = 40, rootPackage = clockPkg, serviceAvailable = true)

        val r = ClockControl.queryAlarms(ctx)

        assertThat(r.verified).isTrue()
        assertThat(r.detail).contains("tab=selected")
    }

    @Test fun `close alarm refuses to read a foreign window`() {
        // closeAlarm used to read the raw active window: our own voice window would look like an
        // alarm list without the target time, i.e. "no such alarm" instead of "could not read".
        every { UiAutomator.clickText("闹钟") } returns true
        every { UiAutomator.tabSelected("闹钟") } returns null
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("ClosePaw"), nodesVisited = 3, rootPackage = "ai.closepaw", serviceAvailable = true)

        val r = ClockControl.closeAlarm(ctx, 8, 0)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.speak).doesNotContain("没有8点0分的闹钟")
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    @Test fun `close alarm with a click-only tab reach does not claim the alarm is absent`() {
        every { UiAutomator.clickText("闹钟") } returns false
        every { UiAutomator.waitForText("闹钟", any()) } returns true
        every { UiAutomator.tabSelected("闹钟") } returns null
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("9:00"), nodesVisited = 20, rootPackage = clockPkg, serviceAvailable = true)

        val r = ClockControl.closeAlarm(ctx, 8, 0)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).doesNotContain("没有8点0分的闹钟")
    }
}
