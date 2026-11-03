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
 * Review `6551563018` items 2 and 3 — which node a feature click is allowed to land on.
 *
 * 2. (P1/P2) `clickableWithin` tested `isClickable` **before** the window-root boundary, so when the
 *    label and its intermediate parents were not clickable but the active window *root* was, the
 *    whole window root was returned and clicked. That is not the control the user named, and it
 *    contradicts the documented "never cross the window root".
 * 3. (P2) `featureControl` took the **first** `label.contains(text)` node from a BFS, with no
 *    exact-label preference and no ambiguity rule: a "扫一扫说明" card listed before the real entry
 *    could be clicked, and if the first hit happened to be a non-clickable title the real button was
 *    never reached (`label-not-clickable`).
 *
 * Pure JVM, synthetic trees, mocked accessibility boundary. No device evidence.
 */
class AppFeatureControlAmbiguityTest {
    private val ctx = mockk<Context>()
    private val pkg = "com.tencent.mm"
    private val app = AppRegistry.App("微信", pkg, listOf("微信"))
    private val nodes = ArrayList<AccessibilityNodeInfo>()

    /** A synthetic accessibility node in the app's own window. */
    private fun n(
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = false,
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.text } returns text
        every { node.contentDescription } returns desc
        every { node.packageName } returns pkg
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
            texts = listOf("微信", "扫一扫"),
            nodesVisited = 30,
            rootPackage = pkg,
            serviceAvailable = true,
        )
    }

    @After
    fun cleanup() = unmockkAll()

    // ── item 2: the window root is never the click target ─────────────────────────────────────

    @Test
    fun `a clickable window root is not the click target of a feature label`() {
        val label = n("扫一扫", clickable = false)
        val group = n(clickable = false, children = listOf(label))
        val root = n(clickable = true, children = listOf(group))
        every { UiAutomator.root() } returns root

        val lookup = UiAutomator.featureControl(pkg, "扫一扫", 60)

        assertThat(lookup.control).isNull()
        assertThat(lookup.why).isEqualTo("label-not-clickable")
        // The whole-window node must never be the thing we click.
        verify(exactly = 0) { root.performAction(any()) }
        assertNoActions()
    }

    // ── item 3: candidate enumeration instead of first hit ────────────────────────────────────

    @Test
    fun `two exact-label entries are ambiguous and nothing is clicked`() {
        val card = n("扫一扫", clickable = true)
        val entry = n("扫一扫", clickable = true)
        val body = n(children = listOf(card, entry))
        every { UiAutomator.root() } returns n(children = listOf(body))

        val lookup = UiAutomator.featureControl(pkg, "扫一扫", 60)

        assertThat(lookup.control).isNull()
        assertThat(lookup.why).startsWith("ambiguous")
        assertNoActions()
    }

    @Test
    fun `two loose matches with no exact label are ambiguous`() {
        val card = n("扫一扫说明", clickable = true)
        val other = n("扫一扫历史", clickable = true)
        val body = n(children = listOf(card, other))
        every { UiAutomator.root() } returns n(children = listOf(body))

        val lookup = UiAutomator.featureControl(pkg, "扫一扫", 60)

        assertThat(lookup.control).isNull()
        assertThat(lookup.why).startsWith("ambiguous")
        assertNoActions()
    }

    @Test
    fun `a non-clickable first match does not hide a later clickable entry`() {
        val title = n("扫一扫", clickable = false)
        val entry = n("扫一扫", clickable = true)
        val body = n(children = listOf(title, entry))
        every { UiAutomator.root() } returns n(children = listOf(body))

        val lookup = UiAutomator.featureControl(pkg, "扫一扫", 60)

        assertThat(lookup.control).isNotNull()
        assertThat(lookup.control!!.node).isSameInstanceAs(entry)
        assertThat(lookup.why).isNull()
    }

    @Test
    fun `an exact label next to a longer card is not identity either`() {
        // Round 42 (review 6552469328, item 1): this test used to assert that the exactly labelled
        // entry wins over a 「扫一扫设置」 card. Two different clickable controls that both answer to
        // the name carry no evidence of which one is the feature, so the honest outcome is a refusal.
        val card = n("扫一扫设置", clickable = true)
        val entry = n("扫一扫", clickable = true)
        val body = n(children = listOf(card, entry))
        every { UiAutomator.root() } returns n(children = listOf(body))

        val lookup = UiAutomator.featureControl(pkg, "扫一扫", 60)

        assertThat(lookup.control).isNull()
        assertThat(lookup.why).startsWith("ambiguous")
        verify(exactly = 0) { card.performAction(any()) }
        verify(exactly = 0) { entry.performAction(any()) }
    }

    // ── the navigator must not click when the lookup refused ──────────────────────────────────

    @Test
    fun `an ambiguous lookup is spoken and never clicked`() {
        every { UiAutomator.featureControl(any<String>(), any<List<String>>(), any<Long>()) } returns
            UiAutomator.FeatureLookup(null, "ambiguous(2)")

        val r = AppNavigator.openFeatureResolved(ctx, app, "扫一扫", "unit-test")

        assertThat(r.ok).isFalse()
        assertThat(r.speak).contains("多个")
        assertThat(r.detail).contains("ambiguous")
        assertNoActions()
    }
}
