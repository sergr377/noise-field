package dev.noisefield.data

/**
 * Инкремент ID точки: P03a → P03b → P04a.
 *
 * Буква — это член пары (фасад/двор). Дойдя до второго члена, переходим к
 * следующему номеру и снова к первой букве. ID без буквы просто нумеруется:
 * P04 → P05. Ширина числа сохраняется, чтобы P09 давало P10, а не P010.
 *
 * Результат всегда можно переписать руками — правило угадывает, а не диктует.
 */
object PointIds {

    private val WITH_LETTER = Regex("^(.*?)(\\d+)([A-Za-z])$")
    private val WITH_NUMBER = Regex("^(.*?)(\\d+)$")

    fun next(previous: String?): String {
        val id = previous?.trim().orEmpty()
        if (id.isEmpty()) return DEFAULT

        WITH_LETTER.matchEntire(id)?.let { m ->
            val (prefix, digits, letter) = m.destructured
            return if (letter.equals(FIRST_LETTER, ignoreCase = true)) {
                prefix + digits + secondLetter(letter)
            } else {
                prefix + bump(digits) + firstLetter(letter)
            }
        }

        WITH_NUMBER.matchEntire(id)?.let { m ->
            val (prefix, digits) = m.destructured
            return prefix + bump(digits)
        }

        return id
    }

    /** Увеличивает число на единицу, сохраняя ведущие нули. */
    private fun bump(digits: String): String {
        val value = digits.toLongOrNull()?.plus(1) ?: return digits
        val text = value.toString()
        return if (text.length >= digits.length) text else text.padStart(digits.length, '0')
    }

    private fun firstLetter(sample: String) = if (sample[0].isUpperCase()) "A" else "a"
    private fun secondLetter(sample: String) = if (sample[0].isUpperCase()) "B" else "b"

    private const val FIRST_LETTER = "a"
    const val DEFAULT = "P01a"
}
