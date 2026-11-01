package ai.closepaw.voice.tools

import ai.closepaw.voice.semantics.SemanticParser
import ai.closepaw.voice.semantics.SemanticTask
import ai.closepaw.voice.semantics.Target
import ai.closepaw.voice.semantics.TerminalState
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * Round 45: bluetooth is the last switch surface that reads the adapter and then throws the read
 * away — and the first one that condemns the platform from a single immediate read.
 *
 * Two separate defects, both measured on the old code before any production change:
 *
 *  1. **Evidence is dropped.** `btSet` reads `isEnabled` back and sets `verified = true`, but leaves
 *     `observed` null. Since round 24 `TerminalState` needs structured evidence for any task that
 *     declares `expectedState` — and the parser declares `on:BLUETOOTH` for "打开蓝牙" — so a
 *     bluetooth switch that really flipped was spoken as "请求已经发出，但没能确认结果".
 *     Same class as round 34's Wi-Fi / torch hole; bluetooth was simply not part of that round.
 *
 *  2. **One immediate read is treated as the platform's final answer.** `BluetoothAdapter.enable()`
 *     / `disable()` are asynchronous: a `true` return means "the process has begun". Reading
 *     `isEnabled` in the next instruction can still see the old value, and the old code then said
 *     "Android 不允许普通应用直接切换蓝牙…点一下开关就行" and opened settings. That sentence is a
 *     claim about a platform limitation, not about what was observed — and it is wrong whenever the
 *     adapter was merely still turning on. A `SecurityException` (missing `BLUETOOTH_CONNECT`) hit
 *     the same branch, so a permission problem was also reported as a platform limit.
 *
 * Rules pinned here:
 *  - a state actually read back is handed to the gate as `ObservedState("bluetooth", on|off)`;
 *  - an accepted request is given a bounded, observable settling window before "no change" is
 *    spoken, and "did not settle in time" is not the same sentence as "not permitted";
 *  - a refusal (API returns false) keeps the platform-limit wording; a `SecurityException` is
 *    `PERMISSION_DENIED`;
 *  - nothing invents evidence on the refusal / permission / unsettled paths.
 *
 * The adapter is a mock, so this proves the contract inside `btSet`, not what a real phone does.
 */
class BluetoothReadbackEvidenceTest {

    private fun btContext(
        states: List<Boolean>,
        toggleResult: Boolean = true,
        toggleThrows: Throwable? = null,
    ): Context {
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        var reads = 0
        // Past the scripted values the last one keeps answering, so a fixture mistake shows up as
        // a failed assertion rather than a different code path.
        every { adapter.isEnabled } answers { states[minOf(reads++, states.lastIndex)] }
        every { adapter.enable() } answers {
            if (toggleThrows != null) throw toggleThrows else toggleResult
        }
        every { adapter.disable() } answers {
            if (toggleThrows != null) throw toggleThrows else toggleResult
        }
        val manager = mockk<BluetoothManager>(relaxed = true)
        every { manager.adapter } returns adapter
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
        return ctx
    }

    private fun btTask(text: String): SemanticTask {
        val task = SemanticParser.parse(text).single()
        assertThat(task.target).isEqualTo(Target.BLUETOOTH)
        assertThat(task.targetIdentity).isEqualTo("bluetooth")
        return task
    }

    @Test
    fun `turn on already enabled reports the state it read back`() {
        val r = LocalCapabilities.btSet(btContext(states = listOf(true)), true)
        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isTrue()
        assertThat(r.observed?.identity).isEqualTo("bluetooth")
        assertThat(r.observed?.state).isEqualTo("on")
    }

    @Test
    fun `turn on accepted and settled reports the state it read back`() {
        // before=false, first settle poll still false (turning on), second poll true.
        val r = LocalCapabilities.btSet(btContext(states = listOf(false, false, true)), true)
        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isTrue()
        assertThat(r.observed?.identity).isEqualTo("bluetooth")
        assertThat(r.observed?.state).isEqualTo("on")
    }

    @Test
    fun `turn off accepted and settled reports the state it read back`() {
        val r = LocalCapabilities.btSet(btContext(states = listOf(true, true, false)), false)
        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isTrue()
        assertThat(r.observed?.state).isEqualTo("off")
    }

    @Test
    fun `a still settling adapter is not called a platform limit`() {
        // The old code read isEnabled once, saw false and spoke "Android 不允许普通应用直接切换蓝牙".
        val r = LocalCapabilities.btSet(btContext(states = listOf(false, false, true)), true)
        assertThat(r.speak).doesNotContain("不允许")
        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
    }

    @Test
    fun `api refusal keeps the platform limit wording and carries no evidence`() {
        val r = LocalCapabilities.btSet(btContext(states = listOf(false), toggleResult = false), true)
        assertThat(r.status).isEqualTo(ToolStatus.SYSTEM_RESTRICTED)
        assertThat(r.verified).isFalse()
        assertThat(r.observed).isNull()
        assertThat(r.speak).contains("不允许")
    }

    @Test
    fun `missing bluetooth permission is reported as a permission`() {
        val r = LocalCapabilities.btSet(
            btContext(states = listOf(false), toggleThrows = SecurityException("BLUETOOTH_CONNECT")),
            true,
        )
        assertThat(r.status).isEqualTo(ToolStatus.PERMISSION_DENIED)
        assertThat(r.verified).isFalse()
        assertThat(r.observed).isNull()
        assertThat(r.speak).contains("权限")
        assertThat(r.speak).doesNotContain("不允许普通应用")
    }

    @Test
    fun `accepted but never settled is unproven, not a platform limit`() {
        val r = LocalCapabilities.btSet(btContext(states = listOf(false)), true)
        assertThat(r.verified).isNull()
        assertThat(r.observed).isNull()
        assertThat(r.dispatched).isTrue()
        assertThat(r.speak).doesNotContain("不允许普通应用")
        assertThat(r.detail).contains("dispatched")
    }

    @Test
    fun `bluetooth evidence is what the terminal gate needs to confirm`() {
        val task = btTask("打开蓝牙")
        assertThat(task.expectedState).isEqualTo("on:BLUETOOTH")
        val r = LocalCapabilities.btSet(btContext(states = listOf(false, false, true)), true)

        assertThat(TerminalState.verdict(task, r)).isEqualTo(TerminalState.Verdict.CONFIRMED)
        val enforced = TerminalState.enforce(task, r)
        assertThat(enforced.verified).isTrue()
        assertThat(enforced.speak).isEqualTo(r.speak)
    }

    @Test
    fun `bluetooth without evidence stays unproven`() {
        // Control group: the same success result with the evidence stripped must still be hedged.
        val r = ToolResult.ok("bt", "蓝牙已打开", verified = true, detail = "attempted=true")
        assertThat(TerminalState.verdict(btTask("打开蓝牙"), r))
            .isEqualTo(TerminalState.Verdict.UNPROVEN)
    }
}
