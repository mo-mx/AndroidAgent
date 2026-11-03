package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.IntentActions
import ai.closepaw.voice.tools.ToolStatus
import ai.closepaw.voice.tools.UiAutomator
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Review round 18 (`chatgpt-review SHA:56fd90b`) asked for fixtures where a *content* page carries
 * two clock tab labels but has no navigation bar, proved through the real entry points: zero clicks,
 * `selectedState` null, and query / binding refusal.
 *
 * Round 19 pinned the structure rules on `UiAutomator.navControl` directly. This class drives the
 * actual `ClockControl` entry points (`queryTimers`, `queryAlarms`, `cancelTimer`, `closeAlarm`)
 * against such a tree, so the whole chain is covered: `clickClockTab` → `navControl` →
 * `clickTabControl`, then the read/bind gates.
 *
 * One hardening is pinned here too: page content lives inside a scrolling list, a tab strip does
 * not. A candidate bar that has a scrollable ancestor is rejected instead of being clicked.
 *
 * All trees are synthetic accessibility fixtures; nothing here is device evidence.
 */
class ClockContentLabelGateTest {
    private val ctx = mockk<Context>()
    private val clockPkg = "com.sec.android.app.clockpackage"
    private val nodes = ArrayList<AccessibilityNodeInfo>()

    private fun n(
        text: String? = null,
        clickable: Boolean = false,
        selected: Boolean = false,
        scrollable: Boolean = false,
        pkg: String = clockPkg,
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.text } returns text
        every { node.contentDescription } returns null
        every { node.packageName } returns pkg
        every { node.isClickable } returns clickable
        every { node.isSelected } returns selected
        every { node.isScrollable } returns scrollable
        every { node.isCheckable } returns false
        every { node.performAction(any()) } returns true
        every { node.parent } returns null
        every { node.childCount } returns children.size
        children.forEachIndexed { i, child -> every { node.getChild(i) } returns child }
        children.forEach { every { it.parent } returns node }
        nodes.add(node)
        return node
    }

    /**
     * A settings-style page: the window root holds a body with a `闹钟` section header and a
     * `定时器` row. Both labels are same-package exact matches, so the old two-label rule accepted
     * the body as a tab bar and the click landed on the row.
     */
    private fun contentPage(scrollList: Boolean): AccessibilityNodeInfo {
        val header = n("闹钟")
        val row = n("定时器", clickable = true)
        val body = n(children = listOf(header, row), scrollable = scrollList)
        return n(children = listOf(body))
    }

    /** No node of the tree may have received any action (click, focus, set-text) at all. */
    private fun assertNoActions() {
        nodes.forEach { node -> verify(exactly = 0) { node.performAction(any()) } }
    }

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(UiAutomator, IntentActions)
        every { UiAutomator.foregroundPackage() } returns clockPkg
        every { IntentActions.nextAlarm(ctx) } returns null
        // No navigation bar on this tree → no tab-selection evidence, and the label is still visible.
        every { UiAutomator.tabSelected(any()) } returns null
        every { UiAutomator.waitForText(any(), any()) } returns true
    }

    @After fun cleanup() = unmockkAll()

    private fun stubRead(texts: List<String>, selectedTabs: Map<String, Boolean?> = emptyMap()) {
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = texts, nodesVisited = texts.size, rootPackage = clockPkg,
            selectedTabs = selectedTabs, serviceAvailable = true)
    }

    @Test
    fun `a content body with two clock labels is never a navigation bar`() {
        val root = contentPage(scrollList = false)
        every { UiAutomator.root() } returns root

        val m = ClockControl::class.java.getDeclaredMethod("clickClockTab", String::class.java)
        m.isAccessible = true
        assertThat(m.invoke(ClockControl, "定时器")).isEqualTo(null)
        assertThat(m.invoke(ClockControl, "闹钟")).isEqualTo(null)
        assertNoActions()
    }

    @Test
    fun `a tab-shaped container inside a scrolling list is not a navigation bar`() {
        val tabA = n("闹钟", clickable = true)
        val tabB = n("定时器", clickable = true)
        val bar = n(children = listOf(tabA, tabB))
        val list = n(children = listOf(bar), scrollable = true)
        val root = n(children = listOf(list))
        every { UiAutomator.root() } returns root

        assertThat(UiAutomator.navControl(root, "定时器")).isNull()

        val m = ClockControl::class.java.getDeclaredMethod("clickClockTab", String::class.java)
        m.isAccessible = true
        assertThat(m.invoke(ClockControl, "定时器")).isEqualTo(null)
        assertNoActions()
    }

    @Test
    fun `a genuine bottom tab strip is still a navigation bar`() {
        // Control group: the fail-closed rules above must not reject a real strip.
        val labels = listOf("闹钟", "定时器", "世界时钟", "秒表").map { n(it, clickable = true) }
        val bar = n(children = labels)
        val root = n(children = listOf(n(clickable = true), bar))

        val nav = UiAutomator.navControl(root, "定时器")
        assertThat(nav).isNotNull()
        assertThat(nav!!.container).isSameInstanceAs(bar)
        assertThat(nav.control).isSameInstanceAs(labels[1])
    }

    @Test
    fun `timer query on a content page refuses instead of counting`() {
        every { UiAutomator.root() } returns contentPage(scrollList = false)
        stubRead(listOf("闹钟", "定时器", "添加定时器"), selectedTabs = mapOf("定时器" to null))

        val r = ClockControl.queryTimers(ctx)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isFalse()
        assertThat(r.speak).doesNotContain("现在没有正在运行的倒计时")
        assertThat(r.detail).contains("tab=click-failed-label-only")
        assertNoActions()
    }

    @Test
    fun `alarm query on a content page never claims the list is empty`() {
        every { UiAutomator.root() } returns contentPage(scrollList = false)
        stubRead(listOf("闹钟", "定时器"))

        val r = ClockControl.queryAlarms(ctx)

        assertThat(r.verified).isFalse()
        assertThat(r.speak).doesNotContain("闹钟列表为空")
        assertThat(r.detail).contains("tab=click-failed-label-only")
        assertNoActions()
    }

    @Test
    fun `cancel timer on a content page clicks nothing`() {
        every { UiAutomator.root() } returns contentPage(scrollList = false)
        stubRead(listOf("闹钟", "定时器", "取消"))

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.ok).isFalse()
        assertThat(r.detail).contains("clock not reachable")
        assertNoActions()
    }

    @Test
    fun `close alarm on a content page never claims absence and clicks nothing`() {
        every { UiAutomator.root() } returns contentPage(scrollList = false)
        stubRead(listOf("闹钟", "定时器", "8:00"))

        val r = ClockControl.closeAlarm(ctx, 8, 0)

        assertThat(r.ok).isFalse()
        assertThat(r.verified).isFalse()
        assertThat(r.speak).doesNotContain("不存在")
        assertNoActions()
    }
}
