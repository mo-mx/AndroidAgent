package ai.closepaw.voice.tools

import ai.closepaw.voice.semantics.Action
import ai.closepaw.voice.semantics.ClockControl
import ai.closepaw.voice.semantics.SemanticTask
import ai.closepaw.voice.semantics.Target
import ai.closepaw.voice.semantics.TerminalState
import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Round 31: creating a timer was the last mutation with no read-back at all.
 * `IntentActions.setTimer` proves the Intent was delivered (or that the clock page shows *some*
 * countdown wording), never that *the requested* timer exists. So `expectedState =
 * "timer_running:300"` could only ever be UNPROVEN on a real device.
 *
 * The contract pinned here — deliberately the narrowest one that can be attributed:
 *
 *  - a complete before-read of the timer page with **no** card declaring the requested original
 *    duration, then a complete after-read with the header count exactly +1 and **exactly one** card
 *    declaring that original duration → `verified = true` with
 *    `ObservedState("timer:<s>", "running:<s>")`;
 *  - an existing same-duration twin (before count of that duration > 0), a count that did not rise
 *    by exactly one, zero or several matching cards, a truncated read, or a page whose identity is
 *    not the clock → no evidence at all (`verified = null`, `observed = null`), never a success.
 *
 * The duration label is the card's own declared *original* duration, not a stable object id, so a
 * third party creating a timer concurrently still defeats attribution; that residual assumption is
 * documented rather than papered over. Synthetic trees only — no device evidence.
 */
class TimerCreateReadbackTest {
    private val ctx = mockk<Context>()
    private val clockPkg = "com.sec.android.app.clockpackage"
    private val reads = ArrayDeque<UiAutomator.ScreenRead>()

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

    private fun queue(vararg s: UiAutomator.ScreenRead) {
        reads.clear()
        reads.addAll(s)
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
        every { AppRegistry.find(any(), any()) } returns AppRegistry.App("时钟", clockPkg, emptyList())
        every { AppRegistry.launch(any(), any()) } returns ToolResult.ok("app", "opened")
        // The Intent layer has its own suite (IntentCreationVerificationTest); here it is a boundary
        // that reports "delivered, nothing proven", which is exactly what it can report on device.
        every { IntentActions.setTimer(any(), any()) } returns ToolResult.sentWithoutProof(
            "timer", "5分钟的倒计时请求已经发送，但无法确认新倒计时是否开始，请在时钟里核对"
        )
        // Read attempts: the queue advances until one entry is left, then that entry repeats (the
        // read helper retries when the root is not the clock).
        every { UiAutomator.readScreen(any()) } answers {
            if (reads.size > 1) reads.removeFirst() else reads.first()
        }
    }

    @After
    fun cleanup() = unmockkAll()

    private fun createTask() = SemanticTask(
        action = Action.CREATE,
        target = Target.TIMER,
        params = mapOf("seconds" to "300"),
        expectedState = "timer_running:300",
        targetIdentity = "timer:300",
        raw = "设一个五分钟的倒计时",
    )

    @Test
    fun `a new card with the requested original duration plus a count increase proves the creation`() {
        queue(
            screen("0 个定时器正在运行"),
            screen("1 个定时器正在运行", "初始时长：5分钟", "取消"),
        )

        val r = ClockControl.createTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isTrue()
        assertThat(r.observed?.identity).isEqualTo("timer:300")
        assertThat(r.observed?.state).isEqualTo("running:300")
        assertThat(r.detail).contains("before=count=0")
        assertThat(r.detail).contains("after=count=1")
        assertThat(TerminalState.verdict(createTask(), r)).isEqualTo(TerminalState.Verdict.CONFIRMED)
    }

    @Test
    fun `a same duration timer that already existed cannot be attributed to the new request`() {
        // The user already had a 5-minute timer. Count 1 -> 2 and two 5-minute cards now exist, but
        // the *new* object is indistinguishable from the old one: nothing may be claimed.
        queue(
            screen("1 个定时器正在运行", "初始时长：5分钟", "取消"),
            screen("2 个定时器正在运行", "初始时长：5分钟", "取消", "初始时长：5分钟", "取消"),
        )

        val r = ClockControl.createTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isNull()
        assertThat(r.observed).isNull()
        assertThat(r.detail).contains("readback=same-duration-twin")
        assertThat(r.speak).doesNotContain("已经设好")
        assertThat(TerminalState.verdict(createTask(), r)).isEqualTo(TerminalState.Verdict.UNPROVEN)
    }

