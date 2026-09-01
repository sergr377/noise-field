package dev.noisefield

import dev.noisefield.audio.AWeightingFilter
import dev.noisefield.audio.Biquad
import dev.noisefield.audio.LevelMath
import dev.noisefield.audio.OctaveBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/**
 * Обязательная проверка тракта, §2.4. Пока эти тесты не зелёные, тракту нельзя
 * верить: ошибка A-взвешивания или усреднения не видна в результате — числа
 * выглядят правдоподобно и при этом неверны.
 */
class AudioTractTest {

    private val fs = AWeightingFilter.SAMPLE_RATE

    /**
     * §2.4.1 — АЧХ A-взвешивания. Синтетические синусы единичной амплитуды против
     * табличной A-кривой.
     *
     * Допуск расширяется к краям полосы, как его и задаёт IEC 61672: ±0.5 дБ
     * в полосе 63 Гц – 4 кГц, ±1.5 дБ на 8 кГц, ±2 дБ на 31.5 Гц.
     *
     * Расширение на краях — не поблажка реализации, а свойство билинейного
     * преобразования: оно сжимает частотную ось к Найквисту, и на 8 кГц при
     * 44100 Гц дискретный фильтр закономерно лежит примерно на 0.7 дБ ниже
     * аналогового прототипа. Подгонять полюса под единый узкий допуск нельзя:
     * так получается фильтр, проходящий тест, но переставший быть A-взвешиванием,
     * а вся конструкция держится на сравнимости с прибором класса 2.
     */
    @Test
    fun aWeightingMatchesReferenceCurve() {
        val freqs = AWeightingFilter.REFERENCE_FREQUENCIES
        val expected = AWeightingFilter.REFERENCE_RESPONSE_DB
        val tolerance = AWeightingFilter.REFERENCE_TOLERANCE_DB
        for (i in freqs.indices) {
            val measured = weightedSineLevelDb(freqs[i])
            val error = measured - expected[i]
            assertTrue(
                "на " + freqs[i] + " Гц получено " + fmt(measured) +
                    " дБ, ожидалось " + expected[i] + " дБ, отклонение " + fmt(error) +
                    " при допуске ±" + tolerance[i],
                abs(error) <= tolerance[i],
            )
        }
    }

    /**
     * §2.4.2 — усреднение по энергии. Ряд из 30 секунд по 60 дБ и 30 секунд по 80 дБ
     * даёт 77.0, а не 70.0. Среднее децибелов — самая частая ошибка в самописных
     * шумомерах, и она даёт правдоподобные заниженные числа.
     */
    @Test
    fun integratedLevelAveragesEnergyNotDecibels() {
        val series = List(30) { 60.0 } + List(30) { 80.0 }
        val laeq = LevelMath.energyAverage(series)
        assertEquals(77.0, laeq, 0.05)
        assertTrue("усреднение пошло по децибелам", abs(laeq - 70.0) > 5.0)
    }

    /**
     * §2.4.3 — непрерывность состояния фильтра. Один длинный синус, поданный целиком
     * и поданный кусками разной длины, должен дать одинаковый уровень с точностью
     * 0.01 дБ.
     */
    @Test
    fun filterStateSurvivesBufferBoundaries() {
        val freq = 997.0
        val total = fs * 3

        val whole = AWeightingFilter()
        var sumWhole = 0.0
        for (i in 0 until total) {
            val y = whole.process(sin(2.0 * PI * freq * i / fs))
            sumWhole += y * y
        }

        val chunked = AWeightingFilter()
        var sumChunked = 0.0
        val chunkSizes = intArrayOf(1000, 4096, 777, 44100, 13, 60000)
        var index = 0
        var chunk = 0
        while (index < total) {
            val n = minOf(chunkSizes[chunk % chunkSizes.size], total - index)
            for (k in 0 until n) {
                val y = chunked.process(sin(2.0 * PI * freq * (index + k) / fs))
                sumChunked += y * y
            }
            index += n
            chunk++
        }

        assertEquals(10.0 * log10(sumWhole / total), 10.0 * log10(sumChunked / total), 0.01)
    }

    /** LA90 — фон, LA10 — пики. Порядок обратный номеру перцентиля, легко перепутать. */
    @Test
    fun percentilesAreOrderedFromBackgroundToPeaks() {
        val series = (0..100).map { 40.0 + it * 0.5 }
        val la90 = LevelMath.percentileExceeded(series, 90.0)
        val la50 = LevelMath.percentileExceeded(series, 50.0)
        val la10 = LevelMath.percentileExceeded(series, 10.0)
        assertTrue("LA90 должен быть ниже LA50", la90 < la50)
        assertTrue("LA50 должен быть ниже LA10", la50 < la10)
        assertEquals(45.0, la90, 0.01)
        assertEquals(65.0, la50, 0.01)
        assertEquals(85.0, la10, 0.01)
    }

