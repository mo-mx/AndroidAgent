package ai.closepaw.voice

import android.content.Context
import android.content.Intent
import android.util.Log
import ai.closepaw.app.MainActivity

/**
 * Hands a recognized voice goal to the agent.
 *
 * Uses the same entry point as an external goal intent (MainActivity + `goal` extra) so the
 * voice path shares one code path with typed goals and external launches — no second
 * dispatcher, no duplicated session bootstrap. Debug builds start immediately; release
 * builds surface the standard confirmation card before executing.
 */
object VoiceGoalDispatcher {
    private const val TAG = "VoiceGoalDispatcher"

    fun dispatch(context: Context, goal: String): Boolean = try {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_GOAL, goal)
            putExtra(MainActivity.EXTRA_FRESH_SESSION, false)
        }
        context.startActivity(intent)
        Log.i(TAG, "Dispatched voice goal: $goal")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to dispatch voice goal", t)
        false
    }
}
