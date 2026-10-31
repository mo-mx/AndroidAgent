package ai.closepaw.voice.semantics

import ai.closepaw.voice.VoiceSession
import ai.closepaw.voice.tools.*
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Round 25: the timer cancel path proved its object at *scan* time and then clicked a node it had
 * been holding. Between those two moments the tree it came from can be replaced — the clock moves
 * to another tab, the card is re-created, the list scrolls — and the node the code still holds
 * describes a screen that no longer exists. The only pre-click facts checked were
 * "the node's package is the clock" (a property of the *old* wrapper) and "some clock window is in
 * front" (a re-read that can pass while the bound node belongs to a different window).
 *
 * These tests pin the click-instant contract: before `performAction(ACTION_CLICK)` the bound node
 * itself must still exist (`refresh()`), still be the clock's, still be visible to the user, still
 * be clickable, and live in the same window as the freshly read root. Any of those failing is a
 * refusal, never a click.
 *
 * All trees are synthetic accessibility fixtures; this is not device evidence.
 */
class ClockClickTimeBindingTest {
    private val ctx = mockk<Context>()
    private val clockPkg = "com.sec.android.app.clockpackage"

    private fun node(text: String = "", vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.text } returns text
        every { n.contentDescription } returns null
        every { n.packageName } returns clockPkg
        every { n.childCount } returns children.size
        children.forEachIndexed { i, c -> every { n.getChild(i) } returns c }
        every { n.refresh() } returns true
        every { n.isVisibleToUser } returns true
        every { n.performAction(any()) } returns true
        every { UiAutomator.selectedState(n, any()) } returns true
        return n
    }

    /** One timer card: an explicit original duration plus its cancel control. */
    private fun card(duration: String, cancel: AccessibilityNodeInfo) =
        node("", node(duration), cancel)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(VoiceSession, UiAutomator, IntentActions, AppRegistry)
        every { VoiceSession.isCurrent(any()) } returns true
        every { UiAutomator.foregroundPackage() } returns clockPkg
        every { UiAutomator.clickText(any()) } returns true
        every { UiAutomator.tabSelected(any()) } returns true
        every { UiAutomator.click(any()) } returns true
        every { AppRegistry.find(any(), any()) } returns AppRegistry.App("时钟", clockPkg, emptyList())
        every { AppRegistry.launch(any(), any()) } returns ToolResult.ok("app", "opened")
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("2 个定时器正在运行"),
            nodesVisited = 3,
            rootPackage = clockPkg,
            serviceAvailable = true,
        )
    }

    @After
    fun cleanup() = unmockkAll()

    private fun singleCardTree(cancel: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val root = node("", card("初始时长：5分钟", cancel))
        every { UiAutomator.root() } returns root
        return root
    }

    @Test
    fun `cancel refuses a control that no longer exists at click time`() {
        val cancel = node("取消")
        every { cancel.isClickable } returns true
        // The wrapper survived in our hands; the framework no longer has that node.
        every { cancel.refresh() } returns false
        singleCardTree(cancel)

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.speak).doesNotContain("已请求取消")
        verify(exactly = 0) { cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    @Test
    fun `cancel refuses a control that belongs to another window`() {
        val cancel = node("取消")
        every { cancel.isClickable } returns true
        every { cancel.windowId } returns 77
        val root = singleCardTree(cancel)
        every { root.windowId } returns 12

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.detail).contains("window")
        verify(exactly = 0) { cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    @Test
    fun `cancel refuses a control that is no longer visible to the user`() {
        val cancel = node("取消")
        every { cancel.isClickable } returns true
        every { cancel.isVisibleToUser } returns false
        singleCardTree(cancel)

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).doesNotContain("已请求取消")
        verify(exactly = 0) { cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `cancel refuses when refreshing the bound control throws`() {
        val cancel = node("取消")
        every { cancel.isClickable } returns true
        every { cancel.refresh() } throws IllegalStateException("sealed")
        singleCardTree(cancel)

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        verify(exactly = 0) { cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `cancel clicks the bound control exactly once when it is still attached`() {
        val cancel = node("取消")
        every { cancel.isClickable } returns true
        every { cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
        val root = singleCardTree(cancel)
        every { root.windowId } returns 12
        every { cancel.windowId } returns 12

        val r = ClockControl.cancelTimer(ctx, 300)

        // A delivered click is still not proof the timer stopped: SUCCESS without verification.
        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isNull()
        assertThat(r.speak).contains("无法确认")
        verify(exactly = 1) { cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }
}
