package ai.closepaw.voice.tools

import ai.closepaw.voice.semantics.*
import ai.closepaw.voice.semantics.Target
import ai.closepaw.voice.VoiceSession
import android.view.accessibility.AccessibilityNodeInfo

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

/** Real intent capability; only Android and accessibility boundaries are mocked. */
class ClockUndoSafetyTest {
    private val ctx = mockk<Context>()
    private val pm = mockk<PackageManager>()
    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { ctx.packageManager } returns pm
        every { pm.resolveActivity(any<Intent>(), any<Int>()) } returns mockk<ResolveInfo>()
        every { ctx.startActivity(any()) } just Runs
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Int>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
        mockkObject(UiAutomator, ActionMemory)
        every { UiAutomator.foregroundPackage() } returns "com.sec.android.app.clockpackage"
        every { UiAutomator.clickText("定时器") } returns true
        // Existing unrelated timer remains unchanged after OEM ignores the new request.
        every { UiAutomator.screenTexts(any()) } returns listOf("1 个定时器正在运行", "剩余 09:58", "10 分钟 0 秒")
    }
    @After fun cleanup() = unmockkAll()

    private fun node(text: String = "", vararg children: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.text } returns text
        every { n.contentDescription } returns null
        every { n.childCount } returns children.size
        children.forEachIndexed { i, c -> every { n.getChild(i) } returns c }
        return n
    }

    @Test fun `ignored same duration creation then runner undo never clicks old timer`() {
        mockkObject(VoiceSession)
        every { VoiceSession.isCurrent(any()) } returns true
        val cancel = node("取消")
        every { cancel.isClickable } returns true
        every { cancel.performAction(any()) } returns true
        every { UiAutomator.root() } returns node("", node("", node("初始时长：5分钟"), cancel))
        every { UiAutomator.screenTexts(any()) } returns listOf("1 个定时器正在运行", "初始时长：5分钟")
        SemanticRunner.run(ctx, "设置五分钟倒计时", 1)
        val reply = SemanticRunner.run(ctx, "撤销刚才的操作", 1)
        assertThat(reply).contains("不能安全撤销")
        verify(exactly = 1) { ActionMemory.recordResult(Action.CREATE, Target.TIMER, mapOf("seconds" to "300"), any()) }
        verify(exactly = 0) { cancel.performAction(any()) }
        verify(exactly = 1) { ctx.startActivity(any()) }
        assertThat(ActionMemory.last).isNull()
    }

    @Test fun `ignored same time creation then undo never toggles old alarm`() {
        val manager = mockk<android.app.AlarmManager>()
        every { ctx.getSystemService(Context.ALARM_SERVICE) } returns manager
        every { manager.nextAlarmClock } returns null
        every { UiAutomator.clickText("闹钟") } returns true
        every { UiAutomator.screenTexts(any()) } returns listOf("8:00")
        val toggle = node()
        every { toggle.isCheckable } returns true
        every { toggle.isChecked } returns true
        every { toggle.isClickable } returns true
        every { toggle.performAction(any()) } returns true
        every { UiAutomator.root() } returns node("", node("", node("8:00"), toggle))
        mockkObject(TorchControl)
        every { TorchControl.set(ctx, false) } returns ToolResult.ok("torch", "旧手电被关闭", verified = true)
        ActionMemory.recordResult(Action.ENABLE, Target.TORCH, emptyMap(),
            ToolResult.ok("torch", "已开启", verified = true))
        val result = ClockControl.createAlarm(ctx, 8, 0)
        assertThat(result.ok).isTrue()
        assertThat(result.verified).isFalse()
        val undo = ActionMemory.undo(ctx)
        assertThat(undo.speak).contains("不能安全撤销")
        verify(exactly = 0) { TorchControl.set(any(), any()) }
        verify(exactly = 1) { ActionMemory.recordResult(Action.CREATE, Target.ALARM, any(), any()) }
        verify(exactly = 0) { toggle.performAction(any()) }
        verify(exactly = 1) { ctx.startActivity(any()) }
        assertThat(ActionMemory.last).isNull()
    }
}
