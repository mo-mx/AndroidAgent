package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.*
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

/**
 * Review `6539848799` (head 3253319) P1/P2 leftovers:
 *  - `closeAlarm` proved which window it *read* but then bound the switch from a second, unverified
 *    `root()` read; `cancelTimer` scanned `root()` with no window check at all.
 *  - The queries counted `read.texts` without caring that the read had been truncated, so a clipped
 *    tree could become a definite count.
 * Both must fail closed. No coordinate or blind-point fallback is allowed here.
 */
class ClockWindowBindingTest {
    private val ctx = mockk<Context>()
    private val clockPkg = "com.sec.android.app.clockpackage"
    private val ownPkg = "ai.closepaw"

    private fun node(text: String = "", vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.text } returns text
        every { n.contentDescription } returns null
        every { n.isClickable } returns false
        every { n.isCheckable } returns false
        every { n.isChecked } returns false
        every { n.childCount } returns children.size
        children.forEachIndexed { i, c -> every { n.getChild(i) } returns c }
        return n
    }

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(UiAutomator, IntentActions)
        every { UiAutomator.foregroundPackage() } returns clockPkg
        every { UiAutomator.clickText(any()) } returns true
        every { UiAutomator.tabSelected(any()) } returns true
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00"), nodesVisited = 20, rootPackage = clockPkg, serviceAvailable = true)
    }
    @After fun cleanup() = unmockkAll()

    @Test fun `alarm diagnostics never log private screen text`() {
        val secret = "PRIVATE_ALARM_LABEL_8d291"
        every { UiAutomator.root() } returns node()
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00", secret), nodesVisited = 20,
            rootPackage = clockPkg, serviceAvailable = true)
        ClockControl.closeAlarm(ctx, 8, 0)
        verify(exactly = 0) { Log.i(any(), match { it.contains(secret) }) }
    }

    @Test fun `close alarm does not click a switch bound from a window that is no longer the clock`() {
        val toggle = node("8:00")
        every { toggle.isCheckable } returns true
        every { toggle.isChecked } returns true
        every { toggle.isClickable } returns true
        val root = node("", toggle)
        every { UiAutomator.root() } returns root
        // openClockTab, then the pre-scan check, then the pre-click re-check.
        every { UiAutomator.foregroundPackage() } returnsMany listOf(clockPkg, clockPkg, ownPkg)

        val r = ClockControl.closeAlarm(ctx, 8, 0)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isNotEqualTo(true)
        verify(exactly = 0) { toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    @Test fun `close alarm refuses to scan when a foreign window is active`() {
        val toggle = node("8:00")
        every { toggle.isCheckable } returns true
        every { toggle.isChecked } returns true
        every { toggle.isClickable } returns true
        every { UiAutomator.root() } returns node("", toggle)
        every { UiAutomator.foregroundPackage() } returnsMany listOf(clockPkg, ownPkg)

        val r = ClockControl.closeAlarm(ctx, 8, 0)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).doesNotContain("没有8点0分的闹钟")
        verify(exactly = 0) { toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test fun `timer cancel refuses to scan a foreign window`() {
        val cancel = node("取消")
        every { cancel.isClickable } returns true
        val duration = node("初始时长 5 分钟")
        val card = node("", cancel, duration)
        every { UiAutomator.root() } returns node("", card)
        every { UiAutomator.foregroundPackage() } returnsMany listOf(clockPkg, ownPkg)
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("5 分钟", "取消"), nodesVisited = 30, rootPackage = clockPkg, serviceAvailable = true)

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).doesNotContain("已请求取消")
        verify(exactly = 0) { cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test fun `timer count is unknown when the read was truncated`() {
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(selectedTabs = mapOf("闹钟" to true, "定时器" to true),
            texts = listOf("30 分钟", "29 分钟 30 秒钟", "取消", "暂停"),
            nodesVisited = 600, nodesTruncated = true, textsTruncated = true,
            rootPackage = clockPkg, serviceAvailable = true)

        val r = ClockControl.queryTimers(ctx)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).doesNotContain("现在没有正在运行的倒计时")
        assertThat(r.speak).doesNotContain("现在有1个倒计时在运行")
        assertThat(r.detail).contains("count=unparsed")
    }

    @Test fun `timer count keeps an explicit running-count header even when truncated`() {
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(selectedTabs = mapOf("闹钟" to true, "定时器" to true),
            texts = listOf("3 个定时器正在运行"),
            nodesVisited = 600, nodesTruncated = true, textsTruncated = true,
            rootPackage = clockPkg, serviceAvailable = true)

        val r = ClockControl.queryTimers(ctx)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isTrue()
        assertThat(r.detail).contains("count=3")
    }

    @Test fun `alarm list is not readable when the tree was truncated`() {
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00"), nodesVisited = 600, nodesTruncated = true,
            rootPackage = clockPkg, serviceAvailable = true)

        val r = ClockControl.queryAlarms(ctx)

        assertThat(r.verified).isFalse()
        assertThat(r.speak).doesNotContain("当前可见列表有")
        assertThat(r.detail).contains("readable=false")
        assertThat(r.detail).contains("truncated=true")
    }
}
