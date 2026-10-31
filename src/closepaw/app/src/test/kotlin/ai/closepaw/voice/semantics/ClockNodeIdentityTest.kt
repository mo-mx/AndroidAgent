package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.UiAutomator
import android.view.accessibility.AccessibilityNodeInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Test

/**
 * Review round 22 (`chatgpt-hourly-review SHA:f5d877a`) raised a P1 on `UiAutomator`: the navigation
 * code decided "same container" / "reached the boundary" with Kotlin reference identity (`===`,
 * `!==`, `IdentityHashMap<AccessibilityNodeInfo, …>`), but the platform may hand back a *different
 * wrapper object for the same logical node* from `getParent()`/`getChild()`. Consequences named in
 * the review:
 *
 *  ① one strip seen through two wrappers was mistaken for two strips → navigation refused;
 *  ② the "never click the bar container / never treat the window root as a bar" boundaries stopped
 *    holding, because `node === container` was false for an equivalent wrapper;
 *  ③ `depthBelow` returned the 1000 "unreachable" sentinel for labels that are inside the bar, and
 *    the sentinel was not rejected, so membership could pass without a proven parent chain;
 *  ④ (same review, P2) `collectExactLabels` silently stopped at 8 candidates while claiming to have
 *    considered every candidate, so ambiguity was decided on a truncated set.
 *
 * These tests cover ①②③④ on synthetic trees only — no device evidence, no real Samsung layout.
 * Nothing here claims the structural heuristic is validated against the real clock UI.
 */
class ClockNodeIdentityTest {
    private val clockPkg = "com.sec.android.app.clockpackage"
    private val nodes = ArrayList<AccessibilityNodeInfo>()

