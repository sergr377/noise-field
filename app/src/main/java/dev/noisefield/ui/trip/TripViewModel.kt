package dev.noisefield.ui.trip

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.noisefield.Graph
import dev.noisefield.data.JsonCodec
import dev.noisefield.data.Measurement
import dev.noisefield.data.Repository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

data class TripUi(
    val dayStartMs: Long = Repository.dayStart(System.currentTimeMillis()),
    val measurements: List<Measurement> = emptyList(),
    val hasCalibration: Boolean = true,
    val message: String? = null,
) {
    val count: Int get() = measurements.size

    /** Полная пара — та, где сняты обе роли: и фасад, и двор. */
    val completePairs: Int
        get() = measurements
            .filter { !it.pairId.isNullOrBlank() }
            .groupBy { it.pairId }
            .count { (_, group) ->
                group.any { it.pairRole == Measurement.ROLE_FACADE } &&
                    group.any { it.pairRole == Measurement.ROLE_YARD }
            }

    val flagged: Int
        get() = measurements.count { JsonCodec.decodeStrings(it.flagsJson).isNotEmpty() }

    val isToday: Boolean
        get() = dayStartMs >= Repository.dayStart(System.currentTimeMillis())
}

class TripViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = Graph.repository

    private val _ui = MutableStateFlow(TripUi())
    val ui: StateFlow<TripUi> = _ui.asStateFlow()

    /** Готовый Intent шаринга: экран сам его запускает. */
    private val _share = MutableStateFlow<Intent?>(null)
    val share: StateFlow<Intent?> = _share.asStateFlow()

    private var dayJob: Job? = null

    init {
        observeDay(_ui.value.dayStartMs)
        viewModelScope.launch {
            repository.activeCalibration.collect { calibration ->
                _ui.value = _ui.value.copy(hasCalibration = calibration != null)
            }
        }
    }

    private fun observeDay(dayStartMs: Long) {
        dayJob?.cancel()
        dayJob = viewModelScope.launch {
            repository.measurementsOfDay(dayStartMs).collect { list ->
                _ui.value = _ui.value.copy(measurements = list)
            }
        }
    }

    fun shiftDay(days: Int) {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = _ui.value.dayStartMs
        calendar.add(Calendar.DAY_OF_YEAR, days)
        val next = Repository.dayStart(calendar.timeInMillis)
        _ui.value = _ui.value.copy(dayStartMs = next, measurements = emptyList())
        observeDay(next)
    }

    fun export() {
        viewModelScope.launch {
            val result = runCatching { Graph.exporter.exportDay(_ui.value.dayStartMs) }
            result.fold(
                onSuccess = { export ->
                    // Повторы point_id не проверяются намеренно: контрольный
                    // повторный замер тех же точек — часть протокола, а связи
                    // в CSV идут по суррогатному id.
                    _share.value = export.intent
                },
                onFailure = { e ->
                    _ui.value = _ui.value.copy(message = e.message ?: "не удалось выгрузить")
                },
            )
        }
    }

    fun shareConsumed() { _share.value = null }

    fun dismissMessage() { _ui.value = _ui.value.copy(message = null) }
}
