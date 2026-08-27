package dev.noisefield.data

import dev.noisefield.audio.LevelMath
import dev.noisefield.audio.NoiseTract
import dev.noisefield.session.CaptureState
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import java.util.TimeZone

/** Счёт машин по замеру. */
data class VehicleCounts(val light: Int, val heavy: Int) {
    val total: Int get() = light + heavy
}

class Repository(private val db: NoiseDb) {

    val activeCalibration: Flow<Calibration?> = db.calibrations().activeFlow()

    suspend fun activeCalibrationNow(): Calibration? = db.calibrations().active()

    suspend fun calibrationById(id: Long): Calibration? = db.calibrations().byId(id)

    suspend fun saveCalibration(calibration: Calibration): Long = db.calibrations().insert(calibration)

    suspend fun measurement(id: Long): Measurement? = db.measurements().byId(id)

    fun measurementFlow(id: Long): Flow<Measurement?> = db.measurements().byIdFlow(id)

    suspend fun lastMeasurement(): Measurement? = db.measurements().last()

    fun measurementsOfDay(dayStartMs: Long): Flow<List<Measurement>> =
        db.measurements().betweenFlow(dayStartMs, dayStartMs + DAY_MS)

    suspend fun measurementsOfDayNow(dayStartMs: Long): List<Measurement> =
        db.measurements().between(dayStartMs, dayStartMs + DAY_MS)

    fun allStarts(): Flow<List<Long>> = db.measurements().allStartsFlow()

    suspend fun counts(measurementId: Long): VehicleCounts = VehicleCounts(
        light = db.vehicleEvents().countLight(measurementId),
        heavy = db.vehicleEvents().countHeavy(measurementId),
    )

    suspend fun events(measurementId: Long): List<VehicleEvent> =
        db.vehicleEvents().forMeasurement(measurementId)

    suspend fun eventsFor(ids: List<Long>): List<VehicleEvent> =
        if (ids.isEmpty()) emptyList() else db.vehicleEvents().forMeasurements(ids)

    /**
     * Записывает завершённый замер и все нажатия счётчика.
     *
     * Поля карточки подставляются из предыдущего сохранённого замера, кроме ID точки —
     * он инкрементируется (§5). Флаг ветра здесь не ставится: он вычисляется при
     * сохранении карточки, когда значение уже подтверждено глазами.
     */
    suspend fun finishMeasurement(state: CaptureState): Long {
        val previous = db.measurements().last()
        val summary = LevelMath.summarize(state.series)
        val durationSec = state.series.size
        val clipRatio = state.clipRatio

        val position = state.position

        val flags = buildList {
            if (clipRatio > CLIP_FLAG_THRESHOLD) add(Flags.CLIP)
            val worst = state.worstAccuracyM
            if (worst == null || worst > GPS_ACCURACY_LIMIT_M) add(Flags.GPS_POOR)
            if (durationSec < SHORT_FLAG_SEC) add(Flags.SHORT)
        }

        val measurement = Measurement(
            startedAt = state.startedAt,
            durationSec = durationSec,
            calibrationId = state.calibrationId,
            laeq = summary.laeq,
            laMax = summary.laMax,
            la90 = summary.la90,
            la50 = summary.la50,
            la10 = summary.la10,
            clipRatio = clipRatio,
            // Координаты — медиана по фиксам точнее 10 м, точность — от лучшего фикса.
            lat = position?.lat ?: 0.0,
            lon = position?.lon ?: 0.0,
            gpsAccuracyM = normalizeAccuracy(state.bestAccuracyM),
            laeqPerSecJson = JsonCodec.encodeDoubles(state.series),
            // Аудио не сохраняется, поэтому октавы считаются на лету и хранятся
            // всегда: задним числом их не восстановить ничем, кроме новой поездки.
            octavesPerSecJson = state.octaves.takeIf { it.isNotEmpty() }
                ?.let { JsonCodec.encodeMatrix(it) },
            pointId = PointIds.next(previous?.pointId),
            pairId = previous?.pairId,
            pairRole = previous?.pairRole,
            distRoadM = previous?.distRoadM,
            distFacadeM = previous?.distFacadeM,
            windMs = previous?.windMs,
            surface = previous?.surface ?: Measurement.SURFACE_DRY,
            notes = previous?.notes,
            flagsJson = JsonCodec.encodeStrings(flags),
        )

        val id = db.measurements().insert(measurement)
        if (state.taps.isNotEmpty()) {
            db.vehicleEvents().insertAll(
                state.taps.map { VehicleEvent(measurementId = id, tMs = it.tMs, heavy = it.heavy) }
            )
        }
        return id
    }

    /**
     * Сохраняет карточку точки. Флаг ветра ставится здесь: раньше его поставить
     * неоткуда, ветер вводится руками уже после замера.
     */
    suspend fun saveCard(measurement: Measurement): Measurement {
        val flags = JsonCodec.decodeStrings(measurement.flagsJson).toMutableList()
        val windy = (measurement.windMs ?: 0.0) > WIND_FLAG_LIMIT_MS
        flags.remove(Flags.WIND)
        if (windy) flags.add(Flags.WIND)
        val updated = measurement.copy(flagsJson = JsonCodec.encodeStrings(flags))
        db.measurements().update(updated)
        return updated
    }

    /** «Переснять»: замер удаляется целиком вместе с событиями счётчика. */
    suspend fun deleteMeasurement(id: Long) {
        db.vehicleEvents().deleteForMeasurement(id)
        db.measurements().deleteById(id)
    }

    /** Калибровка тракта из живого состояния — то, что пишется вместе с офсетом. */
    fun calibrationFrom(
        device: String,
        tract: NoiseTract.Info,
        pairs: List<CalPair>,
        offsetDb: Double,
        spreadDb: Double,
    ) = Calibration(
        createdAt = System.currentTimeMillis(),
        device = device,
        audioSource = tract.audioSource,
        sampleRate = tract.sampleRate,
        offsetDb = offsetDb,
        spreadDb = spreadDb,
        pairsJson = JsonCodec.encodePairs(pairs),
        agcDisabled = tract.agcDisabled,
        nsDisabled = tract.nsDisabled,
        aecDisabled = tract.aecDisabled,
    )

    companion object {
        /** Точность без фикса или без оценки. В CSV такое поле уходит пустым. */
        const val ACCURACY_UNKNOWN = -1.0

        private fun normalizeAccuracy(value: Double?): Double =
            if (value == null || !value.isFinite() || value >= 1e6) ACCURACY_UNKNOWN else value

        const val DAY_MS = 24L * 60L * 60L * 1000L

        /** Пороги автоматических флагов, §3. */
        const val CLIP_FLAG_THRESHOLD = 0.001
        const val GPS_ACCURACY_LIMIT_M = 10.0
        const val SHORT_FLAG_SEC = 300
        const val WIND_FLAG_LIMIT_MS = 5.0

        /**
         * Разброс разниц по парам, выше которого офсет одним числом непригоден:
         * тракт нелинеен, скорее всего не отключился AGC (§4.3).
         */
        const val SPREAD_LIMIT_DB = 1.5

        /** Размах уровней между самой громкой и самой тихой парой калибровки. */
        const val CAL_RANGE_TARGET_DB = 20.0
        const val CAL_RANGE_WARN_DB = 15.0

        /** Начало местных суток, в которые попадает момент [atMs]. */
        fun dayStart(atMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
            val c = Calendar.getInstance(zone)
            c.timeInMillis = atMs
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
    }
}
