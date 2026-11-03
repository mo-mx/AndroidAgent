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
 * Review `6551941593` — three items about which control a feature click is allowed to land on.
 *
 * 1. (P1/P2) The exact tier was exclusive: `if (exact.isNotEmpty()) exact else loose` threw away the
 *    real entry whenever a *help card* carried the exact label and the button carried a descriptive
 *    one (「扫一扫」 vs 「扫一扫，打开相机」). Exact text does not prove business identity.
 * 2. (P2) 「扫码」 and 「扫一扫」 were not normalised, so a genuine spoken alias produced
 *    `not-found` against a screen that said 扫一扫.
 * 3. (P2, static race) The node found by `featureControl` was clicked with no re-read of the active
 *    window in between, so a popup or a page jump in that gap could land the click on a stale node.
 *
 * Pure JVM, synthetic trees, mocked accessibility boundary. No device evidence.
 */
class AppFeatureIdentityTest {
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

    // ── item 1: exact text does not prove identity ────────────────────────────────────────────

    @Test
    fun `an exactly labelled help card does not outrank the real entry with a descriptive label`() {
        val cardTitle = n("扫一扫")
        val card = n(clickable = true, children = listOf(cardTitle))
        val entry = n(desc = "扫一扫，打开相机", clickable = true)
        val body = n(children = listOf(card, entry))
        every { UiAutomator.root() } returns n(children = listOf(body))

        val lookup = UiAutomator.featureControl(pkg, "扫一扫", 60)

        assertThat(lookup.control).isNull()
        assertThat(lookup.why).startsWith("ambiguous")
        assertNoActions()
    }

    @Test
    fun `an exact label next to a longer text card is not identity either`() {
        // Round 42 (review 6552469328, item 1): this used to assert that the exactly labelled entry
        // *wins* over a 「扫一扫设置」 card. Exact own text does not prove business identity, so the
        // expectation is now the honest one: two different clickable controls, no click.
        val card = n("扫一扫设置", clickable = true, viewId = "id/settings_row")
        val entry = n("扫一扫", clickable = true, viewId = "id/scan_entry")
        val body = n(children = listOf(card, entry))
        every { UiAutomator.root() } returns n(children = listOf(body))

        val lookup = UiAutomator.featureControl(pkg, "扫一扫", 60)

        assertThat(lookup.control).isNull()
        assertThat(lookup.why).startsWith("ambiguous")
        verify(exactly = 0) { card.performAction(any()) }
        verify(exactly = 0) { entry.performAction(any()) }
    }

    @Test
    fun `a lone exactly labelled control is still clicked`() {
        val entry = n("扫一扫", clickable = true)
        val body = n(children = listOf(entry))
        every { UiAutomator.root() } returns n(children = listOf(body))

        val lookup = UiAutomator.featureControl(pkg, "扫一扫", 60)

        assertThat(lookup.control).isNotNull()
        assertThat(lookup.control!!.node).isSameInstanceAs(entry)
        assertThat(lookup.why).isNull()
    }

    // ── item 2: 扫码 is a spoken alias of 扫一扫 ───────────────────────────────────────────────

    @Test
    fun `the utterance 扫码 is parsed to the canonical feature identity`() {
        val tasks = SemanticParser.parse("打开微信扫码")

        assertThat(tasks).hasSize(1)
        assertThat(tasks[0].target).isEqualTo(Target.APP_FEATURE)
        assertThat(tasks[0].params["feature"]).isEqualTo("扫一扫")
        assertThat(tasks[0].params["name"]).isEqualTo("微信")
    }

    @Test
    fun `an utterance of 扫码 reaches a control that only says 扫一扫`() {
        val entry = n("扫一扫", clickable = true)
        val body = n(children = listOf(entry))
        every { UiAutomator.root() } returns n(children = listOf(body))

        val feature = SemanticParser.parse("打开微信扫码")[0].params.getValue("feature")
        val r = AppNavigator.openFeatureResolved(ctx, app, feature, "unit-test")

        assertThat(r.speak).doesNotContain("没有找到")
        verify(exactly = 1) { entry.performAction(any()) }
    }

    @Test
    fun `a feature with no synonym map is still looked up by its own name`() {
        assertThat(AppFeatureAliases.labelsFor("扫一扫")).contains("扫一扫")
        assertThat(AppFeatureAliases.labelsFor("朋友圈")).containsExactly("朋友圈")
    }

    // ── item 3: the window is re-read at the action boundary ──────────────────────────────────

    @Test
    fun `a window swap between the scan and the click refuses the click`() {
        val entry = n("扫一扫", clickable = true)
        val first = n(children = listOf(n(children = listOf(entry))))
        val second = n(children = listOf(n(text = "收款码", clickable = true)))
        every { UiAutomator.root() } returnsMany listOf(first, second)

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.ok).isFalse()
        assertThat(r.detail).contains("beyond-scan")
        assertNoActions()
    }

    @Test
    fun `the click lands on the control re-resolved at the action boundary, not the stale one`() {
        // Round 42: the fresh node must also carry the same identity signals (resource id/class) and
        // sit on the same page structure, otherwise the identity gate refuses it by design.
        val stale = n("扫一扫", clickable = true, viewId = "id/scan_entry")
        val first = n(children = listOf(n(children = listOf(stale), viewId = "id/home")))
        val fresh = n("扫一扫", clickable = true, viewId = "id/scan_entry")
        val second = n(children = listOf(n(children = listOf(fresh), viewId = "id/home")))
        every { UiAutomator.root() } returnsMany listOf(first, second)

        AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        verify(exactly = 0) { stale.performAction(any()) }
        verify(exactly = 1) { fresh.performAction(any()) }
    }

    @Test
    fun `a window that changed package between the scan and the click refuses the click`() {
        val entry = n("扫一扫", clickable = true)
        val first = n(children = listOf(n(children = listOf(entry))))
        val second = n(owner = "com.android.systemui", children = listOf(n(text = "允许", clickable = true, owner = "com.android.systemui")))
        every { UiAutomator.root() } returnsMany listOf(first, second)

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.ok).isFalse()
        assertThat(r.detail).contains("beyond-scan=window-not-target")
        verify(exactly = 0) { entry.performAction(any()) }
    }

    @Test
    fun `an unchanged window still clicks`() {
        val entry = n("扫一扫", clickable = true, viewId = "id/scan_entry")
        val tree = n(children = listOf(n(children = listOf(entry), viewId = "id/home")))
        every { UiAutomator.root() } returnsMany listOf(tree, tree)

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.detail).doesNotContain("beyond-scan")
        verify(exactly = 1) { entry.performAction(any()) }
    }
}
