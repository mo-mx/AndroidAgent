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
 * Review `6551213132` items 2 and 3 — opening an in-app feature.
 *
 * 2. The launch check ran *before* the read, and the read's package was never compared with the
 *    app's. `findNode` then matched `text/contentDescription.contains(feature)` in whatever window
 *    happened to be in front, so a permission sheet or a same-text card from another app could be
 *    clicked; the foreground check after the click cannot undo that.
 * 3. `findNode == null` was spoken as 「界面上没有找到X」 even when the read had been clipped — a
 *    claim about the app's UI derived from a partial sample.
 *
 * Pure JVM, mocked accessibility boundary.
 */
class AppFeatureWindowIdentityTest {
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
        rootPackage: String? = pkg,
        textsTruncated: Boolean = false,
        nodesTruncated: Boolean = false,
        service: Boolean = true,
    ) = UiAutomator.ScreenRead(
        texts = texts.toList(),
        nodesVisited = 40,
        nodesTruncated = nodesTruncated,
        textsTruncated = textsTruncated,
        rootPackage = rootPackage,
        serviceAvailable = service,
    )

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any<Throwable>()) } returns 0
        mockkObject(VoiceSession, UiAutomator, AppRegistry)
        every { VoiceSession.isCurrent(any()) } returns true
        every { AppRegistry.launch(any(), any()) } returns ToolResult.ok("app", "已打开微信", verified = true)
        every { UiAutomator.foregroundPackage() } returns pkg
        every { UiAutomator.readScreen(any()) } returns read("微信", "通讯录", "扫一扫")
        every { UiAutomator.featureControl(any<String>(), any<List<String>>(), any<Long>()) } returns
            UiAutomator.FeatureLookup(control(), null, node)
        // Round 40: the click now acts on a control re-resolved at the action boundary, so the
        // fresh one-shot lookup is stubbed with the same result. The re-resolution itself is
        // covered against real implementations in AppFeatureIdentityTest.
        every { UiAutomator.featureControlOnce(any<String>(), any<List<String>>()) } returns
            UiAutomator.FeatureLookup(control(), null, node)
        every { node.performAction(any()) } returns true
    }

    @After
    fun cleanup() = unmockkAll()

    /** Item 2: the tree we are about to click in is not the app's own window. */
    @Test
    fun `a foreign window in front is not searched for the feature`() {
        every { UiAutomator.readScreen(any()) } returns
            read("微信", "扫一扫", rootPackage = "com.android.permissioncontroller")
        every { UiAutomator.featureControl(any<String>(), any<List<String>>(), any<Long>()) } returns
            UiAutomator.FeatureLookup(null, "window-not-target(com.android.permissioncontroller)")

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.ok).isFalse()
        assertThat(r.speak).doesNotContain("已经进入")
        assertThat(r.detail).contains("window")
        verify(exactly = 0) { node.performAction(any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    /** Item 2: the lookup itself stayed bound to the app's window — no generic click fallback. */
    @Test
    fun `the found control is clicked directly and never through a coordinate fallback`() {
        var call = 0
        every { UiAutomator.readScreen(any()) } answers {
            call++
            if (call == 1) read("微信", "通讯录", "发现")
            else read("微信", "通讯录", "发现", "扫一扫", "收付款")
        }

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        verify(exactly = 1) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    /** Item 2: the target window must still be the app's at bind time. */
    @Test
    fun `a lookup that lost the app window refuses without clicking`() {
        every { UiAutomator.featureControl(any<String>(), any<List<String>>(), any<Long>()) } returns
            UiAutomator.FeatureLookup(null, "window-not-target(com.android.systemui)")

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.ok).isFalse()
        assertThat(r.speak).contains("窗口")
        verify(exactly = 0) { node.performAction(any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    /** Item 3: clipped read + control not found ≠ "the control is not there". */
    @Test
    fun `a text-capped read that missed the control does not claim it is absent`() {
        every { UiAutomator.readScreen(any()) } returns
            read("微信", "通讯录", textsTruncated = true)
        every { UiAutomator.featureControl(any<String>(), any<List<String>>(), any<Long>()) } returns
            UiAutomator.FeatureLookup(null, "not-found")

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.speak).doesNotContain("没有找到")
        assertThat(r.speak).contains("没法确认")
        assertThat(r.detail).contains("before-truncated")
        verify(exactly = 0) { node.performAction(any()) }
    }

    /** Item 3: the node cap is the same kind of sample limit. */
    @Test
    fun `a node-capped read that missed the control does not claim it is absent`() {
        every { UiAutomator.readScreen(any()) } returns
            read("微信", "通讯录", nodesTruncated = true)
        every { UiAutomator.featureControl(any<String>(), any<List<String>>(), any<Long>()) } returns
            UiAutomator.FeatureLookup(null, "not-found")

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.speak).doesNotContain("没有找到")
        assertThat(r.speak).contains("没法确认")
    }

    /** Control: a complete read that really has no such label keeps the old wording. */
    @Test
    fun `a complete read that missed the control still says it was not found`() {
        every { UiAutomator.featureControl(any<String>(), any<List<String>>(), any<Long>()) } returns
            UiAutomator.FeatureLookup(null, "not-found")

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.speak).contains("没有找到")
        assertThat(r.speak).doesNotContain("没法确认")
    }
}
