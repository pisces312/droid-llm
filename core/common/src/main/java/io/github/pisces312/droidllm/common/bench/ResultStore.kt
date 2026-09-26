package io.github.pisces312.droidllm.common.bench

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "benchmark_runs")
data class BenchmarkRunEntity(
    @PrimaryKey val runId: String,
    val timestampMs: Long,
    val engineId: String,
    val modelName: String,
    val modelPath: String,
    val quantHint: String?,
    val caseId: String,
    val warmup: Int,
    val runs: Int,
    val loadMs: Long?,
    val ttftMs: Long?,
    val prefillTps: Double?,
    val decodeTps: Double?,
    val perTokenMsP50: Double?,
    val rssMbBaseline: Long?,
    val rssMbLoad: Long?,
    val rssMbPeak: Long?,
    val tempC: Double?,
    val soc: String?,
    val sdkInt: Int,
    val payloadJson: String,
)

@Dao
interface BenchmarkDao {
    @Insert
    suspend fun insert(run: BenchmarkRunEntity)

    @Query("SELECT * FROM benchmark_runs ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<BenchmarkRunEntity>>

    @Query("DELETE FROM benchmark_runs")
    suspend fun clear()
}

@Database(entities = [BenchmarkRunEntity::class], version = 1, exportSchema = false)
abstract class ResultStoreDatabase : RoomDatabase() {
    abstract fun benchmarkDao(): BenchmarkDao
}