    /** Каждая октавная полоса пропускает свою центральную частоту без ослабления. */
    @Test
    fun octaveBandsPassTheirOwnCenterFrequency() {
        for (i in OctaveBank.CENTERS.indices) {
            val level = bandResponseDb(OctaveBank.SECTIONS[i], OctaveBank.CENTERS[i])
            assertEquals("полоса " + OctaveBank.CENTERS[i] + " Гц", 0.0, level, 0.05)
        }
    }

    /** На границах октавы (fc/√2 и fc·√2) полоса даёт положенные −3 дБ. */
    @Test
    fun octaveBandsAreThreeDecibelsDownAtBandEdges() {
        val sqrt2 = Math.sqrt(2.0)
        // Верхние полосы искажаются билинейным преобразованием, поэтому проверяются
        // те, что заведомо далеко от Найквиста.
        for (i in 0 until OctaveBank.CENTERS.size - 2) {
            val fc = OctaveBank.CENTERS[i]
            val low = bandResponseDb(OctaveBank.SECTIONS[i], fc / sqrt2)
            val high = bandResponseDb(OctaveBank.SECTIONS[i], fc * sqrt2)
            assertEquals("нижняя граница " + fc, -3.0, low, 0.15)
            assertEquals("верхняя граница " + fc, -3.0, high, 0.15)
        }
    }

    /** Синус в одной полосе поднимает свою полосу и оставляет дальние внизу. */
    @Test
    fun octaveBankSeparatesBands() {
        val bank = OctaveBank()
        val freq = 1000.0
        // Первая секунда — прогрев: щелчок в начале синуса размазывается по всем
        // полосам, и его энергию в оценку разделения брать незачем.
        for (i in 0 until fs) bank.process(sin(2.0 * PI * freq * i / fs))
        bank.takeLevels()
        for (i in fs until fs * 2) bank.process(sin(2.0 * PI * freq * i / fs))
        val levels = bank.takeLevels()
        val index = OctaveBank.CENTERS.indexOfFirst { it == 1000.0 }

        for (i in levels.indices) {
            if (i == index) continue
            val distance = abs(i - index)
            val gap = levels[index] - levels[i]
            // Соседняя полоса подслушивает: один биквад на полосу даёт всего
            // −7.4 дБ на центре соседней. Через полосу — уже около −15 дБ.
            val expected = if (distance == 1) 5.0 else 12.0
            assertTrue(
                "полоса " + OctaveBank.CENTERS[i] + " Гц отстоит всего на " + fmt(gap) + " дБ",
                gap > expected,
            )
        }
    }

    /** Состояние октавного банка тоже переживает границы блоков. */
    @Test
    fun octaveBankKeepsFilterStateBetweenBlocks() {
        val freq = 500.0
        val whole = OctaveBank()
        for (i in 0 until fs * 2) whole.process(sin(2.0 * PI * freq * i / fs))
        val wholeLevels = whole.takeLevels()

        val chunked = OctaveBank()
        var produced = 0
        val perBlockEnergy = DoubleArray(OctaveBank.CENTERS.size)
        for (block in 0 until 2) {
            for (k in 0 until fs) {
                chunked.process(sin(2.0 * PI * freq * (produced + k) / fs))
            }
            produced += fs
            val levels = chunked.takeLevels()
            for (i in levels.indices) perBlockEnergy[i] += Math.pow(10.0, levels[i] / 10.0) / 2.0
        }

        val index = OctaveBank.CENTERS.indexOfFirst { it == 500.0 }
        val chunkedLevel = 10.0 * log10(perBlockEnergy[index])
        assertEquals(wholeLevels[index], chunkedLevel, 0.05)
    }

    /**
     * Прогон синуса единичной амплитуды через фильтр. Уровень считается относительно
     * невзвешенного синуса (его средний квадрат — 0.5), первая половина отбрасывается
     * как переходный процесс.
     */
    private fun weightedSineLevelDb(freq: Double): Double {
        val filter = AWeightingFilter()
        val total = fs * 4
        val settle = fs * 2
        var sumSquares = 0.0
        var counted = 0
        for (i in 0 until total) {
            val y = filter.process(sin(2.0 * PI * freq * i / fs))
            if (i >= settle) {
                sumSquares += y * y
                counted++
            }
        }
        return 10.0 * log10(sumSquares / counted) - 10.0 * log10(0.5)
    }

    /** Аналитическая АЧХ одного биквада — быстрее, чем гонять синус. */
    private fun bandResponseDb(section: Biquad, freq: Double): Double {
        val w = 2.0 * PI * freq / fs
        val zr = kotlin.math.cos(-w)
        val zi = sin(-w)
        val z2r = kotlin.math.cos(-2.0 * w)
        val z2i = sin(-2.0 * w)
        val nr = section.b0 + section.b1 * zr + section.b2 * z2r
        val ni = section.b1 * zi + section.b2 * z2i
        val dr = 1.0 + section.a1 * zr + section.a2 * z2r
        val di = section.a1 * zi + section.a2 * z2i
        val d = dr * dr + di * di
        val hr = (nr * dr + ni * di) / d
        val hi = (ni * dr - nr * di) / d
        return 10.0 * log10(hr * hr + hi * hi)
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.3f", v)
}
