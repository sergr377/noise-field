package dev.noisefield.export

import dev.noisefield.audio.OctaveBank
import dev.noisefield.data.Calibration
import dev.noisefield.data.JsonCodec
import dev.noisefield.data.Measurement
import dev.noisefield.data.VehicleCounts
import dev.noisefield.data.VehicleEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Замер со всем, что нужно для строки CSV. */
data class ExportRow(
    val measurement: Measurement,
    val calibration: Calibration?,
    val counts: VehicleCounts,
    val events: List<VehicleEvent>,
)

/**
 * Сборка трёх плоских таблиц. Три файла, а не один с вложенным JSON: их читает
 * скрипт на Node, и плоские таблицы разбираются без парсера (§6).
 *
 * Разделитель — запятая, десятичная — точка, кодировка UTF-8, перевод строки \n,
 * экранирование по RFC 4180.
 */
object CsvBuilder {

    const val MEASUREMENTS_HEADER =
        "id,point_id,lat,lon,gps_accuracy_m,datetime_iso,duration_sec,laeq,lamax,la90,la50," +
            "la10,clip_ratio,mic_height_m,dist_to_road_m,dist_to_facade_m,light_total," +
            "heavy_total,light_per_min,heavy_per_min,wind_ms,surface,pair_id,pair_role," +
            "offset_db,spread_db,audio_source,device,flags,notes"

    const val SERIES_HEADER = "measurement_id,t_sec,laeq_1s"

    const val VEHICLES_HEADER = "measurement_id,t_ms,heavy"

    val OCTAVES_HEADER = "measurement_id,t_sec," +
        OctaveBank.CENTERS.joinToString(",") { "hz_" + it.toInt() }

    /**
     * Высота микрофона. Константа, отдельным столбцом — чтобы совпадать с высотой
     * приёмников в валидационном прогоне модели (§6).
     */
    const val MIC_HEIGHT_M = 1.5

    const val NEWLINE = "\n"

    /**
     * Ключ, по которому series.csv, vehicles.csv и octaves.csv связываются
     * с measurements.csv, — суррогатный id замера.
     *
     * ID точки для этого не годится и не должен: повторный замер тех же точек
     * через несколько дней — обязательная часть датасета, разброс между днями даёт
     * порог достижимой точности. Повторы point_id — норма, а не ошибка.
     */
    fun rowId(measurement: Measurement): String = measurement.id.toString()

    fun measurements(rows: List<ExportRow>, zone: ZoneId = ZoneId.systemDefault()): String {
        val sb = StringBuilder()
        sb.append(MEASUREMENTS_HEADER).append(NEWLINE)
        for (row in rows) {
            val m = row.measurement
            val c = row.calibration
            val flags = JsonCodec.decodeStrings(m.flagsJson).joinToString(";")
            val cells = listOf(
                rowId(m),
                m.pointId,
                num(m.lat, 7),
                num(m.lon, 7),
                accuracy(m.gpsAccuracyM),
                isoLocal(m.startedAt, zone),
                m.durationSec.toString(),
                num(m.laeq, 1),
                num(m.laMax, 1),
                num(m.la90, 1),
                num(m.la50, 1),
                num(m.la10, 1),
                num(m.clipRatio, 6),
                num(MIC_HEIGHT_M, 1),
                numOrEmpty(m.distRoadM, 1),
                numOrEmpty(m.distFacadeM, 1),
                row.counts.light.toString(),
                row.counts.heavy.toString(),
                num(perMinute(row.counts.light, m.durationSec), 2),
                num(perMinute(row.counts.heavy, m.durationSec), 2),
                numOrEmpty(m.windMs, 1),
                m.surface,
                m.pairId.orEmpty(),
                m.pairRole.orEmpty(),
                numOrEmpty(c?.offsetDb, 2),
                numOrEmpty(c?.spreadDb, 2),
                c?.audioSource.orEmpty(),
                c?.device.orEmpty(),
                flags,
                m.notes.orEmpty(),
            )
            sb.append(cells.joinToString(",") { escape(it) }).append(NEWLINE)
        }
        return sb.toString()
    }

    /**
     * Посекундные ряды.
     *
     * t_sec — номер секундного блока от начала замера, отсчёт от нуля.
     * t_sec = 0 — это НАЧАЛО первого блока; сам блок покрывает интервал
     * [t_sec, t_sec + 1). Уровень в строке относится ко всему блоку, а не к моменту.
     */
    fun series(rows: List<ExportRow>): String {
        val sb = StringBuilder()
        sb.append(SERIES_HEADER).append(NEWLINE)
        for (row in rows) {
            val id = escape(rowId(row.measurement))
            val values = JsonCodec.decodeDoubles(row.measurement.laeqPerSecJson)
            for (i in values.indices) {
                sb.append(id).append(',').append(i).append(',').append(num(values[i], 1)).append(NEWLINE)
            }
        }
        return sb.toString()
    }

    /**
     * События счётчика.
     *
     * t_ms — миллисекунды от момента старта замера, t_ms = 0 — сам старт.
     * heavy пишется как 0/1: файл разбирается без парсера булевых.
     */
    fun vehicles(rows: List<ExportRow>): String {
        val sb = StringBuilder()
        sb.append(VEHICLES_HEADER).append(NEWLINE)
        for (row in rows) {
            val id = escape(rowId(row.measurement))
            for (event in row.events.sortedBy { it.tMs }) {
                sb.append(id).append(',').append(event.tMs).append(',')
                    .append(if (event.heavy) '1' else '0').append(NEWLINE)
            }
        }
        return sb.toString()
    }

    /**
     * Октавные полосы по секундам, широкая форма: одна строка на секунду,
     * по колонке на полосу. t_sec — тот же, что в series.csv.
     *
     * Уровни НЕвзвешенные (линейные), офсет калибровки применён. Замеры, снятые
     * до появления октав, строк здесь не дают.
     */
    fun octaves(rows: List<ExportRow>): String {
        val sb = StringBuilder()
        sb.append(OCTAVES_HEADER).append(NEWLINE)
        for (row in rows) {
            val id = escape(rowId(row.measurement))
            val bands = JsonCodec.decodeMatrix(row.measurement.octavesPerSecJson)
            for (t in bands.indices) {
                sb.append(id).append(',').append(t)
                for (value in bands[t]) sb.append(',').append(num(value, 1))
                sb.append(NEWLINE)
            }
        }
        return sb.toString()
    }

    /** Локальное время со смещением: 2026-06-18T11:22:41+03:00. */
    fun isoLocal(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs)
            .atZone(zone)
            .truncatedTo(ChronoUnit.SECONDS)
            .toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /** Точность без фикса (отрицательная) уходит пустым полем, а не числом-выдумкой. */
    private fun accuracy(value: Double): String =
        if (value < 0.0 || !value.isFinite()) "" else num(value, 1)

    private fun perMinute(count: Int, durationSec: Int): Double =
        if (durationSec <= 0) 0.0 else count * 60.0 / durationSec

    private fun num(value: Double, decimals: Int): String {
        if (value.isNaN() || value.isInfinite()) return ""
        return String.format(Locale.US, "%." + decimals + "f", value)
    }

    private fun numOrEmpty(value: Double?, decimals: Int): String =
        if (value == null) "" else num(value, decimals)

    /** RFC 4180: кавычки, запятые и переводы строк в заметке. */
    fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
