package dev.noisefield.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Захват и измерение уровня. Самая ответственная часть приложения: ошибка здесь
 * не видна в результате — числа выглядят правдоподобно и при этом неверны.
 *
 * Микрофон фиксирован навсегда (по умолчанию нижний), выбора нет: офсет калибровки
 * привязан к конкретному микрофону (§2.1, §8).
 *
 * Тракт отдаёт посекундные значения БЕЗ офсета. Офсет применяется выше, в сессии:
 * экран калибровки как раз и меряет сырой уровень, когда офсета ещё нет.
 */
class NoiseTract(private val context: Context) {

    /** Состояние тракта на момент открытия. Уходит в чипы на экране калибровки. */
    data class Info(
        val audioSource: String,
        val sampleRate: Int,
        val agc: EffectState,
        val ns: EffectState,
        val aec: EffectState,
    ) {
        val agcDisabled: Boolean get() = agc.isOff
        val nsDisabled: Boolean get() = ns.isOff
        val aecDisabled: Boolean get() = aec.isOff
    }

    /**
     * Состояние обработки. ABSENT и DISABLED оба означают «обработка не применяется»,
     * и в [dev.noisefield.data.Calibration] оба пишутся как true. Различие сохранено
     * для чипов: «выключен» и «недоступен» — не одно и то же, и оператору стоит
     * видеть, что именно произошло.
     */
    enum class EffectState {
        ABSENT,
        DISABLED,
        STUCK_ON;

        val isOff: Boolean get() = this != STUCK_ON
    }

    /** Одна секунда измерения. Уровни без офсета. */
    class Second(
        val rawLevelDb: Double,
        /** Октавные полосы, невзвешенные, порядок [OctaveBank.CENTERS]. */
        val octaveLevelsDb: DoubleArray,
        val clippedSamples: Long,
        val totalSamples: Long,
    )

    class TractException(message: String) : Exception(message)

    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var agc: AutomaticGainControl? = null
    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    private var worker: Thread? = null

    var info: Info? = null
        private set

    /**
     * Открывает AudioRecord и глушит обработку. Требует уже выданного RECORD_AUDIO.
     * Бросает [TractException], если 44100 Гц недоступны: коэффициенты A-взвешивания
     * посчитаны именно под эту частоту, подменять её молча нельзя.
     */
    @SuppressLint("MissingPermission")
    fun open(): Info {
        check(record == null) { "тракт уже открыт" }

        val sampleRate = AWeightingFilter.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            throw TractException("44100 Гц, моно, PCM 16 бит не поддерживаются устройством")
        }
        // Четырёхкратный запас: на записи в 15–30 минут важнее не потерять отсчёты,
        // чем сэкономить память (§2.1).
        val bufferBytes = minBuffer * 4

        val source = pickSource()
        val rec = AudioRecord(
            source.constant,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw TractException("не удалось открыть микрофон, источник " + source.label)
        }
        record = rec

