package io.github.pisces312.droidllm.engine.genie

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

/** P3 placeholder. Real Genie adapter lands in P3. */
@Singleton
class GenieEngine @Inject constructor() : LlmEngine by UnboundEngine(
    id = EngineId.GENIE,
    displayName = "Genie (QNN)",
    reason = "Genie adapter not integrated yet (P3)",
)

@Module
@InstallIn(SingletonComponent::class)
abstract class GenieEngineModule {
    @Binds
    @IntoSet
    abstract fun bindEngine(impl: GenieEngine): LlmEngine
}
