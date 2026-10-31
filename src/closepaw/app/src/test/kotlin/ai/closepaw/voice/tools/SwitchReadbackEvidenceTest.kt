package ai.closepaw.voice.tools

import ai.closepaw.voice.semantics.SemanticParser
import ai.closepaw.voice.semantics.SemanticTask
import ai.closepaw.voice.semantics.Target
import ai.closepaw.voice.semantics.TerminalState
import android.content.Context
import android.net.wifi.WifiManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * Round 34: the two switch surfaces already read the device back, but never told the gate so.
 *
 * `WifiControl` reads `isWifiEnabled` before/after the attempt and `TorchControl` waits for
 * `onTorchModeChanged`. Both set `verified = true` on that evidence — and both left `observed`
 * null. Since round 24 the terminal gate requires structured evidence for any task that declares
 * an `expectedState` (`on:WIFI`, `off:TORCH`, …), so a Wi-Fi switch that really flipped the radio
 * and a torch that really came on were spoken as "请求已经发出，但没能确认结果". Over-conservative
 * is not honest either: the evidence existed and the contract threw it away.
 *
 * The rules pinned here:
 *   1. A state that was actually read back is reported as evidence (identity + state token).
 *   2. A refusal or a timeout has no evidence and must not invent one.
 *   3. End to end with a real parsed task, that evidence is what turns the claim into CONFIRMED
 *      instead of UNPROVEN — and the spoken sentence is left alone.
 *
 * Wi-Fi runs through mocks of the framework calls it makes. The torch mapping is the pure
 * `TorchControl.outcome`, because the CameraManager callback plumbing needs a real Looper and is
 * not JVM-testable here (it stays device-only evidence, as before).
 */
class SwitchReadbackEvidenceTest {

    // ── Wi-Fi ──

    private fun wifiContext(states: List<Boolean>, setAccepted: Boolean = false): Context {
        val wm = mockk<WifiManager>(relaxed = true)
        // The capability reads the radio more than once per attempt; past the scripted values the
        // last one keeps answering, so a fixture mistake cannot masquerade as a behaviour change.
        var reads = 0
        every { wm.isWifiEnabled } answers { states[minOf(reads++, states.lastIndex)] }
        every { wm.setWifiEnabled(any()) } returns setAccepted
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        every { ctx.getSystemService(Context.WIFI_SERVICE) } returns wm
        every { ctx.startActivity(any()) } returns Unit
        return ctx
    }

    private fun wifiTask(text: String): SemanticTask {
        val task = SemanticParser.parse(text).single()
        // The producer's identity string and the parser's derivation must agree, or the gate can
        // never confirm anything. Pinned here instead of assumed.
        assertThat(task.target).isEqualTo(Target.WIFI)
        assertThat(task.targetIdentity).isEqualTo("wifi")
        return task
    }

    @Test fun `wifi already on reports the state it read back`() {
        val r = WifiControl.enable(wifiContext(states = listOf(true)))
        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isTrue()
        assertThat(r.observed?.identity).isEqualTo("wifi")
        assertThat(r.observed?.state).isEqualTo("on")
    }

    @Test fun `wifi switched on reports the state it read back`() {
        val r = WifiControl.enable(wifiContext(states = listOf(false, true), setAccepted = true))
        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.observed?.state).isEqualTo("on")
    }

    @Test fun `wifi already off reports the state it read back`() {
        val r = WifiControl.disable(wifiContext(states = listOf(false)))
        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.observed?.identity).isEqualTo("wifi")
        assertThat(r.observed?.state).isEqualTo("off")
    }

    // The SYSTEM_RESTRICTED branch is deliberately not covered here: it builds `Intent(...)` for
    // the Wi-Fi panel, and the mockable android.jar returns null from that constructor, so
    // `openWifiPanel` NPEs before any assertion could run. That path stays device-only evidence
    // (it was already reported as such); nothing in this round changed it.

    @Test fun `wifi evidence is what the terminal gate needs to confirm`() {
        val task = wifiTask("打开wifi")
        assertThat(task.expectedState).isEqualTo("on:WIFI")
        val r = WifiControl.enable(wifiContext(states = listOf(false, true), setAccepted = true))

        assertThat(TerminalState.verdict(task, r)).isEqualTo(TerminalState.Verdict.CONFIRMED)
        val enforced = TerminalState.enforce(task, r)
        assertThat(enforced.verified).isTrue()
        assertThat(enforced.speak).isEqualTo(r.speak)
    }

    @Test fun `wifi without evidence stays unproven`() {
        // Control group: the same success result with the evidence stripped must still be hedged.
        val r = ToolResult.ok("wifi", "Wi-Fi 已打开", verified = true, detail = "setWifiEnabled ok")
        assertThat(TerminalState.verdict(wifiTask("打开wifi"), r)).isEqualTo(TerminalState.Verdict.UNPROVEN)
    }

    // ── torch ──

    private fun torchTask(text: String): SemanticTask {
        val task = SemanticParser.parse(text).single()
        assertThat(task.target).isEqualTo(Target.TORCH)
        assertThat(task.targetIdentity).isEqualTo("torch")
        return task
    }

    @Test fun `torch on reports the callback state as evidence`() {
        val r = TorchControl.outcome(on = true, cameraId = "0", observed = true, unavailable = false)
        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isTrue()
        assertThat(r.observed?.identity).isEqualTo("torch")
        assertThat(r.observed?.state).isEqualTo("on")
        assertThat(TerminalState.verdict(torchTask("打开手电筒"), r)).isEqualTo(TerminalState.Verdict.CONFIRMED)
    }

    @Test fun `torch off reports the callback state as evidence`() {
        val r = TorchControl.outcome(on = false, cameraId = "0", observed = false, unavailable = false)
        assertThat(r.observed?.state).isEqualTo("off")
        assertThat(TerminalState.verdict(torchTask("关闭手电筒"), r)).isEqualTo(TerminalState.Verdict.CONFIRMED)
    }

    @Test fun `torch without a callback carries no evidence`() {
        val r = TorchControl.outcome(on = true, cameraId = "0", observed = null, unavailable = false)
        assertThat(r.status).isEqualTo(ToolStatus.TIMEOUT)
        assertThat(r.verified).isNull()
        assertThat(r.observed).isNull()
    }

    @Test fun `torch busy camera carries no evidence`() {
        val r = TorchControl.outcome(on = true, cameraId = "0", observed = null, unavailable = true)
        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.observed).isNull()
    }

    @Test fun `torch reported the opposite state is an error not a success`() {
        val r = TorchControl.outcome(on = true, cameraId = "0", observed = false, unavailable = false)
        assertThat(r.status).isEqualTo(ToolStatus.EXECUTION_ERROR)
        assertThat(r.verified).isFalse()
        assertThat(r.observed).isNull()
    }
}
