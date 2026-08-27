package dev.noisefield.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Calibration::class, Measurement::class, VehicleEvent::class],
    version = 1,
    exportSchema = true,
)
abstract class NoiseDb : RoomDatabase() {
    abstract fun calibrations(): CalibrationDao
    abstract fun measurements(): MeasurementDao
    abstract fun vehicleEvents(): VehicleEventDao

    companion object {
        fun open(context: Context): NoiseDb =
            Room.databaseBuilder(context.applicationContext, NoiseDb::class.java, "noise-field.db")
                .build()
    }
}
