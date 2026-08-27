package dev.noisefield.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import dev.noisefield.ui.theme.Mono
import dev.noisefield.ui.theme.Palette

/** Шапка экрана: название слева, служебная строка справа. */
@Composable
fun TopBar(title: String, sub: String? = null, onTitleClick: (() -> Unit)? = null) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onTitleClick != null) Modifier.clickable { onTitleClick() } else Modifier)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (sub != null) {
                Text(text = sub, fontSize = 12.sp, fontFamily = Mono, color = Palette.Ink3, maxLines = 1)
            }
        }
        Divider()
    }
}

@Composable
fun Divider(color: Color = Palette.Rule2) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

/** Мелкая подпись поля: моно, капслок, разрядка — как `.lbl` в макете. */
@Composable
fun Lbl(text: String, modifier: Modifier = Modifier, color: Color = Palette.Ink3, align: TextAlign? = null) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        fontSize = 10.sp,
        fontFamily = Mono,
        letterSpacing = 1.1.sp,
        color = color,
        textAlign = align,
        maxLines = 1,
    )
}

@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 13,
    color: Color = Palette.Ink,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        maxLines = 1,
        style = TextStyle(
            fontSize = size.sp,
            fontFamily = Mono,
            fontWeight = weight,
            color = color,
            textAlign = align ?: TextAlign.Unspecified,
            fontFeatureSettings = TABULAR_FIGURES,
        ),
    )
}

/**
 * Табличные цифры. Без них пропорциональные цифры системного шрифта меняют
 * ширину при каждом обновлении, и крупный LAeq дёргается — а смотреть на него
 * придётся пятнадцать минут подряд.
 */
const val TABULAR_FIGURES = "tnum"

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    ButtonBox(
        text = text,
        modifier = modifier,
        enabled = enabled,
        background = if (enabled) Palette.Ink else Palette.Rule,
        border = if (enabled) Palette.Ink else Palette.Rule,
        content = if (enabled) Color.White else Palette.Ink3,
        onClick = onClick,
    )
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    ButtonBox(
        text = text,
        modifier = modifier,
        enabled = enabled,
        background = Color.Transparent,
        border = Palette.Rule,
        content = if (enabled) Palette.Ink else Palette.Ink3,
        onClick = onClick,
    )
}

@Composable
fun StopButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ButtonBox(
        text = text,
        modifier = modifier,
        enabled = true,
        background = Color.Transparent,
        border = Palette.Warn,
        content = Palette.Warn,
        onClick = onClick,
    )
}

@Composable
private fun ButtonBox(
    text: String,
    modifier: Modifier,
    enabled: Boolean,
    background: Color,
    border: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = content)
    }
}

enum class ChipTone { NEUTRAL, GOOD, BAD }

@Composable
fun Chip(text: String, tone: ChipTone = ChipTone.NEUTRAL) {
    val color = when (tone) {
        ChipTone.NEUTRAL -> Palette.Ink2
        ChipTone.GOOD -> Palette.Ok
        ChipTone.BAD -> Palette.Warn
    }
    val borderColor = when (tone) {
        ChipTone.NEUTRAL -> Palette.Rule
        ChipTone.GOOD -> Palette.Ok
        ChipTone.BAD -> Palette.Warn
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            maxLines = 1,
            style = TextStyle(
                fontSize = 10.sp,
                fontFamily = Mono,
                color = color,
                fontFeatureSettings = TABULAR_FIGURES,
            ),
        )
    }
}

/**
 * Спарклайн посекундного ряда. Показывает форму замера: одна сирена видна сразу,
 * и по ней потом решают, обрезать ли окно при обработке.
 */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 1.0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val path = Path()
        for (i in values.indices) {
            val x = i * stepX
            val y = size.height - ((values[i] - min) / span * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = color, style = Stroke(width = 2f))
    }
}

/** Горизонтальная полоса прогресса замера. */
@Composable
fun ProgressTrack(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Palette.Rule2)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(3.dp)
                .background(Palette.Ink)
        )
    }
}

/** Сегментированный переключатель на два положения — как `.seg` в макете. */
@Composable
fun Segmented(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, Palette.Rule, RoundedCornerShape(7.dp))
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .background(if (selected) Palette.Ink else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Color.White else Palette.Ink,
                )
            }
        }
    }
}

/** Поле ввода с подписью — `.fld` + `.in`. */
@Composable
fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    onValueChange: (String) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Lbl(label)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, Palette.Rule, RoundedCornerShape(7.dp))
                .padding(horizontal = 10.dp, vertical = 11.dp)
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(text = placeholder, fontSize = 14.sp, fontFamily = Mono, color = Palette.Ink3)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = keyboard,
                textStyle = TextStyle(fontSize = 14.sp, fontFamily = Mono, color = Palette.Ink),
                cursorBrush = SolidColor(Palette.Ink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Столбик «подпись + крупное число» из сводки выезда. */
@Composable
fun Tally(label: String, value: String, color: Color = Palette.Ink) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Lbl(label)
        MonoText(value, size = 21, color = color)
    }
}

/** Легенда шкалы — восемь полос из макета. */
@Composable
fun ScaleLegend(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Palette.Scale.forEach { color ->
            Box(
                Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
    }
}

/** Цветная точка полосы в списке замеров. */
@Composable
fun LevelDot(color: Color) {
    Box(
        Modifier
            .width(9.dp)
            .height(9.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}

/**
 * Кнопка с раздельной обработкой обычного и долгого нажатия.
 *
 * Обработчики держатся через [rememberUpdatedState], а `pointerInput` заводится
 * один раз: ключ из лямбд перезапускал бы распознавание жестов на каждой
 * рекомпозиции, то есть раз в секунду, и часть нажатий терялась бы.
 */
@Composable
fun TapAndHold(
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    content: @Composable () -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onLongPress)
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { _: Offset -> tap() },
                onLongPress = { _: Offset -> hold() },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
