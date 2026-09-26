package io.github.pisces312.droidllm.chattemplate

/**
 * P0 shell. P1 fills this with minja + nlohmann/json JNI chat-template
 * formatting so all engines share one prompt template path.
 */
object ChatTemplate {
    /**
     * Format [messages] into a single prompt string for engines that do not
     * apply templates internally (llama.cpp / MNN / Genie). LiteRT-LM applies
     * its own template and must not call this.
     */
    fun format(messages: List<Pair<String, String>>): String {
        return messages.joinToString("\n") { (role, content) -> "$role: $content" } + "\nassistant:"
    }
}
