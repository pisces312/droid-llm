package io.github.pisces312.droidllm.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.pisces312.droidllm.common.model.DataStoreModelPathStore
import io.github.pisces312.droidllm.common.model.FileFormatValidator
import io.github.pisces312.droidllm.common.model.ModelFormatValidator
import io.github.pisces312.droidllm.common.model.ModelPathStore
import io.github.pisces312.droidllm.common.settings.AppSettingsStore
import io.github.pisces312.droidllm.common.settings.DataStoreAppSettingsStore
import io.github.pisces312.droidllm.engineapi.LlmEngine
import io.github.pisces312.droidllm.engineapi.fake.FakeEngine
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindings {

    @Binds
    @Singleton
    abstract fun bindModelPathStore(impl: DataStoreModelPathStore): ModelPathStore

    @Binds
    @Singleton
    abstract fun bindAppSettingsStore(impl: DataStoreAppSettingsStore): AppSettingsStore

    @Binds
    abstract fun bindValidator(impl: FileFormatValidatorImpl): ModelFormatValidator
}

@Module
@InstallIn(SingletonComponent::class)
object EngineProviders {

    @Provides
    @IntoSet
    fun provideFakeEngine(): LlmEngine = FakeEngine()
}

@Singleton
class FileFormatValidatorImpl @Inject constructor() :
    ModelFormatValidator by FileFormatValidator
