package dev.noisefield.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CalibrationDao {
    @Insert
    suspend fun insert(calibration: Calibration): Long

    @Query("SELECT * FROM calibration ORDER BY createdAt DESC LIMIT 1")
    suspend fun active(): Calibration?

    /** Активная калибровка — последняя сохранённая. */
    @Query("SELECT * FROM calibration ORDER BY createdAt DESC LIMIT 1")
    fun activeFlow(): Flow<Calibration?>

    @Query("SELECT * FROM calibration WHERE id = :id")
    suspend fun byId(id: Long): Calibration?

    @Query("SELECT * FROM calibration ORDER BY createdAt DESC")
    suspend fun all(): List<Calibration>
}

@Dao
interface MeasurementDao {
    @Insert
    suspend fun insert(measurement: Measurement): Long

    @Update
    suspend fun update(measurement: Measurement)

    @Delete
    suspend fun delete(measurement: Measurement)

    @Query("DELETE FROM measurement WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM measurement WHERE id = :id")
    suspend fun byId(id: Long): Measurement?

    @Query("SELECT * FROM measurement WHERE id = :id")
    fun byIdFlow(id: Long): Flow<Measurement?>

    @Query("SELECT * FROM measurement ORDER BY startedAt DESC LIMIT 1")
    suspend fun last(): Measurement?

    @Query("SELECT * FROM measurement WHERE startedAt >= :fromMs AND startedAt < :toMs ORDER BY startedAt ASC")
    fun betweenFlow(fromMs: Long, toMs: Long): Flow<List<Measurement>>

    @Query("SELECT * FROM measurement WHERE startedAt >= :fromMs AND startedAt < :toMs ORDER BY startedAt ASC")
    suspend fun between(fromMs: Long, toMs: Long): List<Measurement>

    @Query("SELECT DISTINCT startedAt FROM measurement ORDER BY startedAt DESC")
    fun allStartsFlow(): Flow<List<Long>>

    @Query("SELECT * FROM measurement ORDER BY startedAt ASC")
    suspend fun all(): List<Measurement>
}

@Dao
interface VehicleEventDao {
    @Insert
    suspend fun insertAll(events: List<VehicleEvent>)

    @Query("SELECT * FROM vehicle_event WHERE measurementId = :measurementId ORDER BY tMs ASC")
    suspend fun forMeasurement(measurementId: Long): List<VehicleEvent>

    @Query("SELECT * FROM vehicle_event WHERE measurementId IN (:ids) ORDER BY measurementId ASC, tMs ASC")
    suspend fun forMeasurements(ids: List<Long>): List<VehicleEvent>

    @Query("SELECT COUNT(*) FROM vehicle_event WHERE measurementId = :measurementId AND heavy = 0")
    suspend fun countLight(measurementId: Long): Int

    @Query("SELECT COUNT(*) FROM vehicle_event WHERE measurementId = :measurementId AND heavy = 1")
    suspend fun countHeavy(measurementId: Long): Int

    @Query("DELETE FROM vehicle_event WHERE measurementId = :measurementId")
    suspend fun deleteForMeasurement(measurementId: Long)
}
