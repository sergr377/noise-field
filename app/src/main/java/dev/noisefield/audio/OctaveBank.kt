package dev.noisefield.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Банк октавных полос: 63 / 125 / 250 / 500 / 1000 / 2000 / 4000 / 8000 Гц — те же
 * полосы, что у модели.
 *
 * **Зачем это считается в поле, а не потом.** Аудио не сохраняется ни в каком виде,
 * значит спектр нельзя восстановить задним числом — только пересняв точку, то есть
 * съездив ещё раз. Всё остальное в замере выводится из посекундного ряда, октавы —
 * нет. Когда расхождение с моделью найдётся, широкополосный LAeq скажет «мимо на
 * 4 дБ», а спектр скажет, мимо в низах (поток и состав) или в верхах (покрытие или
 * экранирование).
 *
 * **Полосы считаются по НЕвзвешенному сигналу.** Модель выдаёт линейные спектры,
 * и сравнивать нужно с ними; A-взвешивание полосы — известная константа, его при
 * необходимости накладывают при обработке. Офсет калибровки применяется, как и
 * к широкополосному уровню.
 *
 * **Ограничение, о котором стоит знать при чтении чисел.** На полосу — один биквад
 * (Q = √2, как задаёт октаву отношение f2/f1 = 2). На границах полосы он даёт
 * положенные −3 дБ, но на центре соседней полосы — только −7.4 дБ, то есть полосы
 * заметно подслушивают друг друга: сосед, который громче на 10 дБ, добавляет около
 * +2.6 дБ. Для вопроса «низы или верхи» этого достаточно, для оценки наклона
 * спектра — уже нет. Если понадобится точнее, каждая полоса собирается каскадом
 * из трёх таких же биквадов (Баттерворт 6-го порядка), инфраструктура для этого
 * здесь уже есть.
 */
class OctaveBank {

    private val z1 = DoubleArray(SECTIONS.size)
    private val z2 = DoubleArray(SECTIONS.size)
    private val sumSquares = DoubleArray(SECTIONS.size)
    private var samples = 0

    /**
     * Один отсчёт во все полосы сразу. Отсчёт — сырой, нормированный в −1..1,
     * до A-взвешивания.
     */
    fun process(sample: Double) {
        for (i in SECTIONS.indices) {
            val s = SECTIONS[i]
            val w = sample - s.a1 * z1[i] - s.a2 * z2[i]
            val y = s.b0 * w + s.b1 * z1[i] + s.b2 * z2[i]
            z2[i] = z1[i]
            z1[i] = w
            sumSquares[i] += y * y
        }
        samples++
    }

    /**
     * Уровни полос за накопленный блок и сброс накопителей.
     * Состояние фильтров при этом НЕ сбрасывается — по той же причине, что
     * и в [AWeightingFilter].
     */
    fun takeLevels(offsetDb: Double = 0.0): DoubleArray {
        val n = samples
        val result = DoubleArray(SECTIONS.size) { i ->
            if (n == 0) LevelMath.FLOOR_DB else LevelMath.levelFromMeanSquare(sumSquares[i] / n, offsetDb)
        }
        sumSquares.fill(0.0)
        samples = 0
        return result
    }

    /** Полный сброс — только при открытии нового тракта. */
    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
        sumSquares.fill(0.0)
        samples = 0
    }

    companion object {
        /** Центральные частоты полос, Гц. Порядок значений в JSON и CSV — этот. */
        val CENTERS = doubleArrayOf(63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0)

        /** Октава: f2 / f1 = 2, отсюда Q = fc / (f2 − f1) = √2. */
        private val Q = sqrt(2.0)

        val SECTIONS: Array<Biquad> = Array(CENTERS.size) {
            bandpass(CENTERS[it], AWeightingFilter.SAMPLE_RATE.toDouble())
        }

        /**
         * Полосовой биквад с единичным усилением на центральной частоте
         * (билинейное преобразование, форма RBJ).
         */
        fun bandpass(centerHz: Double, sampleRate: Double): Biquad {
            val w0 = 2.0 * PI * centerHz / sampleRate
            val alpha = sin(w0) / (2.0 * Q)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = alpha / a0,
                b1 = 0.0,
                b2 = -alpha / a0,
                a1 = -2.0 * cos(w0) / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }
    }
}
