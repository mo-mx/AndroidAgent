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
 * Review `6539848799` (head 3253319) P1/P2, first half — the parts that can be stated without any
 * new API, so these cases are also red on the previous commit:
 *
 *  - `cancelTimer` scanned `UiAutomator.root()` after only asking `foregroundPackage()`. Those are two
 *    different reads: the foreground check can pass while the tree that is actually bound and clicked
 *    came from another window. The tree used for binding must *be* the clock window, otherwise the
 *    answer is "cannot confirm the window" — never a blind click, never "no such object".
 *  - `closeAlarm` had the same split between the verified read and the second, unverified root read
 *    it bound the switch from.
 *  - A clock page that is not the alarm tab (the world clock also shows `H:mm`) must not be reported
 *    as an alarm list when the tab does not report its selection.
 *  - A truncated tree cannot turn a bare `添加定时器` marker into "zero timers running".
 *
 * Fixtures are synthetic accessibility trees; this is JVM-level contract evidence, not device proof.
 */
class ClockWindowGateTest {
    private val ctx = mockk<Context>()
    private val clockPkg = "com.sec.android.app.clockpackage"
    private val ownPkg = "ai.closepaw"

    private fun node(
        text: String = "",
        pkg: String = clockPkg,
        vararg children: AccessibilityNodeInfo,
    ): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.text } returns text
        every { n.contentDescription } returns null
        every { n.packageName } returns pkg
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
        every { IntentActions.nextAlarm(ctx) } returns null
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00"), nodesVisited = 20, rootPackage = clockPkg, serviceAvailable = true)
    }

    @After fun cleanup() = unmockkAll()

    @Test fun `timer cancel never binds from a tree that is not the clock window`() {
        every { UiAutomator.clickText("定时器") } returns true
        every { UiAutomator.tabSelected("定时器") } returns true
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("5 分钟", "取消"), nodesVisited = 30, rootPackage = clockPkg, serviceAvailable = true)
        val cancel = node("取消", ownPkg)
        every { cancel.isClickable } returns true
        every { cancel.performAction(any()) } returns true
        val duration = node("初始时长：5分钟", ownPkg)
        val card = node("", ownPkg, cancel, duration)
        // The foreground package check would pass, but this tree belongs to our own window.
        every { UiAutomator.root() } returns node("", ownPkg, card)

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).contains("无法确认当前是时钟窗口")
        assertThat(r.speak).doesNotContain("已请求取消")
        verify(exactly = 0) { cancel.performAction(any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    @Test fun `alarm close never binds a switch out of a foreign tree`() {
        every { UiAutomator.clickText("闹钟") } returns true
        every { UiAutomator.tabSelected("闹钟") } returns true
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00"), nodesVisited = 30, rootPackage = clockPkg, serviceAvailable = true)
        val toggle = node("", ownPkg)
        every { toggle.isCheckable } returns true
        every { toggle.isChecked } returns true
        every { toggle.isClickable } returns true
        every { toggle.performAction(any()) } returns true
        every { UiAutomator.root() } returns node("", ownPkg, node("", ownPkg, node("8:00", ownPkg), toggle))

        val r = ClockControl.closeAlarm(ctx, 8, 0)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).doesNotContain("已请求关闭")
        assertThat(r.speak).doesNotContain("已经是关着的")
        verify(exactly = 0) { toggle.performAction(any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    @Test fun `a clock page that does not prove the alarm tab reports no count`() {
        every { UiAutomator.clickText("闹钟") } returns true
        // Selection state is not exposed, and this page has no alarm switches: an `H:mm` list alone
        // could be the world clock, so it cannot be enumerated as alarms.
        every { UiAutomator.tabSelected("闹钟") } returns null
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00", "9:00", "北京"), nodesVisited = 40, rootPackage = clockPkg, serviceAvailable = true)

        val r = ClockControl.queryAlarms(ctx)

        assertThat(r.verified).isFalse()
        assertThat(r.speak).doesNotContain("当前可见列表")
        assertThat(r.detail).contains("alarmPage=false")
        assertThat(r.detail).contains("readable=false")
    }

    @Test fun `truncated timer tree cannot turn the add marker into zero running timers`() {
        // The empty-state marker only means "empty" when the whole page was read.
        assertThat(ClockControl.timerCountFrom(listOf("添加定时器", "取消"), truncated = true)).isEqualTo(-1)
        assertThat(ClockControl.timerCountFrom(listOf("添加定时器", "取消"), truncated = false)).isEqualTo(0)
    }

    @Test fun `truncated timer tree still trusts an explicit running-count header`() {
        assertThat(ClockControl.timerCountFrom(listOf("3 个定时器正在运行"), truncated = true)).isEqualTo(3)
    }

    @Test fun `an unselected tab never produces a timer count`() {
        every { UiAutomator.clickText("定时器") } returns true
        every { UiAutomator.tabSelected("定时器") } returns false
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("5 分钟", "取消", "暂停"), nodesVisited = 30, rootPackage = clockPkg, serviceAvailable = true)

        val r = ClockControl.queryTimers(ctx)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).doesNotContain("现在有1个倒计时在运行")
    }

    @Test fun `timer cancel refuses a cancel button whose node belongs to another app`() {
        every { UiAutomator.clickText("定时器") } returns true
        every { UiAutomator.tabSelected("定时器") } returns true
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("5 分钟", "取消"), nodesVisited = 30, rootPackage = clockPkg, serviceAvailable = true)
        // The root is the clock, but the control that would be clicked claims another owner.
        val cancel = node("取消", ownPkg)
        every { cancel.isClickable } returns true
        every { cancel.performAction(any()) } returns true
        every { UiAutomator.root() } returns node("", clockPkg, node("", clockPkg, cancel, node("初始时长：5分钟")))

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).contains("取消按钮不属于时钟窗口")
        verify(exactly = 0) { cancel.performAction(any()) }
    }

    @Test fun `alarm close refuses a switch whose node belongs to another app`() {
        every { UiAutomator.clickText("闹钟") } returns true
        every { UiAutomator.tabSelected("闹钟") } returns true
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00"), nodesVisited = 30, rootPackage = clockPkg, serviceAvailable = true)
        val toggle = node("", ownPkg)
        every { toggle.isCheckable } returns true
        every { toggle.isChecked } returns true
        every { toggle.isClickable } returns true
        every { toggle.performAction(any()) } returns true
        every { UiAutomator.root() } returns node("", clockPkg, node("", clockPkg, node("8:00"), toggle))

        val r = ClockControl.closeAlarm(ctx, 8, 0)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).contains("这个开关不属于时钟窗口")
        verify(exactly = 0) { toggle.performAction(any()) }
    }
}
