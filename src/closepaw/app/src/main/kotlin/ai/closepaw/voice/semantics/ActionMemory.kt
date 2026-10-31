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

    data class Entry(
        val action: Action,
        val target: Target,
        val params: Map<String, String>,
        val speech: String,
        val at: Long = System.currentTimeMillis(),
    )

    @Volatile
    var last: Entry? = null
        private set

    /** Single memory boundary. An attempted mutation invalidates older undo state.
     * Clock intents expose no newly-created object ID and no identity-bound inverse API.
     * Even verified=true cannot make a duration/time into object identity: fail closed.
     */
    fun recordResult(action: Action, target: Target, params: Map<String, String>, result: ToolResult) {
        if (!action.mutates || target == Target.LAST_ACTION) return
        last = if (result.ok && result.verified == true && target == Target.TORCH &&
            action in setOf(Action.ENABLE, Action.DISABLE)) {
            Entry(action, target, params.toMap(), result.speak)
        } else null
    }



    /** Undo the previous task by issuing its inverse — never by creating something new. */
    fun undo(ctx: Context): ToolResult {
        val e = last ?: return ToolResult.unavailable("undo", "无法确认刚才变更的具体对象，不能安全撤销", "no verified identity-bound inverse")
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
        Log.i(TAG, "undo(${e.action}/${e.target}) → ${r.status} ${r.detail}")
        return ToolResult(r.status, "undo", r.speak, "undo of ${e.action}/${e.target}: ${r.detail}", r.verified)
    }
}
