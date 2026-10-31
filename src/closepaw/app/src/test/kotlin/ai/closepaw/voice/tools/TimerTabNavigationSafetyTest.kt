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

/**
 * Review `6546660320` P2 (item 3): after `ACTION_SET_TIMER`, the verification path called
 * `UiAutomator.clickText("定时器")` — a *containing-text* match whose click climbs up to a clickable
 * parent and finally falls back to a coordinate gesture. That bypassed every constraint already
 * enforced by `ClockControl.clickClockTab` (exact label, clock-owned window, no coordinate fallback),
 * so a content row such as `定时器设置` or a hint card could be tapped while the read still came back
 * from the clock package.
 *
 * Contract under test: the timer-creation read path may only touch the tab bar through the exact
 * navigation control. When no verified control exists (or the control refuses), nothing is clicked
 * and no countdown evidence is derived from whatever page happens to be on screen.
 */
class TimerTabNavigationSafetyTest {
    private val ctx = mockk<Context>()
    private val pm = mockk<PackageManager>()
    private val clockPkg = "com.sec.android.app.clockpackage"

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
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Int>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
        mockkObject(UiAutomator)
        every { UiAutomator.foregroundPackage() } returns clockPkg
        // Loose matchers below still exist in the layer; none of them may be used by this path.
        every { UiAutomator.clickText(any()) } returns true
        every { UiAutomator.click(any<android.view.accessibility.AccessibilityNodeInfo>()) } returns true
        every { UiAutomator.tap(any(), any()) } returns true
    }

    @After fun cleanup() = unmockkAll()

    /** No verified nav control (a hint card with the same text is not one): zero clicks of any kind. */
    @Test fun `missing nav control performs no generic or coordinate click`() {
        every { UiAutomator.clickClockNavTab("定时器") } returns null
        // Even a page that *looks* like a running countdown must not be read as this creation.
        every { UiAutomator.screenTexts(any()) } returns listOf("1 个定时器正在运行", "剩余 04:58")

        val r = IntentActions.setTimer(ctx, 300)

        verify(exactly = 0) { UiAutomator.clickText(any()) }
        verify(exactly = 0) { UiAutomator.click(any<android.view.accessibility.AccessibilityNodeInfo>()) }
        verify(exactly = 0) { UiAutomator.tap(any(), any()) }
        assertThat(r.verified).isNull()
        assertThat(r.speak).contains("无法确认")
        assertThat(r.detail).contains("tab=no-verified-tab-control")
        assertThat(r.detail).doesNotContain("countdown-ui=true")
    }

    /** Control exists but the click was refused: still zero fallbacks, still no evidence claim. */
    @Test fun `refused nav click performs no generic click and claims no countdown`() {
        every { UiAutomator.clickClockNavTab("定时器") } returns false
        every { UiAutomator.screenTexts(any()) } returns listOf("10 分钟 0 秒")

        val r = IntentActions.setTimer(ctx, 300)

        verify(exactly = 0) { UiAutomator.clickText(any()) }
        verify(exactly = 0) { UiAutomator.click(any<android.view.accessibility.AccessibilityNodeInfo>()) }
        assertThat(r.verified).isNull()
        assertThat(r.speak).contains("无法确认")
        assertThat(r.detail).contains("tab=refused")
    }

    /** Control group: the exact tab is clicked once, and it is the only click in the whole path. */
    @Test fun `exact nav tab is the only click and diagnostics name it`() {
        every { UiAutomator.clickClockNavTab("定时器") } returns true
        every { UiAutomator.screenTexts(any()) } returns listOf("1 个定时器正在运行", "剩余 09:58")

        val r = IntentActions.setTimer(ctx, 300)

        verify(exactly = 1) { UiAutomator.clickClockNavTab("定时器") }
        verify(exactly = 0) { UiAutomator.clickText(any()) }
        verify(exactly = 0) { UiAutomator.click(any<android.view.accessibility.AccessibilityNodeInfo>()) }
        verify(exactly = 0) { UiAutomator.tap(any(), any()) }
        assertThat(r.detail).contains("tab=clicked")
        assertThat(r.detail).contains("countdown-ui=true")
        // Even a matching countdown on the timer tab is not this creation's identity.
        assertThat(r.verified).isNull()
    }

    /** Reached tab but no countdown wording: the honest sentence says so instead of inferring success. */
    @Test fun `reached tab without countdown wording never implies success`() {
        every { UiAutomator.clickClockNavTab("定时器") } returns true
        every { UiAutomator.screenTexts(any()) } returns listOf("定时器", "添加定时器")

        val r = IntentActions.setTimer(ctx, 300)

        assertThat(r.verified).isNull()
        assertThat(r.speak).doesNotContain("已经开始")
        assertThat(r.detail).contains("countdown-ui=false")
        verify(exactly = 0) { UiAutomator.clickText(any()) }
    }
}
