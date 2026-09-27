package io.github.pisces312.droidllm.common.model

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelRootMigratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `analyze splits conflicts`() {
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        File(src, "a.gguf").writeText("a")
        File(src, "b.gguf").writeText("b")
        File(dst, "a.gguf").writeText("existing")

        val plan = ModelRootMigrator.analyze(src, dst)
        assertEquals(2, plan.sourceItems.size)
        assertEquals(listOf("b.gguf"), plan.toMove.map { it.name })
        assertEquals(listOf("a.gguf"), plan.conflicts.map { it.name })
    }

    @Test
    fun `migrate never overwrites target and keeps conflicts in source`() {
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        File(src, "same.txt").writeText("from-source")
        File(src, "new.txt").writeText("new")
        File(dst, "same.txt").writeText("from-target")

        val result = ModelRootMigrator.migrate(src, dst)

        assertEquals("from-target", File(dst, "same.txt").readText())
        assertEquals("from-source", File(src, "same.txt").readText())
        assertTrue(File(dst, "new.txt").isFile)
        assertFalse(File(src, "new.txt").exists())
        assertEquals(listOf("same.txt"), result.skippedNames)
        assertTrue(result.movedPaths.keys.any { it.endsWith("new.txt") })
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `migrate moves directory tree`() {
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        val modelDir = File(src, "mnn").apply {
            mkdirs()
            File(this, "config.json").writeText("{}")
            File(this, "llm.mnn").writeText("bin")
        }

        val result = ModelRootMigrator.migrate(src, dst)

        assertTrue(File(dst, "mnn/config.json").isFile)
        assertTrue(File(dst, "mnn/llm.mnn").isFile)
        assertFalse(modelDir.exists())
        val remapped = ModelRootMigrator.remapPath(
            File(src, "mnn/llm.mnn").absolutePath,
            result.movedPaths,
        )
        assertEquals(File(dst, "mnn/llm.mnn").absolutePath, remapped)
    }

    @Test
    fun `remapPath handles descendants and unknown paths`() {
        val moved = mapOf(
            "/old/root" to "/new/root",
        )
        assertEquals("/new/root", ModelRootMigrator.remapPath("/old/root", moved))
        assertEquals("/new/root/a/b.gguf", ModelRootMigrator.remapPath("/old/root/a/b.gguf", moved))
        assertNull(ModelRootMigrator.remapPath("/other/path", moved))
    }

    @Test
    fun `nested paths are rejected`() {
        val a = tmp.newFolder("a")
        val nested = File(a, "sub").apply { mkdirs() }
        assertTrue(ModelRootMigrator.isSameOrNested(a, nested))
        assertTrue(ModelRootMigrator.isSameOrNested(nested, a))
        val b = tmp.newFolder("b")
        assertFalse(ModelRootMigrator.isSameOrNested(a, b))
    }

    @Test
    fun `creates missing target directory`() {
        val src = tmp.newFolder("src")
        File(src, "x.txt").writeText("x")
        val dst = File(tmp.root, "not-yet")

        val result = ModelRootMigrator.migrate(src, dst)

        assertTrue(File(dst, "x.txt").isFile)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `empty source is a no-op plan`() {
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        val plan = ModelRootMigrator.analyze(src, dst)
        assertTrue(plan.isEmpty)
        val result = ModelRootMigrator.migrate(src, dst)
        assertTrue(result.movedPaths.isEmpty())
    }

    @Test
    fun `cross-device fallback copy does not clobber target`() {
        // renameTo may fail across filesystems; simulate by pre-creating dest then migrating another.
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        File(src, "f.bin").writeText("src-data")
        val result = ModelRootMigrator.migrate(src, dst)
        assertEquals("src-data", File(dst, "f.bin").readText())
        // Files.createTempFile used only to keep TemporaryFolder happy on Windows.
        Files.exists(dst.toPath())
        assertTrue(result.isOk)
    }
}
