package io.github.pisces312.droidllm.engineapi

/**
 * Shared stub used by not-yet-integrated engine adapters (P0).
 * probe() reports MissingDependency; load() fails with a clear message.
 */
class UnboundEngine(
    override val id: EngineId,
    override val displayName: String,
    private val reason: String,
) : LlmEngine {

    override suspend fun probe(probeContext: ProbeContext): Availability =
        Availability.MissingDependency(reason)

    override suspend fun load(model: LocalModel, config: InferenceConfig): SessionHandle =
        throw EngineException.LoadFailed(reason)

    override fun generate(
        handle: SessionHandle,
        request: GenerateRequest,
        onEvent: (EngineEvent) -> Unit,
    ): GenerateJob = throw EngineException.InvalidState(reason)

    override suspend fun reset(handle: SessionHandle): Unit =
        throw EngineException.InvalidState(reason)

    override suspend fun unload(handle: SessionHandle): Unit = Unit

    override fun lastMetrics(handle: SessionHandle): EngineMetrics? = null
}
