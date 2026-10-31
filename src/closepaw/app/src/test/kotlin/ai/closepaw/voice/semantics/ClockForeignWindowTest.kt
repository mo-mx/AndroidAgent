package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.*
import android.content.Context
import android.util.Log
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

/**
 * Device evidence (2026-10-31, round 5, build 83aedb73): QUERY/TIMER reported
 * `tab=clicked ... rootPkg=ai.closepaw` — the read came back from ClosePaw's own voice window, not
 * from the clock, even though the clock tab had been reached. The same shape explains the earlier
 * alarm `readable=false visible=[]`. So a read is only about the clock when the root package says so;
 * anything else must fail closed instead of being parsed as "no timers / no alarms".
 */
class ClockForeignWindowTest {
    private val ctx = mockk<Context>()
    private val clockPkg = "com.sec.android.app.clockpackage"
    private val ownPkg = "ai.closepaw"

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(UiAutomator, IntentActions)
        every { UiAutomator.foregroundPackage() } returns clockPkg
        every { UiAutomator.clickText("定时器") } returns true
        every { UiAutomator.clickText("闹钟") } returns true
        every { IntentActions.nextAlarm(ctx) } returns null
        every { UiAutomator.tabSelected(any()) } returns true
    }
    @After fun cleanup() = unmockkAll()

    @Test fun `timer query rejects a read taken from our own window`() {
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("添加定时器"), nodesVisited = 3, rootPackage = ownPkg, serviceAvailable = true)
        val r = ClockControl.queryTimers(ctx)
        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.detail).contains("rootPkg=$ownPkg")
        assertThat(r.speak).doesNotContain("现在没有正在运行的倒计时")
    }

    @Test fun `alarm query does not report an empty list from a foreign window`() {
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(
            texts = listOf("8:00", "暂无闹钟"), nodesVisited = 3, rootPackage = ownPkg, serviceAvailable = true)
        val r = ClockControl.queryAlarms(ctx)
        assertThat(r.verified).isFalse()
        assertThat(r.speak).contains("无法读取闹钟列表")
        assertThat(r.detail).contains("readable=false")
        assertThat(r.detail).contains("rootPkg=$ownPkg")
    }

    @Test fun `a foreign read is retried and the clock read is the one used`() {
        mockkObject(AppRegistry)
        val clockApp = AppRegistry.App("时钟", clockPkg, emptyList())
        every { AppRegistry.find(any(), any()) } returns clockApp
        every { AppRegistry.launch(any(), any()) } returns ToolResult.ok("app", "已打开时钟", verified = true)
        every { UiAutomator.readScreen(any()) } returnsMany listOf(
            UiAutomator.ScreenRead(selectedTabs = mapOf("闹钟" to true, "定时器" to true), texts = listOf("ClosePaw"), nodesVisited = 3, rootPackage = ownPkg, serviceAvailable = true),
            UiAutomator.ScreenRead(selectedTabs = mapOf("闹钟" to true, "定时器" to true), texts = listOf("2 个定时器正在运行"), nodesVisited = 9, rootPackage = clockPkg, serviceAvailable = true),
        )
        val r = ClockControl.queryTimers(ctx)
        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isTrue()
        assertThat(r.speak).contains("2")
        assertThat(r.detail).contains("rootPkg=$clockPkg")
    }

    @Test fun `a foreign read re-opens the clock instead of only re-reading`() {
        // The voice window can take the foreground mid-turn; re-reading the same occluded screen
        // three times cannot recover, so the retry must put the clock back in front.
        mockkObject(AppRegistry)
        val clockApp = AppRegistry.App("时钟", clockPkg, emptyList())
        var launches = 0
        every { AppRegistry.find(any(), any()) } answers { clockApp }
        every { AppRegistry.launch(any(), any()) } answers {
            launches++
            ToolResult.ok("app", "已打开时钟", verified = true)
        }
        every { UiAutomator.readScreen(any()) } answers {
            if (launches == 0) UiAutomator.ScreenRead(selectedTabs = mapOf("闹钟" to true, "定时器" to true), texts = listOf("ClosePaw"), nodesVisited = 3, rootPackage = ownPkg, serviceAvailable = true)
            else UiAutomator.ScreenRead(selectedTabs = mapOf("闹钟" to true, "定时器" to true), texts = listOf("3 个定时器正在运行"), nodesVisited = 9, rootPackage = clockPkg, serviceAvailable = true)
        }
        val r = ClockControl.queryTimers(ctx)
        assertThat(launches).isEqualTo(1)
        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
    }

    @Test fun `recovery selects exact tab rather than a preceding hint card`() {
        mockkObject(AppRegistry)
        val app = AppRegistry.App("时钟", clockPkg, emptyList())
        every { AppRegistry.find(any(), any()) } returns app
        every { AppRegistry.launch(any(), any()) } returns ToolResult.ok("app", "opened", verified = true)
        val hint = mockk<android.view.accessibility.AccessibilityNodeInfo>(relaxed = true)
        val tab = mockk<android.view.accessibility.AccessibilityNodeInfo>(relaxed = true)
        val root = mockk<android.view.accessibility.AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns clockPkg
        every { tab.packageName } returns clockPkg
        every { hint.text } returns "对关联闹钟进行分组"
        every { tab.text } returns "闹钟"
        every { root.childCount } returns 2
        every { root.getChild(0) } returns hint
        every { root.getChild(1) } returns tab
        // Initial navigation has no root; only recovery sees the hint before the real tab.
        every { UiAutomator.root() } returnsMany listOf(null, root)
        every { UiAutomator.click(tab) } returns true
        every { UiAutomator.tabSelected("闹钟") } returns true
        every { UiAutomator.readScreen(any()) } returnsMany listOf(
            UiAutomator.ScreenRead(selectedTabs = mapOf("闹钟" to true, "定时器" to true), rootPackage = ownPkg, serviceAvailable = true),
            UiAutomator.ScreenRead(selectedTabs = mapOf("闹钟" to true, "定时器" to true), texts = listOf("8:00"), rootPackage = clockPkg,
                serviceAvailable = true, checkableCount = 1))
        val r = ClockControl.queryAlarms(ctx)
        assertThat(r.verified).isTrue()
        verify(exactly = 1) { UiAutomator.click(tab) }
        verify(exactly = 0) { UiAutomator.click(hint) }
        // Missing initial root never permits a containing-text fallback.
        verify(exactly = 0) { UiAutomator.clickText("闹钟") }
    }

    @Test fun `alarms are still read when the clock window is the active one`() {
        every { UiAutomator.readScreen(any()) } returns UiAutomator.ScreenRead(selectedTabs = mapOf("闹钟" to true, "定时器" to true),
            texts = listOf("8:00", "9:00"), nodesVisited = 40, rootPackage = clockPkg,
            serviceAvailable = true, checkableCount = 2)
        val r = ClockControl.queryAlarms(ctx)
        assertThat(r.verified).isTrue()
        assertThat(r.detail).contains("visible=[8:00, 9:00]")
    }
}
