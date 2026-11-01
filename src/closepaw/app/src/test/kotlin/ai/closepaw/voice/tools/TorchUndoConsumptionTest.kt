package ai.closepaw.voice.tools

import ai.closepaw.voice.semantics.Action
import ai.closepaw.voice.semantics.ActionMemory
import ai.closepaw.voice.semantics.Target
import android.content.Context
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import com.google.common.truth.Truth.assertThat

/**
 * Round 43: undoing the same memory twice.
 *
 * `ActionMemory.undo` issues the *inverse* of the last recorded mutation, but it never consumed
 * that entry, and the entry has no expiry. So the memory keeps asserting a world state that the
 * undo itself has already changed:
 *
 *  - "打开手电筒" → "取消刚才的操作" → torch off, memory still says `ENABLE/TORCH`;
 *  - a second "取消刚才的操作" therefore re-issues `set(false)` and speaks "已关闭手电筒" as if it
 *    had just done something. Counted wrong, and if the user turned the torch back on in between,
 *    that second undo silences *their* light while claiming to undo the assistant's earlier action.
 *  - for a `DISABLE` entry the same repeated inverse is `set(true)`: after undoing a torch-off,
 *    a repeated "撤销" re-issues the enable.
 *
 * The memory's own comment ("an attempted mutation invalidates older undo state") is the rule that
 * is half-implemented: a new mutation clears it, the undo that applies it does not.
 *
 * JVM only, Android/CameraManager boundary mocked: this proves the memory bookkeeping, not that a
 * physical torch changed state on a device.
 */
class TorchUndoConsumptionTest {
    private val ctx = mockk<Context>(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        mockkObject(ActionMemory, TorchControl)
        // No memory carried in from another test in the same JVM: any non-verified result clears it.
        ActionMemory.recordResult(Action.CREATE, Target.TIMER, emptyMap(),
            ToolResult.unavailable("timer", "reset", "reset"))
    }

    @After
    fun cleanup() = unmockkAll()

    private fun remember(action: Action = Action.ENABLE, speech: String = "已开启手电筒") =
        ActionMemory.recordResult(action, Target.TORCH, emptyMap(), ToolResult.ok("torch", speech, verified = true))

    @Test
    fun `undo that actually performs the inverse consumes the memory`() {
        every { TorchControl.set(ctx, false) } returns ToolResult.ok("torch", "已关闭手电筒", verified = true)
        remember()
        assertThat(ActionMemory.last).isNotNull()

        val r = ActionMemory.undo(ctx)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(ActionMemory.last).isNull()
    }

    @Test
    fun `second undo of a torch-on does not claim to disable it again`() {
        every { TorchControl.set(ctx, false) } returns ToolResult.ok("torch", "已关闭手电筒", verified = true)
        remember()

        ActionMemory.undo(ctx)
        val again = ActionMemory.undo(ctx)

        assertThat(again.speak).contains("不能安全撤销")
        assertThat(again.status).isNotEqualTo(ToolStatus.SUCCESS)
        // The inverse ran exactly once, for the one mutation that was actually remembered.
        verify(exactly = 1) { TorchControl.set(any(), false) }
    }

    @Test
    fun `second undo of a torch-off does not re-enable the torch`() {
        every { TorchControl.set(ctx, true) } returns ToolResult.ok("torch", "已开启手电筒", verified = true)
        remember(Action.DISABLE, "已关闭手电筒")

        val first = ActionMemory.undo(ctx)
        assertThat(first.speak).isEqualTo("已开启手电筒")

        val again = ActionMemory.undo(ctx)

        assertThat(again.speak).contains("不能安全撤销")
        // Re-issuing enable here would light the torch the user may have switched off by hand.
        verify(exactly = 1) { TorchControl.set(any(), true) }
        verify(exactly = 0) { TorchControl.set(any(), false) }
    }

