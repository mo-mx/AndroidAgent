package ai.closepaw.voice.tools

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * Round 48 review (comment `6554387042`) — two `LocalCapabilities` findings, both about a *bounded
 * wait* being described as a fact about the platform:
 *
 *  [P2] `awaitState` measured its 1.2 s window with `System.currentTimeMillis()`. A backwards
 *       wall-clock correction extends the window (in principle indefinitely), a forward jump ends it
 *       early. The deadline is now taken from an injected monotonic source in **milliseconds**.
 *
 *  [P2] `enable()/disable()` throwing anything other than `SecurityException` was swallowed into
 *       `false`, and `false` is the "the API refused it" branch — which speaks "Android 不允许普通应用
 *       直接切换蓝牙". A dead Bluetooth service is not a platform limitation, so that branch may only
 *       be reached when the API actually returned `false`.
 *
 * The adapter is a mock: this proves `btSet`'s contract, not what a real phone does.
 */
class BluetoothSettleClockTest {

    private fun contextWith(
        adapter: BluetoothAdapter,
    ): Context {
        val manager = mockk<BluetoothManager>(relaxed = true)
        every { manager.adapter } returns adapter
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
        return ctx
    }

    private fun neverSettlingAdapter(reads: MutableList<Int> = ArrayList()): BluetoothAdapter {
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { adapter.isEnabled } answers { reads.add(1); false }
        every { adapter.enable() } returns true
        return adapter
    }

    // ── [P2] the window runs on the injected monotonic clock ──

    @Test
    fun `a forward clock jump ends the settling window immediately`() {
        val reads = ArrayList<Int>()
        val adapter = neverSettlingAdapter(reads)
        var calls = 0
        // First call = deadline setup, then each poll; the wall clock "corrected" itself +5 s.
        val r = LocalCapabilities.awaitState(
            adapter, want = true,
            now = { calls++; if (calls <= 1) 0L else 5_000L },
            pause = { },
        )
        assertThat(r).isEqualTo(0)
        // One read, then the deadline check ends it. No 1.2 s of sleeping against a moved clock.
        assertThat(reads.size).isAtMost(2)
    }

    @Test
    fun `a clock that never advances cannot spin forever`() {
        val reads = ArrayList<Int>()
        val adapter = neverSettlingAdapter(reads)
        val r = LocalCapabilities.awaitState(adapter, want = true, now = { 0L }, pause = { })
        assertThat(r).isEqualTo(0)
        // Hard read cap: a frozen (or hijacked) clock must not turn the window into an endless loop.
        assertThat(reads.size).isLessThan(20)
    }

    @Test
    fun `a settling adapter is still reported as settled`() {
        // Control group: the injected clock must not break the normal path.
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        var n = 0
        every { adapter.isEnabled } answers { n++ >= 1 }
        var t = 0L
        val r = LocalCapabilities.awaitState(adapter, want = true, now = { t }, pause = { t += 150L })
        assertThat(r).isEqualTo(2)
    }

    // ── [P2] a failure is not a platform restriction ──

    @Test
    fun `a toggle that throws is not reported as a platform restriction`() {
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { adapter.isEnabled } returns false
        every { adapter.enable() } throws IllegalStateException("bluetooth service died")

        val r = LocalCapabilities.btSet(contextWith(adapter), on = true)

        assertThat(r.status).isNotEqualTo(ToolStatus.SYSTEM_RESTRICTED)
        assertThat(r.speak).doesNotContain("不允许普通应用")
        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.detail).contains("IllegalStateException")
    }

    @Test
    fun `an api that returns false keeps the platform limit wording`() {
        // Control group: the refusal branch must still exist and still be reachable that way.
        val adapter = mockk<BluetoothAdapter>(relaxed = true)
        every { adapter.isEnabled } returns false
        every { adapter.enable() } returns false

        val r = LocalCapabilities.btSet(contextWith(adapter), on = true)

        assertThat(r.status).isEqualTo(ToolStatus.SYSTEM_RESTRICTED)
        assertThat(r.speak).contains("不允许")
    }
}
