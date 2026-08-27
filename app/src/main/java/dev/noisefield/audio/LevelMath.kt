package dev.noisefield.audio

import kotlin.math.log10
import kotlin.math.pow

/**
 * Арифметика уровней. Вынесена отдельно от захвата, чтобы проверяться юнит-тестами
 * без микрофона.
 */
object LevelMath {

    /**
     * Константа REF из §2.3. Поглощается калибровкой: при нулевом REF офсет просто
     * оказывается большим числом, и это нормально.
     */
    const val REF_DB = 0.0

    /** Нижняя граница уровня. Тишина в цифре даёт meanSq = 0 и log10(0) = −∞. */
    const val FLOOR_DB = -120.0

    /** Уровень одного блока: 10·log10(meanSq) + offset + REF. */
    fun levelFromMeanSquare(meanSquare: Double, offsetDb: Double): Double {
        if (meanSquare <= 0.0 || meanSquare.isNaN()) return FLOOR_DB
        val level = 10.0 * log10(meanSquare) + offsetDb + REF_DB
        return if (level < FLOOR_DB) FLOOR_DB else level
    }

    /**
     * Интегральный LAeq — усреднение ПО ЭНЕРГИИ, не среднее децибелов (§2.3, §8).
     * Среднее децибелов — самая частая ошибка в самописных шумомерах: числа
     * получаются правдоподобные и систематически заниженные.
     */
    fun energyAverage(levels: List<Double>): Double {
        if (levels.isEmpty()) return FLOOR_DB
        var sum = 0.0
        for (l in levels) sum += 10.0.pow(l / 10.0)
        if (sum <= 0.0) return FLOOR_DB
        return 10.0 * log10(sum / levels.size)
    }

    /**
     * LAn — уровень, превышаемый n % времени. LA90 — фон, LA10 — пики,
     * поэтому LA90 ≤ LA50 ≤ LA10. Линейная интерполяция по упорядоченному ряду.
     */
    fun percentileExceeded(levels: List<Double>, percentExceeded: Double): Double {
        if (levels.isEmpty()) return FLOOR_DB
        val sorted = levels.sorted()
        if (sorted.size == 1) return sorted[0]
        val position = (1.0 - percentExceeded / 100.0) * (sorted.size - 1)
        val low = position.toInt().coerceIn(0, sorted.size - 1)
        val high = (low + 1).coerceAtMost(sorted.size - 1)
        val frac = position - low
        return sorted[low] + (sorted[high] - sorted[low]) * frac
    }

    /** Сводка по посекундному ряду: то, что уходит в [dev.noisefield.data.Measurement]. */
    fun summarize(perSecond: List<Double>): Summary = Summary(
        laeq = energyAverage(perSecond),
        laMax = perSecond.maxOrNull() ?: FLOOR_DB,
        la90 = percentileExceeded(perSecond, 90.0),
        la50 = percentileExceeded(perSecond, 50.0),
        la10 = percentileExceeded(perSecond, 10.0),
    )

    data class Summary(
        val laeq: Double,
        val laMax: Double,
        val la90: Double,
        val la50: Double,
        val la10: Double,
    )
}
