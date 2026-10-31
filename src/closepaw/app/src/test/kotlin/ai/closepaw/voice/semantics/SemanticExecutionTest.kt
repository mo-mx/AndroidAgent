package ai.closepaw.voice.semantics

import ai.closepaw.voice.VoiceSession
import ai.closepaw.voice.tools.*
import android.content.Context
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

/** Real parser, capability table, runner, gate and clock; only Android/UI boundary is mocked. */
class SemanticExecutionTest {
    private val ctx = mockk<Context>()
    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(VoiceSession, UiAutomator, IntentActions)
        every { VoiceSession.isCurrent(any()) } returns true
        every { UiAutomator.foregroundPackage() } returns "com.sec.android.app.clockpackage"
        every { UiAutomator.clickText("闹钟") } returns true
        every { UiAutomator.screenTexts(any()) } returns emptyList()
    }
    @After fun cleanup() = unmockkAll()

    @Test fun `planner and call cannot collude against original cancel intent`() {
        val raw = "取消五分钟倒计时"
        val forged = SemanticParser.parse(raw).single().copy(action = Action.CREATE)
        assertThat(IntentGate.check(raw, forged,
            IntentGate.ToolCall(Action.CREATE, Target.TIMER, mapOf("seconds" to "300"))).allowed).isFalse()
    }

    @Test fun `cancel duration cannot disappear at tool boundary`() {
        val raw = "取消五分钟倒计时"
        val task = SemanticParser.parse(raw).single()
        assertThat(IntentGate.check(raw, task,
            IntentGate.ToolCall(Action.CANCEL, Target.TIMER)).allowed).isFalse()
        assertThat(IntentGate.check(raw, task.copy(params = emptyMap()),
            IntentGate.ToolCall(Action.CANCEL, Target.TIMER, mapOf("seconds" to "5"))).allowed).isFalse()
    }

    @Test fun `specified timer never falls back to global first cancel`() {
        every { UiAutomator.clickText("定时器") } returns true
        every { UiAutomator.screenTexts(any()) } returns listOf("2 个定时器正在运行", "5 分钟", "10 分钟", "取消", "取消")
        every { UiAutomator.clickText("取消") } returns true
        every { UiAutomator.root() } returns null
        val reply = SemanticRunner.run(ctx, "取消五分钟倒计时", 1)
        assertThat(reply).contains("无法唯一定位")
        verify(exactly = 0) { UiAutomator.clickText("取消") }
        verify(exactly = 0) { IntentActions.setTimer(any(), any()) }
    }

    @Test fun `wrong nonempty app package is not identity proof`() {
        mockkObject(AppNavigator, AppRegistry)
        val qq = AppRegistry.App("QQ", "com.tencent.mobileqq", emptyList())
        val wechat = AppRegistry.App("微信", "com.tencent.mm", emptyList())
        every { AppRegistry.apps(ctx, any()) } returns listOf(qq, wechat)
        every { AppNavigator.resolve(ctx, "微信") } returns AppNavigator.Resolution(qq, why = "bad resolver")
        every { AppNavigator.openResolved(any(), any(), any()) } returns ToolResult.ok("app", "WRONG LAUNCH", verified = true)
        val reply = SemanticRunner.run(ctx, "打开微信", 1)
        assertThat(reply).doesNotContain("WRONG LAUNCH")
        verify(exactly = 0) { AppNavigator.openResolved(any(), any(), any()) }
    }

    @Test fun `real app resolver launches only named installed app`() {
        mockkObject(AppRegistry)
        val qq = AppRegistry.App("QQ", "com.tencent.mobileqq", emptyList())
        val wechat = AppRegistry.App("微信", "com.tencent.mm", emptyList())
        every { AppRegistry.apps(ctx, any()) } returns listOf(qq, wechat)
        every { AppRegistry.launch(ctx, wechat) } returns ToolResult.ok("app", "正确打开微信", verified = true)
        val reply = SemanticRunner.run(ctx, "打开微信", 1)
        assertThat(reply).isEqualTo("正确打开微信")
        verify(exactly = 1) { AppRegistry.launch(ctx, wechat) }
        verify(exactly = 0) { AppRegistry.launch(ctx, qq) }
    }

    @Test fun `feature does not succeed merely because launch succeeds`() {
        mockkObject(AppRegistry)
        val app = AppRegistry.App("微信", "com.tencent.mm", emptyList())
        every { AppRegistry.apps(ctx, any()) } returns listOf(app)
        every { AppRegistry.launch(ctx, app) } returns ToolResult.ok("app", "已打开微信", verified = true)
        every { UiAutomator.findNode("扫一扫", any()) } returns null
        val reply = SemanticRunner.run(ctx, "打开微信扫一扫", 1)
        assertThat(reply).contains("没有找到扫一扫")
        assertThat(reply).doesNotContain("已经进入")
        verify(exactly = 1) { AppRegistry.launch(ctx, app) }
    }

    @Test fun `undo timer preserves remembered duration`() {
        mockkObject(ClockControl)
        every { ClockControl.cancelTimer(ctx, any()) } returns ToolResult.unavailable("timer_cancel", "未找到")
        ActionMemory.record(Action.CREATE, Target.TIMER, mapOf("seconds" to "300"), "测试")
        ActionMemory.undo(ctx)
        verify(exactly = 1) { ClockControl.cancelTimer(ctx, 300) }
        verify(exactly = 0) { ClockControl.cancelTimer(ctx, null) }
    }

    @Test fun `duplicate alarm times cannot select first row`() {
        every { UiAutomator.screenTexts(any()) } returns listOf("8:00", "8:00")
        val reply = SemanticRunner.run(ctx, "取消八点的闹钟", 1)
        assertThat(reply).contains("无法唯一定位")
        verify(exactly = 0) { UiAutomator.click(any()) }
        verify(exactly = 0) { IntentActions.setAlarm(any(), any(), any()) }
    }

    @Test fun `alarm cancel reaches lookup and absent target never creates`() {
        val reply = SemanticRunner.run(ctx, "取消八点的闹钟", 1)
        assertThat(reply).contains("没有8点0分的闹钟")
        verify(exactly = 1) { UiAutomator.screenTexts(120) }
        verify(exactly = 0) { IntentActions.setAlarm(any(), any(), any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }
}