    @Test
    fun `a count that did not rise is not a creation`() {
        // The OEM clock ignored the request (or the header lags): no evidence either way.
        queue(
            screen("1 个定时器正在运行", "初始时长：10分钟", "取消"),
            screen("1 个定时器正在运行", "初始时长：10分钟", "取消"),
        )

        val r = ClockControl.createTimer(ctx, 300)

        assertThat(r.verified).isNull()
        assertThat(r.observed).isNull()
        assertThat(r.detail).contains("readback=count-not-plus-one")
        assertThat(TerminalState.verdict(createTask(), r)).isEqualTo(TerminalState.Verdict.UNPROVEN)
    }

    @Test
    fun `a new card with another duration does not prove the requested one`() {
        queue(
            screen("0 个定时器正在运行"),
            screen("1 个定时器正在运行", "初始时长：10分钟", "取消"),
        )

        val r = ClockControl.createTimer(ctx, 300)

        assertThat(r.verified).isNull()
        assertThat(r.observed).isNull()
        assertThat(r.detail).contains("readback=duration-card-not-found")
    }

    @Test
    fun `a card that declares the same duration twice is not one attributable object`() {
        // Some card layouts repeat 初始时长 and 总时长. Two labels could also mean two cards, and this
        // read cannot tell them apart, so it must not be graded as exactly one new object.
        queue(
            screen("0 个定时器正在运行"),
            screen("1 个定时器正在运行", "初始时长：5分钟", "总时长：5分钟", "取消"),
        )

        val r = ClockControl.createTimer(ctx, 300)

        assertThat(r.verified).isNull()
        assertThat(r.detail).contains("readback=duration-card-not-single")
    }

    @Test
    fun `a truncated read-back is not evidence`() {
        queue(
            screen("0 个定时器正在运行"),
            screen("1 个定时器正在运行", "初始时长：5分钟", truncated = true),
        )

        val r = ClockControl.createTimer(ctx, 300)

        assertThat(r.verified).isNull()
        assertThat(r.observed).isNull()
        assertThat(r.detail).contains("readback=after-truncated")
    }

    @Test
    fun `a page that is not the clock is not evidence`() {
        // Our own voice window can take the foreground between the request and the read.
        queue(
            screen("0 个定时器正在运行"),
            screen("1 个定时器正在运行", "初始时长：5分钟", pkg = "ai.closepaw"),
        )

        val r = ClockControl.createTimer(ctx, 300)

        assertThat(r.verified).isNull()
        assertThat(r.observed).isNull()
        assertThat(r.detail).contains("readback=after-page-unconfirmed")
    }

    @Test
    fun `an unreadable before-read blocks attribution instead of trusting the after-read`() {
        // Without a baseline, count 0 -> 1 cannot be attributed: the "1" may always have been there.
        queue(
            screen("1 个定时器正在运行", "初始时长：5分钟", truncated = true),
            screen("1 个定时器正在运行", "初始时长：5分钟", "取消"),
        )

        val r = ClockControl.createTimer(ctx, 300)

        assertThat(r.verified).isNull()
        assertThat(r.detail).contains("readback=before-truncated")
    }

    @Test
    fun `an intent the system refused is passed through and never graded`() {
        every { IntentActions.setTimer(any(), any()) } returns
            ToolResult.error("timer", "倒计时没能设置，系统拒绝了请求", "startActivity failed")
        queue(screen("0 个定时器正在运行"))

        val r = ClockControl.createTimer(ctx, 300)

        assertThat(r.status).isEqualTo(ToolStatus.EXECUTION_ERROR)
        assertThat(r.verified).isFalse()
        assertThat(r.observed).isNull()
        // The refusal sentence is the Intent layer's, not a read-back verdict.
        assertThat(r.speak).contains("系统拒绝")
    }
}
