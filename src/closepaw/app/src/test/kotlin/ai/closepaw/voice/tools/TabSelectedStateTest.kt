package ai.closepaw.voice.tools

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import com.google.common.truth.Truth.assertThat

/**
 * Selection state of a tab is the only in-tree evidence that the *target page* was reached:
 * a visible label only proves the nav control exists. `null` means "not exposed by this tree" and
 * must stay distinct from `false` ("present and explicitly not selected").
 *
 * Review `6543364579` item 3: selection is only read from a node that is a tab *inside a navigation
 * bar* (two or more distinct clock tab labels under one ancestor), so every fixture here carries the
 * real parent chain and a peer tab label. The assertions are unchanged.
 */
class TabSelectedStateTest {
    private val peer = "定时器"

    private fun node(text: String?, desc: String? = null, selected: Boolean = false): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.text } returns text
        every { n.contentDescription } returns desc
        every { n.isSelected } returns selected
        every { n.childCount } returns 0
        every { n.parent } returns null
        return n
    }

    private fun rootWith(vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val bar = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { bar.text } returns null
        every { bar.contentDescription } returns null
        every { bar.isSelected } returns false
        every { bar.childCount } returns children.size
        children.forEachIndexed { i, c -> every { bar.getChild(i) } returns c }
        children.forEach { c -> every { c.parent } returns bar }
        // Round 19: a tab bar is never the window root, so the fixture exposes the window above it.
        val win = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { win.text } returns null
        every { win.contentDescription } returns null
        every { win.isSelected } returns false
        every { win.parent } returns null
        every { win.childCount } returns 1
        every { win.getChild(0) } returns bar
        every { bar.parent } returns win
        return win
    }

    /** A node that only wraps [child] and carries the selection flag, as a clickable tab item does. */
    private fun wrapper(selected: Boolean, vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val w = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { w.text } returns null
        every { w.contentDescription } returns null
        every { w.isSelected } returns selected
        every { w.childCount } returns children.size
        children.forEachIndexed { i, c -> every { w.getChild(i) } returns c }
        children.forEach { c -> every { c.parent } returns w }
        return w
    }

    @Test fun `selected tab is reported as selected`() {
        val tab = node("闹钟", selected = true)
        assertThat(UiAutomator.selectedState(rootWith(tab, node(peer)), "闹钟")).isTrue()
    }

    @Test fun `tab that exists but is not selected while another tab is selected reports false`() {
        val alarms = node("闹钟", selected = false)
        val timer = node(peer, selected = true)
        assertThat(UiAutomator.selectedState(rootWith(alarms, timer), "闹钟")).isFalse()
    }

    @Test fun `tree without any selection state is unknown, not false`() {
        val alarms = node("闹钟", selected = false)
        assertThat(UiAutomator.selectedState(rootWith(alarms, node(peer, selected = false)), "闹钟")).isNull()
    }

    @Test fun `absent tab is unknown`() {
        val timer = node(peer, selected = true)
        assertThat(UiAutomator.selectedState(rootWith(timer), "闹钟")).isNull()
    }

    @Test fun `content description labels are matched too`() {
        val tab = node(null, desc = "闹钟", selected = true)
        assertThat(UiAutomator.selectedState(rootWith(tab, node(peer)), "闹钟")).isTrue()
    }

    @Test fun `selection carried by a clickable ancestor still counts`() {
        val tab = node("闹钟", selected = false)
        val item = wrapper(selected = true, tab)
        assertThat(UiAutomator.selectedState(rootWith(item, node(peer)), "闹钟")).isTrue()
    }

    @Test fun `a label that merely contains the tab name does not decide the selection`() {
        // Device evidence 2026-10-31: the alarm tab carries a hint card reading 对关联闹钟进行分组.
        // A containing match hit that card first, so the selected tab was reported as not selected
        // (and the query refused a page that was actually correct).
        val hint = node("对关联闹钟进行分组")
        val tab = node(null, desc = "闹钟", selected = true)
        val page = node("第 1 页，共 2 页", selected = true)
        assertThat(UiAutomator.selectedState(rootWith(hint, page, tab, node(peer)), "闹钟")).isTrue()
    }

    @Test fun `containing label cannot establish tab selection without exact tab`() {
        val tab = node("闹钟 已开启", selected = true)
        assertThat(UiAutomator.selectedState(rootWith(tab, node(peer)), "闹钟")).isNull()
    }

    @Test fun `hint card with selected parent cannot establish tab selection`() {
        val hint = node("对关联闹钟进行分组")
        val parent = node(null, selected = true)
        every { hint.parent } returns parent
        assertThat(UiAutomator.selectedState(rootWith(hint, parent), "闹钟")).isNull()
    }
}
