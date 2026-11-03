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
 * Round 44 review P1/P2: round 41 compared two numbers that are not the same population.
 *
 * `N 个定时器正在运行` counts **running** timers. `candidates.size` counted every card that carried an
 * original-duration label and one cancel control — including paused/finished cards, which keep both.
 * With one running and one paused card in view and one running twin below the fold, the page says 2,
 * the bound set is 2, `candidates.size >= statedTotal` passes, `matching.size == 1`, and the visible
 * 5-minute card gets cancelled while a running 5-minute twin sits off-screen.
 *
 * The count comparison is now only made between like populations: every bound card has to *prove*
 * it is running (a remaining-time readout and no paused/finished marker) before the page total can
 * be said to be covered. Any card whose running state is unproven or contradicted makes the set
 * mixed, and a mixed set proves nothing about the running list — refuse, zero clicks.
 *
 * All trees are synthetic accessibility fixtures; nothing here is device evidence.
 */
class TimerCancelRunningSetTest {
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

    /**
     * One timer card: an original-duration label, its state texts (control label and/or readout),
     * its own cancel control. Round 46: a remaining readout alone is no longer running evidence, so
     * the positive controls below carry the state-specific `暂停` control too.
     */
    private fun card(duration: String, state: String?, cancel: AccessibilityNodeInfo, state2: String? = null) =
        node("", *listOfNotNull(node(duration), state?.let { node(it) }, state2?.let { node(it) }, cancel).toTypedArray())

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
            texts = emptyList(),
            nodesVisited = 3,
            rootPackage = clockPkg,
            serviceAvailable = true,
        )
    }

    @After
    fun cleanup() = unmockkAll()

    @Test
    fun `cancel refuses when a paused card is counted against the running total`() {
        val five = cancelControl()
        val ten = cancelControl()
        // Page: 2 running. Viewport: one running 5-minute card + one *paused* 10-minute card.
        // A running 5-minute twin can therefore sit below the fold — the sizes matching is a
        // coincidence of two different populations, not coverage.
        tree(
            scrollable = true,
            header = "2 个定时器正在运行",
            cards = arrayOf(
                card("初始时长：5分钟", "剩余 4 分 50 秒", five),
                card("初始时长：10分钟", "已暂停", ten),
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
    fun `cancel refuses when a bound card carries no running evidence`() {
        val five = cancelControl()
        val ten = cancelControl()
        // Neither card states a remaining time or a paused state: which population each belongs to
        // is unknown, so the two counts cannot be compared.
        tree(
            scrollable = true,
            header = "2 个定时器正在运行",
            cards = arrayOf(
                card("初始时长：5分钟", null, five),
                card("初始时长：10分钟", null, ten),
            ),
        )

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.BLOCKED)
        assertThat(r.detail).contains("unproven=2")
        verify(exactly = 0) { five.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `cancel proceeds when every bound card proves it is running`() {
        val five = cancelControl()
        val ten = cancelControl()
        // Control: all bound cards are running, the page total is covered, so the requested
        // 5-minute card is provably the only running one of its duration.
        tree(
            scrollable = true,
            header = "2 个定时器正在运行",
            cards = arrayOf(
                card("初始时长：5分钟", "暂停", five, state2 = "剩余 4 分 50 秒"),
                card("初始时长：10分钟", "暂停", ten, state2 = "剩余 9 分 30 秒"),
            ),
        )

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isNull()
        verify(exactly = 1) { five.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { ten.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    @Test
    fun `a non-scrollable page with a paused sibling still cancels`() {
        val five = cancelControl()
        val ten = cancelControl()
        // Control: nothing can sit below the fold here, so the walk covered the list whatever each
        // card's running state — the population mismatch only matters when something can be hidden.
        tree(
            scrollable = false,
            header = null,
            cards = arrayOf(
                card("初始时长：5分钟", "剩余 4 分 50 秒", five),
                card("初始时长：10分钟", "已暂停", ten),
            ),
        )

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        verify(exactly = 1) { five.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { ten.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }
}
