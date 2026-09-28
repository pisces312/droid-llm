package io.github.pisces312.droidllm.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.pisces312.droidllm.api.ApiServerPreferences
import io.github.pisces312.droidllm.api.DefaultApiInferenceBridge
import io.github.pisces312.droidllm.apiserver.ApiInferenceBridge
import io.github.pisces312.droidllm.apiserver.ApiServerConfigSource
import io.github.pisces312.droidllm.common.model.DataStoreModelParamsStore
import io.github.pisces312.droidllm.common.model.DataStoreModelPathStore
import io.github.pisces312.droidllm.common.model.FileFormatValidator
import io.github.pisces312.droidllm.common.model.ModelFormatValidator
import io.github.pisces312.droidllm.common.model.ModelParamsStore
import io.github.pisces312.droidllm.common.model.ModelPathStore
import io.github.pisces312.droidllm.common.settings.AppSettingsStore
import io.github.pisces312.droidllm.common.settings.DataStoreAppSettingsStore
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
    abstract fun bindModelParamsStore(impl: DataStoreModelParamsStore): ModelParamsStore

    @Binds
    @Singleton
    abstract fun bindAppSettingsStore(impl: DataStoreAppSettingsStore): AppSettingsStore

    @Binds
    abstract fun bindValidator(impl: FileFormatValidatorImpl): ModelFormatValidator

    @Binds
    @Singleton
    abstract fun bindApiConfigSource(impl: ApiServerPreferences): ApiServerConfigSource

    @Binds
    @Singleton
    abstract fun bindApiInferenceBridge(impl: DefaultApiInferenceBridge): ApiInferenceBridge
}

@Singleton
class FileFormatValidatorImpl @Inject constructor() :
    ModelFormatValidator by FileFormatValidator
