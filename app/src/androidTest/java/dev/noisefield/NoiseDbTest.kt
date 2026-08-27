package dev.noisefield

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.noisefield.data.Calibration
import dev.noisefield.data.JsonCodec
import dev.noisefield.data.Measurement
import dev.noisefield.data.NoiseDb
import dev.noisefield.data.VehicleEvent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Проверка этапа 1: все три сущности пишутся и читаются. */
@RunWith(AndroidJUnit4::class)
class NoiseDbTest {

    private lateinit var db: NoiseDb

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NoiseDb::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun allThreeEntitiesRoundTrip() = runBlocking {
        val calibrationId = db.calibrations().insert(
            Calibration(
                createdAt = 1_700_000_000_000,
                device = "Google Pixel 7",
                audioSource = "UNPROCESSED",
                sampleRate = 44100,
                offsetDb = 4.1,
                spreadDb = 0.4,
                pairsJson = JsonCodec.encodePairs(emptyList()),
                agcDisabled = true,
                nsDisabled = true,
                aecDisabled = true,
            )
        )

        val active = db.calibrations().active()
        assertNotNull(active)
        assertEquals(4.1, active!!.offsetDb, 0.001)
        assertEquals(44100, active.sampleRate)

        val series = listOf(65.1, 64.8, 70.2)
        val measurementId = db.measurements().insert(
            Measurement(
                startedAt = 1_700_000_100_000,
                durationSec = series.size,
                calibrationId = calibrationId,
                laeq = 67.4,
                laMax = 70.2,
                la90 = 64.9,
                la50 = 65.1,
                la10 = 69.7,
                clipRatio = 0.0,
                lat = 55.751244,
                lon = 37.618423,
                gpsAccuracyM = 4.0,
                laeqPerSecJson = JsonCodec.encodeDoubles(series),
                pointId = "P03a",
                pairId = "03",
                pairRole = Measurement.ROLE_FACADE,
                distRoadM = 7.5,
                distFacadeM = 4.0,
                windMs = 2.0,
                surface = Measurement.SURFACE_DRY,
                notes = "рядом стройка, слышно",
                flagsJson = JsonCodec.encodeStrings(listOf("short")),
            )
        )

        val stored = db.measurements().byId(measurementId)
        assertNotNull(stored)
        assertEquals("P03a", stored!!.pointId)
        assertEquals(calibrationId, stored.calibrationId)
        assertEquals(series.size, JsonCodec.decodeDoubles(stored.laeqPerSecJson).size)
        assertEquals(listOf("short"), JsonCodec.decodeStrings(stored.flagsJson))
        assertEquals("рядом стройка, слышно", stored.notes)

        db.vehicleEvents().insertAll(
            listOf(
                VehicleEvent(measurementId = measurementId, tMs = 1_500, heavy = false),
                VehicleEvent(measurementId = measurementId, tMs = 4_200, heavy = true),
                VehicleEvent(measurementId = measurementId, tMs = 9_100, heavy = false),
            )
        )

        val events = db.vehicleEvents().forMeasurement(measurementId)
        assertEquals(3, events.size)
        assertEquals(1_500L, events.first().tMs)
        assertEquals(2, db.vehicleEvents().countLight(measurementId))
        assertEquals(1, db.vehicleEvents().countHeavy(measurementId))

        // «Переснять» уносит и замер, и его события.
        db.vehicleEvents().deleteForMeasurement(measurementId)
        db.measurements().deleteById(measurementId)
        assertEquals(null, db.measurements().byId(measurementId))
        assertEquals(0, db.vehicleEvents().forMeasurement(measurementId).size)
    }
}
