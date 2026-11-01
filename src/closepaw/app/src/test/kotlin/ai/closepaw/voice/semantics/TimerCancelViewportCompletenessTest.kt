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
 * Round 41: `cancelTimer` bound "the only card with this original duration" from *the loaded tree*,
 * and treated the walk staying inside its node budget (`complete == true`) as proof that no other
 * card existed. That is the same partial-scan-as-whole-set error `closeAlarm` already refuses on
 * (`isScrollable` → BLOCKED): a scrollable timer list holds cards below the fold that this walk
 * never loaded, so a viewport scan can hand back exactly one 5-minute card while the phone runs two
 * of them — and the visible one is then cancelled.
 *
 * The fix keys on evidence the *page itself* states. `N 个定时器正在运行` is a total, whereas a
 * viewport card count is only a lower bound ([ClockControl.timerCountFrom] documents the same
 * distinction). So on a scrollable tree the cancel may proceed only when the number of fully bound
 * cards is at least the stated total — i.e. the walk saw every timer the page says exists. Without
 * a stated total, or with fewer bound cards than the page reports, it refuses without clicking.
 *
 * All trees are synthetic accessibility fixtures; this is not device evidence.
 */
class TimerCancelViewportCompletenessTest {
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

    /** One timer card: an explicit original duration plus its own cancel control. */
    private fun card(duration: String, cancel: AccessibilityNodeInfo) = node("", node(duration), cancel)

    private fun cancelControl(): AccessibilityNodeInfo =
        node("取消").also { every { it.isClickable } returns true }

    /** root → container → (optional header, cards…). [scrollable] is set on the container only. */
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
    fun `cancel refuses when the list scrolls and the page reports more timers than were bound`() {
        val cancel = cancelControl()
        // The page says two timers run; this walk loaded one card. The second may be the one asked
        // for, so binding "the only 5-minute card I saw" is not a unique object.
        tree(scrollable = true, header = "2 个定时器正在运行", cards = arrayOf(card("初始时长：5分钟", cancel)))

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.BLOCKED)
        assertThat(r.speak).doesNotContain("已请求取消")
        assertThat(r.speak).contains("没有执行取消")
        assertThat(r.detail).contains("scrollable=true")
        verify(exactly = 0) { cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    @Test
    fun `cancel refuses when the list scrolls and the page states no total`() {
        val cancel = cancelControl()
        // Scrollable list, no `N 个定时器正在运行` sentence: the viewport count cannot be the set.
        tree(scrollable = true, header = null, cards = arrayOf(card("初始时长：5分钟", cancel)))

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.BLOCKED)
        assertThat(r.speak).doesNotContain("已请求取消")
        assertThat(r.detail).contains("stated=-1")
        verify(exactly = 0) { cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `cancel proceeds on a scrollable list once the page total is covered by bound cards`() {
        val five = cancelControl()
        val ten = cancelControl()
        // Two timers stated, two cards fully bound → the requested 5-minute card is provably the
        // only one of its duration. This is the control that keeps the fix from being a blanket
        // refusal.
        tree(
            scrollable = true,
            header = "2 个定时器正在运行",
            cards = arrayOf(card("初始时长：5分钟", five), card("初始时长：10分钟", ten)),
        )

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isNull()
        assertThat(r.speak).contains("无法确认")
        verify(exactly = 1) { five.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { ten.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    @Test
    fun `cancel proceeds on a scrollable list whose stated total is the single bound card`() {
        val cancel = cancelControl()
        tree(scrollable = true, header = "1 个定时器正在运行", cards = arrayOf(card("初始时长：5分钟", cancel)))

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        verify(exactly = 1) { cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `cancel still proceeds on a non-scrollable page without a stated total`() {
        val cancel = cancelControl()
        // No scrollable container: nothing can sit below the fold, so the walk covered the list.
        tree(scrollable = false, header = null, cards = arrayOf(card("初始时长：5分钟", cancel)))

        val r = ClockControl.cancelTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        verify(exactly = 1) { cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }
}
