package ai.closepaw.voice.tools

import ai.closepaw.voice.semantics.Action
import ai.closepaw.voice.semantics.ClockControl
import ai.closepaw.voice.semantics.SemanticTask
import ai.closepaw.voice.semantics.Target
import ai.closepaw.voice.semantics.TerminalState
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Round 29: the cancel path clicked the bound card and then declared the outcome unknown, because
 * nothing was read back. `expectedState = "no_running_timer"` was therefore *always* UNPROVEN, and
 * `CONFIRMED` / `CONTRADICTED` existed only as JVM constructs. Those two verdicts only mean
 * something if a real producer supplies `ObservedState`.
 *
 * These fixtures pin the post-click read-back contract on the same page that was bound:
 *
 *  - page identity + a complete read + the bound duration label gone + a *decreased* running count
 *    → `verified = true` with `ObservedState(identity = "timer:<seconds>", state = "none")`;
 *  - the bound duration label still listed → `verified = false` with `state = "running:<seconds>"`,
 *    which [TerminalState] refuses as CONTRADICTED;
 *  - unreadable page, truncated tree, unparsed count, or two facts that disagree
 *    (label gone but count not lower) → no evidence at all (`verified = null`, `observed = null`).
 *
 * Synthetic trees only: this is not device evidence, and Samsung's real timer page has not been
 * re-observed in this round.
 */
class TimerCancelReadbackTest {
    private val ctx = mockk<Context>()
    private val clockPkg = "com.sec.android.app.clockpackage"
    private lateinit var cancel: AccessibilityNodeInfo

    private fun node(text: String = "", vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.text } returns text
        every { n.contentDescription } returns null
        every { n.packageName } returns clockPkg
        every { n.childCount } returns children.size
        children.forEachIndexed { i, c -> every { n.getChild(i) } returns c }
        every { n.refresh() } returns true
        every { n.isVisibleToUser } returns true
        every { n.windowId } returns 1
        every { n.performAction(any()) } returns true
        return n
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(UiAutomator, AppRegistry, IntentActions)
        every { UiAutomator.foregroundPackage() } returns clockPkg
        every { UiAutomator.clickClockNavTab(any()) } returns true
        every { UiAutomator.tabSelected(any()) } returns true
        every { UiAutomator.waitForText(any(), any()) } returns true
        every { UiAutomator.click(any()) } returns true
        every { AppRegistry.find(any(), any()) } returns AppRegistry.App("时钟", clockPkg, emptyList())
        every { AppRegistry.launch(any(), any()) } returns ToolResult.ok("app", "opened")

        cancel = node("取消")
        every { cancel.isClickable } returns true
        val card = node("", node("初始时长：5分钟"), cancel)
        val root = node("", node("1 个定时器正在运行"), card)
        every { UiAutomator.root() } returns root
        every { UiAutomator.selectedState(root, "定时器") } returns true
    }

    @After
    fun cleanup() = unmockkAll()

    private fun screen(
        vararg texts: String,
        pkg: String? = clockPkg,
        truncated: Boolean = false,
    ) = UiAutomator.ScreenRead(
        texts = texts.toList(),
        nodesVisited = 12,
        nodesTruncated = truncated,
        textsTruncated = truncated,
        rootPackage = pkg,
        serviceAvailable = true,
        selectedTabs = mapOf("定时器" to true, "闹钟" to false),
    )

    private fun cancelTask() = SemanticTask(
        action = Action.CANCEL,
        target = Target.TIMER,
        params = mapOf("seconds" to "300"),
        expectedState = "no_running_timer",
        targetIdentity = "timer:300",
        raw = "取消五分钟倒计时",
    )

    @Test
    fun `a disappeared duration label is not proof that the requested timer is gone`() {
        // Round 29 review P1: a missing label in one viewport plus a lower count cannot be
        // attributed to the clicked card — there is no stable timer id to bind them.
        every { UiAutomator.readScreen(any()) } returns screen("添加定时器")

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isNull()
        assertThat(r.observed).isNull()
        assertThat(r.detail).contains("readback=count-not-attributable")
        assertThat(TerminalState.verdict(cancelTask(), r)).isEqualTo(TerminalState.Verdict.UNPROVEN)
        verify(exactly = 1) { cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `another timer ending while the bound card leaves the viewport is not a cancellation`() {
        // Review P1 reproduction: two timers were running, the click missed, the other timer ran
        // out, and the 300 s card is out of the visible tree. Count went 2 -> 1 and the label is
        // absent, but nothing ties that drop to the object the user asked to cancel.
        every { UiAutomator.readScreen(any()) } returns
            screen("1 个定时器正在运行", "初始时长：10分钟", "取消")

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isNull()
        assertThat(r.observed).isNull()
        assertThat(r.speak).doesNotContain("已取消")
        assertThat(r.detail).contains("stillListed=false")
        assertThat(r.detail).contains("readback=")
        assertThat(TerminalState.verdict(cancelTask(), r)).isEqualTo(TerminalState.Verdict.UNPROVEN)
    }

    @Test
    fun `cancel whose card is still listed contradicts the promised absence`() {
        every { UiAutomator.readScreen(any()) } returns
            screen("1 个定时器正在运行", "初始时长：5分钟", "取消")

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isFalse()
        assertThat(r.observed!!.identity).isEqualTo("timer:300")
        assertThat(r.observed!!.state).isEqualTo("running:300")
        assertThat(TerminalState.verdict(cancelTask(), r)).isEqualTo(TerminalState.Verdict.CONTRADICTED)
        assertThat(TerminalState.enforce(cancelTask(), r).status).isEqualTo(ToolStatus.EXECUTION_ERROR)
        verify(exactly = 1) { cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `readback from another package is not evidence about the timer`() {
        every { UiAutomator.readScreen(any()) } returns screen("添加定时器", pkg = "ai.closepaw")

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isNull()
        assertThat(r.observed).isNull()
        assertThat(r.detail).contains("page-unconfirmed")
        assertThat(TerminalState.verdict(cancelTask(), r)).isEqualTo(TerminalState.Verdict.UNPROVEN)
    }

    @Test
    fun `truncated readback is not evidence that the card disappeared`() {
        every { UiAutomator.readScreen(any()) } returns screen("添加定时器", truncated = true)

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.verified).isNull()
        assertThat(r.observed).isNull()
        assertThat(r.detail).contains("truncated")
    }

    @Test
    fun `label gone while the running count is not lower is not evidence`() {
        // The card label vanished but the page still claims three timers are running: the two
        // observations disagree, so neither one may be reported as a completed cancellation.
        every { UiAutomator.readScreen(any()) } returns screen("3 个定时器正在运行")

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isNull()
        assertThat(r.observed).isNull()
        assertThat(r.detail).contains("count-not-lower")
        assertThat(TerminalState.verdict(cancelTask(), r)).isEqualTo(TerminalState.Verdict.UNPROVEN)
    }
}
