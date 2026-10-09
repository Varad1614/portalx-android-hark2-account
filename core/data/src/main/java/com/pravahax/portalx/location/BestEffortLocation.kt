package com.pravahax.portalx.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** One position reading attached to a check-in/out. Metadata only: it never gates anything. */
data class Fix(val latitude: Double, val longitude: Double, val accuracyMeters: Float?) {
    /** Text parts for the multipart form (fixed decimals: Double.toString would give "1.0E-5" near 0°). */
    fun formFields(): Map<String, String> = buildMap {
        put("latitude", String.format(Locale.US, "%.6f", latitude))
        put("longitude", String.format(Locale.US, "%.6f", longitude))
        accuracyMeters?.takeIf { it.isFinite() && it >= 0f }?.let { put("accuracyMeters", String.format(Locale.US, "%.1f", it)) }
    }
}

fun interface LocationSource {
    /** A single fix, or null. Must never throw for "no permission / no provider / no fix". */
    suspend fun currentFix(): Fix?
}

object BestEffortLocation {
    const val TIMEOUT_MS = 5_000L
    /** A last-known fix older than this is not "where you checked in". */
    const val MAX_LAST_KNOWN_AGE_MS = 10 * 60 * 1000L
    const val PREFS = "portal_prefs"
    private const val ASKED_KEY = "locationPermissionAsked"
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

    fun granted(ctx: Context): Boolean = PERMISSIONS.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    /** Show the rationale + system prompt only the first time (never nag on every check-in). */
    fun shouldAsk(ctx: Context): Boolean = !granted(ctx) && !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED_KEY, false)
    fun markAsked(ctx: Context) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED_KEY, true).apply() }

    /** Runs [source] with a hard timeout; any failure is "no location". */
    suspend fun fix(source: LocationSource, timeoutMs: Long = TIMEOUT_MS): Fix? = try {
        withTimeoutOrNull(timeoutMs) { source.currentFix() }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
}

/**
 * Platform LocationManager only (no Google Play Services). API 30+: getCurrentLocation on the best enabled
 * provider, falling back to a recent last-known fix. API 26–29: the freshest recent last-known fix.
 */
class PlatformLocationSource(private val ctx: Context) : LocationSource {
    @SuppressLint("MissingPermission")
    override suspend fun currentFix(): Fix? = withContext(Dispatchers.Main.immediate) {
        if (!BestEffortLocation.granted(ctx)) return@withContext null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@withContext null
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val preferred = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { it in enabled }
        // Leave headroom inside the caller's 5 s budget for the last-known fallback.
        val current = if (Build.VERSION.SDK_INT >= 30 && preferred.isNotEmpty())
            withTimeoutOrNull(BestEffortLocation.TIMEOUT_MS - 1_000) { current(lm, preferred.first()) } else null
        (current ?: lastKnown(lm, enabled))?.let { Fix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun current(lm: LocationManager, provider: String): Location? = try {
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { runCatching { signal.cancel() } }
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(ctx)) { loc -> if (cont.isActive) cont.resume(loc) }
            } else cont.resume(null)
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager, providers: List<String>): Location? {
        val now = SystemClock.elapsedRealtimeNanos()
        return providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .filter { (now - it.elapsedRealtimeNanos) / 1_000_000 <= BestEffortLocation.MAX_LAST_KNOWN_AGE_MS }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }
}
