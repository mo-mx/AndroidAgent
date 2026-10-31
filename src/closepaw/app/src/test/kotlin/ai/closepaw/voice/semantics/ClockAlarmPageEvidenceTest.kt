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
 * Review `6539848799` (head 3253319) P2, second half, plus the recovery half of P1.
 *
 * These cases need the fields and helpers this round adds (`ScreenRead.checkableCount`), so they
 * cannot be compiled against the previous commit; the red half of the same contract lives in
 * `ClockWindowGateTest` (foreign tree, foreign node owner, `添加定时器` under truncation, clock page
 * without alarm proof).
 *
 * What is pinned here:
 *  - the alarm list is only enumerated when the page structurally *is* the alarm list (switches) or
 *    the tab reports selected, so world-clock `H:mm` rows cannot become alarms;
 *  - the verified window is re-acquired by reopening the clock and re-selecting the tab, exactly
 *    like the read path, and the bound node is the one that gets clicked;
 *  - if the window never becomes confirmable, nothing is clicked and the answer is "cannot confirm".
 *
 * Synthetic accessibility trees: JVM contract evidence, not device acceptance.
 */
class ClockAlarmPageEvidenceTest {
    private val ctx = mockk<Context>()
    private val clockPkg = "com.sec.android.app.clockpackage"

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
        children.forEach { c -> every { c.parent } returns n }
        every { UiAutomator.selectedState(n, any()) } returns true
        return n
    }

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(UiAutomator, IntentActions, AppRegistry)
        every { UiAutomator.foregroundPackage() } returns clockPkg
        every { UiAutomator.root() } returns null
        every { UiAutomator.waitForText(any(), any()) } returns true
        every { UiAutomator.click(any()) } returns true
        every { UiAutomator.clickText(any()) } returns true
        every { UiAutomator.tabSelected(any()) } returns true
        every { IntentActions.nextAlarm(ctx) } returns null
        every { AppRegistry.find(any(), any()) } returns AppRegistry.App("时钟", clockPkg, emptyList())
        every { AppRegistry.launch(any(), any()) } returns ToolResult.ok("app", "已打开时钟", verified = true)
    }

    @After fun cleanup() = unmockkAll()

    @Test fun `switches without selected tab cannot establish alarm page`() {
        every { UiAutomator.tabSelected("闹钟") } returns true
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00"), rootPackage = clockPkg, serviceAvailable = true,
            checkableCount = 2, selectedTabs = mapOf("闹钟" to false))
        val r = ClockControl.queryAlarms(ctx)
        assertThat(r.verified).isFalse()
        assertThat(r.detail).contains("alarmPage=false")
        assertThat(r.speak).contains("无法")
        assertThat(r.speak).doesNotContain("当前可见列表")
        assertThat(r.speak).doesNotContain("闹钟列表为空")
    }

    @Test fun `empty marker without selected tab cannot establish alarm page`() {
        every { UiAutomator.tabSelected("闹钟") } returns true
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("暂无闹钟"), rootPackage = clockPkg, serviceAvailable = true,
            selectedTabs = mapOf("闹钟" to null))
        val r = ClockControl.queryAlarms(ctx)
        assertThat(r.verified).isFalse()
        assertThat(r.detail).contains("alarmPage=false")
        assertThat(r.speak).contains("无法")
        assertThat(r.speak).doesNotContain("当前可见列表")
        assertThat(r.speak).doesNotContain("闹钟列表为空")
    }

    @Test fun `fresh selected alarm read is independent of navigation selection`() {
        val tab = node("闹钟")
        every { tab.isClickable } returns true
        every { tab.performAction(any()) } returns true
        val peer = node("定时器")
        every { peer.isClickable } returns true
        val nav = node("", clockPkg, tab, peer)
        // Round 19: a tab bar is never the window root, so the fixture needs a window above it.
        every { UiAutomator.root() } returns node("", clockPkg, nav)
        every { UiAutomator.tabSelected("闹钟") } returns null
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(selectedTabs = mapOf("闹钟" to true, "定时器" to true),
            texts = listOf("8:00", "9:00"), nodesVisited = 40, rootPackage = clockPkg,
            serviceAvailable = true, checkableCount = 2)

        val r = ClockControl.queryAlarms(ctx)

        assertThat(r.verified).isTrue()
        assertThat(r.detail).contains("alarmPage=true")
        assertThat(r.detail).contains("checkables=2")
        assertThat(r.speak).contains("当前可见列表")
    }

    @Test fun `alarm page without switches and without selection stays unverified`() {
        every { UiAutomator.tabSelected("闹钟") } returns null
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00"), nodesVisited = 40, rootPackage = clockPkg,
            serviceAvailable = true, checkableCount = 0)

        val r = ClockControl.queryAlarms(ctx)

        assertThat(r.verified).isFalse()
        assertThat(r.detail).contains("alarmPage=false")
        assertThat(r.speak).doesNotContain("当前可见列表")
    }

    @Test fun `target outside loaded scroll viewport is not declared nonexistent`() {
        // Recycler-style viewport: the off-screen 08:00 row is not loaded into this tree.
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(selectedTabs = mapOf("闹钟" to true, "定时器" to true),
            texts = listOf("9:00"), nodesVisited = 30, rootPackage = clockPkg,
            serviceAvailable = true, checkableCount = 1)

        val r = ClockControl.closeAlarm(ctx, 8, 0)

        assertThat(r.verified).isFalse()
        assertThat(r.speak).contains("当前可见区域未找到")
        assertThat(r.speak).contains("不能确认不存在")
        assertThat(r.speak).doesNotContain("不用关闭")
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    @Test fun `explicit empty marker still proves an empty alarm list`() {
        val tab = node("闹钟")
        every { tab.isClickable } returns true
        every { tab.performAction(any()) } returns true
        val peer = node("定时器")
        every { peer.isClickable } returns true
        val nav = node("", clockPkg, tab, peer)
        // Round 19: a tab bar is never the window root, so the fixture needs a window above it.
        every { UiAutomator.root() } returns node("", clockPkg, nav)
        every { UiAutomator.tabSelected("闹钟") } returns null
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(selectedTabs = mapOf("闹钟" to true, "定时器" to true),
            texts = listOf("暂无闹钟"), nodesVisited = 12, rootPackage = clockPkg,
            serviceAvailable = true, checkableCount = 0)

        val r = ClockControl.queryAlarms(ctx)

        assertThat(r.verified).isTrue()
        assertThat(r.speak).contains("闹钟列表为空")
    }

    @Test fun `close alarm re-acquires the clock window when our own window took the foreground`() {
        // First root read is our own window; the recovery puts the clock back and re-selects 闹钟.
        val toggle = node("8:00")
        every { toggle.isCheckable } returns true
        every { toggle.isChecked } returns true
        every { toggle.isClickable } returns true
        every { toggle.performAction(any()) } returns true
        val clockRoot = node("", clockPkg, node("", clockPkg, node("8:00"), toggle))
        // First read: the tab click lookup. Second: the window gate. Both see our own window; the
        // gate then reopens the clock and re-selects the tab, and the third read is the clock.
        every { UiAutomator.root() } returnsMany listOf(
            node("ClosePaw", "ai.closepaw"), node("ClosePaw", "ai.closepaw"), clockRoot)
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(selectedTabs = mapOf("闹钟" to true, "定时器" to true),
            texts = listOf("8:00"), nodesVisited = 30, rootPackage = clockPkg,
            serviceAvailable = true, checkableCount = 1)

        // Positive recovery now requires evidence from the recovered binding tree itself.
        every { UiAutomator.selectedState(clockRoot, "闹钟") } returns true
        val r = ClockControl.closeAlarm(ctx, 8, 0)

        assertThat(r.status).isEqualTo(ToolStatus.BLOCKED)
        assertThat(r.speak).contains("对象身份")
        assertThat(r.verified).isFalse() // recovered viewport is not global object identity
        verify(exactly = 0) { toggle.performAction(any()) }
        verify(exactly = 1) { AppRegistry.launch(any(), any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    @Test fun `timer cancel clicks the bound clock node after recovering the window`() {
        val cancel = node("取消")
        every { cancel.isClickable } returns true
        every { cancel.performAction(any()) } returns true
        val clockRoot = node("", clockPkg,
            node("", clockPkg, node("初始时长：5分钟"), cancel))
        every { UiAutomator.root() } returnsMany listOf(
            node("ClosePaw", "ai.closepaw"), node("ClosePaw", "ai.closepaw"), clockRoot)
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("5 分钟", "取消"), nodesVisited = 30, rootPackage = clockPkg, serviceAvailable = true)

        every { UiAutomator.selectedState(clockRoot, "定时器") } returns true
        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.ok).isTrue()
        // The click was delivered but no business state was read back: unknown, not "unchanged".
        assertThat(r.verified).isNull()
        verify(exactly = 1) { cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    @Test fun `timer cancel never clicks when no clock window can be confirmed`() {
        every { UiAutomator.root() } returns node("ClosePaw", "ai.closepaw",
            node("取消", "ai.closepaw", node("初始时长：5分钟", "ai.closepaw")))
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("5 分钟", "取消"), nodesVisited = 30, rootPackage = clockPkg, serviceAvailable = true)

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.detail).contains("window=not-clock")
        verify(exactly = 0) { UiAutomator.click(any()) }
    }
}
