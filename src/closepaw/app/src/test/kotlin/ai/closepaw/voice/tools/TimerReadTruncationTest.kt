package ai.closepaw.voice.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

/**
 * Review backlog slice: a bounded screen read is a *sample*, so "nothing found in it" is not
 * "not there".
 *
 * `IntentActions.setTimer` reads the clock once after the tab click. `readScreen(80)` may stop on
 * the node cap (600) or the text budget (80). When that happened, the old code still said
 * 「我在时钟界面里没看到倒计时」 — an assertion about the clock UI derived from a partial view, and
 * the same class of defect the round-36 fix removed from `AppNavigator`.
 *
 * The rule pinned here:
 * - countdown wording present            → `countdown-ui=true` (evidence found, unaffected)
 * - an explicit 「0 个定时器正在运行」     → `countdown-ui=false` (affirmative zero, survives clipping)
 * - clipped read, nothing found          → `countdown-ui=unknown`, "读取不完整", never "没看到倒计时"
 * - complete read, nothing found         → `countdown-ui=false` (old behaviour kept)
 */
class TimerReadTruncationTest {
    private val ctx = mockk<Context>()
    private val pm = mockk<PackageManager>()
    private val clockPkg = UiAutomator.CLOCK_PKG

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { ctx.packageManager } returns pm
        every { pm.resolveActivity(any<Intent>(), any<Int>()) } returns mockk<ResolveInfo>()
        every { ctx.startActivity(any()) } just Runs
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Int>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
        mockkObject(UiAutomator)
        every { UiAutomator.foregroundPackage() } returns clockPkg
        every { UiAutomator.clickClockNavTab("定时器") } returns true
    }

    @After fun cleanup() = unmockkAll()

    private fun stubRead(
        texts: List<String>,
        textsTruncated: Boolean = false,
        nodesTruncated: Boolean = false,
    ) {
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = texts,
            nodesVisited = 600,
            nodesTruncated = nodesTruncated,
            textsTruncated = textsTruncated,
            rootPackage = clockPkg,
            serviceAvailable = true,
            selectedTabs = mapOf("定时器" to true),
        )
    }

    /** Text budget hit, no countdown wording inside it: the countdown may simply be further down. */
    @Test fun `a text-capped read cannot say the countdown is missing`() {
        stubRead(listOf("添加定时器"), textsTruncated = true)

        val r = IntentActions.setTimer(ctx, 300)

        assertThat(r.verified).isNull()
        assertThat(r.detail).contains("countdown-ui=unknown")
        assertThat(r.detail).doesNotContain("countdown-ui=false")
        assertThat(r.speak).contains("读取不完整")
        assertThat(r.speak).doesNotContain("没看到倒计时")
    }

    /** Node cap hit: equally a clipped view, not an empty page. */
    @Test fun `a node-capped read is equally incomplete`() {
        stubRead(listOf("添加定时器"), nodesTruncated = true)

        val r = IntentActions.setTimer(ctx, 300)

        assertThat(r.detail).contains("countdown-ui=unknown")
        assertThat(r.speak).contains("读取不完整")
        assertThat(r.speak).doesNotContain("没看到倒计时")
    }

    /** An explicit zero is affirmative evidence about the page, so clipping cannot erase it. */
    @Test fun `an explicit zero count survives clipping`() {
        stubRead(listOf("0 个定时器正在运行"), textsTruncated = true)

        val r = IntentActions.setTimer(ctx, 300)

        assertThat(r.detail).contains("countdown-ui=false")
        assertThat(r.detail).doesNotContain("countdown-ui=unknown")
    }

    /** Complete read that really found nothing: the old wording is still the honest one. */
    @Test fun `a complete read that found nothing still reports no countdown`() {
        stubRead(listOf("添加定时器"))

        val r = IntentActions.setTimer(ctx, 300)

        assertThat(r.detail).contains("countdown-ui=false")
        assertThat(r.speak).contains("没看到倒计时")
        assertThat(r.speak).doesNotContain("读取不完整")
    }

    /** Positive control: found countdown wording is evidence regardless of clipping. */
    @Test fun `countdown wording found in a clipped read is still evidence`() {
        stubRead(listOf("1 个定时器正在运行", "剩余 04:58"), textsTruncated = true)

        val r = IntentActions.setTimer(ctx, 300)

        assertThat(r.detail).contains("countdown-ui=true")
        assertThat(r.verified).isNull()
    }
}
