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

data class ToolResult(
    val status: ToolStatus,
    val tool: String,
    /** Human sentence to speak (already natural, not a stack trace). */
    val speak: String,
    val detail: String = "",
    /** Verified post-condition, when the tool can check the device state. */
    val verified: Boolean? = null,
) {
    val ok: Boolean get() = status == ToolStatus.SUCCESS

    fun toJson(): JSONObject = JSONObject()
        .put("status", status.name)
        .put("tool", tool)
        .put("speak", speak)
        .put("detail", detail)
        .put("verified", verified)

    companion object {
        fun ok(tool: String, speak: String, verified: Boolean? = null, detail: String = "") =
            ToolResult(ToolStatus.SUCCESS, tool, speak, detail, verified)

        fun denied(tool: String, speak: String, detail: String = "") =
            ToolResult(ToolStatus.PERMISSION_DENIED, tool, speak, detail, false)

        fun blocked(tool: String, speak: String, detail: String = "") =
            ToolResult(ToolStatus.BLOCKED, tool, speak, detail, false)

        fun restricted(tool: String, speak: String, detail: String = "") =
            ToolResult(ToolStatus.SYSTEM_RESTRICTED, tool, speak, detail, false)

        fun unavailable(tool: String, speak: String, detail: String = "") =
            ToolResult(ToolStatus.DEVICE_UNAVAILABLE, tool, speak, detail, false)

        fun error(tool: String, speak: String, detail: String = "") =
            ToolResult(ToolStatus.EXECUTION_ERROR, tool, speak, detail, false)
    }
}
