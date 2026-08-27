package dev.noisefield

import dev.noisefield.audio.OctaveBank
import dev.noisefield.data.Calibration
import dev.noisefield.data.JsonCodec
import dev.noisefield.data.Measurement
import dev.noisefield.data.VehicleCounts
import dev.noisefield.data.VehicleEvent
import dev.noisefield.export.CsvBuilder
import dev.noisefield.export.ExportRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Формат CSV проверяется тестом, а не глазами: файлы читает скрипт валидации,
 * и он должен открыться без правки руками (§6, §7).
 */
class CsvBuilderTest {

    private val moscow = ZoneId.of("Europe/Moscow")

    private val calibration = Calibration(
        id = 7,
        createdAt = 0,
        device = "Google Pixel 7",
        audioSource = "UNPROCESSED",
        sampleRate = 44100,
        offsetDb = 4.13,
        spreadDb = 0.41,
        pairsJson = "[]",
        agcDisabled = true,
        nsDisabled = true,
        aecDisabled = true,
    )

    private fun measurement(
        id: Long = 1,
        pointId: String = "P03a",
        notes: String? = null,
        flags: List<String> = emptyList(),
        durationSec: Int = 900,
        octaves: List<DoubleArray> = emptyList(),
    ) = Measurement(
        id = id,
        startedAt = ZonedDateTime.of(2026, 6, 18, 11, 22, 41, 0, moscow).toInstant().toEpochMilli(),
        durationSec = durationSec,
        calibrationId = 7,
        laeq = 68.42,
        laMax = 88.1,
        la90 = 57.3,
        la50 = 64.0,
        la10 = 72.6,
        clipRatio = 0.0,
        lat = 55.751244,
        lon = 37.618423,
        gpsAccuracyM = 4.0,
        laeqPerSecJson = JsonCodec.encodeDoubles(listOf(65.1, 64.8, 70.2)),
        octavesPerSecJson = octaves.takeIf { it.isNotEmpty() }?.let { JsonCodec.encodeMatrix(it) },
        pointId = pointId,
        pairId = "03",
        pairRole = Measurement.ROLE_FACADE,
        distRoadM = 7.5,
        distFacadeM = 4.0,
        windMs = 2.0,
        surface = Measurement.SURFACE_DRY,
        notes = notes,
        flagsJson = JsonCodec.encodeStrings(flags),
    )

    private fun row(
        m: Measurement = measurement(),
        counts: VehicleCounts = VehicleCounts(light = 92, heavy = 11),
        events: List<VehicleEvent> = emptyList(),
    ) = ExportRow(m, calibration, counts, events)

    @Test
    fun headersMatchSpecification() {
        val expected = "id,point_id,lat,lon,gps_accuracy_m,datetime_iso,duration_sec,laeq,lamax," +
            "la90,la50,la10,clip_ratio,mic_height_m,dist_to_road_m,dist_to_facade_m,light_total," +
            "heavy_total,light_per_min,heavy_per_min,wind_ms,surface,pair_id,pair_role,offset_db," +
            "spread_db,audio_source,device,flags,notes"
        assertEquals(expected, CsvBuilder.MEASUREMENTS_HEADER)
        assertEquals(30, CsvBuilder.MEASUREMENTS_HEADER.split(",").size)
        assertEquals("measurement_id,t_sec,laeq_1s", CsvBuilder.SERIES_HEADER)
        assertEquals("measurement_id,t_ms,heavy", CsvBuilder.VEHICLES_HEADER)
        assertEquals(
            "measurement_id,t_sec,hz_63,hz_125,hz_250,hz_500,hz_1000,hz_2000,hz_4000,hz_8000",
            CsvBuilder.OCTAVES_HEADER,
        )
    }

    @Test
    fun rowHasExactlyAsManyCellsAsHeader() {
        val csv = CsvBuilder.measurements(listOf(row()), moscow)
        val lines = csv.split("\n").filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
        assertEquals(lines[0].split(",").size, lines[1].split(",").size)
    }

    @Test
    fun localTimeCarriesOffset() {
        val cells = CsvBuilder.measurements(listOf(row()), moscow).split("\n")[1].split(",")
        assertEquals("2026-06-18T11:22:41+03:00", cells[5])
    }

    @Test
    fun numbersUseDotAndFixedPrecision() {
        val cells = CsvBuilder.measurements(listOf(row()), moscow).split("\n")[1].split(",")
        assertEquals("1", cells[0])
        assertEquals("P03a", cells[1])
        assertEquals("55.7512440", cells[2])
        assertEquals("4.0", cells[4])
        assertEquals("900", cells[6])
        assertEquals("68.4", cells[7])
        // Высота микрофона — константа 1.5, отдельным столбцом.
        assertEquals("1.5", cells[13])
        // Счётчики раздельные: сумму скрипт выведет сам, разделение из суммы — нет.
        assertEquals("92", cells[16])
        assertEquals("11", cells[17])
        // 92 машины за 900 с = 6.13 в минуту.
        assertEquals("6.13", cells[18])
        assertEquals("0.73", cells[19])
        assertEquals("4.13", cells[24])
        assertEquals("0.41", cells[25])
    }

