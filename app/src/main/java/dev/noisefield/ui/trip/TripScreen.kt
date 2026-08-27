package dev.noisefield.ui.trip

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.noisefield.data.JsonCodec
import dev.noisefield.data.Measurement
import dev.noisefield.ui.common.Divider
import dev.noisefield.ui.common.GhostButton
import dev.noisefield.ui.common.LevelDot
import dev.noisefield.ui.common.MonoText
import dev.noisefield.ui.common.PrimaryButton
import dev.noisefield.ui.common.ScaleLegend
import dev.noisefield.ui.common.Tally
import dev.noisefield.ui.common.TopBar
import dev.noisefield.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TripScreen(
    onNewMeasurement: () -> Unit,
    onCalibration: () -> Unit,
    onOpenPoint: (Long) -> Unit,
    vm: TripViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsState()
    val share by vm.share.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(share) {
        share?.let {
            context.startActivity(it)
            vm.shareConsumed()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = dayTitle(ui.dayStartMs),
            sub = if (ui.isToday) "сегодня" else weekdayLabel(ui.dayStartMs),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            DayStep("← пред.") { vm.shiftDay(-1) }
            DayStep("след. →") { vm.shiftDay(1) }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Tally("Замеров", ui.count.toString())
            Tally("Пар", ui.completePairs.toString())
            Tally("С флагом", ui.flagged.toString(), if (ui.flagged > 0) Palette.Warn else Palette.Ink)
        }

        Spacer(Modifier.height(10.dp))
        ScaleLegend(Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(10.dp))

        if (ui.measurements.isEmpty()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "За этот день замеров нет",
                    fontSize = 13.sp,
                    color = Palette.Ink3,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                items(ui.measurements, key = { it.id }) { measurement ->
                    MeasurementRow(measurement) { onOpenPoint(measurement.id) }
                }
            }
        }

        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton("Экспорт CSV", enabled = ui.measurements.isNotEmpty()) { vm.export() }
            GhostButton("Новый замер", onClick = onNewMeasurement)
            GhostButton(
                text = if (ui.hasCalibration) "Калибровка" else "Калибровка — нужна перед замером",
                onClick = onCalibration,
            )
        }
    }

    ui.message?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            title = { Text("Экспорт") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("Понятно") } },
        )
    }
}

@Composable
private fun DayStep(label: String, onClick: () -> Unit) {
    Box(Modifier.clickable { onClick() }.padding(vertical = 4.dp)) {
        MonoText(label, size = 12, color = Palette.Ink2)
    }
}

@Composable
private fun MeasurementRow(measurement: Measurement, onClick: () -> Unit) {
    val flags = JsonCodec.decodeStrings(measurement.flagsJson)
    Column(Modifier.clickable { onClick() }) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LevelDot(Palette.forLevel(measurement.laeq))
            MonoText(measurement.pointId, modifier = Modifier.width(48.dp), size = 13)
            MonoText(fixed(measurement.laeq, 1), modifier = Modifier.width(46.dp), size = 13)
            Text(
                text = meta(measurement),
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
                color = Palette.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (flags.isNotEmpty()) {
                MonoText(
                    text = flagSummary(flags, measurement),
                    size = 10,
                    color = Palette.Warn,
                )
            }
        }
        Divider()
    }
}

private fun meta(measurement: Measurement): String {
    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(measurement.startedAt))
    val role = when (measurement.pairRole) {
        Measurement.ROLE_FACADE -> "фасад"
        Measurement.ROLE_YARD -> "двор"
        else -> null
    }
    return listOfNotNull(time, role).joinToString(" · ")
}

private fun flagSummary(flags: List<String>, measurement: Measurement): String = flags.joinToString(" ") {
    when (it) {
        "clip" -> "клип " + fixed(measurement.clipRatio * 100.0, 1) + "%"
        "wind" -> "ветер " + (measurement.windMs?.let { w -> fixed(w, 0) } ?: "")
        "gps_poor" -> "gps"
        "short" -> "коротко"
        else -> it
    }
}.trim()

private fun dayTitle(dayStartMs: Long): String =
    SimpleDateFormat("d MMMM", Locale("ru")).format(Date(dayStartMs))

private fun weekdayLabel(dayStartMs: Long): String =
    SimpleDateFormat("EEEE", Locale("ru")).format(Date(dayStartMs))

private fun fixed(value: Double, decimals: Int): String =
    if (value.isNaN() || value.isInfinite()) "—"
    else String.format(Locale.US, "%." + decimals + "f", value)
