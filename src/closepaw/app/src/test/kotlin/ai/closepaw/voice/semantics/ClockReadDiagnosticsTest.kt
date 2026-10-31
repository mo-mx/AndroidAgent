package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.*
import android.content.Context
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

/**
 * PR #4 review (2026-10-31T05:21:21Z, comment id IC_kwDOVdfNwc8AAAABhcjt_A): the device run returned
 * ALARM readable=false / visible=[] and TIMER DEVICE_UNAVAILABLE. Both collapse several different
 * world states into one message, so the failure cannot be attributed. These tests pin the *reporting*
 * contract only: the tool must say whether the tab was reached and whether the tree read was
 * truncated. They do not claim any root cause and do not change what counts as a successful read.
 */
class ClockReadDiagnosticsTest {
    private val ctx = mockk<Context>()
    private val clockPkg = "com.sec.android.app.clockpackage"

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

    @Test fun `timer query says the tab was never found instead of a generic ui unavailable`() {
        every { UiAutomator.clickText("定时器") } returns false
        every { UiAutomator.waitForText("定时器", any()) } returns false
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead()
        val r = ClockControl.queryTimers(ctx)
        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.detail).contains("tab=label-not-found")
        assertThat(r.detail).doesNotContain("ui unavailable")
    }

    @Test fun `alarm query distinguishes a label-only tab tap from a clicked tab`() {
        // The tab label is on screen but the click did not report success and no tab in the tree
        // reports selected: either the tap missed or the platform hides selection. Reading such a
        // page cannot prove the alarm list, so the new reason is click-failed-label-only.
        every { UiAutomator.clickText("闹钟") } returns false
        every { UiAutomator.waitForText("闹钟", any()) } returns true
        every { UiAutomator.tabSelected("闹钟") } returns null
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead()
        val r = ClockControl.queryAlarms(ctx)
        assertThat(r.detail).contains("tab=click-failed-label-only")
        assertThat(r.detail).contains("readable=false")
        assertThat(r.verified).isFalse()
        assertThat(r.speak).contains("无法读取闹钟列表")
    }

    @Test fun `alarm query reports an empty read together with its truncation boundary`() {
        every { UiAutomator.clickText("闹钟") } returns true
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            rootPackage = clockPkg, serviceAvailable = true)
        val r = ClockControl.queryAlarms(ctx)
        // Empty list alone is not evidence: the report must carry how much of the tree was read.
        assertThat(r.detail).contains("nodes=")
        assertThat(r.detail).contains("nodesTruncated=")
        assertThat(r.detail).contains("textsTruncated=")
        assertThat(r.detail).contains("rootPkg=$clockPkg")
        assertThat(r.verified).isFalse()
    }

    // ── read boundary, real traversal (synthetic trees, no Android runtime) ──

    private fun tree(text: String = "", children: List<android.view.accessibility.AccessibilityNodeInfo> = emptyList())
        : android.view.accessibility.AccessibilityNodeInfo {
        val n = mockk<android.view.accessibility.AccessibilityNodeInfo>(relaxed = true)
        every { n.text } returns text
        every { n.contentDescription } returns null
        every { n.childCount } returns children.size
        children.forEachIndexed { i, c -> every { n.getChild(i) } returns c }
        return n
    }

    @Test fun `read reports the text limit instead of silently returning a shorter list`() {
        val kids = (0 until 70).map { tree("t$it") }
        every { UiAutomator.root() } returns tree("root", kids)
        val read = UiAutomator.readScreen(60)
        assertThat(read.texts).hasSize(60)
        assertThat(read.textsTruncated).isTrue()
        assertThat(read.nodesTruncated).isFalse()
        // Root + 70 children were all scanned; only the first 60 texts were kept.
        assertThat(read.nodesVisited).isEqualTo(71)
    }

    @Test fun `read reports the node guard when the tree is deeper than the bound`() {
        var node = tree("leaf")
        repeat(699) { node = tree("n$it", listOf(node)) }
        every { UiAutomator.root() } returns node
        val read = UiAutomator.readScreen(120)
        assertThat(read.nodesVisited).isEqualTo(UiAutomator.MAX_NODES)
        assertThat(read.nodesTruncated).isTrue()
        // The leaf sits beyond the bound: an empty match here is a boundary, not an empty screen.
        assertThat(read.texts).doesNotContain("leaf")
    }

    @Test fun `a fully read tree reports no truncation`() {
        every { UiAutomator.root() } returns tree("root", listOf(tree("8:00"), tree("9:00")))
        val read = UiAutomator.readScreen(120)
        assertThat(read.texts).containsExactly("root", "8:00", "9:00").inOrder()
        assertThat(read.nodesVisited).isEqualTo(3)
        assertThat(read.nodesTruncated).isFalse()
        assertThat(read.textsTruncated).isFalse()
    }
}
