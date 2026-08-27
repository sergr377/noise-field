package dev.noisefield.data

import java.util.Locale

/** Пара «прибор / приложение» одной строки калибровки. */
data class CalPair(val refDb: Double, val appDb: Double) {
    val deltaDb: Double get() = refDb - appDb
}

/**
 * Кодирование рядов и списков в JSON. Написано вручную, а не через org.json:
 * форм здесь ровно три, а JVM-тестам нужен код, работающий без Android.
 */
object JsonCodec {

    fun encodeDoubles(values: List<Double>, decimals: Int = 1): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ",") { format(it, decimals) }

    fun decodeDoubles(json: String?): List<Double> {
        val body = json?.trim()?.removeSurrounding("[", "]")?.trim().orEmpty()
        if (body.isEmpty()) return emptyList()
        return body.split(',').mapNotNull { it.trim().toDoubleOrNull() }
    }

    /** Октавные полосы по секундам: [[..8 значений..], ...]. */
    fun encodeMatrix(rows: List<DoubleArray>, decimals: Int = 1): String =
        rows.joinToString(prefix = "[", postfix = "]", separator = ",") { row ->
            row.joinToString(prefix = "[", postfix = "]", separator = ",") { format(it, decimals) }
        }

    fun decodeMatrix(json: String?): List<DoubleArray> {
        if (json.isNullOrBlank()) return emptyList()
        return ROW_RE.findAll(json.trim().removeSurrounding("[", "]"))
            .map { match -> decodeDoubles(match.value).toDoubleArray() }
            .toList()
    }

    private val ROW_RE = Regex("\\[[^\\[\\]]*]")

    fun encodeStrings(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"" + escape(it) + "\"" }

    fun decodeStrings(json: String?): List<String> {
        val body = json?.trim()?.removeSurrounding("[", "]")?.trim().orEmpty()
        if (body.isEmpty()) return emptyList()
        return body.split(',')
            .map { unescape(it.trim().removeSurrounding("\"")) }
            .filter { it.isNotEmpty() }
    }

    fun encodePairs(pairs: List<CalPair>): String =
        pairs.joinToString(prefix = "[", postfix = "]", separator = ",") {
            "{\"refDb\":" + format(it.refDb, 1) + ",\"appDb\":" + format(it.appDb, 1) + "}"
        }

    fun decodePairs(json: String?): List<CalPair> {
        if (json.isNullOrBlank()) return emptyList()
        return PAIR_RE.findAll(json)
            .map { CalPair(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
            .toList()
    }

    private val PAIR_RE = Regex(
        "\\{\\s*\"refDb\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*\"appDb\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)\\s*}"
    )

    private fun format(value: Double, decimals: Int): String =
        String.format(Locale.US, "%." + decimals + "f", value)

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\")
}
