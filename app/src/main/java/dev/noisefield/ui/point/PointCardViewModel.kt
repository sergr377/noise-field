package dev.noisefield.ui.point

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.noisefield.Graph
import dev.noisefield.data.JsonCodec
import dev.noisefield.data.Measurement
import dev.noisefield.data.VehicleCounts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PointCardUi(
    val loaded: Boolean = false,
    val measurement: Measurement? = null,
    val counts: VehicleCounts = VehicleCounts(0, 0),
    val pointId: String = "",
    val pairId: String = "",
    val roleIndex: Int = 0,
    val distRoad: String = "",
    val distFacade: String = "",
    val wind: String = "",
    val surfaceIndex: Int = 0,
    val notes: String = "",
    val confirmingDelete: Boolean = false,
    val deleted: Boolean = false,
) {
    val flags: List<String>
        get() = JsonCodec.decodeStrings(measurement?.flagsJson)

    /** Ветер больше 5 м/с поставит флаг wind при сохранении. */
    val windFlagged: Boolean
        get() = (wind.trim().replace(',', '.').toDoubleOrNull() ?: 0.0) > 5.0
}

class PointCardViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = Graph.repository

    private val _ui = MutableStateFlow(PointCardUi())
    val ui: StateFlow<PointCardUi> = _ui.asStateFlow()

    private var measurementId: Long = 0

    fun load(id: Long) {
        if (measurementId == id && _ui.value.loaded) return
        measurementId = id
        viewModelScope.launch {
            val measurement = repository.measurement(id)
            val counts = repository.counts(id)
            if (measurement == null) {
                _ui.value = _ui.value.copy(loaded = true, measurement = null)
                return@launch
            }
            _ui.value = PointCardUi(
                loaded = true,
                measurement = measurement,
                counts = counts,
                pointId = measurement.pointId,
                pairId = measurement.pairId.orEmpty(),
                roleIndex = if (measurement.pairRole == Measurement.ROLE_YARD) 1 else 0,
                distRoad = measurement.distRoadM?.let { trimNumber(it) }.orEmpty(),
                distFacade = measurement.distFacadeM?.let { trimNumber(it) }.orEmpty(),
                wind = measurement.windMs?.let { trimNumber(it) }.orEmpty(),
                surfaceIndex = if (measurement.surface == Measurement.SURFACE_WET) 1 else 0,
                notes = measurement.notes.orEmpty(),
            )
        }
    }

    fun setPointId(value: String) { _ui.value = _ui.value.copy(pointId = value) }
    fun setPairId(value: String) { _ui.value = _ui.value.copy(pairId = value) }
    fun setRole(index: Int) { _ui.value = _ui.value.copy(roleIndex = index) }
    fun setDistRoad(value: String) { _ui.value = _ui.value.copy(distRoad = value) }
    fun setDistFacade(value: String) { _ui.value = _ui.value.copy(distFacade = value) }
    fun setWind(value: String) { _ui.value = _ui.value.copy(wind = value) }
    fun setSurface(index: Int) { _ui.value = _ui.value.copy(surfaceIndex = index) }
    fun setNotes(value: String) { _ui.value = _ui.value.copy(notes = value) }

    fun save(onSaved: () -> Unit) {
        val state = _ui.value
        val measurement = state.measurement ?: return
        viewModelScope.launch {
            repository.saveCard(
                measurement.copy(
                    pointId = state.pointId.trim().ifEmpty { measurement.pointId },
                    pairId = state.pairId.trim().ifEmpty { null },
                    pairRole = if (state.roleIndex == 1) Measurement.ROLE_YARD else Measurement.ROLE_FACADE,
                    distRoadM = number(state.distRoad),
                    distFacadeM = number(state.distFacade),
                    windMs = number(state.wind),
                    surface = if (state.surfaceIndex == 1) Measurement.SURFACE_WET else Measurement.SURFACE_DRY,
                    notes = state.notes.trim().ifEmpty { null },
                )
            )
            onSaved()
        }
    }

    /** «Переснять» — с подтверждением: нажатие на улице бывает случайным (§5). */
    fun askDelete() { _ui.value = _ui.value.copy(confirmingDelete = true) }

    fun cancelDelete() { _ui.value = _ui.value.copy(confirmingDelete = false) }

    fun confirmDelete(onDeleted: () -> Unit) {
        val measurement = _ui.value.measurement ?: return
        viewModelScope.launch {
            repository.deleteMeasurement(measurement.id)
            _ui.value = _ui.value.copy(deleted = true, confirmingDelete = false)
            onDeleted()
        }
    }

    private fun number(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(java.util.Locale.US, "%.1f", value)
}
