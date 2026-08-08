package dev.yaver.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where the user is.
 *
 * Uses the platform LocationManager rather than Play Services: no dependency,
 * no Google Play requirement, and for "what is near me" a fix from the last
 * few minutes is as good as a fresh one.
 *
 * Coarse permission only. Street-level precision buys nothing for finding a
 * pharmacy or a restaurant, and asking for less is the difference between a
 * permission people grant and one they think about.
 */
object Whereabouts {

    const val REQUEST_CODE = 6120
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** A fix older than this is worth refreshing. */
    private const val STALE_MS = 10 * 60 * 1000L

    data class Fix(val latitude: Double, val longitude: Double, val accuracyMetres: Float, val ageMinutes: Long)

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun request(activity: Activity) {
        activity.requestPermissions(PERMISSIONS, REQUEST_CODE)
    }

    private fun best(manager: LocationManager): Location? {
        val providers = try { manager.getProviders(true) } catch (e: Exception) { emptyList<String>() }
        var found: Location? = null
        for (provider in providers) {
            val candidate = try {
                @Suppress("MissingPermission")
                manager.getLastKnownLocation(provider)
            } catch (e: SecurityException) { null } catch (e: Exception) { null }
            if (candidate == null) continue
            if (found == null || candidate.time > found.time) found = candidate
        }
        return found
    }

    /**
     * A cached fix if it is recent, otherwise one live reading. Never blocks
     * for long: an agent waiting thirty seconds for a GPS lock is an agent that
     * looks broken.
     */
    fun current(context: Context, waitMs: Long = 8000): Fix? {
        if (!granted(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        best(manager)?.let { cached ->
            val age = System.currentTimeMillis() - cached.time
            if (age < STALE_MS) return cached.toFix()
        }

        val latch = CountDownLatch(1)
        var live: Location? = null
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                live = location
                latch.countDown()
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) { latch.countDown() }
            @Deprecated("required by the interface on older versions")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }

        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return best(manager)?.toFix()
        }

        return try {
            @Suppress("MissingPermission")
            manager.requestLocationUpdates(provider, 0L, 0f, listener, android.os.Looper.getMainLooper())
            latch.await(waitMs, TimeUnit.MILLISECONDS)
            manager.removeUpdates(listener)
            (live ?: best(manager))?.toFix()
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            Log.error("location failed: ${e.message}")
            best(manager)?.toFix()
        }
    }

    private fun Location.toFix() = Fix(
        latitude = latitude,
        longitude = longitude,
        accuracyMetres = accuracy,
        ageMinutes = (System.currentTimeMillis() - time) / 60000
    )
}
