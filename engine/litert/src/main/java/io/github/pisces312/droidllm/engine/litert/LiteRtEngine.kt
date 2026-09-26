package io.github.pisces312.droidllm.engine.litert

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.pisces312.droidllm.engineapi.EngineId
import io.github.pisces312.droidllm.engineapi.LlmEngine
import io.github.pisces312.droidllm.engineapi.UnboundEngine
import javax.inject.Inject
import javax.inject.Singleton

/** P2 placeholder. Real LiteRT-LM adapter lands in P2. */
@Singleton
class LiteRtEngine @Inject constructor() : LlmEngine by UnboundEngine(
    id = EngineId.LITERT,
    displayName = "LiteRT-LM",
    reason = "LiteRT-LM adapter not integrated yet (P2)",
)

@Module
@InstallIn(SingletonComponent::class)
abstract class LiteRtEngineModule {
    @Binds
    @IntoSet
    abstract fun bindEngine(impl: LiteRtEngine): LlmEngine
}
