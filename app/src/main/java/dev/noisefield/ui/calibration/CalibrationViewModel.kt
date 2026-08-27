package dev.noisefield.ui.calibration

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.noisefield.Graph
import dev.noisefield.audio.NoiseTract
import dev.noisefield.data.CalPair
import dev.noisefield.data.Repository
import dev.noisefield.session.CaptureKind
import dev.noisefield.session.CaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Строка калибровки: уровень, показание прибора и снятое приложением значение. */
data class CalRow(
    val title: String,
    val hint: String,
    val refText: String = "",
    val appDb: Double? = null,
) {
    val refDb: Double? get() = refText.trim().replace(',', '.').toDoubleOrNull()
    val complete: Boolean get() = refDb != null && appDb != null
    val deltaDb: Double? get() {
        val ref = refDb ?: return null
        val app = appDb ?: return null
        return ref - app
    }
}

data class CalibrationUi(
    val rows: List<CalRow> = DEFAULT_ROWS,
    val tract: NoiseTract.Info? = null,
    val tractError: String? = null,
    val probingRow: Int? = null,
    val saved: Boolean = false,
) {
    val pairs: List<CalPair>
        get() = rows.mapNotNull { row ->
            val ref = row.refDb ?: return@mapNotNull null
            val app = row.appDb ?: return@mapNotNull null
            CalPair(ref, app)
        }

    val deltas: List<Double> get() = pairs.map { it.deltaDb }

    /** Офсет — среднее по парам. */
    val offsetDb: Double? get() = deltas.takeIf { it.isNotEmpty() }?.average()

    /** Разброс — размах разниц по парам. Он важнее самого офсета. */
    val spreadDb: Double?
        get() = deltas.takeIf { it.size >= 2 }?.let { (it.max() - it.min()) }

    /**
     * Разброс больше 1.5 дБ означает, что тракт нелинеен — скорее всего не
     * отключился AGC. Офсет одним числом в этом случае непригоден (§4.3).
     */
    val spreadTooWide: Boolean get() = (spreadDb ?: 0.0) > Repository.SPREAD_LIMIT_DB

    /**
     * Размах уровней между самой громкой и самой тихой парой.
     *
     * Три пары, снятые на одном уровне, линейность не проверяют вовсе: офсет
     * тогда измерен в точке, а наклон — нет. Считается по показаниям прибора:
     * они и задают уровень, на котором снята пара.
     */
    val rangeDb: Double?
        get() = pairs.takeIf { it.size >= 2 }?.let { p -> p.maxOf { it.refDb } - p.minOf { it.refDb } }

    /** Размах меньше 15 дБ — калибровка проверяет только точку, а не наклон. */
    val rangeTooNarrow: Boolean
        get() = rangeDb?.let { it < Repository.CAL_RANGE_WARN_DB } ?: false

    /** Размах меньше желаемых 20 дБ, но ещё не критично. */
    val rangeBelowTarget: Boolean
        get() = rangeDb?.let { it < Repository.CAL_RANGE_TARGET_DB } ?: false

    val canSave: Boolean get() = pairs.size >= REQUIRED_PAIRS && tract != null

    companion object {
        const val REQUIRED_PAIRS = 3

        val DEFAULT_ROWS = listOf(
            CalRow("Громко", "у магистрали"),
            CalRow("Средне", "обычная улица"),
            CalRow("Тихо", "двор"),
        )
    }
}

class CalibrationViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = Graph.repository

    private val _ui = MutableStateFlow(CalibrationUi())
    val ui: StateFlow<CalibrationUi> = _ui.asStateFlow()

    init {
        readTractInfo()
    }

    /**
     * Короткое открытие тракта, чтобы показать чипы состояния до первой пробы:
     * какой источник фактически достался, отключился ли AGC, какая частота.
     */
    private fun readTractInfo() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val tract = NoiseTract(getApplication())
                try {
                    Result.success(tract.open())
                } catch (e: Exception) {
                    Result.failure(e)
                } finally {
                    tract.close()
                }
            }
            _ui.value = result.fold(
                onSuccess = { _ui.value.copy(tract = it, tractError = null) },
                onFailure = { _ui.value.copy(tract = null, tractError = it.message ?: "микрофон недоступен") },
            )
        }
    }

    fun setRef(index: Int, text: String) {
        _ui.value = _ui.value.copy(
            rows = _ui.value.rows.mapIndexed { i, row -> if (i == index) row.copy(refText = text) else row }
        )
    }

    /** Замер пары: пять минут тем же трактом, каким пойдут настоящие замеры. */
    fun startProbe(index: Int) {
        if (_ui.value.probingRow != null) return
        _ui.value = _ui.value.copy(probingRow = index)
        CaptureService.start(
            context = getApplication(),
            kind = CaptureKind.CALIBRATION,
            plannedSec = PROBE_SECONDS,
            offsetDb = 0.0,
            calibrationId = 0L,
            fix = null,
        )
    }

    fun stopProbe() {
        CaptureService.stop(getApplication())
    }

    /** Результат пробы кладётся в строку: это «Прил.» в макете. */
    fun applyProbe(levelDb: Double, tract: NoiseTract.Info?) {
        val index = _ui.value.probingRow ?: return
        _ui.value = _ui.value.copy(
            rows = _ui.value.rows.mapIndexed { i, row ->
                if (i == index) row.copy(appDb = levelDb) else row
            },
            tract = tract ?: _ui.value.tract,
            probingRow = null,
        )
    }

    fun cancelProbe() {
        _ui.value = _ui.value.copy(probingRow = null)
    }

    fun addRow() {
        _ui.value = _ui.value.copy(
            rows = _ui.value.rows + CalRow("Ещё пара", "любой уровень")
        )
    }

    /** Сохранённая калибровка становится активной, её id пишется в каждый замер. */
    fun save(onSaved: () -> Unit) {
        val state = _ui.value
        val tract = state.tract ?: return
        val offset = state.offsetDb ?: return
        viewModelScope.launch {
            repository.saveCalibration(
                repository.calibrationFrom(
                    device = CaptureService.deviceName(),
                    tract = tract,
                    pairs = state.pairs,
                    offsetDb = offset,
                    spreadDb = state.spreadDb ?: 0.0,
                )
            )
            _ui.value = _ui.value.copy(saved = true)
            onSaved()
        }
    }

    companion object {
        /** Пять минут на пару (§4.1). */
        const val PROBE_SECONDS = 300
    }
}
