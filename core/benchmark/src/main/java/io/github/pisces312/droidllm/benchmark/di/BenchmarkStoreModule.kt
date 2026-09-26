package io.github.pisces312.droidllm.benchmark.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.pisces312.droidllm.common.bench.BenchmarkDao
import io.github.pisces312.droidllm.common.bench.ResultStoreDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BenchmarkStoreModule {

    @Provides
    @Singleton
    fun provideResultStore(@ApplicationContext context: Context): ResultStoreDatabase {
        return Room.databaseBuilder(context, ResultStoreDatabase::class.java, "droid-llm-bench.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Provides
    fun provideBenchmarkDao(db: ResultStoreDatabase): BenchmarkDao = db.benchmarkDao()
}
