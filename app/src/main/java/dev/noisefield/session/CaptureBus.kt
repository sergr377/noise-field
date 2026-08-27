package dev.noisefield.session

import dev.noisefield.audio.LevelMath
import dev.noisefield.audio.NoiseTract
import dev.noisefield.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Что именно пишет тракт: замер точки или пятиминутная проба для калибровки. */
enum class CaptureKind { MEASUREMENT, CALIBRATION }

enum class CapturePhase { IDLE, RUNNING, SAVING, DONE, FAILED }

/** Нажатие счётчика, ещё не записанное в базу. */
data class VehicleTap(val tMs: Long, val heavy: Boolean)

/**
 * Живое состояние захвата. Широкополосные уровни здесь уже с офсетом — офсет
 * прикладывается при укладке секунды в ряд, чтобы на экране и в базе было одно
 * и то же число.
 */
data class CaptureState(
    val phase: CapturePhase = CapturePhase.IDLE,
    val kind: CaptureKind = CaptureKind.MEASUREMENT,
    val startedAt: Long = 0L,
    val plannedSec: Int? = null,
    val series: List<Double> = emptyList(),
    /** Октавные полосы по секундам, порядок значений — [dev.noisefield.audio.OctaveBank.CENTERS]. */
    val octaves: List<DoubleArray> = emptyList(),
    val clippedSamples: Long = 0L,
    val totalSamples: Long = 0L,
    val taps: List<VehicleTap> = emptyList(),
    /** Все фиксы за замер: из них выводятся и координаты, и обе оценки точности. */
    val fixes: List<Fix> = emptyList(),
    val tract: NoiseTract.Info? = null,
    val offsetDb: Double = 0.0,
    val calibrationId: Long = 0L,
    /** id сохранённого замера — появляется в фазе DONE. */
    val savedMeasurementId: Long? = null,
    val error: String? = null,
) {
    val elapsedSec: Int get() = series.size
    val running: Boolean get() = phase == CapturePhase.RUNNING

    val laeq: Double get() = LevelMath.energyAverage(series)
    val laMax: Double get() = series.maxOrNull() ?: LevelMath.FLOOR_DB
    val la90: Double get() = LevelMath.percentileExceeded(series, 90.0)

    val clipRatio: Double
        get() = if (totalSamples <= 0L) 0.0 else clippedSamples.toDouble() / totalSamples.toDouble()

    val lightCount: Int get() = taps.count { !it.heavy }
    val heavyCount: Int get() = taps.count { it.heavy }

    /** Последний фикс — то, что показывается чипом GPS прямо сейчас. */
    val lastFix: Fix? get() = fixes.lastOrNull()

    /** Точность лучшего фикса: она и уходит в `gps_accuracy_m`. */
    val bestAccuracyM: Double? get() = fixes.minOfOrNull { it.accuracyM }

    /** Точность худшего фикса: она решает судьбу флага `gps_poor`. */
    val worstAccuracyM: Double? get() = fixes.maxOfOrNull { it.accuracyM }

    /**
     * Координаты точки — медиана по фиксам точнее 10 м.
     *
     * Стоишь на месте 15 минут, фиксов накапливается много, и медиана заметно
     * устойчивее любого одиночного: она не тянется за случайным выбросом.
     * Широта и долгота медианятся независимо — для неподвижной точки этого
     * достаточно.
     */
    val position: Fix?
        get() {
            if (fixes.isEmpty()) return null
            val good = fixes.filter { it.accuracyM <= Repository.GPS_ACCURACY_LIMIT_M }
            val source = good.ifEmpty { fixes }
            return Fix(
                lat = median(source.map { it.lat }),
                lon = median(source.map { it.lon }),
                accuracyM = bestAccuracyM ?: Double.MAX_VALUE,
                atMs = source.last().atMs,
            )
        }

    /** Интенсивность в единицах в минуту по фактически прошедшему времени. */
    fun perMinute(heavy: Boolean): Double {
        val minutes = elapsedSec / 60.0
        if (minutes <= 0.0) return 0.0
        return taps.count { it.heavy == heavy } / minutes
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}

/**
 * Общая шина между сервисом захвата и экранами. Одно приложение, один
 * пользователь, один замер за раз — отдельный биндинг к сервису тут был бы
 * церемонией без смысла.
 */
object CaptureBus {

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    internal fun update(transform: (CaptureState) -> CaptureState) {
        _state.value = transform(_state.value)
    }

    internal fun set(state: CaptureState) {
        _state.value = state
    }

    /**
     * Нажатие счётчика. Метка времени берётся от начала замера, а не от системных
     * часов: из меток выводится и сумма, и интенсивность по минутам.
     */
    fun tap(heavy: Boolean): Boolean {
        val current = _state.value
        if (!current.running) return false
        val tMs = System.currentTimeMillis() - current.startedAt
        _state.value = current.copy(taps = current.taps + VehicleTap(tMs, heavy))
        return true
    }

    /** Отмена последнего нажатия — долгий тап по счётчику. */
    fun undoTap(heavy: Boolean): Boolean {
        val current = _state.value
        if (!current.running) return false
        val index = current.taps.indexOfLast { it.heavy == heavy }
        if (index < 0) return false
        _state.value = current.copy(taps = current.taps.toMutableList().also { it.removeAt(index) })
        return true
    }

    /** Сброс после того, как экран забрал результат. */
    fun clear() {
        _state.value = CaptureState()
    }
}
