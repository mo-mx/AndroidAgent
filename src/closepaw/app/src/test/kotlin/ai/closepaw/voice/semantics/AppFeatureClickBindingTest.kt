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
 * Review `6552469328` (HEAD `4d23e5c`) — two items about *which control* a feature click may land
 * on, and one about a test that was verifying the wrong overload.
 *
 * 1. (P1/P2) A clickable help card whose **own** label is exactly 「扫一扫」 still beat the real
 *    entry labelled 「扫一扫，打开相机」, because `direct.size == 1` was returned before the
 *    multi-target check. Exact own text is not business identity: when two different clickable
 *    targets both answer to the name, nothing on the tree says which one is the feature.
 * 2. (P2) The re-read at the action boundary only re-checked package + candidate count. Two
 *    different pages of the same package, each with a unique same-named control, both satisfy that,
 *    so a page jump in the gap still landed a click on an unrelated control. The fresh target must
 *    be bound to the first target's identity (window id, control resource-id/role) and to the page
 *    structure it was found on.
 *
 * Pure JVM, synthetic trees, mocked accessibility boundary. No device evidence: which signals a
 * real app exposes (view ids on Compose screens, whether the page structure is stable) is
 * **unverified**.
 */
class AppFeatureClickBindingTest {
    private val ctx = mockk<Context>(relaxed = true)
    private val pkg = "com.tencent.mm"
    private val app = AppRegistry.App("微信", pkg, listOf("微信"))
    private val nodes = ArrayList<AccessibilityNodeInfo>()

