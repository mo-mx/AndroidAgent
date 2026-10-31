package ai.closepaw.voice.semantics

import ai.closepaw.voice.tools.ToolResult
import ai.closepaw.voice.tools.ToolStatus

/**
 * Post-execution terminal-state contract — the `expectedState` half of the semantic gate.
 *
 * [IntentGate] answers "is this the operation the user asked for?" *before* the capability runs.
 * This object answers the other question, *after* it runs: "does the evidence the tool brought back
 * support the state we said we would reach?"
 *
 * The first version of this gate only read `verified`, which meant a capability could be graded
 * CONFIRMED for reading back the *wrong* object, or for a permission sheet that merely changed the
 * screen. `expectedState` (`timer_running:300`, `no_running_timer`, `on:TORCH`, `feature_visible`,
 * …) was still descriptive text. So confirmation now requires structured evidence — see
 * [ai.closepaw.voice.tools.ObservedState] — that matches both the declared identity and the
 * declared state:
 *
 *  - **CONFIRMED** — evidence names the target object *and* shows the promised state.
 *  - **UNPROVEN** — the request was delivered but nothing object-level could be read back, or the
 *    object that was read is not the one we promised. Stays SUCCESS (it may have landed) but is
 *    never spoken as done.
 *  - **CONTRADICTED** — the target object was read back and its state differs from the promise.
 *    Refused at the run boundary and rewritten, never reported as success.
 *
 * A task that declares no expected state keeps the older, weaker tri-state grading of `verified`:
 * there is nothing to compare against, and inventing a comparison would be worse than admitting
 * the evidence is coarse.
 *
 * `Action.OPEN` is not exempt. "打开了微信扫一扫" promises a visible page just as much as a timer
 * creation promises a running countdown.
 *
 * Pure Kotlin, no Android imports: the whole rule set is JVM-testable. It never upgrades a claim —
 * it only rewrites CONTRADICTED, and hedges a CONFIRMED claim that has no evidence.
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
        if (result.status != ToolStatus.SUCCESS) return Verdict.NOT_APPLICABLE
        if (!promisesState(task)) return Verdict.NOT_APPLICABLE
        // The tool's own read-back disagreed with itself; that is authoritative either way.
        if (result.verified == false) return Verdict.CONTRADICTED
        if (!declaresState(task)) {
            // Nothing to compare against: keep the older, coarser grading of `verified` rather than
            // pretending an object-level check happened.
            return if (result.verified == true) Verdict.CONFIRMED else Verdict.UNPROVEN
        }

        val observed = result.observed ?: return Verdict.UNPROVEN
        if (!identityMatches(task, observed)) return Verdict.UNPROVEN
        val expected = expectation(task)
        val actual = observed.state?.trim()?.lowercase()
            ?: return Verdict.UNPROVEN     // read the object, recorded no state: no evidence
        if (expected.isEmpty()) {
            // The parser promised a state this gate has no predicate for. Refusing to confirm is
            // the only honest answer; it must not be read as "verified by default".
            return Verdict.UNPROVEN
        }
        if (actual !in expected) return Verdict.CONTRADICTED
        return if (result.verified == true) Verdict.CONFIRMED else Verdict.UNPROVEN
    }

    /**
     * Applies the verdict to the result the assistant is about to speak.
     *
     * CONTRADICTED becomes EXECUTION_ERROR with a sentence that says the state did not change; the
     * original detail is kept so the trace still shows what was observed. An UNPROVEN claim that
     * still says `verified = true` is hedged: the request really was delivered, so the status stays
     * SUCCESS, but neither the flag nor the sentence may report a completed job — and this applies
     * to every action that promises a state, not only [Action.OPEN]. (`Action.OPEN` predated this
     * rule and keeps its page-specific wording.) Everything else is returned untouched, which makes
     * the function idempotent: after one pass the flag is null and the verdict can no longer be a
     * claim of success.
     */
    fun enforce(task: SemanticTask, result: ToolResult): ToolResult =
        when (verdict(task, result)) {
            Verdict.CONTRADICTED -> ToolResult(
                ToolStatus.EXECUTION_ERROR,
                result.tool,
                "已经发出请求，但设备状态没有变化，所以不能说做好了",
                result.detail + "; postcondition=contradicted",
                false,
                result.observed,
            )
            Verdict.UNPROVEN -> if (result.verified == true) {
                if (task.action == Action.OPEN) {
                    val feature = task.params["feature"]?.takeIf { it.isNotBlank() } ?: "那个功能"
                    result.copy(
                        speak = "点了$feature，但没能确认页面已经打开，请你看一眼",
                        verified = null,
                        detail = result.detail + "; postcondition=unproven(no page identity)",
                    )
                } else {
                    result.copy(
                        speak = "请求已经发出，但没能确认结果，请你看一眼",
                        verified = null,
                        detail = result.detail + "; postcondition=unproven(no object evidence)",
                    )
                }
            } else {
                result
            }
            else -> result
        }

    /** Does this task promise a state that could be checked? Mutations and opens both do. */
    private fun promisesState(task: SemanticTask): Boolean =
        task.action.mutates || task.action == Action.OPEN

    /** Does it also say *which* state, in a form this gate can compare? */
    private fun declaresState(task: SemanticTask): Boolean = task.expectedState.isNotBlank()

    /**
     * The state token(s) [ai.closepaw.voice.tools.ObservedState.state] must carry for this task's
     * `expectedState`.
     *
     * Empty set means "no machine-checkable predicate for this expectation" — a confirmation claim
     * then stays UNPROVEN rather than being trusted. Queries never reach here.
     */
    private fun expectation(task: SemanticTask): Set<String> {
        val key = task.expectedState.substringBefore(':').trim().lowercase()
        val arg = task.expectedState.substringAfter(':', "").trim()
        return when (key) {
            "timer_running" -> setOf("running:$arg")
            "alarm_exists" -> setOf("exists:$arg")
            "no_running_timer", "no_such_alarm" -> setOf("none", "absent", "off")
            "on" -> setOf("on", "enabled", "true")
            "off" -> setOf("off", "disabled", "false")
            "feature_visible" -> setOf("visible", "shown")
            else -> emptySet()
        }
    }

    /**
     * A read-back is about the promised object only when it names one and, if the task also named
     * one, the names agree. A missing identity on either side is absence of evidence, not proof.
     */
    private fun identityMatches(task: SemanticTask, observed: ai.closepaw.voice.tools.ObservedState): Boolean {
        val want = task.targetIdentity?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        val got = observed.identity?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return want.equals(got, ignoreCase = true)
    }
}
