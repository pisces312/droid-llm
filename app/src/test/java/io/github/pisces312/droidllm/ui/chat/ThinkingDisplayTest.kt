package io.github.pisces312.droidllm.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingDisplayTest {
    private val open = "<think>"
    private val close = "</think>"

    @Test
    fun emptyClosedBlock_isDropped() {
        assertEquals("answer", ThinkingDisplay.forDisplay("<think></think>\n\nanswer", thinkingEnabled = true))
        assertEquals("answer", ThinkingDisplay.forDisplay("<think>\n  \n</think>\nanswer", thinkingEnabled = true))
    }

    @Test
    fun emptyOpenTag_isDropped() {
        assertEquals("", ThinkingDisplay.forDisplay(open, thinkingEnabled = true))
        assertEquals("", ThinkingDisplay.forDisplay("<think>\n\n", thinkingEnabled = true))
        assertEquals("answer", ThinkingDisplay.forDisplay("<think>\n</think>" + "answer", thinkingEnabled = true))
    }

    @Test
    fun thinkingOff_dropsWholeBlock() {
        assertEquals("answer", ThinkingDisplay.forDisplay("<think>\nreasoning</think>\nanswer", thinkingEnabled = false))
        assertEquals("", ThinkingDisplay.forDisplay("<think>\nreasoning", thinkingEnabled = false))
    }

    @Test
    fun thinkingOn_keepsNonEmptyBlock() {
        val raw = "<think>\nreasoning</think>\nanswer"
        assertEquals(raw, ThinkingDisplay.forDisplay(raw, thinkingEnabled = true))
    }

    @Test
    fun plainText_untouched() {
        assertEquals("hello", ThinkingDisplay.forDisplay("hello", thinkingEnabled = true))
        assertEquals("hello", ThinkingDisplay.forDisplay("hello", thinkingEnabled = false))
    }
}
