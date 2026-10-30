package ai.closepaw.voice

import ai.closepaw.protocol.TaskOutcome
import android.util.Log

/**
 * Speaks task outcomes through [TtsSpeaker].
 *
 * Kept as a plain object (not a service) so [ai.closepaw.app.AgentServiceEventHandler] can
 * call it from the accessibility service process without plumbing a Context through the
 * event handler. All calls are no-ops until [TtsSpeaker.init] has been called once — which
 * happens on voice-session show, or explicitly from the accessibility service on connect.
 */
object VoiceFeedbackBridge {
    private const val TAG = "VoiceFeedbackBridge"

    /** Speak a short acknowledgement when a task starts. */
    fun onTaskStarted() {
        // Intentionally silent: the voice session already said "好的，开始执行".
        // Long-running tasks must not talk over the user.
    }

    /** Speak the task result (or a short failure explanation). */
    fun onTaskCompleted(outcome: TaskOutcome, answer: String?) {
        val spoken = when {
            isError(outcome) && !answer.isNullOrBlank() ->
                "任务失败：" + TtsSpeaker.sanitize(answer)
            isError(outcome) ->
                "任务失败：" + defaultErrorText(outcome)
            !answer.isNullOrBlank() -> TtsSpeaker.sanitize(answer)
            outcome == TaskOutcome.USER_STOPPED -> "已停止"
            else -> "任务完成"
        }
        Log.i(TAG, "speaking outcome=$outcome chars=${spoken.length}")
        TtsSpeaker.speak(spoken, interrupt = true)
    }

    private fun isError(outcome: TaskOutcome): Boolean = when (outcome) {
        TaskOutcome.GOAL_ACHIEVED, TaskOutcome.USER_STOPPED -> false
        else -> true
    }

    private fun defaultErrorText(outcome: TaskOutcome): String = when (outcome) {
        TaskOutcome.ERROR -> "执行出错"
        TaskOutcome.TASK_IMPOSSIBLE -> "这个任务我做不到"
        else -> "没能完成"
    }
}
