package ai.closepaw.voice.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Round 46 — review 6553705515 P1/P2: the baseline window in `TorchControl.set` is bounded at
 * 1200 ms. When it expires the code marks the request and *then* calls `setTorchMode`. A
 * registration event that CameraService had already queued can therefore land between those two
 * calls and be graded as a post-request result: if it happens to report the state we asked for,
 * `confirmLatch` releases, `verified=true` is returned, and `ActionMemory` records an undoable
 * ENABLE/TORCH for a torch this command never touched. A later "撤销" then switches off a light the
 * user had on before.
 *
 * The contract these tests pin: the first `onTorchModeChanged` a fresh callback receives is the
 * registration report of the *current* mode, and it stays a baseline however late it arrives —
 * after `markRequested()` and after `setTorchMode()` too. Only an event that follows that first
 * report can be attributed to the request. With no baseline at all there is no attribution, so the
 * outcome must not claim `verified` and must not create a reversible memory.
 *
 * This is the attribution decision on the JVM. Real CameraService callback ordering on SM-F731U1 is
 * still unverified (see the device matrix in docs/PROJECT_STATUS.md).
 */
class TorchLateRegistrationAttributionTest {

    @Test
    fun `a registration event arriving after a timed-out baseline is a baseline, not our result`() {
        val t = TorchControl.RequestTracker(cameraId = "0", on = true)
        // Baseline window expired with no event; `set()` marks the request and issues setTorchMode.
        t.markRequested()
        assertThat(t.baseline).isNull()

        // The queued registration event lands now, reporting the pre-existing state.
        val confirmed = t.onModeChanged("0", true)

        assertThat(confirmed).isFalse()
        assertThat(t.baseline).isTrue()
        assertThat(t.postState).isNull()
        assertThat(t.confirmed).isFalse()
    }

    @Test
    fun `a late registration event never reaches the success verdict`() {
        val t = TorchControl.RequestTracker(cameraId = "0", on = true)
        t.markRequested()
        t.onModeChanged("0", true)   // late registration report: torch was already on

        val r = TorchControl.outcome(
            on = true, cameraId = "0", observed = t.postState,
            unavailable = t.unavailable, confirmed = t.confirmed, baseline = t.baseline,
            // Round 49: the production caller passes the tracker's own flag; omitting it made this
            // fixture declare a *late* report as pre-request, which is the opposite of what the test
            // models. The assertions below are unchanged.
            baselinePreRequest = t.baselinePreRequest,
        )

        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.observed).isNull()
        assertThat(r.status).isEqualTo(ToolStatus.TIMEOUT)
        assertThat(r.speak).doesNotContain("手电筒已打开")
    }

    @Test
    fun `a real change after the late baseline is still attributed`() {
        val t = TorchControl.RequestTracker(cameraId = "0", on = true)
        t.markRequested()
        t.onModeChanged("0", false)          // late registration report: torch was off
        assertThat(t.baseline).isFalse()
        assertThat(t.postState).isNull()

        assertThat(t.onModeChanged("0", true)).isTrue()   // the change we asked for
        assertThat(t.postState).isTrue()

        val r = TorchControl.outcome(
            on = true, cameraId = "0", observed = t.postState,
            unavailable = t.unavailable, confirmed = t.confirmed, baseline = t.baseline,
        )
        assertThat(r.verified).isTrue()
        assertThat(r.observed?.state).isEqualTo("on")
    }

    @Test
    fun `no verdict claims a change when the camera never reported a baseline`() {
        // Deterministic guard on the decision itself: even with a "confirmed" flag set, a missing
        // baseline means the event cannot be told apart from the registration report.
        val r = TorchControl.outcome(
            on = true, cameraId = "0", observed = true,
            unavailable = false, confirmed = true, baseline = null,
        )
        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.observed).isNull()
        assertThat(r.status).isEqualTo(ToolStatus.TIMEOUT)
    }

    @Test
    fun `a different camera's late event still changes nothing`() {
        val t = TorchControl.RequestTracker(cameraId = "0", on = true)
        t.markRequested()

        assertThat(t.onModeChanged("1", true)).isFalse()
        assertThat(t.baseline).isNull()
        assertThat(t.postState).isNull()
        assertThat(t.confirmed).isFalse()
    }
}