        // Обработку глушим сразу после создания, по audioSessionId.
        val sessionId = rec.audioSessionId
        val result = Info(
            audioSource = source.label,
            sampleRate = sampleRate,
            agc = disableAgc(sessionId),
            ns = disableNs(sessionId),
            aec = disableAec(sessionId),
        )
        info = result
        Log.i(TAG, "тракт открыт: " + result)
        return result
    }

    /**
     * Запускает чтение в отдельном потоке. [onSecond] вызывается из этого потока
     * ровно раз на каждые [AWeightingFilter.SAMPLE_RATE] отсчётов.
     */
    fun start(onSecond: (Second) -> Unit) {
        val rec = record ?: error("тракт не открыт")
        check(!running) { "тракт уже запущен" }
        running = true
        rec.startRecording()

        worker = thread(name = "noise-tract", priority = Thread.MAX_PRIORITY) {
            // Фильтр создаётся один раз на весь замер: состояние переживает границы
            // буферов (§2.2). Сброс здесь дал бы щелчок на каждом стыке.
            val filter = AWeightingFilter()
            // Октавы считаются по невзвешенному сигналу, параллельно A-тракту:
            // спектр модели линейный, и сравнивать надо с ним.
            val octaves = OctaveBank()
            val sampleRate = AWeightingFilter.SAMPLE_RATE
            val buffer = ShortArray(4096)
            var sumSquares = 0.0
            var samplesInSecond = 0
            var clipped = 0L

            while (running) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(TAG, "AudioRecord.read вернул " + read + ", чтение остановлено")
                        break
                    }
                    continue
                }
                for (i in 0 until read) {
                    val raw = buffer[i].toInt()
                    // Клиппирование считается по сырому PCM, до взвешивания (§2.3).
                    if (abs(raw) >= CLIP_THRESHOLD) clipped++
                    val normalized = raw / 32768.0
                    octaves.process(normalized)
                    val weighted = filter.process(normalized)
                    sumSquares += weighted * weighted
                    samplesInSecond++
                    if (samplesInSecond == sampleRate) {
                        val second = Second(
                            rawLevelDb = LevelMath.levelFromMeanSquare(sumSquares / sampleRate, 0.0),
                            octaveLevelsDb = octaves.takeLevels(),
                            clippedSamples = clipped,
                            totalSamples = sampleRate.toLong(),
                        )
                        sumSquares = 0.0
                        samplesInSecond = 0
                        clipped = 0L
                        onSecond(second)
                    }
                }
            }
            // Незавершённая секунда отбрасывается: длина ряда обязана совпадать
            // с durationSec, а неполный блок дал бы уровень по другому окну.
        }
    }

    /** Останавливает поток и освобождает всё. Повторный вызов безопасен. */
    fun close() {
        running = false
        worker?.join(2_000)
        worker = null
        record?.let { rec ->
            runCatching { if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop() }
            runCatching { rec.release() }
        }
        record = null
        runCatching { agc?.release() }
        agc = null
        runCatching { ns?.release() }
        ns = null
        runCatching { aec?.release() }
        aec = null
    }

    private class Source(val constant: Int, val label: String)

    /**
     * UNPROCESSED, если устройство честно о нём заявляет, иначе VOICE_RECOGNITION.
     * Фактически использованный источник записывается в калибровку и показывается
     * на её экране.
     */
    private fun pickSource(): Source {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (supported) {
            Source(MediaRecorder.AudioSource.UNPROCESSED, SOURCE_UNPROCESSED)
        } else {
            Source(MediaRecorder.AudioSource.VOICE_RECOGNITION, SOURCE_VOICE_RECOGNITION)
        }
    }

    private fun disableAgc(sessionId: Int): EffectState {
        if (!AutomaticGainControl.isAvailable()) return EffectState.ABSENT
        val effect = runCatching { AutomaticGainControl.create(sessionId) }.getOrNull()
            ?: return EffectState.ABSENT
        agc = effect
        runCatching { effect.enabled = false }
        return if (effect.enabled) EffectState.STUCK_ON else EffectState.DISABLED
    }

    private fun disableNs(sessionId: Int): EffectState {
        if (!NoiseSuppressor.isAvailable()) return EffectState.ABSENT
        val effect = runCatching { NoiseSuppressor.create(sessionId) }.getOrNull()
            ?: return EffectState.ABSENT
        ns = effect
        runCatching { effect.enabled = false }
        return if (effect.enabled) EffectState.STUCK_ON else EffectState.DISABLED
    }

    private fun disableAec(sessionId: Int): EffectState {
        if (!AcousticEchoCanceler.isAvailable()) return EffectState.ABSENT
        val effect = runCatching { AcousticEchoCanceler.create(sessionId) }.getOrNull()
            ?: return EffectState.ABSENT
        aec = effect
        runCatching { effect.enabled = false }
        return if (effect.enabled) EffectState.STUCK_ON else EffectState.DISABLED
    }

    companion object {
        private const val TAG = "NoiseTract"

        /** Порог клиппирования по сырому 16-битному отсчёту (§2.3). */
        const val CLIP_THRESHOLD = 32700

        const val SOURCE_UNPROCESSED = "UNPROCESSED"
        const val SOURCE_VOICE_RECOGNITION = "VOICE_RECOGNITION"
    }
}
