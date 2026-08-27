package dev.noisefield.ui.calibration

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.noisefield.audio.NoiseTract
import dev.noisefield.data.Repository
import dev.noisefield.session.CaptureBus
import dev.noisefield.session.CaptureKind
import dev.noisefield.session.CapturePhase
import dev.noisefield.ui.common.Chip
import dev.noisefield.ui.common.ChipTone
import dev.noisefield.ui.common.Divider
import dev.noisefield.ui.common.GhostButton
import dev.noisefield.ui.common.Lbl
import dev.noisefield.ui.common.MonoText
import dev.noisefield.ui.common.PrimaryButton
import dev.noisefield.ui.common.StopButton
import dev.noisefield.ui.common.TopBar
import dev.noisefield.ui.theme.Mono
import dev.noisefield.ui.theme.Palette
import java.util.Locale

@Composable
fun CalibrationScreen(
    onDone: () -> Unit,
    vm: CalibrationViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsState()
    val capture by CaptureBus.state.collectAsState()

    val probing = capture.kind == CaptureKind.CALIBRATION && capture.running

    // Проба закончилась — её LAeq становится значением «Прил.» текущей строки.
    LaunchedEffect(capture.phase) {
        if (capture.kind != CaptureKind.CALIBRATION) return@LaunchedEffect
        when (capture.phase) {
            CapturePhase.DONE -> {
                if (capture.elapsedSec > 0) vm.applyProbe(capture.laeq, capture.tract) else vm.cancelProbe()
                CaptureBus.clear()
            }
            CapturePhase.FAILED -> {
                vm.cancelProbe()
                CaptureBus.clear()
            }
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "Калибровка",
            sub = ui.tract?.let { it.audioSource + " · нижний мик" } ?: "нижний мик",
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderRow()

            Column {
                ui.rows.forEachIndexed { index, row ->
                    CalibrationRow(
                        row = row,
                        probing = probing && ui.probingRow == index,
                        liveLevel = if (probing && ui.probingRow == index && capture.elapsedSec > 0) {
                            capture.laeq
                        } else {
                            null
                        },
                        elapsedSec = capture.elapsedSec,
                        enabled = !probing,
                        onRefChange = { vm.setRef(index, it) },
                        onProbe = { vm.startProbe(index) },
                    )
                }
            }

            Verdict(ui)

            TractChips(ui.tract, ui.tractError)

            if (ui.spreadTooWide) {
                Warning(
                    "Разброс " + fixed(ui.spreadDb ?: 0.0, 1) + " дБ — тракт нелинеен, " +
                        "скорее всего не отключился AGC. Офсет одним числом здесь непригоден. " +
                        "Сохранить можно, но разброс уйдёт в CSV вместе с замерами."
                )
            }
            if (ui.rangeTooNarrow) {
                Warning(
                    "Размах уровней всего " + fixed(ui.rangeDb ?: 0.0, 1) + " дБ. " +
                        "Такая калибровка проверяет только точку, а не наклон: пары нужно " +
                        "снимать на разных уровнях, желательно с размахом от " +
                        fixed(Repository.CAL_RANGE_TARGET_DB, 0) + " дБ."
                )
            } else if (ui.rangeBelowTarget) {
                Text(
                    text = "Размах уровней " + fixed(ui.rangeDb ?: 0.0, 1) + " дБ. " +
                        "Желательно не меньше " + fixed(Repository.CAL_RANGE_TARGET_DB, 0) +
                        " дБ — иначе наклон тракта проверен слабо.",
                    fontSize = 12.sp,
                    color = Palette.Ink2,
                )
            }
            if (ui.tractError != null) {
                Warning("Микрофон не открылся: " + ui.tractError)
            }
        }

        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (probing) {
                StopButton("Остановить пробу · " + clock(capture.elapsedSec)) { vm.stopProbe() }
            } else {
                PrimaryButton("Сохранить офсет", enabled = ui.canSave) { vm.save(onDone) }
                GhostButton("Замерить ещё пару", enabled = !probing) { vm.addRow() }
                GhostButton("Назад", onClick = onDone)
            }
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Lbl("Уровень", Modifier.weight(1f))
        Lbl("Прибор", Modifier.width(62.dp), align = TextAlign.Center)
        Lbl("Прил.", Modifier.width(62.dp), align = TextAlign.Center)
        Lbl("Δ", Modifier.width(48.dp), align = TextAlign.End)
    }
}

