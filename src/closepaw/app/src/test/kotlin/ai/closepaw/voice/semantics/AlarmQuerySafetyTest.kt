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
        every { UiAutomator.screenTexts(any()) } returns listOf("闹钟", "8:00", "已关闭")
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
        every { UiAutomator.screenTexts(any()) } returns listOf("闹钟", "暂无闹钟")
        val r = ClockControl.queryAlarms(ctx)
        assertThat(r.speak).contains("闹钟列表为空")
        assertThat(r.verified).isTrue()
    }
    @Test fun `unreadable UI cannot verify empty list`() {
        every { UiAutomator.screenTexts(any()) } returns emptyList()
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
        verify(exactly = 0) { UiAutomator.screenTexts(any()) }
    }
}
