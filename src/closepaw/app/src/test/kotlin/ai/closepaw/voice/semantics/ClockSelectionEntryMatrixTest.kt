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
 * Review round 19/21 leftover: `selectedState` was narrowed to the identified tab strip, so a page
 * *body* element that reports `isSelected` can no longer produce a verdict about a tab. That change
 * touched a safety boundary, so this class pins the verdict at every entry that consumes it —
 * `readTree` → `selectedTabs`, `onTargetPage` (both queries and `closeAlarm`) and the binding gate
 * `verifiedClockWindow` (`cancelTimer`).
 *
 * The matrix is deliberately complete in both directions:
 *  - `null` ("this strip reports no selection at all") must never admit: query refuses, binding
 *    refuses, zero clicks are delivered;
 *  - `false` ("another tab is the selected one") must never admit either, and must stay
 *    distinguishable from `null` in the diagnostic reason;
 *  - `true` must still admit, so the cases above cannot pass by refusing everything.
 *
 * All trees are synthetic accessibility fixtures — JVM contract only, no device evidence.
 */
class ClockSelectionEntryMatrixTest {
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
     * Window root > page body (given nodes) + bottom strip with the four clock tabs.
     * [stripSelected] = the one tab the strip reports as selected, or null for "the strip exposes no
     * selection state at all" — the two states the round-19 change separated.
     */
    private fun clockWindow(
        body: List<AccessibilityNodeInfo>,
        stripSelected: String?,
    ): AccessibilityNodeInfo {
        val tabs = UiAutomator.CLOCK_TABS.map { n(it, clickable = true, selected = it == stripSelected) }
        val strip = n(children = tabs)
        val page = n(children = body)
        return n(children = listOf(page, strip))
    }

