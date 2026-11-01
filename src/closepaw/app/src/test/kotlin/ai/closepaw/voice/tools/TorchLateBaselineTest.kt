package ai.closepaw.voice.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Round 48 review (comment `6554387042`, HEAD `278fdce`) [P1]: `RequestTracker.onModeChanged` forced
 * the **first** event it ever received to be the baseline, no matter when that event arrived.
 *
 * `set()` waits [TorchControl] baseline window (1200 ms), then marks the request and calls
 * `setTorchMode`. If the registration event is late or dropped, the first event after the request is
 * already the post-request state — promoting it to "baseline" invents a causal story: a following
 * matching event is then graded `verified=true`, and `ActionMemory` records an undoable
 * `ENABLE/TORCH` for a torch the request may not have touched. The mirror case is a false timeout:
 * a single genuine post-request event is swallowed as a baseline and the user is told nothing came
 * back.
 *
 * Rule pinned here: a baseline is only a baseline when it was seen **before** the request was issued.
 * Without that, the evidence cannot attribute the state to this request, so the verdict must stay
 * unproven — a state read back after an unattributed request is still not "I did it".
 *
 * All of this is JVM-only: the tracker/outcome split exists exactly so attribution can be tested
 * without a Looper or a camera.
 */
class TorchLateBaselineTest {

    private fun outcomeOf(t: TorchControl.RequestTracker, on: Boolean) = TorchControl.outcome(
        on = on,
        cameraId = "0",
        observed = t.postState,
        unavailable = t.unavailable,
        confirmed = t.confirmed,
        baseline = t.baseline,
        baselinePreRequest = t.baselinePreRequest,
    )

    @Test
    fun `a first report that arrives after the request is not a pre-request baseline`() {
        val t = TorchControl.RequestTracker("0", on = true)
        t.markRequested()
        // The initial event landed only now, i.e. it may already report our own request's result.
        assertThat(t.onModeChanged("0", false)).isFalse()
        assertThat(t.baselinePreRequest).isFalse()
        assertThat(t.onModeChanged("0", true)).isTrue()

        val r = outcomeOf(t, on = true)

        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.status).isNotEqualTo(ToolStatus.SUCCESS)
        assertThat(r.speak).doesNotContain("手电筒已打开")
        assertThat(r.detail).contains("late-baseline")
    }

    @Test
    fun `two matching reports after a late baseline still prove nothing`() {
        val t = TorchControl.RequestTracker("0", on = true)
        t.markRequested()
        assertThat(t.onModeChanged("0", true)).isFalse()   // late "baseline"
        assertThat(t.onModeChanged("0", true)).isTrue()    // confirming

        val r = outcomeOf(t, on = true)

        // The old code reported success here and wrote an undoable memory for it.
        assertThat(r.verified).isNotEqualTo(true)
        assertThat(r.observed).isNull()
        assertThat(r.dispatched).isTrue()
    }

    @Test
    fun `a baseline seen before the request still confirms`() {
        // Control group: the normal order must keep working, or this becomes "refuse everything".
        val t = TorchControl.RequestTracker("0", on = true)
        assertThat(t.onModeChanged("0", false)).isFalse()
        assertThat(t.baselinePreRequest).isTrue()
        t.markRequested()
        assertThat(t.onModeChanged("0", true)).isTrue()

        val r = outcomeOf(t, on = true)

        assertThat(r.status).isEqualTo(ToolStatus.SUCCESS)
        assertThat(r.verified).isTrue()
        assertThat(r.observed?.state).isEqualTo("on")
    }

    @Test
    fun `a callback that never arrives stays an honest timeout`() {
        val t = TorchControl.RequestTracker("0", on = true)
        t.markRequested()

        val r = outcomeOf(t, on = true)

        assertThat(r.status).isEqualTo(ToolStatus.TIMEOUT)
        assertThat(r.verified).isNull()
        assertThat(r.observed).isNull()
        assertThat(r.dispatched).isTrue()
    }
}
