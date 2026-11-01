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
 * Round 46 — review 6553705515 P1: `cardRunningState` accepted *any* remaining-time text as proof
 * that a timer is running. A **paused** Samsung timer card keeps its frozen readout — and shows a
 * "继续" (resume) control rather than an "已暂停" marker — so it was counted in `runningProven`, the
 * page total (`N 个定时器正在运行`, which counts running timers) matched `candidates.size`, the set
 * looked comparable, and the visible card was clicked while a running twin of the same duration sat
 * below the fold.
 *
 * Running state now needs independent evidence: a pause control or an explicit running marker.
 * A resume control proves the opposite. A remaining-time readout alone proves nothing (a paused card
 * keeps it), so it returns `null` — unproven — and an unproven card makes the set incomparable.
 *
 * All trees are synthetic accessibility fixtures; this is not device evidence.
 */
class TimerPausedFrozenReadoutTest {
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
        every { n.isClickable } returns false
        every { n.isScrollable } returns false
        every { n.performAction(any()) } returns true
        every { UiAutomator.selectedState(n, any()) } returns true
        return n
    }

    /** One card: original duration, its state texts (control label and/or readout), its cancel. */
    private fun card(duration: String, states: List<String>, cancel: AccessibilityNodeInfo) =
        node("", *(listOf(node(duration)) + states.map { node(it) } + listOf(cancel)).toTypedArray())

    private fun cancelControl(): AccessibilityNodeInfo =
        node("取消").also { every { it.isClickable } returns true }

    private fun tree(scrollable: Boolean, header: String?, vararg cards: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val container = node("", *listOfNotNull(header?.let { node(it) }, *cards).toTypedArray())
        every { container.isScrollable } returns scrollable
        val root = node("", container)
        every { UiAutomator.root() } returns root
        return root
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any<Throwable>()) } returns 0
        mockkObject(VoiceSession, UiAutomator, IntentActions, AppRegistry)
        every { VoiceSession.isCurrent(any()) } returns true
        every { UiAutomator.foregroundPackage() } returns clockPkg
        every { UiAutomator.clickText(any()) } returns true
        every { UiAutomator.tabSelected(any()) } returns true
        every { UiAutomator.click(any()) } returns true
        every { AppRegistry.find(any(), any()) } returns AppRegistry.App("时钟", clockPkg, emptyList())
        every { AppRegistry.launch(any(), any()) } returns ToolResult.ok("app", "opened")
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = emptyList(), nodesVisited = 3, rootPackage = clockPkg, serviceAvailable = true,
        )
    }

    @After
    fun cleanup() = unmockkAll()

    // ── the predicate itself ──

    @Test
    fun `a frozen remaining readout with a resume control is a paused card`() {
        assertThat(ClockControl.cardRunningState(listOf("继续", "剩余 4 分 50 秒"))).isFalse()
    }

    @Test
    fun `a pause control plus a readout is a running card`() {
        assertThat(ClockControl.cardRunningState(listOf("暂停", "剩余 4 分 50 秒"))).isTrue()
    }

    @Test
    fun `a remaining readout alone proves nothing`() {
        assertThat(ClockControl.cardRunningState(listOf("剩余 4 分 50 秒"))).isNull()
        assertThat(ClockControl.cardRunningState(listOf("剩余 09:58"))).isNull()
    }

    @Test
    fun `an explicit running marker is evidence`() {
        assertThat(ClockControl.cardRunningState(listOf("正在运行", "剩余 4 分 50 秒"))).isTrue()
    }

    // ── the door this opened in cancelTimer ──

    @Test
    fun `cancel refuses when the only matching card is paused but shows a frozen readout`() {
        val five = cancelControl()
        val ten = cancelControl()
        // Page says 2 running. Viewport: paused 5-minute card ("继续" + frozen readout) and a
        // running 10-minute card. A running 5-minute twin sits below the fold.
        tree(
            scrollable = true,
            header = "2 个定时器正在运行",
            cards = arrayOf(
                card("初始时长：5分钟", listOf("继续", "剩余 4 分 50 秒"), five),
                card("初始时长：10分钟", listOf("暂停", "剩余 9 分 30 秒"), ten),
            ),
        )

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.BLOCKED)
        assertThat(r.speak).contains("没有执行取消")
        assertThat(r.detail).contains("set-mixed")
        verify(exactly = 0) { five.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { ten.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `cancel still proceeds when every bound card carries a pause control`() {
        val five = cancelControl()
        val ten = cancelControl()
        tree(
            scrollable = true,
            header = "2 个定时器正在运行",
            cards = arrayOf(
                card("初始时长：5分钟", listOf("暂停", "剩余 4 分 50 秒"), five),
                card("初始时长：10分钟", listOf("暂停", "剩余 9 分 30 秒"), ten),
            ),
        )

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        verify(exactly = 1) { five.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { ten.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }
}