    /**
     * Повторный замер тех же точек через несколько дней — обязательная часть
     * протокола: разброс между днями даёт порог достижимой точности. Значит
     * point_id повторяется, а связь рядов держится на суррогатном id.
     */
    @Test
    fun repeatedPointIdKeepsDistinctSurrogateKeys() {
        val first = row(measurement(id = 11, pointId = "P03a"))
        val second = row(measurement(id = 12, pointId = "P03a"))

        val lines = CsvBuilder.measurements(listOf(first, second), moscow)
            .split("\n").filter { it.isNotEmpty() }
        val idA = lines[1].split(",")[0]
        val idB = lines[2].split(",")[0]
        assertNotEquals("суррогатные id склеились", idA, idB)
        assertEquals("P03a", lines[1].split(",")[1])
        assertEquals("P03a", lines[2].split(",")[1])

        val series = CsvBuilder.series(listOf(first, second)).split("\n").filter { it.isNotEmpty() }
        assertEquals(1 + 3 + 3, series.size)
        assertEquals(3, series.count { it.startsWith("11,") })
        assertEquals(3, series.count { it.startsWith("12,") })
    }

    @Test
    fun flagsAreJoinedBySemicolonAndEmptyWhenNone() {
        val withFlags = CsvBuilder.measurements(
            listOf(row(measurement(flags = listOf("clip", "wind")))), moscow,
        ).split("\n")[1].split(",")
        assertEquals("clip;wind", withFlags[28])

        val without = CsvBuilder.measurements(listOf(row()), moscow).split("\n")[1].split(",")
        assertEquals("", without[28])
    }

    /** Заметка может содержать запятую — экранирование по RFC 4180. */
    @Test
    fun notesWithCommasAndQuotesAreEscaped() {
        val csv = CsvBuilder.measurements(
            listOf(row(measurement(notes = "рядом стройка, слышно \"бабу\""))), moscow,
        )
        assertTrue(csv.split("\n")[1].endsWith("\"рядом стройка, слышно \"\"бабу\"\"\""))
        assertEquals("\"a,b\"", CsvBuilder.escape("a,b"))
        assertEquals("plain", CsvBuilder.escape("plain"))
    }

    /** t_sec = 0 — начало первого секундного блока, а не его конец. */
    @Test
    fun seriesIsFlatAndStartsAtZero() {
        assertEquals(
            listOf(
                "measurement_id,t_sec,laeq_1s",
                "1,0,65.1",
                "1,1,64.8",
                "1,2,70.2",
            ),
            CsvBuilder.series(listOf(row())).split("\n").filter { it.isNotEmpty() },
        )
    }

    /** t_ms = 0 — момент старта замера. */
    @Test
    fun vehiclesAreSortedAndBooleanIsZeroOne() {
        val events = listOf(
            VehicleEvent(id = 2, measurementId = 1, tMs = 4200, heavy = true),
            VehicleEvent(id = 1, measurementId = 1, tMs = 0, heavy = false),
        )
        assertEquals(
            listOf("measurement_id,t_ms,heavy", "1,0,0", "1,4200,1"),
            CsvBuilder.vehicles(listOf(row(events = events))).split("\n").filter { it.isNotEmpty() },
        )
    }

    @Test
    fun octavesAreOneRowPerSecondWithColumnPerBand() {
        val bands = listOf(
            doubleArrayOf(50.1, 55.2, 60.3, 62.4, 58.5, 54.6, 48.7, 40.8),
            doubleArrayOf(51.1, 56.2, 61.3, 63.4, 59.5, 55.6, 49.7, 41.8),
        )
        val lines = CsvBuilder.octaves(listOf(row(measurement(octaves = bands))))
            .split("\n").filter { it.isNotEmpty() }
        assertEquals(3, lines.size)
        assertEquals("1,0,50.1,55.2,60.3,62.4,58.5,54.6,48.7,40.8", lines[1])
        assertEquals("1,1,51.1,56.2,61.3,63.4,59.5,55.6,49.7,41.8", lines[2])
        assertEquals(OctaveBank.CENTERS.size + 2, lines[1].split(",").size)
    }

    /** Замер без октав строк в octaves.csv не даёт, но файл остаётся валидным. */
    @Test
    fun octavesFileIsHeaderOnlyWhenNothingWasRecorded() {
        val csv = CsvBuilder.octaves(listOf(row()))
        assertEquals(listOf(CsvBuilder.OCTAVES_HEADER), csv.split("\n").filter { it.isNotEmpty() })
    }

    @Test
    fun linesEndWithUnixNewline() {
        val csv = CsvBuilder.measurements(listOf(row()), moscow)
        assertTrue(csv.endsWith("\n"))
        assertTrue(!csv.contains("\r"))
    }
}
