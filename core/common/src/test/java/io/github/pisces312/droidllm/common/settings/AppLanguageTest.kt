package io.github.pisces312.droidllm.common.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {

    @Test
    fun `system has no locale tag`() {
        assertNull(AppLanguage.SYSTEM.tag)
        assertEquals("zh", AppLanguage.ZH.tag)
        assertEquals("en", AppLanguage.EN.tag)
    }
}
