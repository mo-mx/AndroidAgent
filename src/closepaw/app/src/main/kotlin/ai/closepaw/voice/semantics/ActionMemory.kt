package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.ToolResult
import ai.closepaw.voice.tools.ToolStatus
import ai.closepaw.voice.tools.TorchControl
import android.content.Context
import android.util.Log

/**
 * Remembers what the assistant just did, so "取消刚才的操作" can be resolved to a concrete inverse
 * instead of being re-parsed as a new command (one of the ways the old code created things the
 * user asked to cancel).
 */
object ActionMemory {
    private const val TAG = "ActionMemory"

    /** How long a recorded mutation stays replayable as "刚才的操作". */
    const val MAX_AGE_MS: Long = 120_000L

    /**
     * Age stamp for the undo window, on a **monotonic** clock.
     *
     * Round 46 review P2: `at` and `undo(now)` both used `System.currentTimeMillis()`, and the only
     * check was `now - at > MAX_AGE_MS`. A wall clock can be moved (user, NTP, timezone tooling), and
     * a backwards correction makes the age negative — which passes the one-sided check, so a memory
     * older than the window became replayable and its inverse ran against an object whose state
     * nobody knows any more. `System.nanoTime()` is monotonic on both ART and the JVM, so 校时 cannot
     * re-open the window, and unit tests need no Robolectric shadow for it.
     */
    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    data class Entry(
        val action: Action,
        val target: Target,
        val params: Map<String, String>,
        val speech: String,
        val at: Long = monotonicMs(),
    )

    @Volatile
    var last: Entry? = null
        private set

    /** Test hook: the object is process-wide, so the JVM test class boundary is not enough. */
    @androidx.annotation.VisibleForTesting
    internal fun clearForTest() {
        last = null
    }

    /** Single memory boundary. An attempted mutation invalidates older undo state.
     * Clock intents expose no newly-created object ID and no identity-bound inverse API.
     * Even verified=true cannot make a duration/time into object identity: fail closed.
     */
    fun recordResult(action: Action, target: Target, params: Map<String, String>, result: ToolResult) {
        if (!action.mutates || target == Target.LAST_ACTION) return
        // Round 49 review B1/B2: a result that was never handed to the system (`dispatched = false`)
        // cannot be the change this turn made — "手电筒本来就是开着的" is a state observation, not a
        // transition, so it must not become an undoable ENABLE/DISABLE that turns the lamp off.
        last = if (result.ok && result.verified == true && result.dispatched != false &&
            target == Target.TORCH && action in setOf(Action.ENABLE, Action.DISABLE)) {
            Entry(action, target, params.toMap(), result.speak)
        } else null
    }



    /**
     * Undo the previous task by issuing its inverse — never by creating something new.
     *
     * Round 43: the memory is consumed by the undo that applies it. Issuing the inverse changes the
     * world, so the remembered state ("torch was ON") stops being true the moment the inverse runs;
     * keeping it made a second "取消刚才的操作" re-issue the same inverse and report it as a fresh
     * success (and, for a remembered `DISABLE`, re-enable a torch the user may have just switched
     * off by hand). An inverse that did **not** run (device unavailable, no inverse) leaves the
     * entry in place, so an honest retry is still possible.
     */
    fun undo(ctx: Context, now: Long = monotonicMs()): ToolResult {
        val e = last ?: return ToolResult.unavailable("undo", "无法确认刚才变更的具体对象，不能安全撤销", "no verified identity-bound inverse")
        // Round 44 review P2: `at` was recorded but never read, so a "撤销刚才的操作" said hours later
        // still ran the inverse of an old remembered state. The torch can be switched by hand or by
        // another app in between, which turns that inverse into a *new* mutation. Outside the window
        // the entry is refused and dropped; inside it nothing about an external change is known
        // either, which is why the result stays unverified.
        // Round 46 review P2: a *negative* age is not "fresh". It only appears when the two readings
        // did not come from the same timeline (a wall clock moved backwards); the age then says
        // nothing about how old the memory is, so it cannot pass the window either.
        val age = now - e.at
        if (age < 0 || age > MAX_AGE_MS) {
            if (last === e) last = null
            Log.i(TAG, "undo(${e.action}/${e.target}) refused age=${age}ms > ${MAX_AGE_MS}ms")
            return ToolResult.unavailable(
                "undo",
                "刚才的操作已经过去太久了，这中间手机状态可能变了，不能安全撤销",
                "stale memory age=${age}ms window=${MAX_AGE_MS}ms",
            )
        }
        val r = when {

            e.target == Target.TORCH && e.action == Action.ENABLE -> TorchControl.set(ctx, false)
            e.target == Target.TORCH && e.action == Action.DISABLE -> TorchControl.set(ctx, true)
            e.action == Action.OPEN -> ToolResult(
                ToolStatus.SYSTEM_RESTRICTED, "undo",
                "打开应用这个操作没法撤销，需要你按返回键",
                "open cannot be undone programmatically"
            )
            else -> ToolResult.unavailable("undo", "刚才的操作没有对应的撤销动作", e.speech)
        }
        // Round 44 review P1: "the inverse was issued" and "the inverse was confirmed" are different
        // facts. `TorchControl.set` calls `setTorchMode` and then waits for a callback; a callback
        // that never arrives yields TIMEOUT even though the torch may already have switched — and
        // `dispatched` records exactly that. Consuming only on SUCCESS kept the memory across such a
        // timeout, so a second "撤销" re-issued the same inverse (for a remembered DISABLE: turning
        // on a torch the user had just switched off by hand).
        //
        // Round 50 review B2/P1: `dispatched == false` alone says *nothing was handed to the OS*, and
        // that covers two opposite facts:
        //   • the device refused before issue (camera unavailable, no flash) → the inverse still has
        //     to happen, so the entry stays and an honest retry is possible;
        //   • the inverse's goal already holds (the user switched the torch off by hand, so
        //     `TorchControl.set(false)` answers `alreadySatisfied` → SUCCESS + verified + `dispatched
        //     = false`) → the memory is spent. Keeping it meant a later "撤销", after the user
        //     turned the torch back on, issued `set(false)` and extinguished that new state while
        //     attributing it to the assistant's older action.
        // A successful inverse — issued or already true — ends the memory either way; only a
        // failure that provably never reached the device keeps it.
        val keepForRetry = !r.ok && r.dispatched == false
        if (!keepForRetry && last === e) last = null
        Log.i(TAG, "undo(${e.action}/${e.target}) → ${r.status} ${r.detail}${if (last == null) " consumed" else " kept for retry"}")
        return ToolResult(r.status, "undo", r.speak, "undo of ${e.action}/${e.target}: ${r.detail}", r.verified,
            dispatched = r.dispatched)
    }
}
