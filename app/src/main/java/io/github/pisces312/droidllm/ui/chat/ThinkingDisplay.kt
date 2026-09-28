package io.github.pisces312.droidllm.ui.chat

/**
 * Display-only cleanup so raw thinking tags never leak into the assistant bubble.
 *
 * Engines sometimes emit thinking open/close tags even when reasoning is off or
 * empty (template prefill, or a model that decided not to reason). Rules:
 * - empty body -> drop the whole block (tags + whitespace)
 * - thinking off -> drop the whole block (reasoning is not the reply)
 * - thinking on + non-empty -> keep as-is so reasoning stays distinguishable
 *   from the answer in this plain transcript
 *
 * Never mutates what is sent back to the engine as history.
 */
object ThinkingDisplay {
    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"
    private val CLOSED = Regex("(?s)" + Regex.escape(OPEN) + "(.*?)" + Regex.escape(CLOSE))

    fun forDisplay(raw: String, thinkingEnabled: Boolean): String {
        val stripped = CLOSED.replace(raw) { m ->
            val body = m.groupValues[1]
            if (body.isBlank() || !thinkingEnabled) "" else m.value
        }
        val openIdx = stripped.lastIndexOf(OPEN)
        val text = if (openIdx >= 0 && stripped.indexOf(CLOSE, openIdx) < 0) {
            val body = stripped.substring(openIdx + OPEN.length)
            if (body.isBlank() || !thinkingEnabled) stripped.substring(0, openIdx) else stripped
        } else {
            stripped
        }
        return text.trimStart('\n', '\r')
    }
}
