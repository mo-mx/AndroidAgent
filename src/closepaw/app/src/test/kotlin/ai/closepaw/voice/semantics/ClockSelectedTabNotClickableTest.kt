package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.UiAutomator
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
 * Device evidence (2026-11-03, SM-F731U1 / Android 17 / Clock 12.5.60.27): on One UI 9 the tab that
 * is currently selected is **not clickable** (`clickable=false, selected=true`), while the other
 * three tabs are. The old `isNavBar` required a click unit for *every* peer label, so the whole strip
 * was rejected as soon as one tab was selected — which made `navControl` return null and produced
 * `tab=click-failed-label-only` for every clock query, even though the app was already sitting on the
 * target page.
 *
 * The fix must keep the safety property that motivated the old rule: two labels that share one
 * clickable card are a content page, not a tab strip. So a label may lack a click unit **only when
 * the strip reports it as selected** — selection is the only legitimate reason for a tab to be
 * unclickable, and a plain label without selection evidence still refuses.
 *
 * All trees here are synthetic accessibility fixtures: JVM contract only, no device evidence.
 */
class ClockSelectedTabNotClickableTest {
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
        every { node.isChecked } returns false
        every { node.performAction(any()) } returns true
        every { node.parent } returns null
        every { node.childCount } returns children.size
        children.forEachIndexed { i, child -> every { node.getChild(i) } returns child }
        children.forEach { every { it.parent } returns node }
        every { node.refresh() } returns true
        every { node.isVisibleToUser } returns true
        nodes.add(node)
        return node
    }

    /**
     * One UI 9 shape: the selected tab reports selection but is not clickable; the other tabs are.
     * Returns the window root and the four tab nodes keyed by label.
     */
    private fun oneUi9Window(selectedTab: String): Pair<AccessibilityNodeInfo, Map<String, AccessibilityNodeInfo>> {
        val tabs = LinkedHashMap<String, AccessibilityNodeInfo>()
        for (label in UiAutomator.CLOCK_TABS) {
            val selected = label == selectedTab
            tabs[label] = n(label, clickable = !selected, selected = selected)
        }
        val strip = n(children = tabs.values.toList())
        val page = n("上午 6", clickable = false)
        return n(children = listOf(page, strip)) to tabs
    }

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After fun cleanup() = unmockkAll()

    @Test fun `selected tab without a click unit still proves the strip is a navigation bar`() {
        val (root, _) = oneUi9Window(selectedTab = "闹钟")
        assertThat(UiAutomator.navControl(root, "闹钟")).isNotNull()
        assertThat(UiAutomator.selectedState(root, "闹钟")).isTrue()
        assertThat(UiAutomator.selectedState(root, "定时器")).isFalse()
    }

    @Test fun `clicking the already-selected tab delivers no click but reports selection`() {
        val (root, tabs) = oneUi9Window(selectedTab = "闹钟")
        mockkObject(UiAutomator)
        every { UiAutomator.root() } returns root

        assertThat(UiAutomator.clickClockNavTab("闹钟")).isFalse()
        for (tab in tabs.values) verify(exactly = 0) { tab.performAction(any()) }
    }

    @Test fun `clicking another tab lands on that tab's own control exactly once`() {
        val (root, tabs) = oneUi9Window(selectedTab = "闹钟")
        mockkObject(UiAutomator)
        every { UiAutomator.root() } returns root

        assertThat(UiAutomator.clickClockNavTab("定时器")).isTrue()
        verify(exactly = 1) { tabs.getValue("定时器").performAction(any()) }
        verify(exactly = 0) { tabs.getValue("闹钟").performAction(any()) }
        verify(exactly = 0) { tabs.getValue("世界时钟").performAction(any()) }
        verify(exactly = 0) { tabs.getValue("秒表").performAction(any()) }
    }

    @Test fun `selection seen after the switch animation still counts as reached`() {
        mockkObject(UiAutomator)
        every { UiAutomator.tabSelected("定时器", any()) } returnsMany listOf(false, true)
        assertThat(UiAutomator.awaitTabSelected("定时器", 600, UiAutomator.CLOCK_TABS)).isTrue()
    }

    @Test fun `a tab that never becomes selected is reported unselected`() {
        mockkObject(UiAutomator)
        every { UiAutomator.tabSelected("定时器", any()) } returns false
        assertThat(UiAutomator.awaitTabSelected("定时器", 300, UiAutomator.CLOCK_TABS)).isFalse()
    }

    @Test fun `picker without a tab strip is left with one BACK and then navigated`() {
        val ctx = mockk<android.content.Context>(relaxed = true)
        mockkObject(UiAutomator, ai.closepaw.voice.tools.IntentActions)
        every { ai.closepaw.voice.tools.IntentActions.nextAlarm(ctx) } returns null
        every { UiAutomator.foregroundPackage() } returns clockPkg
        every { UiAutomator.clickClockNavTab("闹钟") } returnsMany listOf(null, true)
        every { UiAutomator.back() } returns true
        every { UiAutomator.tabSelected(any(), any()) } returns true
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("6:00", "7:30"), rootPackage = clockPkg, serviceAvailable = true)

        val r = ClockControl.queryAlarms(ctx)

        verify(exactly = 1) { UiAutomator.back() }
        assertThat(r.detail).contains("tab=clicked")
    }

    @Test fun `BACK that lands outside the clock is never followed by a tab click`() {
        val ctx = mockk<android.content.Context>(relaxed = true)
        mockkObject(UiAutomator, ai.closepaw.voice.tools.IntentActions)
        every { ai.closepaw.voice.tools.IntentActions.nextAlarm(ctx) } returns null
        every { UiAutomator.foregroundPackage() } returnsMany
            listOf(clockPkg, clockPkg, "com.android.launcher")
        every { UiAutomator.clickClockNavTab("闹钟") } returns null
        every { UiAutomator.back() } returns true
        every { UiAutomator.tabSelected(any(), any()) } returns null
        every { UiAutomator.waitForText(any(), any()) } returns false
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = emptyList(), rootPackage = "com.android.launcher", serviceAvailable = true)

        val r = ClockControl.queryAlarms(ctx)

        verify(exactly = 1) { UiAutomator.clickClockNavTab("闹钟") }
        assertThat(r.verified).isFalse()
    }

    @Test fun `unselected label without a click unit is still not a tab strip`() {
        // Content page shape: a section header "闹钟" (no click unit, not selected) above a settings
        // row "定时器" that is clickable. The old rejection must survive the fix.
        val header = n("闹钟")
        val row = n("定时器", clickable = true)
        val page = n(children = listOf(header, row))
        val root = n(children = listOf(page))

        assertThat(UiAutomator.navControl(root, "闹钟")).isNull()
        mockkObject(UiAutomator)
        every { UiAutomator.root() } returns root
        assertThat(UiAutomator.clickClockNavTab("闹钟")).isNotEqualTo(true)
        verify(exactly = 0) { row.performAction(any()) }
    }
}
