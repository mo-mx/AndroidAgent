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
 * Round 44 review P2: the undo memory has no age bound, so "撤销刚才的操作" resolved against an entry
 * that may be hours old — and a torch can be switched by hand (or by another app) in between, which
 * makes the inverse of a remembered old state a *new* mutation rather than an undo.
 *
 * [ActionMemory.Entry.at] was recorded from round 43 onwards and never read. It now bounds the
 * window: outside it the entry is refused and dropped instead of replayed. This is a refusal, not a
 * claim of correctness inside the window — an external change two seconds after the mutation is
 * still invisible to us, and is reported as such.
 *
 * JVM only: this proves the bookkeeping, not the device.
 */
class UndoMemoryExpiryTest {
    private val ctx = mockk<Context>(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        mockkObject(ActionMemory, TorchControl)
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

    @Test
    fun `an expiry-bound memory is refused without touching the torch`() {
        remember()
        val entry = ActionMemory.last!!
        every { TorchControl.set(any(), any()) } returns
            ToolResult.ok("torch", "已关闭手电筒", verified = true)

        val r = ActionMemory.undo(ctx, now = entry.at + ActionMemory.MAX_AGE_MS + 1)

        assertThat(r.status).isNotEqualTo(ToolStatus.SUCCESS)
        assertThat(r.speak).contains("过去")
        assertThat(r.detail).contains("stale")
        // Nothing was issued: a remembered old state is not evidence about the torch right now.
        verify(exactly = 0) { TorchControl.set(any(), any()) }
        // The entry is dropped, so a second request cannot replay it either.
        assertThat(ActionMemory.last).isNull()
    }

    @Test
    fun `an undo inside the window still runs`() {
        remember()
        val entry = ActionMemory.last!!
        every { TorchControl.set(ctx, false) } returns
            ToolResult.ok("torch", "已关闭手电筒", verified = true)

        val r = ActionMemory.undo(ctx, now = entry.at + 1_000)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        verify(exactly = 1) { TorchControl.set(any(), false) }
        assertThat(ActionMemory.last).isNull()
    }

    @Test
    fun `the expiry boundary itself is still inside the window`() {
        remember()
        val entry = ActionMemory.last!!
        every { TorchControl.set(ctx, false) } returns
            ToolResult.ok("torch", "已关闭手电筒", verified = true)

        val r = ActionMemory.undo(ctx, now = entry.at + ActionMemory.MAX_AGE_MS)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
    }
}
