package dev.noisefield.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Палитра макета. Инструмент для человека, стоящего на тротуаре: экран читается
 * на солнце, поэтому фон светлый. Тёмной темы нет намеренно (§0), поэтому
 * [isSystemInDarkTheme] здесь не спрашивается.
 */
object Palette {
    val Paper = Color(0xFFFAFAF6)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF14171C)
    val Ink2 = Color(0xFF5A6069)
    val Ink3 = Color(0xFF8B9199)
    val Rule = Color(0xFFDDDFD9)
    val Rule2 = Color(0xFFEDEEE9)
    val Ok = Color(0xFF2E7D46)
    val Warn = Color(0xFFB4421F)

    /**
     * Шкала уровня — та же, что в легенде карты: чем громче, тем темнее.
     * Единственное место в приложении, где вообще есть цвет (§3).
     */
    val Db45 = Color(0xFFB7DFA8)
    val Db50 = Color(0xFF93CE7B)
    val Db55 = Color(0xFFC9C645)
    val Db60 = Color(0xFFDFA53B)
    val Db65 = Color(0xFFCE6A2E)
    val Db70 = Color(0xFFA73A2A)
    val Db75 = Color(0xFF79212F)
    val Db80 = Color(0xFF48122E)

    val Scale = listOf(Db45, Db50, Db55, Db60, Db65, Db70, Db75, Db80)

    /** Полоса шкалы по уровню в дБ(A). */
    fun forLevel(db: Double): Color = when {
        db.isNaN() -> Ink3
        db < 50.0 -> Db45
        db < 55.0 -> Db50
        db < 60.0 -> Db55
        db < 65.0 -> Db60
        db < 70.0 -> Db65
        db < 75.0 -> Db70
        db < 80.0 -> Db75
        else -> Db80
    }
}

/** Моноширинный шрифт для всех чисел: цифры не должны прыгать при обновлении. */
val Mono = FontFamily.Monospace

private val NoiseTypography = Typography(
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, color = Palette.Ink),
    bodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, color = Palette.Ink),
    labelSmall = TextStyle(fontSize = 11.sp, fontFamily = Mono, color = Palette.Ink3),
)

private val NoiseColors = lightColorScheme(
    primary = Palette.Ink,
    onPrimary = Color.White,
    background = Palette.Paper,
    onBackground = Palette.Ink,
    surface = Palette.Card,
    onSurface = Palette.Ink,
    error = Palette.Warn,
    onError = Color.White,
    outline = Palette.Rule,
)

@Composable
fun NoiseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NoiseColors,
        typography = NoiseTypography,
        content = content,
    )
}
