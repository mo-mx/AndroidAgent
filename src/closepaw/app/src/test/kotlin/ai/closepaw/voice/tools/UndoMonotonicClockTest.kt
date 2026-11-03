package ai.closepaw.voice.tools

import ai.closepaw.voice.semantics.Action
import ai.closepaw.voice.semantics.ActionMemory
import ai.closepaw.voice.semantics.Target
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Round 46 — review 6553705515 P2: the 120 s undo window was measured with
 * `System.currentTimeMillis()` on both sides. Wall time can be moved (user, NTP, timezone tooling),
 * and the check was one-sided (`now - at > MAX_AGE`): a backwards correction makes the age negative,
 * which passes, so a memory older than the window became replayable again — the inverse then mutates
 * an object whose state nobody knows any more.
 *
 * The window is now measured on a monotonic source (`System.nanoTime`, the JVM/Android monotonic
 * clock: unaffected by 校时 and available without Robolectric) and a negative age is refused too,
 * because it means the two readings did not come from the same timeline.
 */
class UndoMonotonicClockTest {
    private val ctx = mockk<Context>(relaxed = true)

    @Before
    fun setup() {
        mockkObject(TorchControl)
        ActionMemory.clearForTest()
    }

    @After
    fun cleanup() = unmockkAll()

    private fun recordTorchEnable() {
        ActionMemory.recordResult(Action.ENABLE, Target.TORCH, emptyMap(),
            ToolResult.ok("torch", "手电筒已打开", verified = true))
        assertThat(ActionMemory.last).isNotNull()
    }

    @Test
    fun `a freshly recorded memory is inside the window`() {
        recordTorchEnable()
        every { TorchControl.set(any(), any()) } returns ToolResult.ok("torch", "手电筒已关闭", verified = true)

        val at = ActionMemory.last!!.at
        val r = ActionMemory.undo(ctx, at + 1_000L)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
    }

    @Test
    fun `the window boundary is exclusive and measured from the recorded stamp`() {
        recordTorchEnable()
        every { TorchControl.set(any(), any()) } returns ToolResult.ok("torch", "手电筒已关闭", verified = true)

        val at = ActionMemory.last!!.at
        val expired = ActionMemory.undo(ctx, at + ActionMemory.MAX_AGE_MS + 1)

        assertThat(expired.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(expired.speak).contains("不能安全撤销")
        assertThat(ActionMemory.last).isNull()
    }

    @Test
    fun `a stamp in the future of the reader is refused instead of executed`() {
        recordTorchEnable()
        every { TorchControl.set(any(), any()) } returns ToolResult.ok("torch", "手电筒已关闭", verified = true)

        val at = ActionMemory.last!!.at
        // What a backwards 校时 by ten minutes used to produce: age = -600_000, i.e. "not stale".
        val r = ActionMemory.undo(ctx, at - 600_000L)

        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.speak).contains("不能安全撤销")
        io.mockk.verify(exactly = 0) { TorchControl.set(any(), any()) }
        assertThat(ActionMemory.last).isNull()
    }

    @Test
    fun `the recorded stamp does not come from the wall clock`() {
        recordTorchEnable()
        val at = ActionMemory.last!!.at
        // A wall-clock stamp is ~1.7e12 ms; the monotonic source is process/boot relative. This
        // asserts the *source* changed, which no behavioural test above can distinguish on its own.
        val wall = System.currentTimeMillis()
        assertThat(kotlin.math.abs(at - wall)).isGreaterThan(1_000L)
    }
}
