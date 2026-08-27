package dev.noisefield.session

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/** Один фикс GPS. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val atMs: Long,
)

/**
 * Подписка на GPS. Намеренно на голом LocationManager, без Play Services:
 * работа полевая и офлайновая, лишняя зависимость на сервисы Google тут только
 * добавляет способов не запуститься.
 *
 * Спутниковый провайдер выбран сознательно — сетевая геолокация даёт точность
 * в сотни метров, а порог допуска к старту замера равен 10 м.
 */
class LocationWatcher(private val context: Context) {

    private val manager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var listener: LocationListener? = null

    val gpsEnabled: Boolean
        get() = runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun start(onFix: (Fix) -> Unit) {
        if (listener != null) return
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onFix(
                    Fix(
                        lat = location.latitude,
                        lon = location.longitude,
                        // Точность без оценки считаем негодной, а не идеальной.
                        accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.MAX_VALUE,
                        atMs = System.currentTimeMillis(),
                    )
                )
            }

            @Deprecated("нужен для совместимости с API 26")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        listener = l
        runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                UPDATE_INTERVAL_MS,
                0f,
                l,
                Looper.getMainLooper(),
            )
        }
        // Последний известный фикс — чтобы экран не был пустым первые секунды.
        runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
            .getOrNull()
            ?.takeIf { it.hasAccuracy() && System.currentTimeMillis() - it.time < LAST_FIX_MAX_AGE_MS }
            ?.let { l.onLocationChanged(it) }
    }

    fun stop() {
        listener?.let { runCatching { manager.removeUpdates(it) } }
        listener = null
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
        const val LAST_FIX_MAX_AGE_MS = 60_000L
    }
}
