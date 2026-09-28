package io.github.pisces312.droidllm.apiserver

import io.github.pisces312.droidllm.apiserver.protocol.OpenAiFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiProtocolTest {

    @Test
    fun parse_minimalChatRequest() {
        val params = parseChatRequest(
            """
            {"model":"m1","messages":[{"role":"user","content":"hi"}]}
            """.trimIndent(),
        )
        assertNotNull(params)
        assertEquals("m1", params!!.model)
        assertEquals(1, params.messages.size)
        assertEquals("user", params.messages[0].role)
        assertEquals("hi", params.messages[0].content)
        assertEquals(false, params.stream)
    }

    @Test
    fun parse_streamAndSampling() {
        val params = parseChatRequest(
            """
            {
              "messages":[{"role":"system","content":"s"},{"role":"user","content":"q"}],
              "stream": true,
              "temperature": 0.2,
              "top_p": 0.9,
              "max_tokens": 32
            }
            """.trimIndent(),
        )
        assertNotNull(params)
        assertEquals(true, params!!.stream)
        assertEquals(0.2f, params.temperature!!, 0.001f)
        assertEquals(0.9f, params.topP!!, 0.001f)
        assertEquals(32, params.maxTokens)
    }

    @Test
    fun parse_rejectsImageContent() {
        val params = parseChatRequest(
            """
            {"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"x"}}]}]}
            """.trimIndent(),
        )
        assertNull(params)
    }

    @Test
    fun parse_rejectsEmptyMessages() {
        assertNull(parseChatRequest("""{"messages":[]}"""))
        assertNull(parseChatRequest("""{"messages":"nope"}"""))
        assertNull(parseChatRequest("""not-json"""))
    }

    @Test
    fun formatter_chatCompletionShape() {
        val json = OpenAiFormatter.chatCompletion(
            id = "chatcmpl-1",
            created = 1L,
            model = "m",
            content = "hello",
            promptTokens = 3,
            completionTokens = 2,
        )
        assertTrue(json.contains("\"object\":\"chat.completion\""))
        assertTrue(json.contains("\"content\":\"hello\""))
        assertTrue(json.contains("\"total_tokens\":5"))
    }

    @Test
    fun formatter_chunkDone() {
        val json = OpenAiFormatter.chunk(
            id = "c",
            created = 1L,
            model = "m",
            finishReason = "stop",
        )
        assertTrue(json.contains("chat.completion.chunk"))
        assertTrue(json.contains("\"finish_reason\":\"stop\""))
    }
}
