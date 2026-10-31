package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.*
import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class ClockFreshEvidenceTest {
    private val ctx = mockk<Context>()
    private val pkg = "com.sec.android.app.clockpackage"
    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        mockkObject(UiAutomator, IntentActions, AppRegistry)
        every { UiAutomator.root() } returns null
        every { UiAutomator.foregroundPackage() } returns pkg
        every { UiAutomator.clickText(any()) } returns true
        every { UiAutomator.tabSelected(any()) } returns true
        every { IntentActions.nextAlarm(ctx) } returns null
        every { AppRegistry.find(any(), any()) } returns AppRegistry.App("时钟", pkg, emptyList())
        every { AppRegistry.launch(any(), any()) } returns ToolResult.ok("app", "opened")
    }
    private fun bindingSelection(selected: Boolean?, recovered: Boolean = false) {
        val root = mockk<android.view.accessibility.AccessibilityNodeInfo>(relaxed = true)
        val foreign = mockk<android.view.accessibility.AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns pkg
        every { foreign.packageName } returns "ai.closepaw"
        every { root.childCount } returns 0
        every { UiAutomator.root() } returnsMany
            (if (recovered) listOf(null, foreign, null, root) else listOf(null, root))
        every { UiAutomator.selectedState(root, "定时器") } returns selected
        val r = ClockControl.cancelTimer(ctx, 300)
        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isFalse()
        verify(exactly = 1) { UiAutomator.selectedState(root, "定时器") }
        verify(exactly = 0) { UiAutomator.click(any()) }
        verify(exactly = 0) { root.performAction(any()) }
    }
    @Test fun `initial binding null cannot borrow navigation selected true`() = bindingSelection(null)
    @Test fun `initial binding false independently refuses`() = bindingSelection(false)
    @Test fun `recovered binding false independently refuses`() = bindingSelection(false, true)

    @Test fun `cancel click failure independently refuses before binding`() {
        every { UiAutomator.clickText("定时器") } returns false
        every { UiAutomator.tabSelected("定时器") } returns null
        every { UiAutomator.waitForText("定时器", any()) } returns true
        val r = ClockControl.cancelTimer(ctx, 300)
        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isFalse()
        verify(exactly = 0) { UiAutomator.selectedState(any(), any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }

    private fun closeRecovery(selected: Boolean?) {
        every { UiAutomator.readScreen(any()) } returnsMany listOf(
            UiAutomator.ScreenRead(rootPackage = "ai.closepaw"),
            UiAutomator.ScreenRead(texts = listOf("9:00"), rootPackage = pkg, checkableCount = 1,
                selectedTabs = mapOf("闹钟" to selected)))
        val r = ClockControl.closeAlarm(ctx, 8, 0)
        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isFalse()
        assertThat(r.speak).contains("没读到时钟的闹钟页面")
        assertThat(r.speak).doesNotContain("当前可见区域未找到")
        verify(exactly = 0) { UiAutomator.selectedState(any(), any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }
    @Test fun `close alarm recovered null independently refuses stale selected`() = closeRecovery(null)
    @Test fun `close alarm recovered false independently refuses stale selected`() = closeRecovery(false)

    private fun closeBinding(selected: Boolean?) {
        val root = mockk<android.view.accessibility.AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns pkg
        every { root.childCount } returns 0
        every { UiAutomator.root() } returnsMany listOf(null, root)
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00"), rootPackage = pkg, selectedTabs = mapOf("闹钟" to true))
        every { UiAutomator.selectedState(root, "闹钟") } returns selected
        val r = ClockControl.closeAlarm(ctx, 8, 0)
        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isFalse()
        verify(exactly = 1) { UiAutomator.selectedState(root, "闹钟") }
        verify(exactly = 0) { root.performAction(any()) }
        verify(exactly = 0) { UiAutomator.click(any()) }
    }
    @Test fun `close binding null independently rejects old navigation true`() = closeBinding(null)
    @Test fun `close binding false independently rejects confirmed query page`() = closeBinding(false)

    @After fun cleanup() = unmockkAll()

    private fun queryRecovery(selected: Map<String, Boolean?>) {
        every { UiAutomator.readScreen(any()) } returnsMany listOf(
            UiAutomator.ScreenRead(rootPackage = "ai.closepaw"),
            UiAutomator.ScreenRead(texts = listOf("2 个定时器正在运行"), rootPackage = pkg,
                selectedTabs = selected))
        val r = ClockControl.queryTimers(ctx)
        assertThat(r.verified).isFalse()
        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).doesNotContain("2个倒计时")
        assertThat(r.detail).doesNotContain("count=2")
        verify(exactly = 2) { UiAutomator.readScreen(any()) }
    }
    @Test fun `recovered query null selection rejects stale true`() = queryRecovery(mapOf("定时器" to null))
    @Test fun `recovered query false selection rejects stale true`() = queryRecovery(mapOf("定时器" to false))
    @Test fun `recovered query missing evidence rejects stale true`() = queryRecovery(emptyMap())

    @Test fun `alarm query false on fresh read rejects old true`() {
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00"), rootPackage = pkg, checkableCount = 1,
            selectedTabs = mapOf("闹钟" to false))
        val r = ClockControl.queryAlarms(ctx)
        assertThat(r.verified).isFalse()
        assertThat(r.detail).contains("visible=[]")
    }

    @Test fun `close alarm false on fresh read never binds or claims absence`() {
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00"), rootPackage = pkg, checkableCount = 1,
            selectedTabs = mapOf("闹钟" to false))
        val r = ClockControl.closeAlarm(ctx, 8, 0)
        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isFalse()
        assertThat(r.speak).doesNotContain("不存在")
        verify(exactly = 0) { UiAutomator.click(any()) }
        verify(exactly = 0) { UiAutomator.selectedState(any(), any()) }
    }
}
