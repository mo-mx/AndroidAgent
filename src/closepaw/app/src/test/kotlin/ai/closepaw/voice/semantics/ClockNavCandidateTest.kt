package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.IntentActions
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
 * Review round 19 (`chatgpt-hourly-review SHA:3b7cc66`) left three P2s on `UiAutomator`. Two of them
 * are structural and can be closed offline; this class pins those.
 *
 * 1. `navControl` used `findFirst { exactLabel }` — the *first* same-name node in the whole tree. A
 *    `闹钟` section header or settings row that comes earlier in the tree made the real bottom tab
 *    strip unreachable, so navigation was refused even though a genuine strip existed. The scan must
 *    consider every exact-label candidate; two *different* strips that both qualify are ambiguous and
 *    are refused rather than silently picking the first.
 * 2. `selectedState` fell back to scanning the *whole window* for any `isSelected` node, so an
 *    unrelated selected body element was reported as "this tab is not selected". Selection must only
 *    be judged inside the identified tab strip; outside evidence is not tab selection.
 *
 * The third round-19 request (a small two-label *content* container passing the structural rules) is
 * not claimed here — see `docs/PROJECT_STATUS.md` for why structure alone cannot settle it.
 *
 * All trees are synthetic accessibility fixtures; none of this is device evidence.
 */
class ClockNavCandidateTest {
    private val ctx = mockk<Context>()
    private val clockPkg = "com.sec.android.app.clockpackage"
    private val nodes = ArrayList<AccessibilityNodeInfo>()

    private fun n(
        text: String? = null,
        clickable: Boolean = false,
        selected: Boolean = false,
        pkg: String = clockPkg,
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.text } returns text
        every { node.contentDescription } returns null
        every { node.packageName } returns pkg
        every { node.isClickable } returns clickable
        every { node.isSelected } returns selected
        every { node.isScrollable } returns false
        every { node.isCheckable } returns false
        every { node.performAction(any()) } returns true
        every { node.parent } returns null
        every { node.childCount } returns children.size
        children.forEachIndexed { i, child -> every { node.getChild(i) } returns child }
        children.forEach { every { it.parent } returns node }
        nodes.add(node)
        return node
    }

    private fun strip(vararg selected: String) =
        n(children = listOf("闹钟", "定时器", "世界时钟", "秒表").map { n(it, clickable = true, selected = it in selected) })

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(UiAutomator, IntentActions)
        every { UiAutomator.foregroundPackage() } returns clockPkg
        every { IntentActions.nextAlarm(ctx) } returns null
        every { UiAutomator.waitForText(any(), any()) } returns true
    }

    @After fun cleanup() = unmockkAll()

    private fun clickClockTab(tab: String): Boolean? {
        val m = ClockControl::class.java.getDeclaredMethod("clickClockTab", String::class.java)
        m.isAccessible = true
        return m.invoke(ClockControl, tab) as Boolean?
    }

    @Test
    fun `a content label earlier in the tree does not preempt the real tab strip`() {
        // 窗口根 > body[「闹钟」段标题, 可点「定时器」设置行], 底部 tab 条
        val header = n("闹钟")
        val row = n("定时器", clickable = true)
        val body = n(children = listOf(header, row))
        val bar = strip("闹钟")
        val root = n(children = listOf(body, bar))
        every { UiAutomator.root() } returns root

        val nav = UiAutomator.navControl(root, "定时器")
        assertThat(nav).isNotNull()
        assertThat(nav!!.container).isSameInstanceAs(bar)

        assertThat(clickClockTab("定时器")).isTrue()
        verify(exactly = 0) { header.performAction(any()) }
        verify(exactly = 0) { row.performAction(any()) }
        verify(exactly = 1) { nav.control.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `two structurally valid strips are ambiguous and neither is clicked`() {
        val barA = strip("闹钟")
        val barB = strip("闹钟")
        val root = n(children = listOf(barA, barB))
        every { UiAutomator.root() } returns root

        assertThat(UiAutomator.navControl(root, "定时器")).isNull()
        assertThat(clickClockTab("定时器")).isNull()
        nodes.forEach { verify(exactly = 0) { it.performAction(any()) } }
    }

    @Test
    fun `selection outside the tab strip is not this tab's selection state`() {
        // 正文卡片 selected=true，导航条本身不暴露任何选中态。
        val card = n("闹钟", selected = true)
        val body = n(children = listOf(card))
        val bar = strip()
        val root = n(children = listOf(body, bar))

        assertThat(UiAutomator.selectedState(root, "定时器")).isNull()
        assertThat(UiAutomator.selectedState(root, "闹钟")).isNull()
    }

    @Test
    fun `a peer selected inside the strip still reports this tab as not selected`() {
        val bar = strip("闹钟")
        val root = n(children = listOf(bar))

        assertThat(UiAutomator.selectedState(root, "定时器")).isFalse()
        assertThat(UiAutomator.selectedState(root, "闹钟")).isTrue()
    }

    @Test
    fun `body selection does not turn into a not-selected verdict in reads`() {
        val card = n("闹钟", selected = true)
        val body = n(children = listOf(card))
        val bar = strip()
        val root = n(children = listOf(body, bar))

        val read = UiAutomator.readTree(root, 60, true)

        // 没有栏内选中证据 → 不可确认，而不是“目标未选中”。
        assertThat(read.selectedTabs["定时器"]).isNull()
    }
}
