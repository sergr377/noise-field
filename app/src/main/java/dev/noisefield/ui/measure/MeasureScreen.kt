package dev.noisefield.ui.measure

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.noisefield.session.CaptureBus
import dev.noisefield.session.CapturePhase
import dev.noisefield.session.CaptureState
import dev.noisefield.ui.common.Chip
import dev.noisefield.ui.common.ChipTone
import dev.noisefield.ui.common.GhostButton
import dev.noisefield.ui.common.Lbl
import dev.noisefield.ui.common.MonoText
import dev.noisefield.ui.common.PrimaryButton
import dev.noisefield.ui.common.ProgressTrack
import dev.noisefield.ui.common.Sparkline
import dev.noisefield.ui.common.StopButton
import dev.noisefield.ui.common.TapAndHold
import dev.noisefield.ui.common.TopBar
import dev.noisefield.ui.theme.Palette
import java.util.Locale

@Composable
fun MeasureScreen(
    onFinished: (Long) -> Unit,
    onCalibration: () -> Unit,
    onBack: () -> Unit,
    vm: MeasureViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsState()
    val capture by CaptureBus.state.collectAsState()

    // Замер закончился — сохранённый замер уходит на карточку точки (§3.7).
    LaunchedEffect(capture.phase, capture.savedMeasurementId) {
        val id = capture.savedMeasurementId
        if (capture.phase == CapturePhase.DONE && id != null) {
            CaptureBus.clear()
            onFinished(id)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (capture.running || capture.phase == CapturePhase.SAVING) {
            TopBar(
                title = ui.predictedPointId + roleSuffix(ui.predictedRole),
                sub = ui.predictedPair?.let { "пара " + it },
            )
            RunningBody(capture, nonlinearCalibration = ui.calibrationNonlinear, onStop = vm::stop)
        } else {
            TopBar(title = "Замер", sub = ui.predictedPointId)
            IdleBody(
                ui = ui,
                error = capture.error,
                onPreset = vm::setPreset,
                onStart = vm::start,
                onCalibration = onCalibration,
                onBack = onBack,
            )
        }
    }
}

// ---------- до старта ----------

@Composable
private fun IdleBody(
    ui: MeasureUi,
    error: String?,
    onPreset: (DurationPreset) -> Unit,
    onStart: () -> Unit,
    onCalibration: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Lbl("Точность GPS")
            val accuracy = ui.accuracyM
            MonoText(
                text = if (accuracy == null) "—" else "±" + String.format(Locale.US, "%.0f", accuracy) + " м",
                size = 38,
                color = if (ui.accuracyGood) Palette.Ok else Palette.Warn,
            )
        }

        val calibration = ui.calibration
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(
                text = if (ui.gpsEnabled) "GPS включён" else "GPS выключен",
                tone = if (ui.gpsEnabled) ChipTone.GOOD else ChipTone.BAD,
            )
            Chip(
                text = if (calibration != null) "офсет " + signed(calibration.offsetDb) else "нет калибровки",
                tone = if (calibration != null) ChipTone.GOOD else ChipTone.BAD,
            )
            if (ui.calibrationNonlinear) Chip("калибровка нелинейна", ChipTone.BAD)
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Lbl("Плановая длительность")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DurationPreset.entries.forEach { preset ->
                    PresetChip(
                        label = preset.label,
                        selected = preset == ui.preset,
                        modifier = Modifier.weight(1f),
                    ) { onPreset(preset) }
                }
            }
        }

        val reason = error ?: ui.blockReason
        if (reason != null) {
            Text(
                text = reason,
                fontSize = 13.sp,
                color = if (ui.canStart) Palette.Ink2 else Palette.Warn,
            )
        }

        Spacer(Modifier.weight(1f))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Начать замер", enabled = ui.canStart, onClick = onStart)
            if (calibration == null) {
                GhostButton("Калибровка", onClick = onCalibration)
            }
            GhostButton("Назад", onClick = onBack)
        }
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Palette.Ink else Color.Transparent)
            .border(1.dp, if (selected) Palette.Ink else Palette.Rule, RoundedCornerShape(7.dp))
            .heightIn(min = 44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.padding(horizontal = 4.dp, vertical = 10.dp)) {
            Text(
                text = label,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color.White else Palette.Ink,
            )
        }
    }
}

// ---------- во время замера ----------

