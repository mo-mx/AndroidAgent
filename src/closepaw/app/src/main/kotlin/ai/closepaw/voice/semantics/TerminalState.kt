package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.ToolResult
import ai.closepaw.voice.tools.ToolStatus

/**
 * Post-execution terminal-state contract — the `expectedState` half of the semantic gate.
 *
 * [IntentGate] answers "is this the operation the user asked for?" *before* the capability runs.
 * This object answers the other question, *after* it runs: "does the evidence the tool brought back
 * support the state we said we would reach?" Nothing else in the pipeline did that, so
 * `expectedState` was descriptive text and every mutating tool could report SUCCESS regardless of
 * what it had actually been able to observe.
 *
 * Three facts about a mutation's outcome must never collapse into SUCCESS:
 *  - **CONFIRMED** — the tool read the device back and it agrees (`verified = true`).
 *  - **UNPROVEN** — the request was delivered but nothing could be read back (`verified = null`);
 *    the action may have landed, so this stays SUCCESS but may never be spoken as done.
 *  - **CONTRADICTED** — the tool read the device back and it disagrees (`verified = false`); the
 *    request did *not* produce the expected state and must not be reported as success.
 *
 * `verified = false` therefore means "read back, state unchanged", not "I did not check". Producers
 * that only delivered an Intent use [ToolResult.sentWithoutProof] (`verified = null`). This gate is
 * what keeps those two apart at the run boundary, so an honest hedge never degenerates into a
 * completion claim later.
 *
 * Pure Kotlin, no Android imports: the whole rule set is JVM-testable. It never upgrades a claim —
 * only CONTRADICTED is rewritten, and only downwards.
 */
object TerminalState {

    enum class Verdict { CONFIRMED, UNPROVEN, CONTRADICTED, NOT_APPLICABLE }

    /**
     * What the returned evidence says about the expected end state.
     *
     * A refusal (BLOCKED / PERMISSION_DENIED / …) is not a failed postcondition — it is a
     * postcondition that was never attempted, so it stays [Verdict.NOT_APPLICABLE] and its own
     * sentence is spoken unchanged. Queries do not promise a state at all.
     */
    fun verdict(task: SemanticTask, result: ToolResult): Verdict {
        if (!task.action.mutates) return Verdict.NOT_APPLICABLE
        if (result.status != ToolStatus.SUCCESS) return Verdict.NOT_APPLICABLE
        return when (result.verified) {
            true -> Verdict.CONFIRMED
            false -> Verdict.CONTRADICTED
            null -> Verdict.UNPROVEN
        }
    }

    /**
     * Applies the verdict to the result the assistant is about to speak.
     *
     * CONTRADICTED becomes EXECUTION_ERROR with a sentence that says the state did not change; the
     * original detail is kept so the trace still shows what was observed. Everything else is
     * returned untouched, which makes the function idempotent.
     */
    fun enforce(task: SemanticTask, result: ToolResult): ToolResult {
        if (verdict(task, result) != Verdict.CONTRADICTED) return result
        return ToolResult(
            ToolStatus.EXECUTION_ERROR,
            result.tool,
            "已经发出请求，但设备状态没有变化，所以不能说做好了",
            result.detail + "; postcondition=contradicted",
            false,
        )
    }
}
