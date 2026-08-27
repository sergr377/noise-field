package dev.noisefield.ui.point

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.noisefield.ui.common.Chip
import dev.noisefield.ui.common.ChipTone
import dev.noisefield.ui.common.Field
import dev.noisefield.ui.common.GhostButton
import dev.noisefield.ui.common.Lbl
import dev.noisefield.ui.common.MonoText
import dev.noisefield.ui.common.PrimaryButton
import dev.noisefield.ui.common.Segmented
import dev.noisefield.ui.common.TopBar
import dev.noisefield.ui.theme.Palette
import java.util.Locale

@Composable
fun PointCardScreen(
    measurementId: Long,
    onDone: () -> Unit,
    vm: PointCardViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsState()

    LaunchedEffect(measurementId) { vm.load(measurementId) }

    if (ui.loaded && ui.measurement == null) {
        // Замер уже удалён — возвращаемся к выезду, а не показываем пустую форму.
        LaunchedEffect(Unit) { onDone() }
        return
    }

    val measurement = ui.measurement

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "Замер готов",
            sub = measurement?.let { clock(it.durationSec) },
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (measurement != null) {
                Summary(
                    laeq = measurement.laeq,
                    light = ui.counts.light,
                    heavy = ui.counts.heavy,
                    accuracyM = measurement.gpsAccuracyM,
                )

                if (ui.flags.isNotEmpty() || ui.windFlagged) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ui.flags.forEach { Chip(flagLabel(it, measurement.clipRatio), ChipTone.BAD) }
                        if (ui.windFlagged && !ui.flags.contains("wind")) Chip("ветер", ChipTone.BAD)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Field(
                    label = "ID точки",
                    value = ui.pointId,
                    modifier = Modifier.weight(1f),
                    onValueChange = vm::setPointId,
                )
                Field(
                    label = "Пара",
                    value = ui.pairId,
                    modifier = Modifier.weight(1f),
                    placeholder = "03",
                    onValueChange = vm::setPairId,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Lbl("Роль в паре")
                Segmented(listOf("Фасад", "Двор"), ui.roleIndex, onSelect = vm::setRole)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Field(
                    label = "До дороги, м",
                    value = ui.distRoad,
                    modifier = Modifier.weight(1f),
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    onValueChange = vm::setDistRoad,
                )
                Field(
                    label = "До фасада, м",
                    value = ui.distFacade,
                    modifier = Modifier.weight(1f),
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    onValueChange = vm::setDistFacade,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Field(
                    label = "Ветер, м/с",
                    value = ui.wind,
                    modifier = Modifier.weight(1f),
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    onValueChange = vm::setWind,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Lbl("Покрытие")
                    Segmented(listOf("Сухо", "Мокро"), ui.surfaceIndex, onSelect = vm::setSurface)
                }
            }

            Field(
                label = "Заметка",
                value = ui.notes,
                placeholder = "что было рядом",
                onValueChange = vm::setNotes,
            )
        }

        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton("Сохранить точку") { vm.save(onDone) }
            GhostButton("Переснять") { vm.askDelete() }
        }
    }

    if (ui.confirmingDelete) {
        AlertDialog(
            onDismissRequest = vm::cancelDelete,
            title = { Text("Переснять точку?") },
            text = {
                Text(
                    "Замер будет удалён целиком: уровни, посекундный ряд и все нажатия " +
                        "счётчика. Восстановить его будет нечем."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmDelete(onDone) }) {
                    Text("Удалить замер", color = Palette.Warn)
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelDelete) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun Summary(laeq: Double, light: Int, heavy: Int, accuracyM: Double) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(3.dp)
                .height(46.dp)
                .background(Palette.forLevel(laeq))
        )
        Spacer(Modifier.width(11.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            MonoText(fixed(laeq, 1) + " дБ(A)", size = 20)
            Text(
                text = light.toString() + " легковых · " + heavy + " грузовых · " +
                    (if (accuracyM < 0) "без фикса" else "±" + fixed(accuracyM, 0) + " м"),
                fontSize = 12.sp,
                color = Palette.Ink2,
            )
        }
    }
}

private fun flagLabel(flag: String, clipRatio: Double): String = when (flag) {
    "clip" -> "клип " + fixed(clipRatio * 100.0, 1) + "%"
    "gps_poor" -> "gps"
    "short" -> "коротко"
    "wind" -> "ветер"
    else -> flag
}

private fun clock(seconds: Int): String =
    String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

private fun fixed(value: Double, decimals: Int): String =
    if (value.isNaN() || value.isInfinite()) "—"
    else String.format(Locale.US, "%." + decimals + "f", value)