    /** A synthetic accessibility node inside an app window. */
    private fun n(
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = false,
        owner: String = pkg,
        cls: String = "android.widget.LinearLayout",
        viewId: String? = null,
        windowId: Int = 7,
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.text } returns text
        every { node.contentDescription } returns desc
        every { node.packageName } returns owner
        every { node.className } returns cls
        every { node.viewIdResourceName } returns viewId
        every { node.windowId } returns windowId
        every { node.isClickable } returns clickable
        every { node.isVisibleToUser } returns true
        every { node.isScrollable } returns false
        every { node.performAction(any()) } returns true
        every { node.parent } returns null
        every { node.childCount } returns children.size
        children.forEachIndexed { i, child ->
            every { node.getChild(i) } returns child
            every { child.parent } returns node
        }
        nodes.add(node)
        return node
    }

    private fun assertNoActions() {
        nodes.forEach { node -> verify(exactly = 0) { node.performAction(any()) } }
    }

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
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("微信"),
            nodesVisited = 30,
            rootPackage = pkg,
            serviceAvailable = true,
        )
    }

    @After
    fun cleanup() = unmockkAll()

    // ── item 1: an exact own label is not business identity ──────────────────────────────────

    @Test
    fun `a clickable help card labelled exactly like the feature does not outrank the real entry`() {
        val card = n("扫一扫", clickable = true, cls = "android.widget.FrameLayout", viewId = "id/help_card")
        val entry = n(desc = "扫一扫，打开相机", clickable = true, viewId = "id/scan_entry")
        val body = n(children = listOf(card, entry), viewId = "id/page")
        every { UiAutomator.root() } returns n(children = listOf(body), viewId = "id/root")

        val lookup = UiAutomator.featureControl(pkg, "扫一扫", 60)

        assertThat(lookup.control).isNull()
        assertThat(lookup.why).startsWith("ambiguous")
        assertNoActions()
    }

    @Test
    fun `an exact own label does not win just because the rival is a longer text`() {
        val card = n("扫一扫设置", clickable = true, viewId = "id/settings_row")
        val entry = n("扫一扫", clickable = true, viewId = "id/scan_entry")
        val body = n(children = listOf(card, entry), viewId = "id/page")
        every { UiAutomator.root() } returns n(children = listOf(body), viewId = "id/root")

        val lookup = UiAutomator.featureControl(pkg, "扫一扫", 60)

        assertThat(lookup.control).isNull()
        assertThat(lookup.why).startsWith("ambiguous")
        assertNoActions()
    }

    @Test
    fun `a lone exactly labelled control is still clicked`() {
        val entry = n("扫一扫", clickable = true, viewId = "id/scan_entry")
        val body = n(children = listOf(entry), viewId = "id/page")
        every { UiAutomator.root() } returns n(children = listOf(body), viewId = "id/root")

        val lookup = UiAutomator.featureControl(pkg, "扫一扫", 60)

        assertThat(lookup.control).isNotNull()
        assertThat(lookup.control!!.node).isSameInstanceAs(entry)
        assertThat(lookup.why).isNull()
    }

    // ── item 2: the fresh target is bound to the first target's identity ─────────────────────

    @Test
    fun `a same-package page jump with a same-named unique control refuses the click`() {
        val firstEntry = n("扫一扫", clickable = true, viewId = "id/scan_entry")
        val firstPage = n(children = listOf(n(children = listOf(firstEntry), viewId = "id/home")), viewId = "id/page_a")
        // Page B: same package, same window, its own unique 「扫一扫」 — structure differs.
        val secondEntry = n("扫一扫", clickable = true, viewId = "id/scan_entry")
        val secondPage = n(children = listOf(n(children = listOf(secondEntry), viewId = "id/more")), viewId = "id/page_b")
        every { UiAutomator.root() } returnsMany listOf(firstPage, secondPage)

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.ok).isFalse()
        assertThat(r.detail).contains("beyond-scan-identity")
        assertThat(r.detail).contains("identity-page-changed")
        verify(exactly = 0) { secondEntry.performAction(any()) }
    }

    @Test
    fun `a control whose resource id changed between the two reads refuses the click`() {
        val firstEntry = n("扫一扫", clickable = true, viewId = "id/scan_entry")
        val firstPage = n(children = listOf(n(children = listOf(firstEntry), viewId = "id/home")), viewId = "id/page")
        // Same tree shape, but the second read's 「扫一扫」 is a different widget.
        val secondEntry = n("扫一扫", clickable = true, viewId = "id/help_scan")
        val secondPage = n(children = listOf(n(children = listOf(secondEntry), viewId = "id/home")), viewId = "id/page")
        every { UiAutomator.root() } returnsMany listOf(firstPage, secondPage)

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.ok).isFalse()
        assertThat(r.detail).contains("identity-target-changed")
        verify(exactly = 0) { secondEntry.performAction(any()) }
    }

    @Test
    fun `a window id change between the two reads refuses the click`() {
        val firstEntry = n("扫一扫", clickable = true, viewId = "id/scan_entry", windowId = 7)
        val firstPage = n(children = listOf(n(children = listOf(firstEntry), viewId = "id/home")), viewId = "id/page", windowId = 7)
        val secondEntry = n("扫一扫", clickable = true, viewId = "id/scan_entry", windowId = 8)
        val secondPage = n(children = listOf(n(children = listOf(secondEntry), viewId = "id/home")), viewId = "id/page", windowId = 8)
        every { UiAutomator.root() } returnsMany listOf(firstPage, secondPage)

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.ok).isFalse()
        assertThat(r.detail).contains("beyond-scan-identity")
        verify(exactly = 0) { secondEntry.performAction(any()) }
    }

    @Test
    fun `an unchanged page still clicks the fresh node`() {
        val stale = n("扫一扫", clickable = true, viewId = "id/scan_entry")
        val firstPage = n(children = listOf(n(children = listOf(stale), viewId = "id/home")), viewId = "id/page")
        val fresh = n("扫一扫", clickable = true, viewId = "id/scan_entry")
        val secondPage = n(children = listOf(n(children = listOf(fresh), viewId = "id/home")), viewId = "id/page")
        every { UiAutomator.root() } returnsMany listOf(firstPage, secondPage)

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.detail).doesNotContain("beyond-scan-identity")
        verify(exactly = 0) { stale.performAction(any()) }
        verify(exactly = 1) { fresh.performAction(any()) }
    }
}
