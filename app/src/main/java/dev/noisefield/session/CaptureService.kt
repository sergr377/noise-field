package dev.noisefield.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.noisefield.Graph
import dev.noisefield.MainActivity
import dev.noisefield.R
import dev.noisefield.audio.NoiseTract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Захват идёт в foreground service с типом microphone|location: иначе замер
 * на 15–30 минут оборвётся, как только погаснет экран (§2.5).
 *
 * Wake lock — только partial. Экран гасить можно и нужно, замер это переживает.
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private var tract: NoiseTract? = null
    private var watcher: LocationWatcher? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundNow()
                begin(intent)
            }
            ACTION_STOP -> finish()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        if (tract != null) return

        val kind = if (intent.getStringExtra(EXTRA_KIND) == CaptureKind.CALIBRATION.name) {
            CaptureKind.CALIBRATION
        } else {
            CaptureKind.MEASUREMENT
        }
        val plannedSec = intent.getIntExtra(EXTRA_PLANNED_SEC, 0).takeIf { it > 0 }
        val offsetDb = intent.getDoubleExtra(EXTRA_OFFSET_DB, 0.0)
        val calibrationId = intent.getLongExtra(EXTRA_CALIBRATION_ID, 0L)
        val initialFix = if (intent.hasExtra(EXTRA_FIX_LAT)) {
            Fix(
                lat = intent.getDoubleExtra(EXTRA_FIX_LAT, 0.0),
                lon = intent.getDoubleExtra(EXTRA_FIX_LON, 0.0),
                accuracyM = intent.getDoubleExtra(EXTRA_FIX_ACC, Double.MAX_VALUE),
                atMs = System.currentTimeMillis(),
            )
        } else {
            null
        }

        val newTract = NoiseTract(this)
        val info = try {
            newTract.open()
        } catch (e: Exception) {
            Log.e(TAG, "тракт не открылся", e)
            CaptureBus.set(
                CaptureState(
                    phase = CapturePhase.FAILED,
                    kind = kind,
                    error = e.message ?: "микрофон недоступен",
                )
            )
            newTract.close()
            stopSelf()
            return
        }
        tract = newTract

        CaptureBus.set(
            CaptureState(
                phase = CapturePhase.RUNNING,
                kind = kind,
                startedAt = System.currentTimeMillis(),
                plannedSec = plannedSec,
                fixes = listOfNotNull(initialFix),
                tract = info,
                offsetDb = offsetDb,
                calibrationId = calibrationId,
            )
        )

        acquireWakeLock()
        startLocation()

        newTract.start { second ->
            // Офсет прикладывается здесь: на экране и в базе должно быть одно
            // и то же число (§1, «уровни хранятся уже с офсетом»).
            val level = second.rawLevelDb + offsetDb
            val bands = DoubleArray(second.octaveLevelsDb.size) { second.octaveLevelsDb[it] + offsetDb }
            var reachedPlan = false
            CaptureBus.update { s ->
                if (s.phase != CapturePhase.RUNNING) return@update s
                val series = s.series + level
                reachedPlan = s.plannedSec != null && series.size >= s.plannedSec
                s.copy(
                    series = series,
                    octaves = s.octaves + bands,
                    clippedSamples = s.clippedSamples + second.clippedSamples,
                    totalSamples = s.totalSamples + second.totalSamples,
                )
            }
            main.post { updateNotification() }
            if (reachedPlan) main.post { finish() }
        }
    }

    private fun startLocation() {
        val w = LocationWatcher(this)
        watcher = w
        w.start { fix ->
            CaptureBus.update { s ->
                if (s.phase != CapturePhase.RUNNING) return@update s
                // Копим все фиксы: координаты потом берутся медианой по хорошим,
                // точность — от лучшего, флаг gps_poor — от худшего.
                s.copy(fixes = s.fixes + fix)
            }
        }
    }

    /** Завершение: по таймеру или кнопкой. */
    private fun finish() {
        if (stopping) return
        stopping = true

        tract?.close()
        tract = null
        watcher?.stop()
        watcher = null
        releaseWakeLock()

        val state = CaptureBus.state.value
        if (state.phase != CapturePhase.RUNNING) {
            stopForegroundAndSelf()
            return
        }

        if (state.kind == CaptureKind.CALIBRATION) {
            // Проба калибровки в базу не идёт: её результат — одно число,
            // которое подставляется в строку на экране калибровки.
            CaptureBus.update { it.copy(phase = CapturePhase.DONE) }
            stopForegroundAndSelf()
            return
        }

        CaptureBus.update { it.copy(phase = CapturePhase.SAVING) }
        scope.launch {
            val saved = runCatching {
                Graph.repository.finishMeasurement(state)
            }
            CaptureBus.update {
                saved.fold(
                    onSuccess = { id -> it.copy(phase = CapturePhase.DONE, savedMeasurementId = id) },
                    onFailure = { e ->
                        Log.e(TAG, "замер не сохранился", e)
                        it.copy(phase = CapturePhase.FAILED, error = e.message ?: "не удалось сохранить замер")
                    },
                )
            }
            main.post { stopForegroundAndSelf() }
        }
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        tract?.close()
        tract = null
        watcher?.stop()
        watcher = null
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ---- уведомление ----

    private fun startForegroundNow() {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        if (stopping) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = CaptureBus.state.value
        val elapsed = state.elapsedSec
        val timer = String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
        val planned = state.plannedSec
        val progress = if (planned != null) {
            timer + " из " + String.format(Locale.US, "%02d:%02d", planned / 60, planned % 60)
        } else {
            timer
        }
        val title = if (state.kind == CaptureKind.CALIBRATION) "Проба калибровки" else "Замер идёт"
        val level = if (elapsed > 0) String.format(Locale.US, "%.1f дБ(A)", state.laeq) else "—"

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title + " · " + level)
            .setContentText(progress)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Остановить", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ---- wake lock ----

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(MAX_CAPTURE_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "noise-field:capture"

        /** Страховка от зависшего замера: дольше двух часов держать лок незачем. */
        private const val MAX_CAPTURE_MS = 2L * 60L * 60L * 1000L

        const val ACTION_START = "dev.noisefield.START"
        const val ACTION_STOP = "dev.noisefield.STOP"

        private const val EXTRA_KIND = "kind"
        private const val EXTRA_PLANNED_SEC = "planned_sec"
        private const val EXTRA_OFFSET_DB = "offset_db"
        private const val EXTRA_CALIBRATION_ID = "calibration_id"
        private const val EXTRA_FIX_LAT = "fix_lat"
        private const val EXTRA_FIX_LON = "fix_lon"
        private const val EXTRA_FIX_ACC = "fix_acc"

        fun deviceName(): String = (Build.MANUFACTURER + " " + Build.MODEL).trim()

        fun start(
            context: Context,
            kind: CaptureKind,
            plannedSec: Int?,
            offsetDb: Double,
            calibrationId: Long,
            fix: Fix?,
        ) {
            val intent = Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_KIND, kind.name)
                .putExtra(EXTRA_PLANNED_SEC, plannedSec ?: 0)
                .putExtra(EXTRA_OFFSET_DB, offsetDb)
                .putExtra(EXTRA_CALIBRATION_ID, calibrationId)
            if (fix != null) {
                intent.putExtra(EXTRA_FIX_LAT, fix.lat)
                intent.putExtra(EXTRA_FIX_LON, fix.lon)
                intent.putExtra(EXTRA_FIX_ACC, fix.accuracyM)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
