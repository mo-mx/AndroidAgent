package ai.closepaw.voice.semantics

import ai.closepaw.voice.VoiceSession
import ai.closepaw.voice.tools.*
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

class TimerObjectExecutionTest {
    private val ctx = mockk<Context>()
    private fun node(text: String = "", vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.text } returns text
        every { n.contentDescription } returns null
        // These fixtures model the clock's own window: binding now requires the node's package.
        every { n.packageName } returns "com.sec.android.app.clockpackage"
        every { n.childCount } returns children.size
        children.forEachIndexed { i, c -> every { n.getChild(i) } returns c }
        every { UiAutomator.selectedState(n, any()) } returns true
        return n
    }
    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(VoiceSession, UiAutomator, IntentActions)
        every { VoiceSession.isCurrent(any()) } returns true
        every { UiAutomator.foregroundPackage() } returns "com.sec.android.app.clockpackage"
        every { UiAutomator.clickText("定时器") } returns true
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("2 个定时器正在运行"), nodesVisited = 3, rootPackage = "com.sec.android.app.clockpackage", serviceAvailable = true)
        every { UiAutomator.clickText("取消") } returns true
    }
    @After fun cleanup() = unmockkAll()

    @Test fun `cancel targets matching card not first button and does not claim verification`() {
        val wrong = node("取消")
        val right = node("取消")
        every { right.isClickable } returns true
        every { right.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
        val root = node("", node("", node("初始时长：10分钟"), wrong), node("", node("初始时长：5分钟"), right))
        every { UiAutomator.root() } returns root
        val reply = SemanticRunner.run(ctx, "取消五分钟倒计时", 1)
        assertThat(reply).contains("无法确认")
        verify(exactly = 1) { right.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { wrong.performAction(any()) }
        verify(exactly = 0) { UiAutomator.clickText("取消") }
    }

    @Test fun `unnamed cancel cannot ignore an unbound second timer`() {
        val first = node("取消")
        val second = node("取消")
        every { first.isClickable } returns true
        every { first.performAction(any()) } returns true
        every { UiAutomator.root() } returns node("", node("", node("初始时长：5分钟"), first), node("", node("剩余4分钟"), second))
        val reply = SemanticRunner.run(ctx, "取消倒计时", 1)
        assertThat(reply).contains("无法唯一定位")
        verify(exactly = 0) { first.performAction(any()) }
        verify(exactly = 0) { second.performAction(any()) }
    }

    @Test fun `alarm row binding without global identity refuses both switches`() {
        val wrong = node()
        val right = node()
        every { wrong.isCheckable } returns true
        every { right.isCheckable } returns true
        every { right.isChecked } returns true
        every { right.isClickable } returns true
        every { right.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
        every { UiAutomator.clickText("闹钟") } returns true
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(selectedTabs = mapOf("闹钟" to true, "定时器" to true),
            texts = listOf("9:00", "8:00"), nodesVisited = 3, rootPackage = "com.sec.android.app.clockpackage", serviceAvailable = true)
        every { UiAutomator.root() } returns node("", node("", node("9:00"), wrong), node("", node("8:00"), right))
        val result = ClockControl.closeAlarm(ctx, 8, 0)
        assertThat(result.status).isEqualTo(ToolStatus.BLOCKED)
        assertThat(result.speak).contains("对象身份")
        assertThat(result.verified).isFalse()
        verify(exactly = 0) { right.performAction(any()) }
        verify(exactly = 0) { wrong.performAction(any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    @Test fun `absent duration never cancels a different timer`() {
        val cancel = node("取消")
        every { UiAutomator.root() } returns node("", node("", node("初始时长：10分钟"), cancel))
        val reply = SemanticRunner.run(ctx, "取消五分钟倒计时", 1)
        assertThat(reply).contains("无法唯一定位")
        verify(exactly = 0) { cancel.performAction(any()) }
        verify(exactly = 0) { UiAutomator.clickText("取消") }
    }

    @Test fun `global duration text cannot bind an unrelated cancel control`() {
        val cancel = node("取消")
        every { cancel.isClickable } returns true
        every { cancel.performAction(any()) } returns true
        every { UiAutomator.root() } returns node("", node("初始时长：5分钟"), node("", cancel))
        val reply = SemanticRunner.run(ctx, "取消五分钟倒计时", 1)
        assertThat(reply).contains("无法唯一定位")
        verify(exactly = 0) { cancel.performAction(any()) }
    }

    @Test fun `duplicate durations refuse instead of first match`() {
        val first = node("取消")
        val second = node("取消")
        every { UiAutomator.root() } returns node("", node("", node("初始时长：5分钟"), first), node("", node("初始时长：5分钟"), second))
        val reply = SemanticRunner.run(ctx, "取消五分钟倒计时", 1)
        assertThat(reply).contains("无法唯一定位")
        verify(exactly = 0) { first.performAction(any()) }
        verify(exactly = 0) { second.performAction(any()) }
        verify(exactly = 0) { UiAutomator.clickText("取消") }
    }
}
