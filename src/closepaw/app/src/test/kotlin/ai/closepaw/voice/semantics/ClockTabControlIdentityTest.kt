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
 * Review `6543364579` items 2–3: the clock tab click still went through `UiAutomator.click`, which
 * climbs to a clickable parent and finally injects a screen-coordinate gesture, and the selected
 * evidence accepted *any* same-package node carrying the exact label (not necessarily a nav tab).
 *
 * These tests pin the narrowed contract:
 *  - a tab control only exists inside a navigation container (>= 2 distinct clock tab labels),
 *  - clicking it is ACTION_CLICK on the control or on a clickable ancestor *inside* that container,
 *  - no generic `click`/`tap`/coordinate fallback is ever reached for navigation,
 *  - selection evidence is only read from the control up to its container.
 */
class ClockTabControlIdentityTest {
    private val ctx = mockk<Context>()
    private val clockPkg = "com.sec.android.app.clockpackage"

    private fun clickClockTab(tab: String): Any? {
        val m = ClockControl::class.java.getDeclaredMethod("clickClockTab", String::class.java)
        m.isAccessible = true
        return m.invoke(ClockControl, tab)
    }

    private fun n(
        text: String? = null,
        selected: Boolean = false,
        clickable: Boolean = false,
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.text } returns text
        every { node.contentDescription } returns null
        every { node.packageName } returns clockPkg
        every { node.isSelected } returns selected
        every { node.isClickable } returns clickable
        every { node.performAction(any()) } returns true
        every { node.childCount } returns 0
        every { node.parent } returns null
        return node
    }

    private fun container(vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val c = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { c.text } returns null
        every { c.contentDescription } returns null
        every { c.packageName } returns clockPkg
        every { c.isSelected } returns false
        every { c.isClickable } returns false
        every { c.performAction(any()) } returns false
        every { c.parent } returns null
        every { c.childCount } returns children.size
        children.forEachIndexed { i, child -> every { c.getChild(i) } returns child }
        return c
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

    @Test fun `exact clock label without a navigation container is not a tab control`() {
        // A same-package row/section that happens to read 闹钟 is not the nav tab.
        val row = n("闹钟", selected = true, clickable = true)
        every { UiAutomator.root() } returns row
        assertThat(clickClockTab("闹钟")).isNotEqualTo(true)
        verify(exactly = 0) { row.performAction(any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
        verify(exactly = 0) { UiAutomator.tap(any(), any()) }
    }

    @Test fun `tab inside nav container is clicked only with ACTION_CLICK on the control`() {
        val tab = n("闹钟", clickable = true)
        val peer = n("定时器")
        val bar = container(tab, peer)
        every { tab.parent } returns bar
        every { peer.parent } returns bar
        every { UiAutomator.root() } returns container(bar)
        assertThat(clickClockTab("闹钟")).isEqualTo(true)
        verify(exactly = 1) { tab.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { peer.performAction(any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
        verify(exactly = 0) { UiAutomator.tap(any(), any()) }
        verify(exactly = 0) { bar.performAction(any()) }
    }

    @Test fun `non-clickable tab with no clickable ancestor never falls back to a gesture`() {
        val tab = n("闹钟", clickable = false)
        val peer = n("定时器")
        val bar = container(tab, peer)
        every { tab.parent } returns bar
        every { peer.parent } returns bar
        every { UiAutomator.root() } returns container(bar)
        assertThat(clickClockTab("闹钟")).isNotEqualTo(true)
        verify(exactly = 0) { tab.performAction(any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
        verify(exactly = 0) { UiAutomator.tap(any(), any()) }
    }

    @Test fun `clickable ancestor inside the nav bar may carry the click but never a gesture`() {
        val label = n("闹钟")
        val item = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { item.text } returns null
        every { item.contentDescription } returns null
        every { item.packageName } returns clockPkg
        every { item.isSelected } returns false
        every { item.isClickable } returns true
        every { item.performAction(any()) } returns true
        every { item.childCount } returns 1
        every { item.getChild(0) } returns label
        every { label.parent } returns item
        val peer = n("定时器")
        val bar = container(item, peer)
        every { item.parent } returns bar
        every { peer.parent } returns bar
        every { UiAutomator.root() } returns container(bar)
        assertThat(clickClockTab("闹钟")).isEqualTo(true)
        verify(exactly = 1) { item.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { UiAutomator.click(any()) }
        verify(exactly = 0) { UiAutomator.tap(any(), any()) }
    }

    @Test fun `selected state of a non-navigation exact label is unknown`() {
        val row = n("闹钟", selected = true)
        assertThat(UiAutomator.selectedState(container(row), "闹钟")).isNull()
    }

    @Test fun `a screen read does not take a non-navigation label as the alarm page`() {
        // Same clock package, an exact "闹钟" label that is a section header (no sibling tab), and
        // selection carried by its parent: the read must still report the tab as unknown.
        val header = n("闹钟", selected = false)
        val headerItem = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { headerItem.text } returns null
        every { headerItem.contentDescription } returns null
        every { headerItem.packageName } returns clockPkg
        every { headerItem.isSelected } returns true
        every { headerItem.childCount } returns 1
        every { headerItem.getChild(0) } returns header
        every { header.parent } returns headerItem
        val read = UiAutomator.readTree(container(headerItem, n("8:00")), 60, true)
        assertThat(read.selectedTabs["闹钟"]).isNull()
        assertThat(read.selectedTabs["定时器"]).isNull()
    }

    @Test fun `selected state is read from the tab and its nav container only`() {
        val tab = n("闹钟", selected = false)
        val peer = n("定时器", selected = true)
        val bar = container(tab, peer)
        every { tab.parent } returns bar
        every { peer.parent } returns bar
        assertThat(UiAutomator.selectedState(container(bar), "闹钟")).isFalse()
        every { tab.isSelected } returns true
        assertThat(UiAutomator.selectedState(container(bar), "闹钟")).isTrue()
    }
}
