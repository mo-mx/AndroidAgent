package ai.closepaw.voice.tools

import ai.closepaw.voice.semantics.Action
import ai.closepaw.voice.semantics.ActionMemory
import ai.closepaw.voice.semantics.Target
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Round 49, review `6555010682` B1/B2 (offline: source + JVM, no device).
 *
 * The torch path records an undoable memory when a request is *confirmed*, but until now it never
 * asked whether the camera was **already** in the requested state. A registration event that
 * reports `on` for a torch the user had switched on by hand, followed by a same-state callback for
 * "打开手电筒", satisfied `confirmed && observed == on && baseline != null && baselinePreRequest`
 * → `SUCCESS + verified=true` → `ActionMemory.recordResult(ENABLE, TORCH, …)`. "撤销刚才的操作" then
 * ran `TorchControl.set(false)` and switched off a lamp this turn never turned on. The reverse
 * direction is the same bug mirrored.
 *
 * The rule pinned here: a request whose trustworthy pre-state already equals the target is reported
 * as "already in that state", carries `dispatched = false`, and never becomes an undo memory.
 */
class TorchAlreadyAtTargetTest {
    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        ActionMemory.clearForTest()
    }

    @After fun cleanup() {
        ActionMemory.clearForTest()
        unmockkAll()
    }

    private fun record(r: ToolResult) =
        ActionMemory.recordResult(Action.ENABLE, Target.TORCH, mapOf("on" to "true"), r)

    /** The lamp was already on; a same-state callback confirms it. That is not a change we made. */
    @Test fun `an already-on torch does not become an undoable enable`() {
        val r = TorchControl.outcome(
            on = true, cameraId = "0", observed = true, unavailable = false,
            confirmed = true, baseline = true, baselinePreRequest = true,
        )

        assertThat(r.speak).contains("本来就是开着的")
        assertThat(r.detail).contains("already-at-target")
        assertThat(r.dispatched).isEqualTo(false)
        record(r)
        assertThat(ActionMemory.last).isNull()
    }

    /** Mirrored: the lamp was already off, and the user asked to switch it off. */
    @Test fun `an already-off torch does not become an undoable disable`() {
        val r = TorchControl.outcome(
            on = false, cameraId = "0", observed = false, unavailable = false,
            confirmed = true, baseline = false, baselinePreRequest = true,
        )

        assertThat(r.detail).contains("already-at-target")
        assertThat(r.dispatched).isEqualTo(false)
        ActionMemory.recordResult(Action.DISABLE, Target.TORCH, mapOf("on" to "false"), r)
        assertThat(ActionMemory.last).isNull()
    }

    /** Control: the torch was off and this request really turned it on → undo memory stays. */
    @Test fun `a real transition is still undoable`() {
        val r = TorchControl.outcome(
            on = true, cameraId = "0", observed = true, unavailable = false,
            confirmed = true, baseline = false, baselinePreRequest = true,
        )

        assertThat(r.verified).isEqualTo(true)
        assertThat(r.dispatched).isEqualTo(true)
        record(r)
        assertThat(ActionMemory.last).isNotNull()
    }

    /** A baseline that arrived after the request proves nothing, including "it was already on". */
    @Test fun `a late baseline cannot prove the state was already satisfied`() {
        val r = TorchControl.outcome(
            on = true, cameraId = "0", observed = true, unavailable = false,
            confirmed = true, baseline = true, baselinePreRequest = false,
        )

        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.speak).doesNotContain("已经是")
        record(r)
        assertThat(ActionMemory.last).isNull()
    }

    /**
     * Belt and braces: whatever produced it, a result that was never handed to the system
     * (`dispatched = false`) cannot be the state this turn changed.
     */
    @Test fun `a not-dispatched result never becomes undo memory`() {
        val r = ToolResult.ok(
            "torch", "手电筒本来就是开着的", verified = true, detail = "already-at-target",
            dispatched = false,
        )

        record(r)

        assertThat(ActionMemory.last).isNull()
    }

    /** Control for the guard above: a dispatched, verified result is still remembered. */
    @Test fun `a dispatched verified result is still remembered`() {
        val r = ToolResult.ok(
            "torch", "手电筒已打开", verified = true, detail = "attributed=post-request",
            dispatched = true,
        )

        record(r)

        assertThat(ActionMemory.last).isNotNull()
        assertThat(ActionMemory.last?.action).isEqualTo(Action.ENABLE)
    }
}