@Composable
private fun CalibrationRow(
    row: CalRow,
    probing: Boolean,
    liveLevel: Double?,
    elapsedSec: Int,
    enabled: Boolean,
    onRefChange: (String) -> Unit,
    onProbe: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = row.title, fontSize = 13.sp, color = Palette.Ink)
                Text(text = row.hint, fontSize = 11.sp, color = Palette.Ink3)
            }

            // Показание прибора вводится руками.
            Box(
                Modifier
                    .width(62.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Palette.Rule, RoundedCornerShape(6.dp))
                    .padding(vertical = 7.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicTextField(
                    value = row.refText,
                    onValueChange = onRefChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = TextStyle(
                        fontSize = 13.sp,
                        fontFamily = Mono,
                        color = Palette.Ink,
                        textAlign = TextAlign.Center,
                    ),
                    cursorBrush = SolidColor(Palette.Ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.width(8.dp))

            // Значение приложения снимается тут же, тап по ячейке запускает пробу.
            Box(
                Modifier
                    .width(62.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Palette.Rule2)
                    .clickable(enabled = enabled) { onProbe() }
                    .padding(vertical = 7.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                val text = when {
                    liveLevel != null -> fixed(liveLevel, 1)
                    probing -> "…"
                    row.appDb != null -> fixed(row.appDb, 1)
                    else -> "замер"
                }
                MonoText(
                    text = text,
                    size = 13,
                    color = if (row.appDb == null && liveLevel == null) Palette.Ink3 else Palette.Ink,
                )
            }

            Spacer(Modifier.width(8.dp))

            MonoText(
                text = row.deltaDb?.let { signed(it) } ?: "—",
                modifier = Modifier.width(48.dp),
                size = 13,
                color = Palette.Ink2,
                align = TextAlign.End,
            )
        }
        if (probing) {
            MonoText(
                text = "проба " + clock(elapsedSec) + " из " + clock(CalibrationViewModel.PROBE_SECONDS),
                size = 10,
                color = Palette.Ink3,
            )
            Spacer(Modifier.height(4.dp))
        }
        Divider()
    }
}

@Composable
private fun Verdict(ui: CalibrationUi) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Palette.Rule, RoundedCornerShape(10.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Lbl("Офсет")
            Spacer(Modifier.height(3.dp))
            MonoText(
                text = ui.offsetDb?.let { signed(it) } ?: "—",
                size = 27,
                weight = FontWeight.Medium,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val spread = ui.spreadDb
            Text(
                text = if (spread == null) "Нужно " + CalibrationUi.REQUIRED_PAIRS + " пары"
                else "Разброс " + fixed(spread, 1) + " дБ",
                fontSize = 11.sp,
                color = if (ui.spreadTooWide) Palette.Warn else Palette.Ok,
                textAlign = TextAlign.End,
            )
            Text(
                text = when {
                    spread == null -> "заполните строки"
                    ui.spreadTooWide -> "Нелинейно — негодно"
                    else -> "Линейно — годится"
                },
                fontSize = 11.sp,
                color = if (ui.spreadTooWide) Palette.Warn else Palette.Ok,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun TractChips(tract: NoiseTract.Info?, error: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tract == null) {
            Chip(if (error == null) "тракт проверяется" else "тракт не открылся", ChipTone.BAD)
            return@Row
        }
        Chip(
            text = when (tract.agc) {
                NoiseTract.EffectState.DISABLED -> "AGC выключен"
                NoiseTract.EffectState.ABSENT -> "AGC недоступен"
                NoiseTract.EffectState.STUCK_ON -> "AGC не выключился"
            },
            tone = if (tract.agcDisabled) ChipTone.GOOD else ChipTone.BAD,
        )
        Chip(
            text = tract.audioSource,
            tone = if (tract.audioSource == NoiseTract.SOURCE_UNPROCESSED) ChipTone.GOOD else ChipTone.NEUTRAL,
        )
        Chip(tract.sampleRate.toString() + " Гц")
    }
}

@Composable
private fun Warning(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Palette.Warn, RoundedCornerShape(8.dp))
            .padding(11.dp)
    ) {
        Text(text = text, fontSize = 12.sp, color = Palette.Warn)
    }
}

private fun clock(seconds: Int): String =
    String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

private fun fixed(value: Double, decimals: Int): String =
    if (value.isNaN() || value.isInfinite()) "—"
    else String.format(Locale.US, "%." + decimals + "f", value)

private fun signed(value: Double): String =
    (if (value >= 0) "+" else "") + String.format(Locale.US, "%.1f", value)
