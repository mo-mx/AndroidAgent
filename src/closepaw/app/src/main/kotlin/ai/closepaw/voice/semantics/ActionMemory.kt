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

    fun record(action: Action, target: Target, params: Map<String, String>, speech: String) {
        last = Entry(action, target, params, speech)
        Log.i(TAG, "recorded $action/$target $params")
    }

    fun lastTimerSeconds(): Int? =
        last?.takeIf { it.target == Target.TIMER && it.action == Action.CREATE }
            ?.params?.get("seconds")?.toIntOrNull()

    /** Undo the previous task by issuing its inverse — never by creating something new. */
    fun undo(ctx: Context): ToolResult {
        val e = last ?: return ToolResult.unavailable("undo", "刚才没有可以撤销的操作", "no memory")
        val r = when {
            e.action == Action.CREATE && e.target == Target.TIMER -> ClockControl.cancelTimer(ctx)
            e.action == Action.CREATE && e.target == Target.ALARM -> {
                val h = e.params["hour"]?.toIntOrNull()
                val m = e.params["minute"]?.toIntOrNull()
                if (h == null || m == null) {
                    ToolResult.error("undo", "记不住刚才设的闹钟时间", "missing params")
                } else ClockControl.closeAlarm(ctx, h, m)
            }
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