    @Test
    fun `an inverse that did not run keeps the memory so the undo can be retried`() {
        // First attempt cannot reach the camera; the torch is still on, so the entry must survive.
        // Round 44: `dispatched = false` is the fact this fixture now has to carry — the real
        // `TorchControl` reports it when the camera refused before `setTorchMode` was called, and it
        // is what distinguishes "safe to retry" from "may already have moved the object".
        every { TorchControl.set(ctx, false) } returns
            ToolResult.unavailable("torch", "现在没法控制手电筒", "camera in use", dispatched = false)
        remember()
        val first = ActionMemory.undo(ctx)
        assertThat(first.status).isNotEqualTo(ToolStatus.SUCCESS)
        assertThat(ActionMemory.last).isNotNull()

        every { TorchControl.set(ctx, false) } returns ToolResult.ok("torch", "已关闭手电筒", verified = true)
        val retry = ActionMemory.undo(ctx)
        assertThat(retry.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(ActionMemory.last).isNull()
        verify(exactly = 2) { TorchControl.set(any(), false) }
    }

    @Test
    fun `a new mutation is recorded again after an undo`() {
        every { TorchControl.set(ctx, false) } returns ToolResult.ok("torch", "已关闭手电筒", verified = true)
        remember()
        ActionMemory.undo(ctx)

        remember()

        assertThat(ActionMemory.last).isNotNull()
        assertThat(ActionMemory.last?.action).isEqualTo(Action.ENABLE)
    }

    /**
     * Round 50 review B2 / P1.
     *
     * The inverse did not have to issue anything because the goal it aims at already holds: the
     * assistant turned the torch on, the user switched it off by hand, and "撤销刚才的操作" found
     * the camera already off. `TorchControl.set` answers exactly that with
     * `alreadySatisfied(...)` — `SUCCESS`, `verified = true`, `dispatched = false`.
     *
     * Round 44's rule ("keep the entry only when `dispatched == false`") was written for a camera
     * that *refused before issuing*, where an honest retry is still possible. This is a different
     * fact with the same `dispatched = false`: the inverse's goal is already achieved, so the
     * memory has nothing left to undo. Keeping it let a second "撤销" (after the user turned the
     * torch back on) issue `set(false)` and switch off the user's own light, attributing it to the
     * assistant's older action.
     */
    private fun alreadySatisfiedOff() = ToolResult.ok(
        "torch",
        "手电筒本来就是关着的，没有再切换",
        verified = true,
        detail = "camera=0 baseline=false already-at-target pre-request (no change attributed)",
        dispatched = false,
    )

    @Test
    fun `an inverse whose goal already holds consumes the memory`() {
        every { TorchControl.set(ctx, false) } returns alreadySatisfiedOff()
        remember()

        val first = ActionMemory.undo(ctx)
        assertThat(first.status).isEqualTo(ToolStatus.SUCCESS)
        // Nothing was issued, but the memory is spent: its inverse goal is already true.
        assertThat(ActionMemory.last).isNull()

        // The user turns the torch back on and says "撤销" again inside the window.
        val again = ActionMemory.undo(ctx)
        assertThat(again.status).isNotEqualTo(ToolStatus.SUCCESS)
        assertThat(again.speak).contains("不能安全撤销")
        verify(exactly = 1) { TorchControl.set(any(), false) }
    }

    @Test
    fun `an already-satisfied enable is consumed symmetrically`() {
        // Remembered DISABLE ("已关闭手电筒"); the user turned it back on by hand, so the inverse
        // (enable) finds the torch already on.
        every { TorchControl.set(ctx, true) } returns ToolResult.ok(
            "torch",
            "手电筒本来就是开着的，没有再切换",
            verified = true,
            dispatched = false,
        )
        remember(Action.DISABLE, "已关闭手电筒")

        ActionMemory.undo(ctx)
        assertThat(ActionMemory.last).isNull()

        ActionMemory.undo(ctx)
        verify(exactly = 1) { TorchControl.set(any(), true) }
    }
}
