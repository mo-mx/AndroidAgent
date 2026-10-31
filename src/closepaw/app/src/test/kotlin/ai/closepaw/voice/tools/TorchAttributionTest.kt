package ai.closepaw.voice.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Round 35 — the round-34 change handed the torch callback state to `TerminalState` as structured
 * evidence, but never checked *which* callback it was. `onTorchModeChanged` recorded every event,
 * including the initial event CameraService delivers when a callback is registered, and `outcome`
 * ignored the latch result. So "the torch was already on" could be reported as "I turned it on",
 * and even `onTorchModeUnavailable` arriving after the request did not stop the success claim.
 *
 * The contract these tests pin:
 *  - an event that arrives before the request has been issued is a *baseline*, never a result;
 *  - only a post-request event that reports the requested state counts as confirmation;
 *  - `onTorchModeUnavailable` after the request always wins over a success claim;
 *  - an event for a different camera id is ignored entirely.
 *
 * The tracking logic is split out of [TorchControl.set] because CameraManager + Looper cannot run
 * on the JVM. This is the attribution decision, not a device test: the real callback timing on
 * SM-F731U1 is still unverified.
 */
class TorchAttributionTest {

    @Test
    fun `a registration event that arrives before the request is a baseline, not a result`() {
        val t = TorchControl.RequestTracker(cameraId = "0", on = true)

        // CameraService delivers the current mode as soon as the callback is registered.
        val confirmed = t.onModeChanged("0", true)

        assertThat(confirmed).isFalse()
        assertThat(t.baseline).isTrue()
        assertThat(t.postState).isNull()
        assertThat(t.confirmed).isFalse()
    }

    @Test
    fun `a registration event is never counted as a result, however late it arrives`() {
        val t = TorchControl.RequestTracker(cameraId = "0", on = false)

        // The baseline window in `set()` is bounded; when it expires the request goes out anyway
        // and a still-in-flight registration event can land after it. Until the caller marks the
        // request, every event is a state report, not a result.
        assertThat(t.onModeChanged("0", false)).isFalse()
        assertThat(t.onModeChanged("0", true)).isFalse()

        assertThat(t.baseline).isFalse()
        assertThat(t.postState).isNull()
        assertThat(t.confirmed).isFalse()
    }

    @Test
    fun `only a post-request event releases the latch`() {
        val t = TorchControl.RequestTracker(cameraId = "0", on = true)
        t.onModeChanged("0", false)          // baseline: torch off
        t.markRequested()

        assertThat(t.onModeChanged("0", true)).isTrue()   // the state we asked for
        assertThat(t.confirmed).isTrue()
        assertThat(t.postState).isTrue()
    }

    @Test
    fun `unavailable after the request wins over an already matching baseline`() {
        val t = TorchControl.RequestTracker(cameraId = "0", on = true)
        t.onModeChanged("0", true)           // baseline already matches the request
        t.markRequested()
        t.onUnavailable("0")

        assertThat(t.baseline).isTrue()
        assertThat(t.postState).isNull()
        assertThat(t.unavailable).isTrue()
        assertThat(t.confirmed).isFalse()

        val r = TorchControl.outcome(
            on = true, cameraId = "0", observed = t.postState,
            unavailable = t.unavailable, confirmed = t.confirmed, baseline = t.baseline,
        )
        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.observed).isNull()
    }

    @Test
    fun `an event for another camera is ignored`() {
        val t = TorchControl.RequestTracker(cameraId = "0", on = true)
        t.markRequested()

        assertThat(t.onModeChanged("1", true)).isFalse()
        t.onUnavailable("1")

        assertThat(t.postState).isNull()
        assertThat(t.unavailable).isFalse()
        assertThat(t.confirmed).isFalse()
    }

    // ── verdict: outcome() must be gated on the confirmation, not on the observed value ──

    @Test
    fun `a matching state that was never confirmed after the request carries no evidence`() {
        val r = TorchControl.outcome(
            on = true, cameraId = "0", observed = null,
            unavailable = false, confirmed = false, baseline = true,
        )
        assertThat(r.status).isEqualTo(ToolStatus.TIMEOUT)
        assertThat(r.verified).isNull()
        assertThat(r.observed).isNull()
        assertThat(r.speak).doesNotContain("已打开")
    }

    @Test
    fun `an unconfirmed request followed by unavailable is not a success`() {
        val r = TorchControl.outcome(
            on = true, cameraId = "0", observed = true,
            unavailable = true, confirmed = false, baseline = true,
        )
        assertThat(r.status).isEqualTo(ToolStatus.DEVICE_UNAVAILABLE)
        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.observed).isNull()
    }

    @Test
    fun `a confirmed switch still reports the callback state as evidence`() {
        val r = TorchControl.outcome(
            on = true, cameraId = "0", observed = true,
            unavailable = false, confirmed = true, baseline = false,
        )
        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isTrue()
        assertThat(r.observed?.identity).isEqualTo("torch")
        assertThat(r.observed?.state).isEqualTo("on")
    }

    @Test
    fun `a confirmation that is then reported unavailable is not sold as success`() {
        val r = TorchControl.outcome(
            on = true, cameraId = "0", observed = true,
            unavailable = true, confirmed = true, baseline = false,
        )
        assertThat(r.status).isNotEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.observed).isNull()
    }
}
