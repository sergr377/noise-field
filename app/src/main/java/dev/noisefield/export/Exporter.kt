package dev.noisefield.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.noisefield.data.Calibration
import dev.noisefield.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Выгрузка четырёх CSV и отдача их наружу через ACTION_SEND_MULTIPLE.
 * Файлы кладутся в cache/export и раздаются FileProvider'ом — наружу уходят
 * только уровни, аудио не сохраняется ни в каком виде (§8).
 */
class Exporter(private val context: Context, private val repository: Repository) {

    data class Result(
        val intent: Intent,
        val measurementCount: Int,
    )

    class NothingToExport : Exception("за выбранный день замеров нет")

    suspend fun exportDay(dayStartMs: Long): Result = withContext(Dispatchers.IO) {
        val measurements = repository.measurementsOfDayNow(dayStartMs)
        if (measurements.isEmpty()) throw NothingToExport()

        val events = repository.eventsFor(measurements.map { it.id }).groupBy { it.measurementId }
        val calibrations = HashMap<Long, Calibration?>()
        val rows = measurements.map { m ->
            val calibration = calibrations.getOrPut(m.calibrationId) {
                repository.calibrationById(m.calibrationId)
            }
            ExportRow(
                measurement = m,
                calibration = calibration,
                counts = repository.counts(m.id),
                events = events[m.id].orEmpty(),
            )
        }

        val dir = File(context.cacheDir, "export")
        dir.mkdirs()
        // Старые выгрузки затираются: файл с прошлого выезда в шаринге — верный
        // способ отдать не те данные.
        dir.listFiles()?.forEach { it.delete() }

        val files = listOf(
            write(dir, "measurements.csv", CsvBuilder.measurements(rows)),
            write(dir, "series.csv", CsvBuilder.series(rows)),
            write(dir, "vehicles.csv", CsvBuilder.vehicles(rows)),
            write(dir, "octaves.csv", CsvBuilder.octaves(rows)),
        )

        val uris = ArrayList<Uri>(files.map { file ->
            FileProvider.getUriForFile(context, context.packageName + ".export", file)
        })

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        Result(
            intent = Intent.createChooser(intent, "Экспорт CSV"),
            measurementCount = rows.size,
        )
    }

    private fun write(dir: File, name: String, content: String): File {
        val file = File(dir, name)
        file.writeText(content, StandardCharsets.UTF_8)
        return file
    }
}
