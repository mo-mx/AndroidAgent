package ai.closepaw.voice.tools

import android.view.accessibility.AccessibilityNodeInfo
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * The device run could not tell "the page had nothing" from "the read was cut short". The report
 * therefore carries nodesVisited / nodesTruncated / textsTruncated, and those fields must only ever
 * be true when the reader really stopped early. A diagnostic that always says "not truncated" is
 * worse than no diagnostic, so the boundary is pinned here.
 */
class UiAutomatorReadTest {
    private fun node(text: String = "", vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.text } returns text
        every { n.contentDescription } returns null
        every { n.packageName } returns "com.sec.android.app.clockpackage"
        every { n.childCount } returns children.size
        children.forEachIndexed { i, c -> every { n.getChild(i) } returns c }
        // Tab identity is proven by a navigation bar, so the fixture must expose the real parents.
        children.forEach { c -> every { c.parent } returns n }
        return n
    }

    @Test fun `selection evidence belongs to the root whose texts were read`() {
        val alarm = node("闹钟")
        val timer = node("定时器")
        every { alarm.isSelected } returns true
        every { timer.isSelected } returns false
        val r = UiAutomator.readTree(node("", node("", alarm, timer), node("8:00")), 60, true)
        assertThat(r.selectedTabs["闹钟"]).isTrue()
        assertThat(r.selectedTabs["定时器"]).isFalse()
        assertThat(r.texts).contains("8:00")
        val missing = UiAutomator.readTree(node("8:00"), 60, true)
        assertThat(missing.selectedTabs["闹钟"]).isNull()
        assertThat(missing.selectedTabs["定时器"]).isNull()
    }

    @Test fun `texts dropped past the limit are reported as truncated`() {
        val r = UiAutomator.readTree(node("", node("a"), node("b"), node("c")), limit = 2, serviceAvailable = true)
        assertThat(r.texts).containsExactly("a", "b")
        assertThat(r.textsTruncated).isTrue()
        assertThat(r.nodesTruncated).isFalse()
    }

    @Test fun `filling the limit exactly without more text is not truncation`() {
        val r = UiAutomator.readTree(node("", node("a"), node("b")), limit = 2, serviceAvailable = true)
        assertThat(r.texts).containsExactly("a", "b")
        assertThat(r.textsTruncated).isFalse()
        assertThat(r.rootPackage).isEqualTo("com.sec.android.app.clockpackage")
    }

    @Test fun `hitting the node cap with work left is reported as node truncation`() {
        var n = node("leaf")
        repeat(UiAutomator.MAX_NODES + 40) { n = node("", n) }
        val r = UiAutomator.readTree(n, limit = 10_000, serviceAvailable = true)
        assertThat(r.nodesVisited).isEqualTo(UiAutomator.MAX_NODES)
        assertThat(r.nodesTruncated).isTrue()
        assertThat(r.textsTruncated).isFalse()
    }

    @Test fun `a null root is an unavailable service state and never an empty page`() {
        val r = UiAutomator.readTree(null, limit = 60, serviceAvailable = true)
        assertThat(r.texts).isEmpty()
        assertThat(r.nodesVisited).isEqualTo(0)
        assertThat(r.nodesTruncated).isFalse()
        assertThat(r.serviceAvailable).isTrue()
        assertThat(r.rootPackage).isNull()
    }
}