@Composable
private fun RunningBody(capture: CaptureState, nonlinearCalibration: Boolean, onStop: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val levelColor = Palette.forLevel(capture.laeq)

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Накопленный LAeq, не мгновенный: это и есть результат замера.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            MonoText(
                text = if (capture.elapsedSec == 0) "—" else fixed(capture.laeq, 1),
                size = 72,
                weight = FontWeight.Medium,
                color = levelColor,
            )
            Spacer(Modifier.height(4.dp))
            Lbl("LAeq дБ(A)")
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        ) {
            Stat("LAmax", if (capture.elapsedSec == 0) "—" else fixed(capture.laMax, 1))
            Stat("LA90", if (capture.elapsedSec == 0) "—" else fixed(capture.la90, 1))
            Stat("Клип", fixed(capture.clipRatio * 100.0, 1) + "%")
        }

        Sparkline(
            values = capture.series,
            color = levelColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
        )

        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MonoText(clock(capture.elapsedSec), size = 16)
                val planned = capture.plannedSec
                MonoText(
                    text = if (planned == null) "без предела" else "из " + clock(planned),
                    size = 12,
                    color = Palette.Ink3,
                )
            }
            Spacer(Modifier.height(6.dp))
            val planned = capture.plannedSec
            ProgressTrack(
                fraction = if (planned == null || planned == 0) 0f
                else capture.elapsedSec.toFloat() / planned.toFloat()
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            CounterButton(
                count = capture.lightCount,
                name = "Легковые",
                rate = capture.perMinute(heavy = false),
                heavy = false,
                modifier = Modifier.weight(1f),
                onTap = {
                    if (CaptureBus.tap(heavy = false)) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onUndo = {
                    if (CaptureBus.undoTap(heavy = false)) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
            )
            CounterButton(
                count = capture.heavyCount,
                name = "Грузовые",
                rate = capture.perMinute(heavy = true),
                heavy = true,
                modifier = Modifier.weight(1f),
                onTap = {
                    if (CaptureBus.tap(heavy = true)) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onUndo = {
                    if (CaptureBus.undoTap(heavy = true)) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val accuracy = capture.lastFix?.accuracyM
            val good = accuracy != null && accuracy <= 10.0
            Chip(
                text = if (accuracy == null) "GPS —" else "GPS ±" + String.format(Locale.US, "%.0f", accuracy) + " м",
                tone = if (good) ChipTone.GOOD else ChipTone.BAD,
            )
            Chip("экран можно гасить")
            if (nonlinearCalibration) Chip("калибровка нелинейна", ChipTone.BAD)
        }

        Spacer(Modifier.weight(1f))

        if (capture.phase == CapturePhase.SAVING) {
            PrimaryButton("Сохраняем…", enabled = false) {}
        } else {
            StopButton("Завершить замер", onClick = onStop)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Lbl(label)
        Spacer(Modifier.height(2.dp))
        MonoText(value, size = 14)
    }
}

/**
 * Кнопка счётчика. Не меньше 96 dp по высоте: нажимать по ней придётся не глядя,
 * глаза на дороге. Долгий тап отменяет последнее нажатие.
 */
@Composable
private fun CounterButton(
    count: Int,
    name: String,
    rate: Double,
    heavy: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
    onUndo: () -> Unit,
) {
    TapAndHold(
        modifier = modifier
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(11.dp))
            .border(
                width = 1.5.dp,
                color = if (heavy) Palette.Ink2 else Palette.Ink,
                shape = RoundedCornerShape(11.dp),
            ),
        onTap = onTap,
        onLongPress = onUndo,
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MonoText(count.toString(), size = 34, weight = FontWeight.Medium)
            Spacer(Modifier.height(5.dp))
            Text(text = name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            Spacer(Modifier.height(2.dp))
            MonoText(fixed(rate, 1) + " / мин", size = 10, color = Palette.Ink3)
        }
    }
}

private fun roleSuffix(role: String?): String = when (role) {
    "facade" -> " · фасад"
    "yard" -> " · двор"
    else -> ""
}

private fun clock(seconds: Int): String =
    String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

private fun fixed(value: Double, decimals: Int): String =
    if (value.isNaN() || value.isInfinite()) "—"
    else String.format(Locale.US, "%." + decimals + "f", value)

private fun signed(value: Double): String =
    (if (value >= 0) "+" else "") + String.format(Locale.US, "%.1f", value)
