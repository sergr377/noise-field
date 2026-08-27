package dev.noisefield.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Калибровка устройства. Привязана к конкретному аппарату, конкретному микрофону
 * и конкретному источнику записи: смена любого из трёх требует новой калибровки,
 * а старые замеры остаются привязанными к своей (см. спецификацию, §8).
 */
@Entity(tableName = "calibration")
data class Calibration(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val device: String,
    val audioSource: String,
    val sampleRate: Int,
    val offsetDb: Double,
    val spreadDb: Double,
    val pairsJson: String,
    val agcDisabled: Boolean,
    val nsDisabled: Boolean,
    val aecDisabled: Boolean,
)

/**
 * Замер. Все акустические величины хранятся УЖЕ с применённым [Calibration.offsetDb]:
 * один способ ошибиться меньше. Сырое значение восстанавливается вычитанием офсета
 * из связанной калибровки.
 */
@Entity(
    tableName = "measurement",
    indices = [Index("calibrationId"), Index("startedAt")],
)
data class Measurement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val durationSec: Int,
    val calibrationId: Long,

    // акустика
    val laeq: Double,
    val laMax: Double,
    val la90: Double,
    val la50: Double,
    val la10: Double,
    val clipRatio: Double,

    // геопозиция
    val lat: Double,
    val lon: Double,
    val gpsAccuracyM: Double,

    // ряды
    val laeqPerSecJson: String,
    val octavesPerSecJson: String? = null,

    // поля карточки точки
    val pointId: String,
    val pairId: String? = null,
    val pairRole: String? = null,
    val distRoadM: Double? = null,
    val distFacadeM: Double? = null,
    val windMs: Double? = null,
    val surface: String = SURFACE_DRY,
    val notes: String? = null,

    val flagsJson: String,
) {
    companion object {
        const val SURFACE_DRY = "dry"
        const val SURFACE_WET = "wet"
        const val ROLE_FACADE = "facade"
        const val ROLE_YARD = "yard"
    }
}

/**
 * Одно нажатие счётчика. Хранятся события, а не суммы: из меток времени выводится
 * и сумма, и интенсивность по минутам, и распределение по ходу замера. Обратно из
 * числа — ничего.
 */
@Entity(
    tableName = "vehicle_event",
    indices = [Index("measurementId")],
)
data class VehicleEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val measurementId: Long,
    val tMs: Long,
    val heavy: Boolean,
)

/** Флаги замера. Флаг не выбрасывает данные — он попадает в CSV и в список выезда. */
object Flags {
    const val CLIP = "clip"
    const val GPS_POOR = "gps_poor"
    const val SHORT = "short"
    const val WIND = "wind"
}
