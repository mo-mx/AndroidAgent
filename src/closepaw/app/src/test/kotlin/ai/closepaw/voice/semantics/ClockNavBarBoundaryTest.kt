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
 * Review round 18 (`chatgpt-review SHA:56fd90b`): the navigation-bar heuristic was unbounded.
 * `navControl` climbed from a same-package exact label until *any* ancestor showed two clock tab
 * labels — which the whole page root always does once the body holds a "闹钟" header and a
 * "定时器" row. `clickTabControl` could then click that ancestor, and the container's own
 * `isSelected` was accepted as tab-selection evidence.
 *
 * These tests pin the narrowed, fail-closed contract:
 *  - the window/page root is never a navigation bar,
 *  - the peer tab labels in the bar must sit at comparable depth (a real tab strip),
 *  - the bar is bounded in size (a content container that swallowed the page is not a bar),
 *  - the bar container itself is never clicked,
 *  - selection is read from the control up to the bar, never from the bar container itself.
 *
 * All fixtures are synthetic accessibility trees; nothing here is device evidence.
 */
class ClockNavBarBoundaryTest {
    private val ctx = mockk<Context>()
    private val clockPkg = "com.sec.android.app.clockpackage"

    private fun clickClockTab(tab: String): Boolean? {
        val m = ClockControl::class.java.getDeclaredMethod("clickClockTab", String::class.java)
        m.isAccessible = true
        return m.invoke(ClockControl, tab) as Boolean?
    }

    private fun n(
        text: String? = null,
        selected: Boolean = false,
        clickable: Boolean = false,
        containerOf: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.text } returns text
        every { node.contentDescription } returns null
        every { node.packageName } returns clockPkg
        every { node.isSelected } returns selected
        every { node.isClickable } returns clickable
        every { node.performAction(any()) } returns true
        every { node.parent } returns null
        every { node.childCount } returns containerOf.size
        containerOf.forEachIndexed { i, child -> every { node.getChild(i) } returns child }
        containerOf.forEach { every { it.parent } returns node }
        return node
    }

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

    @Test
    fun `the page root that happens to hold two clock labels is not a navigation bar`() {
        // Body content: an alarm section header ("闹钟") and an unrelated "定时器" row deeper in a
        // card. The root shows both labels, so the old climb accepted it as the tab bar.
        val card = n()
        val timerRow = n("定时器", clickable = true)
        card.let { every { it.childCount } returns 1; every { it.getChild(0) } returns timerRow }
        every { timerRow.parent } returns card
        val header = n("闹钟", selected = true, clickable = true)
        val page = n(containerOf = listOf(header, card))
        every { UiAutomator.root() } returns page

        assertThat(clickClockTab("闹钟")).isNotEqualTo(true)
        verify(exactly = 0) { header.performAction(any()) }
        verify(exactly = 0) { timerRow.performAction(any()) }
        verify(exactly = 0) { page.performAction(any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
        verify(exactly = 0) { UiAutomator.tap(any(), any()) }
        assertThat(UiAutomator.selectedState(page, "闹钟")).isNull()
    }

    @Test
    fun `a content container whose two labels sit at different depths is not a navigation bar`() {
        // A non-root body container: "闹钟" at depth 1, "定时器" three levels down inside a section.
        val section = n()
        val group = n(containerOf = listOf(n("定时器")))
        every { section.childCount } returns 1
        every { section.getChild(0) } returns group
        every { group.parent } returns section
        val tab = n("闹钟", clickable = true)
        val body = n(containerOf = listOf(tab, section))
        every { UiAutomator.root() } returns n(containerOf = listOf(body))

        assertThat(clickClockTab("闹钟")).isNotEqualTo(true)
        verify(exactly = 0) { tab.performAction(any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
        verify(exactly = 0) { UiAutomator.tap(any(), any()) }
        assertThat(UiAutomator.selectedState(body, "闹钟")).isNull()
    }

    @Test
    fun `an oversized ancestor full of content is not a navigation bar`() {
        val tab = n("闹钟", clickable = true)
        val peer = n("定时器", clickable = true)
        val rows = (0 until 20).map { n(containerOf = listOf(n("08:0$it"), n("重复"))) }
        val bar = n(containerOf = listOf(tab, peer) + rows)
        every { UiAutomator.root() } returns n(containerOf = listOf(bar))

        assertThat(clickClockTab("闹钟")).isNotEqualTo(true)
        verify(exactly = 0) { tab.performAction(any()) }
        verify(exactly = 0) { peer.performAction(any()) }
        verify(exactly = 0) { bar.performAction(any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
        verify(exactly = 0) { UiAutomator.tap(any(), any()) }
    }

    @Test
    fun `the navigation bar container itself is never clicked`() {
        val tab = n("闹钟", clickable = false)
        val peer = n("定时器", clickable = false)
        val bar = n(clickable = true, containerOf = listOf(tab, peer))
        every { UiAutomator.root() } returns n(containerOf = listOf(bar))

        assertThat(clickClockTab("闹钟")).isNotEqualTo(true)
        verify(exactly = 0) { bar.performAction(any()) }
        verify(exactly = 0) { tab.performAction(any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
        verify(exactly = 0) { UiAutomator.tap(any(), any()) }
    }

    @Test
    fun `selection carried only by the nav bar container is not tab selection`() {
        val tab = n("闹钟", selected = false)
        val peer = n("定时器", selected = false)
        val bar = n(selected = true, containerOf = listOf(tab, peer))
        val root = n(containerOf = listOf(bar))

        assertThat(UiAutomator.selectedState(root, "闹钟")).isFalse()
    }

    @Test
    fun `a genuine tab bar still resolves and selects`() {
        // Control case for the narrowed rules: four peer labels at one depth inside a small bar.
        val tab = n("闹钟", selected = true, clickable = true)
        val peers = listOf(n("定时器", clickable = true), n("世界时钟", clickable = true), n("秒表", clickable = true))
        val bar = n(containerOf = listOf(tab) + peers)
        val root = n(containerOf = listOf(n("08:30"), bar))
        every { UiAutomator.root() } returns root

        assertThat(clickClockTab("闹钟")).isEqualTo(true)
        verify(exactly = 1) { tab.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { bar.performAction(any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
        verify(exactly = 0) { UiAutomator.tap(any(), any()) }
        assertThat(UiAutomator.selectedState(root, "闹钟")).isTrue()
        assertThat(UiAutomator.selectedState(root, "定时器")).isFalse()
    }
}
