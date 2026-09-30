package io.github.pisces312.droidllm.common.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {

    @Test
    fun `from maps stored names and rejects junk`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.from(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.from(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.from("ZH_TAIWAN"))
        assertEquals(AppLanguage.ZH, AppLanguage.from("ZH"))
        // Stored as enum name; a lowercase tag is not a match (falls back to SYSTEM).
        assertEquals(AppLanguage.SYSTEM, AppLanguage.from("zh"))
        assertEquals(AppLanguage.EN, AppLanguage.from("EN"))
    }

    @Test
    fun `system has no locale tag`() {
        assertNull(AppLanguage.SYSTEM.tag)
        assertEquals("zh", AppLanguage.ZH.tag)
        assertEquals("en", AppLanguage.EN.tag)
    }
}