    /** One timer card: exactly one cancel control and exactly one 初始时长 label in one subtree. */
    private fun timerCard(clickableCancel: Boolean = true): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
        val cancel = n("取消", clickable = clickableCancel)
        val card = n(children = listOf(n("初始时长：5分钟"), cancel))
        return card to cancel
    }

    private fun switchRow(time: String): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
        val switch = n("", clickable = true)
        every { switch.isCheckable } returns true
        every { switch.isChecked } returns true
        return n(children = listOf(n(time), switch)) to switch
    }

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(UiAutomator, IntentActions)
        every { UiAutomator.foregroundPackage() } returns clockPkg
        every { UiAutomator.available() } returns true
        // The read path and the selection scan are the code under test; only the service lookups
        // (root / foreground / availability) are stubbed.
        every { UiAutomator.readTree(any(), any(), any()) } answers { callOriginal() }
        every { UiAutomator.readScreen(any()) } answers { callOriginal() }
        every { IntentActions.nextAlarm(ctx) } returns null
    }

    @After fun cleanup() = unmockkAll()

    // ── verdict at the read boundary ────────────────────────────────────────────

    @Test fun `a selected body card is not tab selection - strip with no exposed state reads unknown`() {
        val root = clockWindow(
            body = listOf(n("闹钟 7:00"), n("设置", clickable = true, selected = true)),
            stripSelected = null,
        )
        val read = UiAutomator.readTree(root, 120, true)
        assertThat(read.selectedTabs["闹钟"]).isNull()
        assertThat(read.selectedTabs["定时器"]).isNull()
    }

    @Test fun `a strip that reports another tab selected yields an explicit not-selected verdict`() {
        val root = clockWindow(
            body = listOf(n("闹钟 7:00"), n("设置", clickable = true, selected = true)),
            stripSelected = "定时器",
        )
        val read = UiAutomator.readTree(root, 120, true)
        assertThat(read.selectedTabs["闹钟"]).isFalse()
        assertThat(read.selectedTabs["定时器"]).isTrue()
    }

    // ── query entry: unknown / not-selected / selected ──────────────────────────

    @Test fun `timer query refuses and touches nothing when the strip reports no selection`() {
        val card = n("8:00")
        val bodyCard = n("设置", clickable = true, selected = true)
        every { UiAutomator.root() } returns clockWindow(
            body = listOf(n("2 个定时器正在运行"), card, bodyCard), stripSelected = null)

        val r = ClockControl.queryTimers(ctx)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.detail).contains("window=not-clock")
        verify(exactly = 0) { bodyCard.performAction(any()) }
    }

    @Test fun `timer query refuses when the strip reports another tab selected`() {
        val bodyCard = n("设置", clickable = true)
        every { UiAutomator.root() } returns clockWindow(
            body = listOf(n("2 个定时器正在运行"), bodyCard), stripSelected = "闹钟")

        val r = ClockControl.queryTimers(ctx)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isNotEqualTo(true)
        verify(exactly = 0) { bodyCard.performAction(any()) }
    }

    @Test fun `timer query still answers when the strip reports the timer tab selected`() {
        val bodyCard = n("设置", clickable = true)
        every { UiAutomator.root() } returns clockWindow(
            body = listOf(n("2 个定时器正在运行"), bodyCard), stripSelected = "定时器")

        val r = ClockControl.queryTimers(ctx)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isTrue()
        assertThat(r.detail).contains("count=2")
    }

    @Test fun `alarm query refuses when the strip reports no selection`() {
        val (row, switch) = switchRow("8:00")
        every { UiAutomator.root() } returns clockWindow(body = listOf(row), stripSelected = null)

        val r = ClockControl.queryAlarms(ctx)

        assertThat(r.verified).isNotEqualTo(true)
        verify(exactly = 0) { switch.performAction(any()) }
    }

    // ── binding gate: cancelTimer ───────────────────────────────────────────────

    @Test fun `cancel refuses at the binding gate when the strip reports no selection`() {
        val (card, cancel) = timerCard()
        every { UiAutomator.root() } returns clockWindow(
            body = listOf(card, n("设置", clickable = true, selected = true)), stripSelected = null)

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.detail).contains("why=binding-tab-unreported")
        verify(exactly = 0) { cancel.performAction(any()) }
    }

    @Test fun `cancel refuses at navigation when the strip reports another tab selected`() {
        val (card, cancel) = timerCard()
        every { UiAutomator.root() } returns clockWindow(body = listOf(card), stripSelected = "闹钟")

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.detail).contains("clock not reachable")
        verify(exactly = 0) { cancel.performAction(any()) }
    }

    @Test fun `cancel refuses at the binding gate when the binding window shows another tab selected`() {
        // Navigation-time selection is diagnostic only: the window is re-read for binding and that
        // read must establish its own identity. Here the tab was selected while navigating and the
        // binding read comes back with a different tab selected.
        val (navCard, _) = timerCard()
        val navTree = clockWindow(body = listOf(navCard), stripSelected = "定时器")
        val (bindCard, bindCancel) = timerCard()
        val bindTree = clockWindow(body = listOf(bindCard), stripSelected = "闹钟")
        every { UiAutomator.root() } returnsMany listOf(navTree, navTree, bindTree)

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.detail).contains("why=binding-tab-not-selected")
        verify(exactly = 0) { bindCancel.performAction(any()) }
        verify(exactly = 0) { navCard.performAction(any()) }
    }

    @Test fun `cancel still binds the uniquely identified card when the timer tab is selected`() {
        val (card, cancel) = timerCard()
        every { UiAutomator.root() } returns clockWindow(body = listOf(card), stripSelected = "定时器")

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        // Bound and clicked, but the cancel outcome was never read back → unknown (null), not false.
        assertThat(r.verified).isNull()
        verify(exactly = 1) { cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    // ── binding gate: closeAlarm ────────────────────────────────────────────────

    @Test fun `alarm close refuses before any switch click when the strip reports no selection`() {
        val (row, switch) = switchRow("8:00")
        every { UiAutomator.root() } returns clockWindow(body = listOf(row), stripSelected = null)

        val r = ClockControl.closeAlarm(ctx, 8, 0)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.detail).contains("window=not-clock")
        verify(exactly = 0) { switch.performAction(any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }
}
