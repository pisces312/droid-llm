package io.github.pisces312.droidllm.common.settings

import io.github.pisces312.droidllm.common.model.ModelParamsOverride
import io.github.pisces312.droidllm.common.model.StoredModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip and merge rules for the settings backup format. These are the
 * guarantees the import UI relies on: a wrong file must be rejected, and an
 * import must never silently drop local registrations.
 */
class SettingsBundleTest {

    private fun model(id: String, name: String = id) = StoredModel(
        id = id,
        engineId = "MNN",
        displayName = name,
        locationType = "file",
        locationValue = "/models/$id",
    )

    @Test
    fun `round trip preserves settings models and params`() {
        val bundle = SettingsBundle(
            appVersion = "0.1.0",
            settings = BundledSettings(temperature = 0.3f, hfUseMirror = false),
            models = listOf(model("a"), model("b")),
            modelParams = mapOf("a" to ModelParamsOverride(topK = 7)),
        )
        val decoded = SettingsBundleCodec.decode(SettingsBundleCodec.encode(bundle))
        assertTrue(decoded is BundleParseResult.Ok)
        val ok = (decoded as BundleParseResult.Ok).bundle
        assertEquals(0.3f, ok.settings.temperature)
        assertEquals(false, ok.settings.hfUseMirror)
        assertEquals(listOf("a", "b"), ok.models.map { it.id })
        assertEquals(7, ok.modelParams.getValue("a").topK)
    }

    @Test
    fun `blank input reports Empty`() {
        assertEquals(BundleParseResult.Empty, SettingsBundleCodec.decode("   "))
    }

    @Test
    fun `garbage input reports Malformed`() {
        assertTrue(SettingsBundleCodec.decode("{not json") is BundleParseResult.Malformed)
    }

    @Test
    fun `future version is refused`() {
        val text = SettingsBundleCodec.encode(
            SettingsBundle(version = SettingsBundle.CURRENT_VERSION + 1),
        )
        val result = SettingsBundleCodec.decode(text)
        assertTrue(result is BundleParseResult.VersionMismatch)
        assertEquals(
            SettingsBundle.CURRENT_VERSION,
            (result as BundleParseResult.VersionMismatch).expected,
        )
    }

    @Test
    fun `merge keeps local-only models and overwrites matching ids`() {
        val localOnly = model("local", "Local")
        val shared = model("shared", "Old name")
        val plan = SettingsBundleMerger.plan(
            bundle = SettingsBundle(
                models = listOf(model("shared", "New name"), model("new", "New")),
            ),
            existingModels = listOf(localOnly, shared),
            existingParams = mapOf("local" to ModelParamsOverride(topK = 1)),
            includeApiServer = false,
        )
        assertEquals(listOf("new"), plan.modelsToAdd.map { it.id })
        assertEquals(listOf("shared"), plan.modelsToUpdate.map { it.id })
        assertEquals("New name", plan.modelsToUpdate.first().displayName)
        // The local-only overlay survives — merge is additive.
        assertEquals(1, plan.modelParamsToWrite.getValue("local").topK)
    }

    @Test
    fun `empty overrides are dropped when planning`() {
        val plan = SettingsBundleMerger.plan(
            bundle = SettingsBundle(modelParams = mapOf("a" to ModelParamsOverride())),
            existingModels = emptyList(),
            existingParams = emptyMap(),
            includeApiServer = false,
        )
        assertTrue(plan.modelParamsToWrite.isEmpty())
    }

    @Test
    fun `api server config is only included when requested`() {
        val bundle = SettingsBundle(apiServer = BundledApiServer(port = 9090))
        val without = SettingsBundleMerger.plan(bundle, emptyList(), emptyMap(), includeApiServer = false)
        val with = SettingsBundleMerger.plan(bundle, emptyList(), emptyMap(), includeApiServer = true)
        assertEquals(null, without.apiServer)
        assertEquals(9090, with.apiServer?.port)
    }
}
