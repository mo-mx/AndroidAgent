package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.*
import android.content.Context
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

class AlarmQuerySafetyTest {
    private val ctx = mockk<Context>()
    @Before fun setup() {
        mockkObject(UiAutomator, IntentActions)
        every { IntentActions.nextAlarm(ctx) } returns null
        every { UiAutomator.foregroundPackage() } returns "com.sec.android.app.clockpackage"
        every { UiAutomator.clickText("闹钟") } returns true
    }
    @After fun cleanup() = unmockkAll()

    @Test fun `null next alarm with disabled entries is not an empty list`() {
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            // A real alarm row carries a switch; without that structural evidence the page could be
            // the world clock and its `H:mm` rows must not be enumerated as alarms.
            texts = listOf("闹钟", "8:00", "已关闭"), nodesVisited = 3, rootPackage = "com.sec.android.app.clockpackage",
            serviceAvailable = true, checkableCount = 1)
        val r = ClockControl.queryAlarms(ctx)
        assertThat(r.speak).contains("没有下一个已启用")
        assertThat(r.speak).contains("列表仍有")
        assertThat(r.speak).contains("已关闭")
        assertThat(r.speak).doesNotContain("列表为空")
        assertThat(r.speak).doesNotContain("没有设置")
        verify(exactly = 0) { IntentActions.setAlarm(any(), any(), any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }
    @Test fun `explicit empty state permits empty list report`() {
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("闹钟", "暂无闹钟"), nodesVisited = 3, rootPackage = "com.sec.android.app.clockpackage", serviceAvailable = true)
        val r = ClockControl.queryAlarms(ctx)
        assertThat(r.speak).contains("闹钟列表为空")
        assertThat(r.verified).isTrue()
    }
    @Test fun `unreadable UI cannot verify empty list`() {
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead()
        val r = ClockControl.queryAlarms(ctx)
        assertThat(r.speak).contains("无法读取闹钟列表")
        assertThat(r.speak).doesNotContain("列表为空")
        assertThat(r.verified).isFalse()
    }
    @Test fun `unreachable alarm tab cannot verify empty list`() {
        every { UiAutomator.clickText("闹钟") } returns false
        every { UiAutomator.waitForText("闹钟", any()) } returns false
        val r = ClockControl.queryAlarms(ctx)
        assertThat(r.speak).contains("无法读取闹钟列表")
        assertThat(r.verified).isFalse()
        // The page is still read, but only to report which boundary was hit; its texts must not
        // become list evidence. Reads are bounded by the foreign-window retry (3 attempts), and the
        // assertions above prove the content was ignored.
        verify(exactly = 3) { UiAutomator.readScreen(any()) }
        assertThat(r.detail).contains("tab=label-not-found")
    }
}
