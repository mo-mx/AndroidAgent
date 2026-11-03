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
 * Round 36: `AppNavigator.openFeatureResolved` judged "the page changed" / "the page did not
 * change" from `UiAutomator.screenTexts(120)`, which is a *projection* of `readScreen`: the
 * `textsTruncated` / `nodesTruncated` / `serviceAvailable` facts are dropped on the way in.
 *
 * Two consequences, both of them claims the evidence cannot support:
 *
 *  - A read that hit its budget is a sample, not the screen. `after - before` over two samples can
 *    be non-empty on a screen that never changed (text that was there but unsampled), so
 *    "界面有变化" is not supported.
 *  - Symmetrically, an unchanged sample is not "界面没有明显变化": the change may be exactly in the
 *    part the walk never reached.
 *  - A missing accessibility service produced an empty read, which the old branch reported as
 *    "界面上没有找到X" — that is a claim about the app's UI, when the truth is that we could not see
 *    any UI at all.
 *
 * The fixture is a mocked accessibility boundary, not a device run.
 */
class AppFeatureReadCompletenessTest {
    private val ctx = mockk<Context>()
    private val pkg = "com.tencent.mm"
    private val app = AppRegistry.App("微信", pkg, listOf("微信"))
    private val node = mockk<AccessibilityNodeInfo>(relaxed = true)

    /**
     * Round 42: the click is gated on identity, so the stubbed control has to carry the signals a
     * real one does (resource id + class) and be bound to a window snapshot (`node`). The gate
     * itself is exercised against real implementations in AppFeatureClickBindingTest.
     */
    private fun control() = UiAutomator.FeatureControl(
        node, "扫一扫", viewId = "id/scan_entry", className = "android.widget.TextView",
    )

    private fun read(
        vararg texts: String,
        truncated: Boolean = false,
        service: Boolean = true,
    ) = UiAutomator.ScreenRead(
        texts = texts.toList(),
        nodesVisited = 40,
        nodesTruncated = false,
        textsTruncated = truncated,
        rootPackage = pkg,
        serviceAvailable = service,
    )

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(VoiceSession, UiAutomator, AppRegistry)
        every { VoiceSession.isCurrent(any()) } returns true
        every { AppRegistry.launch(any(), any()) } returns ToolResult.ok("app", "已打开微信", verified = true)
        every { UiAutomator.featureControl(any<String>(), any<List<String>>(), any<Long>()) } returns
            UiAutomator.FeatureLookup(control(), null, node)
        // Round 38: the click is `performAction(ACTION_CLICK)` on the bound node only — the old
        // stub `UiAutomator.click(node)` is gone with the parent-climb/coordinate fallback.
        // Round 40: the click now acts on a control re-resolved at the action boundary, so the
        // fresh one-shot lookup is stubbed with the same result. The re-resolution itself is
        // covered against real implementations in AppFeatureIdentityTest.
        every { UiAutomator.featureControlOnce(any<String>(), any<List<String>>()) } returns
            UiAutomator.FeatureLookup(control(), null, node)
        every { node.performAction(any()) } returns true
        every { UiAutomator.foregroundPackage() } returns pkg
    }

    @After
    fun cleanup() = unmockkAll()

    // ── the two facts a truncated read cannot establish ───────────────────────────────────────

    @Test
    fun `truncated reads cannot be the evidence that the page did not change`() {
        every { UiAutomator.readScreen(any()) } returns read("微信", "通讯录", "发现", truncated = true)

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.speak).doesNotContain("没有明显变化")
        assertThat(r.status).isNotEqualTo(ToolStatus.TIMEOUT)
        assertThat(r.verified).isNull()
        assertThat(r.detail).contains("truncated")
    }

    @Test
    fun `truncated reads cannot be the evidence that the page changed either`() {
        var call = 0
        every { UiAutomator.readScreen(any()) } answers {
            call++
            if (call == 1) read("微信", "通讯录", truncated = true)
            else read("微信", "通讯录", "扫一扫", "收付款", truncated = true)
        }

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.speak).doesNotContain("界面有变化")
        assertThat(r.speak).contains("不完整")
        assertThat(r.verified).isNull()
        assertThat(r.detail).contains("truncated")
    }

    @Test
    fun `an unreadable screen is not reported as a missing control`() {
        every { UiAutomator.readScreen(any()) } returns read(service = false)
        every { UiAutomator.featureControl(any<String>(), any<List<String>>(), any<Long>()) } returns
            UiAutomator.FeatureLookup(null, "no-window")

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).contains("无障碍")
        assertThat(r.speak).doesNotContain("没有找到")
        assertThat(r.verified).isFalse()
    }

    // ── control group: complete reads keep the older, weaker grading ──────────────────────────

    @Test
    fun `complete reads with new text still refuse to claim arrival`() {
        var call = 0
        every { UiAutomator.readScreen(any()) } answers {
            call++
            if (call == 1) read("微信", "通讯录", "发现")
            else read("微信", "通讯录", "发现", "扫一扫", "收付款")
        }

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isNull()
        assertThat(r.speak).contains("没能确认")
        assertThat(r.detail).contains("newTexts=")
    }

    @Test
    fun `complete reads with an unchanged screen stay a timeout`() {
        every { UiAutomator.readScreen(any()) } returns read("微信", "通讯录", "发现")

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.status).isEqualTo(ToolStatus.TIMEOUT)
        assertThat(r.speak).contains("没有明显变化")
        assertThat(r.verified).isFalse()
    }

    @Test
    fun `leaving the app is still an execution error`() {
        every { UiAutomator.readScreen(any()) } returns read("微信", "通讯录")
        every { UiAutomator.foregroundPackage() } returns "com.android.settings"

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.status).isEqualTo(ToolStatus.EXECUTION_ERROR)
        assertThat(r.speak).contains("跳出了")
    }
}
