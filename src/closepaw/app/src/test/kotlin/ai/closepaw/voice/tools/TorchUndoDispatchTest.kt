package ai.closepaw.voice.tools

import ai.closepaw.voice.semantics.Action
import ai.closepaw.voice.semantics.ActionMemory
import ai.closepaw.voice.semantics.Target
import android.content.Context
import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Round 44 review P1: "the inverse was issued" and "the inverse was confirmed" are different facts,
 * and round 43 consumed the memory on the wrong one.
 *
 * [TorchControl.set] issues `cm.setTorchMode(...)` and *then* waits up to 4 s for the callback. A
 * callback that never arrives yields `TIMEOUT` — the torch may well have switched, we simply cannot
 * prove it. Round 43 kept the undo memory on anything but `SUCCESS`, so this path left the entry in
 * place: the first undo really did light the torch (unconfirmed), the user then switched it off by
 * hand, and a second "撤销" re-issued `set(true)` — a *new* mutation of the user's state, reported
 * as undoing an old one.
 *
 * The fix separates dispatch from confirmation: `ToolResult.dispatched` records whether the mutation
 * was actually handed to the OS, and the memory is consumed unless dispatch was provably refused.
 * The control group keeps the retry: an inverse the device refused *before* issuing anything still
 * leaves the memory, so an honest retry is possible.
 *
 * JVM only, CameraManager boundary mocked: this proves the memory bookkeeping, not the physics of a
 * torch on a device.
 */
class TorchUndoDispatchTest {
    private val ctx = mockk<Context>(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        mockkObject(ActionMemory, TorchControl)
        // No memory carried in from another test in the same JVM: any non-verified result clears it.
        ActionMemory.recordResult(
            Action.CREATE, Target.TIMER, emptyMap(),
            ToolResult.unavailable("timer", "reset", "reset")
        )
    }

    @After
    fun cleanup() = unmockkAll()

    private fun remember(action: Action = Action.ENABLE, speech: String = "已开启手电筒") =
        ActionMemory.recordResult(
            action, Target.TORCH, emptyMap(),
            ToolResult.ok("torch", speech, verified = true)
        )

    /** What [TorchControl.set] returns when the request went out and no callback confirmed it. */
    private fun dispatchedButUnconfirmed() = ToolResult(
        ToolStatus.TIMEOUT, "torch", "手电筒请求发出去了，但没等到确认",
        "no post-request TorchCallback matching requested=false within 4000ms", null,
        dispatched = true,
    )

    @Test
    fun `a dispatched inverse that timed out still consumes the memory`() {
        every { TorchControl.set(ctx, false) } returns dispatchedButUnconfirmed()
        remember()

        val r = ActionMemory.undo(ctx)

        assertThat(r.status).isEqualTo(ToolStatus.TIMEOUT)
        // The torch may already be off; keeping "it was on" would let a second undo re-issue it.
        assertThat(ActionMemory.last).isNull()

        val again = ActionMemory.undo(ctx)
        assertThat(again.speak).contains("不能安全撤销")
        verify(exactly = 1) { TorchControl.set(any(), false) }
    }

    @Test
    fun `a timed-out undo does not re-enable a torch the user switched off by hand`() {
        every { TorchControl.set(ctx, true) } returns dispatchedButUnconfirmed()
        remember(Action.DISABLE, "已关闭手电筒")

        ActionMemory.undo(ctx)
        // The user's own "off" happens here — nothing the assistant can observe.
        val again = ActionMemory.undo(ctx)

        assertThat(again.speak).contains("不能安全撤销")
        verify(exactly = 1) { TorchControl.set(any(), true) }
    }

    @Test
    fun `an inverse the device refused before dispatch keeps the memory for a retry`() {
        every { TorchControl.set(ctx, false) } returns ToolResult(
            ToolStatus.DEVICE_UNAVAILABLE, "torch", "现在没法控制手电筒", "camera in use", false,
            dispatched = false,
        )
        remember()

        val first = ActionMemory.undo(ctx)

        assertThat(first.status).isNotEqualTo(ToolStatus.SUCCESS)
        assertThat(ActionMemory.last).isNotNull()

        every { TorchControl.set(ctx, false) } returns
            ToolResult.ok("torch", "已关闭手电筒", verified = true)
        val retry = ActionMemory.undo(ctx)

        assertThat(retry.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(ActionMemory.last).isNull()
        verify(exactly = 2) { TorchControl.set(any(), false) }
    }

    @Test
    fun `a timeout verdict states that the request was dispatched`() {
        val r = TorchControl.outcome(
            on = false, cameraId = "0", observed = null, unavailable = false,
            confirmed = false, baseline = true,
        )

        assertThat(r.status).isEqualTo(ToolStatus.TIMEOUT)
        // `outcome` is only reached after setTorchMode returned, so the request did go out.
        assertThat(r.dispatched).isEqualTo(true)
    }

    @Test
    fun `a refused camera is not reported as a dispatched request`() {
        // No camera reports a flash unit: nothing was ever handed to the OS.
        every { ctx.getSystemService(any()) } returns null

        val r = TorchControl.set(ctx, true)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.dispatched).isEqualTo(false)
    }

    @Test
    fun `an unavailable camera after the request still counts as dispatched`() {
        val r = TorchControl.outcome(
            on = true, cameraId = "0", observed = null, unavailable = true,
            confirmed = false, baseline = null,
        )

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.dispatched).isEqualTo(true)
    }
}
