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
 * Review `6547564219` P2 (both items) — credibility of the timer-creation diagnostics.
 *
 * 1. `ACTION_CLICK` returning true only proves the gesture was delivered. It does not prove that
 *    the tree read afterwards belongs to the clock, or that the “定时器” tab is the selected one.
 *    The read path used `screenTexts`, which ignores `rootPackage` and `selectedTabs`, so our own
 *    voice window (a real, historically observed distraction) could be read as the timer page.
 *    The rule pinned here: countdown diagnostics exist only when the *same* read shows the clock
 *    package and `selectedTabs["定时器"] == true`; otherwise the answer is unknown.
 *
 * 2. `it.contains("定时器正在运行")` also matched `"0 个定时器正在运行"` — the empty state was
 *    classified as a running countdown. The count must be parsed and only `N > 0` counts.
 */
class TimerDiagnosticsEvidenceTest {
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
        every { UiAutomator.clickText(any()) } returns true
        every { UiAutomator.tap(any(), any()) } returns true
    }

    @After fun cleanup() = unmockkAll()

    /**
     * Stubs both the tree read and the legacy text read with the same content, so the RED run
     * exercises the *old* path (which only used `screenTexts`) instead of dying on a missing stub.
     */
    private fun stubRead(
        texts: List<String>,
        rootPackage: String? = clockPkg,
        timerSelected: Boolean? = true,
    ): UiAutomator.ScreenRead {
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = texts,
            nodesVisited = texts.size,
            rootPackage = rootPackage,
            serviceAvailable = true,
            selectedTabs = mapOf("定时器" to timerSelected),
        )
        every { UiAutomator.screenTexts(any()) } returns texts
        return UiAutomator.ScreenRead(texts)
    }

    /** The tab was clicked, but the tree afterwards came from our own voice window. */
    @Test fun `read from another window yields unknown countdown state`() {
        stubRead(
            listOf("1 个定时器正在运行", "剩余 04:58"),
            rootPackage = "ai.closepaw",
            timerSelected = null,
        )

        val r = IntentActions.setTimer(ctx, 300)

        assertThat(r.verified).isNull()
        assertThat(r.speak).contains("无法确认")
        assertThat(r.detail).contains("countdown-ui=unknown")
        assertThat(r.detail).doesNotContain("countdown-ui=true")
        assertThat(r.detail).doesNotContain("countdown-ui=false")
    }

    /** Clock package on screen, but the timer tab is not reported selected: no page identity. */
    @Test fun `clock read without a selected timer tab yields unknown countdown state`() {
        stubRead(
            listOf("1 个定时器正在运行", "剩余 04:58"),
            timerSelected = false,
        )

        val r = IntentActions.setTimer(ctx, 300)

        assertThat(r.verified).isNull()
        assertThat(r.detail).contains("countdown-ui=unknown")
        assertThat(r.speak).contains("无法确认")
    }

    /** Explicit empty state is not a running countdown — the bug from review item 2. */
    @Test fun `zero running timers is not countdown evidence`() {
        stubRead(listOf("0 个定时器正在运行", "添加定时器"))

        val r = IntentActions.setTimer(ctx, 300)

        assertThat(r.detail).contains("countdown-ui=false")
        assertThat(r.detail).doesNotContain("countdown-ui=true")
        assertThat(r.speak).doesNotContain("已经开始")
        assertThat(r.verified).isNull()
    }

    /** Positive control: same read, clock package, timer tab selected, N > 0. */
    @Test fun `confirmed timer page with one running timer reports countdown ui only`() {
        stubRead(listOf("1 个定时器正在运行", "剩余 04:58"))

        val r = IntentActions.setTimer(ctx, 300)

        assertThat(r.detail).contains("countdown-ui=true")
        assertThat(r.detail).contains("no newly created object identity")
        assertThat(r.verified).isNull()
        assertThat(r.speak).doesNotContain("已经开始")
    }

    /** Confirmed timer page with no countdown wording: false, not unknown. */
    @Test fun `confirmed timer page without countdown wording reports false`() {
        stubRead(listOf("定时器", "添加定时器"))

        val r = IntentActions.setTimer(ctx, 300)

        assertThat(r.detail).contains("countdown-ui=false")
        assertThat(r.verified).isNull()
        assertThat(r.speak).doesNotContain("已经开始")
    }

    /** The evidence read is the only read of this path and no loose click is used. */
    @Test fun `diagnostics never fall back to a loose click`() {
        stubRead(listOf("1 个定时器正在运行"))

        IntentActions.setTimer(ctx, 300)

        verify(exactly = 1) { UiAutomator.clickClockNavTab("定时器") }
        verify(exactly = 0) { UiAutomator.clickText(any()) }
        verify(exactly = 0) { UiAutomator.tap(any(), any()) }
    }
}
