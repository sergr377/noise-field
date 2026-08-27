package dev.noisefield.ui.measure

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.noisefield.Graph
import dev.noisefield.data.Calibration
import dev.noisefield.data.Measurement
import dev.noisefield.data.PointIds
import dev.noisefield.data.Repository
import dev.noisefield.session.CaptureKind
import dev.noisefield.session.CaptureService
import dev.noisefield.session.Fix
import dev.noisefield.session.LocationWatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Плановая длительность: пресеты 10 / 15 / 30 минут и «без предела» (§3.2). */
enum class DurationPreset(val label: String, val seconds: Int?) {
    TEN("10 мин", 10 * 60),
    FIFTEEN("15 мин", 15 * 60),
    THIRTY("30 мин", 30 * 60),
    OPEN("Без предела", null);

    companion object {
        val DEFAULT = FIFTEEN
    }
}

data class MeasureUi(
    val calibration: Calibration? = null,
    val calibrationLoaded: Boolean = false,
    val fix: Fix? = null,
    val gpsEnabled: Boolean = true,
    val preset: DurationPreset = DurationPreset.DEFAULT,
    /** ID, который получит замер: то же правило, что при сохранении. */
    val predictedPointId: String = PointIds.DEFAULT,
    val predictedRole: String? = null,
    val predictedPair: String? = null,
) {
    val accuracyM: Double? get() = fix?.accuracyM?.takeIf { it.isFinite() && it < 1e6 }

    val accuracyGood: Boolean
        get() = accuracyM != null && accuracyM!! <= Repository.GPS_ACCURACY_LIMIT_M

    /**
     * Старт заблокирован, пока нет калибровки или пока точность хуже 10 м.
     * Это не придирка: разрешение сетки приёмников в модели местами десятки метров.
     */
    val canStart: Boolean get() = calibration != null && accuracyGood

    /**
     * Активная калибровка нелинейна. Показывается чипом и на замере тоже:
     * иначе о проблеме узнаёшь только при обработке, когда переснимать поздно.
     */
    val calibrationNonlinear: Boolean
        get() = calibration != null && calibration.spreadDb > Repository.SPREAD_LIMIT_DB

    val blockReason: String?
        get() = when {
            !calibrationLoaded -> "Проверяем калибровку…"
            calibration == null -> "Нет активной калибровки. Замеры без неё не разрешены."
            !gpsEnabled -> "GPS выключен в системе."
            accuracyM == null -> "Ждём фикс GPS…"
            !accuracyGood -> "Точность ±" + accuracyM!!.toInt() + " м, нужно не хуже ±10 м."
            else -> null
        }
}

class MeasureViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = Graph.repository
    private val watcher = LocationWatcher(app)

    private val _ui = MutableStateFlow(MeasureUi())
    val ui: StateFlow<MeasureUi> = _ui.asStateFlow()

    init {
        _ui.value = _ui.value.copy(gpsEnabled = watcher.gpsEnabled)
        watcher.start { fix -> _ui.value = _ui.value.copy(fix = fix) }
        viewModelScope.launch {
            val calibration = repository.activeCalibrationNow()
            val previous: Measurement? = repository.lastMeasurement()
            _ui.value = _ui.value.copy(
                calibration = calibration,
                calibrationLoaded = true,
                predictedPointId = PointIds.next(previous?.pointId),
                predictedRole = previous?.pairRole,
                predictedPair = previous?.pairId,
            )
        }
    }

    fun setPreset(preset: DurationPreset) {
        _ui.value = _ui.value.copy(preset = preset)
    }

    fun start() {
        val state = _ui.value
        val calibration = state.calibration ?: return
        if (!state.canStart) return
        CaptureService.start(
            context = getApplication(),
            kind = CaptureKind.MEASUREMENT,
            plannedSec = state.preset.seconds,
            offsetDb = calibration.offsetDb,
            calibrationId = calibration.id,
            fix = state.fix,
        )
        // Во время замера позицию ведёт сервис: две подписки на GPS одновременно
        // только жгут батарею.
        watcher.stop()
    }

    fun stop() {
        CaptureService.stop(getApplication())
    }

    override fun onCleared() {
        watcher.stop()
        super.onCleared()
    }
}
