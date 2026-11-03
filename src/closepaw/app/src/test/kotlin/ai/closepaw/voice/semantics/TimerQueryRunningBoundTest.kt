package ai.closepaw.voice.semantics

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
 * Round 47 (no new review that round): the *query* path still said "页面上至少能看到 N 个倒计时在运行"
 * from a **flat text list**, counting a `取消` per card while treating `继续` / `重置` as generic card
 * controls. Round 46 established the same distinction on the cancel path: a paused Samsung card keeps
 * its frozen readout and shows `继续`, so text that both populations carry cannot decide whether a
 * timer is running — only a state-specific control (`暂停` runs, `继续` paused) can.
 *
 * `visibleTimerCards` was therefore able to speak a running count for cards that were paused (and for
 * a tree that came from another window at all — it never looked at nodes). This test pins the query
 * contract to the same per-card evidence the cancel path uses:
 *
 *  - a bound is only ever counted over cards that *prove* they are running, from the tree that was
 *    verified to be the clock;
 *  - a paused card, a readout-only card, a clipped read and an unconfirmed window all yield no bound
 *    (so the answer stays "没法确认", never a running count).
 *
 * All trees are synthetic accessibility fixtures; nothing here is device evidence.
 */
class TimerQueryRunningBoundTest {
    private val ctx = mockk<Context>()
    private val clockPkg = "com.sec.android.app.clockpackage"
    private val ownPkg = "ai.closepaw"

    private fun node(
        text: String = "",
        pkg: String = clockPkg,
        vararg children: AccessibilityNodeInfo,
    ): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.text } returns text
        every { n.contentDescription } returns null
        every { n.packageName } returns pkg
        every { n.childCount } returns children.size
        children.forEachIndexed { i, c -> every { n.getChild(i) } returns c }
        every { n.refresh() } returns true
        every { n.isVisibleToUser } returns true
        every { n.isClickable } returns false
        every { n.isScrollable } returns false
        every { n.performAction(any()) } returns true
        return n
    }

    /** A card that states it is running: the `暂停` control only exists while a timer counts down. */
    private fun runningCard(duration: String) =
        node("", clockPkg, node("初始时长 $duration"), node("暂停"), node("剩余 4 分 50 秒"), node("取消"))

    /** A card that states it is paused: Samsung keeps the frozen readout and shows `继续`. */
    private fun pausedCard(duration: String) =
        node("", clockPkg, node("初始时长 $duration"), node("继续"), node("剩余 4 分 50 秒"), node("取消"))

    /** A card with neither state control: it carries a readout that a running *and* a paused card keep. */
    private fun readoutOnlyCard(duration: String) =
        node("", clockPkg, node("初始时长 $duration"), node("剩余 4 分 50 秒"), node("取消"))

    private fun treeOf(vararg cards: AccessibilityNodeInfo, rootPkg: String = clockPkg) =
        node("", rootPkg, node("", rootPkg, *cards))

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(UiAutomator, IntentActions, AppRegistry)
        every { AppRegistry.find(any(), any()) } returns null
        every { UiAutomator.foregroundPackage() } returns clockPkg
        every { UiAutomator.clickClockNavTab("定时器") } returns true
        every { UiAutomator.tabSelected("定时器") } returns true
        every { UiAutomator.selectedState(any(), "定时器") } returns true
    }

    @After
    fun cleanup() = unmockkAll()

    /** The page read only supplies page identity and the optional header sentence. */
    private fun stubRead(vararg texts: String, truncated: Boolean = false) {
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = texts.toList(),
            nodesVisited = 40,
            nodesTruncated = truncated,
            textsTruncated = truncated,
            rootPackage = clockPkg,
            serviceAvailable = true,
            selectedTabs = mapOf("定时器" to true),
        )
    }

    @Test
    fun `a viewport of paused cards is not a running lower bound`() {
        // Two paused timers. The old flat count saw two `取消` labels and one `继续`, and spoke
        // "页面上至少能看到2个倒计时在运行" — a running count derived from paused cards.
        every { UiAutomator.root() } returns treeOf(pausedCard("5 分钟"), pausedCard("10 分钟"))
        stubRead("初始时长 5 分钟", "继续", "取消", "初始时长 10 分钟", "继续", "取消")

        val r = ClockControl.queryTimers(ctx)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.speak).doesNotContain("至少能看到")
        assertThat(r.detail).contains("running=0")
    }

    @Test
    fun `only the card that proves it is running is counted`() {
        every { UiAutomator.root() } returns treeOf(runningCard("5 分钟"), pausedCard("10 分钟"))
        stubRead("初始时长 5 分钟", "暂停", "剩余 4 分 50 秒", "取消",
            "初始时长 10 分钟", "继续", "剩余 4 分 50 秒", "取消")

        val r = ClockControl.queryTimers(ctx)

        // One proven card is a real lower bound; the paused card must not raise it to two.
        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.speak).contains("至少能看到1个")
        assertThat(r.detail).contains("lowerBound=1")
        assertThat(r.detail).doesNotContain("lowerBound=2")
    }

    @Test
    fun `a remaining readout is not running evidence for a bound`() {
        every { UiAutomator.root() } returns treeOf(readoutOnlyCard("5 分钟"))
        stubRead("初始时长 5 分钟", "剩余 4 分 50 秒", "取消")

        val r = ClockControl.queryTimers(ctx)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).doesNotContain("至少能看到")
        assertThat(r.detail).contains("running=0")
    }

    @Test
    fun `a bound is never counted from a window that is not the clock`() {
        // The page read says the clock answered, but the tree that would be bound belongs to us.
        every { UiAutomator.root() } returns treeOf(runningCard("5 分钟"), rootPkg = ownPkg)
        stubRead("初始时长 5 分钟", "暂停", "取消")

        val r = ClockControl.queryTimers(ctx)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).doesNotContain("至少能看到")
        assertThat(r.verified).isNotEqualTo(true)
    }

    @Test
    fun `a clipped read yields no bound even when a card proves it is running`() {
        every { UiAutomator.root() } returns treeOf(runningCard("5 分钟"))
        stubRead("初始时长 5 分钟", "暂停", "取消", truncated = true)

        val r = ClockControl.queryTimers(ctx)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.detail).contains("truncated=true")
        assertThat(r.speak).doesNotContain("至少能看到")
    }

    @Test
    fun `an explicit header still answers without binding cards`() {
        // A sentence the page states is a total, not a viewport bound, and keeps working even when
        // the cards below it are paused.
        every { UiAutomator.root() } returns treeOf(pausedCard("5 分钟"))
        stubRead("1 个定时器正在运行", "初始时长 5 分钟", "继续", "取消")

        val r = ClockControl.queryTimers(ctx)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isTrue()
        assertThat(r.speak).contains("现在有1个倒计时在运行")
    }
}
