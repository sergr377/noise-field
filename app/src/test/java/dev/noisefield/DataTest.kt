package dev.noisefield

import dev.noisefield.data.CalPair
import dev.noisefield.data.JsonCodec
import dev.noisefield.data.PointIds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PointIdsTest {

    /** P03a → P03b → P04a: буква — член пары, номер — сама пара (§5). */
    @Test
    fun pairMembersAlternateThenNumberAdvances() {
        assertEquals("P03b", PointIds.next("P03a"))
        assertEquals("P04a", PointIds.next("P03b"))
        assertEquals("P05a", PointIds.next("P04b"))
    }

    @Test
    fun leadingZerosSurvive() {
        assertEquals("P10a", PointIds.next("P09b"))
        assertEquals("P100a", PointIds.next("P099b"))
        assertEquals("P5", PointIds.next("P4"))
    }

    @Test
    fun idWithoutNumberIsLeftAlone() {
        assertEquals("мост", PointIds.next("мост"))
    }

    @Test
    fun emptyStartsFromDefault() {
        assertEquals(PointIds.DEFAULT, PointIds.next(null))
        assertEquals(PointIds.DEFAULT, PointIds.next("  "))
    }
}

class JsonCodecTest {

    @Test
    fun seriesSurvivesRoundTrip() {
        val series = listOf(65.1, 64.8, 70.25, 40.0)
        val decoded = JsonCodec.decodeDoubles(JsonCodec.encodeDoubles(series))
        assertEquals(listOf(65.1, 64.8, 70.3, 40.0), decoded)
    }

    @Test
    fun flagsSurviveRoundTrip() {
        val flags = listOf("clip", "gps_poor")
        assertEquals(flags, JsonCodec.decodeStrings(JsonCodec.encodeStrings(flags)))
        assertEquals(emptyList<String>(), JsonCodec.decodeStrings("[]"))
        assertEquals(emptyList<String>(), JsonCodec.decodeStrings(null))
    }

    /** Октавы: аудио не сохраняется, значит потерять этот ряд нельзя ничем. */
    @Test
    fun octaveMatrixSurvivesRoundTrip() {
        val rows = listOf(
            doubleArrayOf(50.1, 55.2, 60.3, 62.4, 58.5, 54.6, 48.7, 40.8),
            doubleArrayOf(-120.0, 0.0, 61.3, 63.4, 59.5, 55.6, 49.7, 41.8),
        )
        val decoded = JsonCodec.decodeMatrix(JsonCodec.encodeMatrix(rows))
        assertEquals(rows.size, decoded.size)
        for (i in rows.indices) {
            assertArrayEquals(rows[i], decoded[i], 0.05)
        }
        assertEquals(emptyList<DoubleArray>(), JsonCodec.decodeMatrix(null))
        assertEquals(emptyList<DoubleArray>(), JsonCodec.decodeMatrix("[]"))
    }

    @Test
    fun calibrationPairsSurviveRoundTrip() {
        val pairs = listOf(CalPair(71.8, 67.5), CalPair(46.1, 41.9))
        val decoded = JsonCodec.decodePairs(JsonCodec.encodePairs(pairs))
        assertEquals(pairs, decoded)
        assertEquals(4.3, decoded[0].deltaDb, 0.001)
    }
}