    private fun n(
        text: String? = null,
        clickable: Boolean = false,
        selected: Boolean = false,
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.text } returns text
        every { node.contentDescription } returns null
        every { node.packageName } returns clockPkg
        every { node.isClickable } returns clickable
        every { node.isSelected } returns selected
        every { node.isScrollable } returns false
        every { node.isCheckable } returns false
        every { node.performAction(any()) } returns true
        every { node.parent } returns null
        every { node.childCount } returns children.size
        children.forEachIndexed { i, child ->
            every { node.getChild(i) } returns child
            every { child.parent } returns node
        }
        nodes.add(node)
        return node
    }

    /**
     * Two distinct node-info objects that the framework reports as the *same* logical node
     * (`equals` true both ways, different object instances) — exactly the wrapper case the review
     * describes.
     */
    private fun twins(): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
        val a = mockk<AccessibilityNodeInfo>(relaxed = true)
        val b = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { a.equals(any()) } returns false
        every { b.equals(any()) } returns false
        every { a.equals(b) } returns true
        every { b.equals(a) } returns true
        nodes.add(a)
        nodes.add(b)
        return a to b
    }

    /** Give [wrapper] the same children as the logical bar built by [strip]. */
    private fun rewrap(wrapper: AccessibilityNodeInfo, children: List<AccessibilityNodeInfo>) {
        every { wrapper.text } returns null
        every { wrapper.contentDescription } returns null
        every { wrapper.packageName } returns clockPkg
        every { wrapper.isClickable } returns false
        every { wrapper.isSelected } returns false
        every { wrapper.isScrollable } returns false
        every { wrapper.isCheckable } returns false
        every { wrapper.performAction(any()) } returns true
        every { wrapper.parent } returns null
        every { wrapper.childCount } returns children.size
        children.forEachIndexed { i, child -> every { wrapper.getChild(i) } returns child }
    }

    @After
    fun cleanup() {
        unmockkAll()
    }

    @Test
    fun `one strip seen through two wrappers is not reported as an ambiguous second strip`() {
        val alarmA = n("闹钟", clickable = true)
        val timerA = n("定时器", clickable = true)
        val alarmB = n("闹钟", clickable = true)
        val timerB = n("定时器", clickable = true)
        // The platform reports the *same* strip twice, as two equivalent node-info objects.
        val (stripReal, stripTwin) = twins()
        rewrap(stripReal, listOf(alarmA, timerA))
        rewrap(stripTwin, listOf(alarmB, timerB))
        every { alarmA.parent } returns stripReal
        every { timerA.parent } returns stripReal
        every { alarmB.parent } returns stripTwin
        every { timerB.parent } returns stripTwin
        val body = n(children = listOf(n("闹钟设置"), n("提示卡")))
        val root = n(children = listOf(body, stripReal, stripTwin))

        // Old code: two identity-distinct containers both qualify → "ambiguous" → null (nav unusable).
        val nav = UiAutomator.navControl(root, "定时器")
        assertThat(nav).isNotNull()
        assertThat(nav!!.control).isSameInstanceAs(timerA)
        assertThat(UiAutomator.clickTabControl(nav)).isTrue()
        verify(exactly = 1) { timerA.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { timerB.performAction(any()) }
    }

    @Test
    fun `the bar container boundary holds when the parent chain returns an equivalent wrapper`() {
        val timer = n("定时器", clickable = false)
        val (container, containerWrapper) = twins()
        rewrap(container, listOf(timer))
        rewrap(containerWrapper, listOf(timer))
        every { containerWrapper.isClickable } returns true // clicking the strip itself would be wrong
        every { timer.parent } returns containerWrapper

        val nav = UiAutomator.NavControl(timer, container)
        // Old code: `node === nav.container` false for the wrapper → ACTION_CLICK on the whole strip.
        assertThat(UiAutomator.clickTabControl(nav)).isFalse()
        verify(exactly = 0) { containerWrapper.performAction(any()) }
        verify(exactly = 0) { container.performAction(any()) }
    }

    @Test
    fun `the window root is refused as a bar even when reached through an equivalent wrapper`() {
        val alarm = n("闹钟", clickable = true)
        val timer = n("定时器", clickable = true)
        val (root, rootWrapper) = twins()
        rewrap(root, listOf(alarm, timer))
        rewrap(rootWrapper, listOf(alarm, timer))
        every { alarm.parent } returns rootWrapper
        every { timer.parent } returns rootWrapper

        // Old code: rootWrapper !== root → a "bar" holding the whole page → body row could be clicked.
        assertThat(UiAutomator.navControl(root, "定时器")).isNull()
        nodes.forEach { verify(exactly = 0) { it.performAction(any()) } }
    }

    @Test
    fun `a bar whose labels cannot be walked back to it is refused, not accepted on depth`() {
        val alarm = n("闹钟", clickable = true)
        val timer = n("定时器", clickable = true)
        // The click target the caller is after does climb to `bar`, but neither peer label does:
        // both depths are the 1000 sentinel, so the "same depth" rule used to pass without any
        // proven membership. Re-clicking the *wrong* sibling was the concrete risk.
        val ctrl = n("定时器", clickable = true)
        val bar = n(children = listOf(alarm, timer, ctrl))
        val root = n(children = listOf(bar))
        val orphanA = n(clickable = true)
        val orphanB = n(clickable = true)
        every { alarm.parent } returns orphanA
        every { timer.parent } returns orphanB

        assertThat(UiAutomator.navControl(root, "定时器")).isNull()
        nodes.forEach { verify(exactly = 0) { it.performAction(any()) } }
    }

    @Test
    fun `a ninth candidate is not silently dropped - a truncated candidate set is refused`() {
        // Two structurally valid strips, but the *second* one's label is the 9th exact match, so the
        // old 8-candidate budget never saw it and returned the first strip as if it were unique.
        val filler = (1..7).map { n("定时器") }
        val stripAlarm = n("闹钟", clickable = true)
        val stripTimer = n("定时器", clickable = true)
        val stripA = n(children = listOf(stripAlarm, stripTimer))
        val body = n(children = filler)
        val barAlarm = n("闹钟", clickable = true)
        val barTimer = n("定时器", clickable = true)
        val stripB = n(children = listOf(barAlarm, barTimer))
        val root = n(children = listOf(body, stripA, stripB))

        // Old code: candidates = 7 fillers + stripAlarm (timer is #9) → sees only strip A → returns it.
        assertThat(UiAutomator.navControl(root, "定时器")).isNull()
        assertThat(UiAutomator.selectedState(root, "定时器")).isNull()
        nodes.forEach { verify(exactly = 0) { it.performAction(any()) } }
    }
}
