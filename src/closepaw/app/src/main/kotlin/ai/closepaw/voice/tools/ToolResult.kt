package ai.closepaw.voice.tools

import org.json.JSONObject

/**
 * Structured tool outcome.
 *
 * The rule this enforces: "could not run", "ran but the device state did not change" and
 * "ran fine" must never collapse into one generic "任务执行失败" message. Every tool returns
 * one of these, and the voice layer speaks a message tailored to the status.
 */
enum class ToolStatus {
    SUCCESS,
    /** Refused by the semantic gate / capability table before anything was executed. */
    BLOCKED,
    PERMISSION_DENIED,
    SYSTEM_RESTRICTED,
    DEVICE_UNAVAILABLE,
    EXECUTION_ERROR,
    TIMEOUT,
}

/**
 * Structured post-condition evidence: *which object* was read back, and *what state it was in*.
 *
 * `verified = true` alone says "a tool claims it looked". It does not say what it looked at, so a
 * read-back of the wrong alarm, the wrong duration or an unrelated page could be graded as the
 * promised terminal state. Evidence names the object and the observation so
 * [ai.closepaw.voice.semantics.TerminalState] can compare it with `expectedState` instead of
 * trusting a flag. Producers that only know "something changed on screen" leave this null.
 */
data class ObservedState(
    /** Concrete identity actually read back — package name, `timer#id`, `alarm:HH:MM`, … */
    val identity: String? = null,
    /** Observed state token, e.g. `running:300`, `none`, `on`, `off`, `visible`. */
    val state: String? = null,
    /** Where the observation came from (tool/UI surface), kept for the trace only. */
    val source: String = "",
)

data class ToolResult(
    val status: ToolStatus,
    val tool: String,
    /** Human sentence to speak (already natural, not a stack trace). */
    val speak: String,
    val detail: String = "",
    /** Verified post-condition, when the tool can check the device state. */
    val verified: Boolean? = null,
    /** What exactly was read back. Null when the tool has no object-level evidence. */
    val observed: ObservedState? = null,
    /**
     * Whether the mutation was actually handed to the OS.
     *
     * Separate from [verified] on purpose: `verified` answers "can we prove the promised state",
     * this answers "did anything get issued at all". A torch request whose callback times out is
     * dispatched-but-unproven; a camera that refused before `setTorchMode` is neither. Undo has to
     * tell them apart (consuming on the first, retrying on the second).
     *
     * `null` means the producer did not say.
     */
    val dispatched: Boolean? = null,
) {
    val ok: Boolean get() = status == ToolStatus.SUCCESS

    fun toJson(): JSONObject = JSONObject()
        .put("status", status.name)
        .put("tool", tool)
        .put("speak", speak)
        .put("detail", detail)
        .put("verified", verified)

    companion object {
        fun ok(
            tool: String,
            speak: String,
            verified: Boolean? = null,
            detail: String = "",
            observed: ObservedState? = null,
            dispatched: Boolean? = null,
        ) = ToolResult(ToolStatus.SUCCESS, tool, speak, detail, verified, observed, dispatched)


        /**
         * The request was handed to the system, but nothing could be read back.
         *
         * `verified = null` (unknown), *not* `false` (read back, unchanged). The two were used
         * interchangeably before, which let an unobserved mutation look like a contradicted one —
         * and, once [ai.closepaw.voice.semantics.TerminalState] judges outcomes, a contradicted
         * SUCCESS is refused. A delivered Intent is not evidence of anything.
         */
        fun sentWithoutProof(tool: String, speak: String, detail: String = "") =
            ToolResult(ToolStatus.SUCCESS, tool, speak, detail, null)

        fun denied(tool: String, speak: String, detail: String = "") =
            ToolResult(ToolStatus.PERMISSION_DENIED, tool, speak, detail, false)

        fun blocked(tool: String, speak: String, detail: String = "") =
            ToolResult(ToolStatus.BLOCKED, tool, speak, detail, false)

        fun restricted(tool: String, speak: String, detail: String = "") =
            ToolResult(ToolStatus.SYSTEM_RESTRICTED, tool, speak, detail, false)

        fun unavailable(tool: String, speak: String, detail: String = "", dispatched: Boolean? = null) =
            ToolResult(ToolStatus.DEVICE_UNAVAILABLE, tool, speak, detail, false, dispatched = dispatched)

        fun error(tool: String, speak: String, detail: String = "") =
            ToolResult(ToolStatus.EXECUTION_ERROR, tool, speak, detail, false)
    }
}
