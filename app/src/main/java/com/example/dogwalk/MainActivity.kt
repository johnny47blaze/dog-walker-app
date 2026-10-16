package com.example.dogwalk

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================================
// 1. WEATHER, HOURLY WALKING WINDOWS & SHADE SCORE ENGINE
// ============================================================================

enum class SafetyLevel { GO, CAUTION, UNSAFE }
enum class WindowRating(val label: String) {
    PERFECT("PERFECT"),
    GOOD("GOOD"),
    CAUTION("CAUTION"),
    AVOID("AVOID")
}

data class HourlyWalkWindow(
    val hour24: Int,
    val timeLabel: String,
    val airTempF: Int,
    val pavementTempF: Int,
    val rainProb: Int,
    val shadePercent: Int,
    val rating: WindowRating
)

data class DogWeatherReport(
    val airTempF: Double,
    val feelsLikeF: Double,
    val pavementTempF: Double,
    val rainInch: Double,
    val windGustMph: Double,
    val solarRadWm2: Double,
    val cloudCoverPct: Int,
    val shadePercent: Int,
    val usAqi: Int,
    val status: SafetyLevel,
    val reason: String,
    val hourlyWindows: List<HourlyWalkWindow>,
    val bestWindowsSummary: String
) {
    fun evaluateForMode(isStroller: Boolean): Pair<SafetyLevel, String> {
        return WeatherRepository.evaluateThresholds(
            airTemp = airTempF,
            feelsLike = feelsLikeF,
            pavement = pavementTempF,
            rain = rainInch,
            gusts = windGustMph,
            aqi = usAqi,
            isStroller = isStroller
        )
    }
}

object WeatherRepository {
    suspend fun fetchDogWeather(lat: Double, lon: Double): Result<DogWeatherReport> =
        withContext(Dispatchers.IO) {
            try {
                val weatherUrl = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,apparent_temperature,precipitation,wind_gusts_10m,shortwave_radiation,cloud_cover" +
                    "&hourly=temperature_2m,apparent_temperature,precipitation_probability,precipitation,wind_gusts_10m,shortwave_radiation,cloud_cover" +
                    "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" +
                    "&forecast_days=2&timezone=auto"

                val aqiUrl = "https://air-quality-api.open-meteo.com/v1/air-quality" +
                    "?latitude=$lat&longitude=$lon&current=us_aqi"

                val rootJson = JSONObject(getUrlText(weatherUrl))
                val weatherJson = rootJson.getJSONObject("current")
                val aqiJson = runCatching {
                    JSONObject(getUrlText(aqiUrl)).getJSONObject("current")
                }.getOrNull()

                val airTemp = weatherJson.optDouble("temperature_2m", 60.0)
                val feelsLike = weatherJson.optDouble("apparent_temperature", airTemp)
                val rain = weatherJson.optDouble("precipitation", 0.0)
                val gusts = weatherJson.optDouble("wind_gusts_10m", 0.0)
                val solarRad = weatherJson.optDouble("shortwave_radiation", 0.0)
                val clouds = weatherJson.optInt("cloud_cover", 20)
                val aqi = aqiJson?.optInt("us_aqi", 25) ?: 25

                val estPavement = ((airTemp + (0.045 * solarRad)) * 10.0).roundToInt() / 10.0
                val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val currentShade = calculateShadePercentage(nowHour, solarRad, clouds)

                val (status, reason) = evaluateThresholds(
                    airTemp = airTemp,
                    feelsLike = feelsLike,
                    pavement = estPavement,
                    rain = rain,
                    gusts = gusts,
                    aqi = aqi,
                    isStroller = false
                )

                val hourlyList = parseHourlyWindows(rootJson.optJSONObject("hourly"), aqi)
                val summary = summarizeBestWindows(hourlyList)

                Result.success(
                    DogWeatherReport(
                        airTempF = airTemp,
                        feelsLikeF = feelsLike,
                        pavementTempF = estPavement,
                        rainInch = rain,
                        windGustMph = gusts,
                        solarRadWm2 = solarRad,
                        cloudCoverPct = clouds,
                        shadePercent = currentShade,
                        usAqi = aqi,
                        status = status,
                        reason = reason,
                        hourlyWindows = hourlyList,
                        bestWindowsSummary = summary
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // Estimates street-level shadow/shade availability (0% = blazing overhead sun, 100% = full shade/night)
    fun calculateShadePercentage(hour24: Int, solarRad: Double, cloudCover: Int): Int {
        if (solarRad <= 15.0 || hour24 <= 6 || hour24 >= 20) return 100
        // Midday (11 AM - 3 PM) has high sun elevation -> short shadows; morning/late afternoon casts long tree/building shadows
        val sunAnglePenalty = when (hour24) {
            12, 13 -> 1.0
            11, 14 -> 0.85
            10, 15 -> 0.65
            9, 16 -> 0.45
            else -> 0.25
        }
        val effectiveSun = (solarRad / 850.0).coerceIn(0.0, 1.0) * sunAnglePenalty * (1.0 - (cloudCover / 130.0))
        return ((1.0 - effectiveSun) * 100.0).roundToInt().coerceIn(10, 100)
    }

    private fun parseHourlyWindows(hourlyObj: JSONObject?, currentAqi: Int): List<HourlyWalkWindow> {
        if (hourlyObj == null) return emptyList()
        val times = hourlyObj.optJSONArray("time") ?: return emptyList()
        val temps = hourlyObj.optJSONArray("temperature_2m") ?: return emptyList()
        val feels = hourlyObj.optJSONArray("apparent_temperature")
        val rainProbs = hourlyObj.optJSONArray("precipitation_probability")
        val rains = hourlyObj.optJSONArray("precipitation")
        val gusts = hourlyObj.optJSONArray("wind_gusts_10m")
        val solars = hourlyObj.optJSONArray("shortwave_radiation")
        val clouds = hourlyObj.optJSONArray("cloud_cover")

        val nowCal = Calendar.getInstance()
        val currentHourPrefix = SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).format(nowCal.time)

        var startIndex = 0
        for (i in 0 until times.length()) {
            if (times.optString(i).startsWith(currentHourPrefix)) {
                startIndex = i
                break
            }
        }

        val windows = mutableListOf<HourlyWalkWindow>()
        val endIndex = min(times.length(), startIndex + 18)

        for (i in startIndex until endIndex) {
            val rawTime = times.optString(i) // "2026-10-15T16:00"
            val hourPart = rawTime.substringAfter("T").substringBefore(":").toIntOrNull() ?: 12
            val label = if (i == startIndex) {
                "Now"
            } else {
                when {
                    hourPart == 0 -> "12 AM"
                    hourPart < 12 -> "$hourPart AM"
                    hourPart == 12 -> "12 PM"
                    else -> "${hourPart - 12} PM"
                }
            }

            val tAir = temps.optDouble(i, 60.0)
            val tFeel = feels?.optDouble(i, tAir) ?: tAir
            val rProb = rainProbs?.optInt(i, 0) ?: 0
            val rInch = rains?.optDouble(i, 0.0) ?: 0.0
            val wGust = gusts?.optDouble(i, 0.0) ?: 0.0
            val sRad = solars?.optDouble(i, 0.0) ?: 0.0
            val cPct = clouds?.optInt(i, 20) ?: 20

            val tPave = tAir + (0.045 * sRad)
            val shadePct = calculateShadePercentage(hourPart, sRad, cPct)

            val rating = when {
                tPave >= 115.0 || tFeel < 32.0 || tAir >= 80.0 || rInch > 0.08 || rProb >= 65 || wGust >= 25.0 || currentAqi > 100 ->
                    WindowRating.AVOID
                tPave in 50.0..95.0 && tFeel in 52.0..72.0 && rProb < 20 && wGust < 15.0 && hourPart in 6..20 ->
                    WindowRating.PERFECT
                tPave < 105.0 && tFeel in 42.0..75.0 && rProb < 35 && wGust < 20.0 ->
                    WindowRating.GOOD
                else ->
                    WindowRating.CAUTION
            }

            windows.add(
                HourlyWalkWindow(
                    hour24 = hourPart,
                    timeLabel = label,
                    airTempF = tAir.roundToInt(),
                    pavementTempF = tPave.roundToInt(),
                    rainProb = rProb,
                    shadePercent = shadePct,
                    rating = rating
                )
            )
        }
        return windows
    }

    private fun summarizeBestWindows(windows: List<HourlyWalkWindow>): String {
        val goodHours = windows.filter {
            (it.rating == WindowRating.PERFECT || it.rating == WindowRating.GOOD) && it.hour24 in 6..21
        }
        if (goodHours.isEmpty()) {
            val cautionHours = windows.filter { it.rating == WindowRating.CAUTION && it.hour24 in 6..21 }
            return if (cautionHours.isNotEmpty()) {
                "Best Available: ${cautionHours.take(3).joinToString(", ") { it.timeLabel }} (Use caution)"
            } else {
                "Rough weather today — use Stroller Mode or short potty breaks"
            }
        }
        val topLabels = goodHours.take(4).map { it.timeLabel }
        return "Best Walk Windows: ${topLabels.joinToString(", ")}"
    }

    fun evaluateThresholds(
        airTemp: Double,
        feelsLike: Double,
        pavement: Double,
        rain: Double,
        gusts: Double,
        aqi: Int,
        isStroller: Boolean
    ): Pair<SafetyLevel, String> {
        val unsafeReasons = mutableListOf<String>()
        val cautionReasons = mutableListOf<String>()

        if (!isStroller) {
            if (pavement >= 118.0) unsafeReasons.add("Pavement burn risk (${pavement}°F)")
            else if (pavement >= 104.0) cautionReasons.add("Warm pavement (${pavement}°F - use grass/stroller)")
        } else if (pavement >= 104.0) {
            cautionReasons.add("Stroller ON: Paws safe from ${pavement}°F asphalt")
        }

        if (feelsLike < 25.0) {
            unsafeReasons.add("Extreme cold (${feelsLike}°F)")
        } else if (feelsLike < 35.0 && !isStroller) {
            unsafeReasons.add("Freezing wind chill (${feelsLike}°F - use stroller/coat)")
        } else if (feelsLike < 50.0) {
            cautionReasons.add(if (isStroller) "Chilly (${feelsLike}°F - blanket in stroller)" else "Chilly (${feelsLike}°F - coat recommended)")
        }

        if (airTemp >= 85.0) {
            unsafeReasons.add("High heat risk (${airTemp}°F - stroller traps heat)")
        } else if (airTemp >= 78.0 && !isStroller) {
            unsafeReasons.add("Overheating risk (${airTemp}°F)")
        } else if (airTemp >= 72.0) {
            cautionReasons.add(if (isStroller) "Warm (${airTemp}°F - keep stroller canopy open)" else "Warm air (${airTemp}°F - watch for panting)")
        }

        if (rain > 0.15) unsafeReasons.add("Heavy rain (${rain} in/h)")
        else if (rain > 0.0) cautionReasons.add(if (isStroller) "Light rain (use stroller cover)" else "Light rain (${rain} in/h)")

        if (gusts >= 28.0) unsafeReasons.add("High wind (${gusts.toInt()} mph)")
        else if (gusts >= 16.0) cautionReasons.add("Breezy (${gusts.toInt()} mph)")

        if (aqi > 100) unsafeReasons.add("Poor AQI ($aqi)")
        else if (aqi > 50) cautionReasons.add("Moderate AQI ($aqi)")

        return when {
            unsafeReasons.isNotEmpty() -> SafetyLevel.UNSAFE to unsafeReasons.joinToString(" • ")
            cautionReasons.isNotEmpty() -> SafetyLevel.CAUTION to cautionReasons.joinToString(" • ")
            else -> SafetyLevel.GO to if (isStroller) "Stroller Mode ON: Optimal conditions" else "Optimal walking conditions"
        }
    }

    private fun getUrlText(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "PersonalDogWalkTracker/6.0 (Android)")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}

// ============================================================================
// 2. SHADE-AWARE OVERPASS CANOPY SCANNER & CALIBRATED OSRM ROUTE GENERATOR
// ============================================================================

data class ShadeCanopyZone(
    val center: GeoPoint,
    val radiusMeters: Double,
    val label: String
)

object ShadeRepository {
    // Queries OpenStreetMap Overpass API for nearby parks, woods, and mapped street trees
    suspend fun fetchNearbyShadeZones(center: GeoPoint): List<ShadeCanopyZone> =
        withContext(Dispatchers.IO) {
            val zones = mutableListOf<ShadeCanopyZone>()
            try {
                val d = 0.010 // ~1.1 km box around current location / Home
                val s = center.latitude - d
                val w = center.longitude - d
                val n = center.latitude + d
                val e = center.longitude + d

                val query = "[out:json][timeout:8];(" +
                    "node[\"natural\"=\"tree\"]($s,$w,$n,$e);" +
                    "way[\"leisure\"=\"park\"]($s,$w,$n,$e);" +
                    "way[\"natural\"=\"wood\"]($s,$w,$n,$e);" +
                    "way[\"landuse\"=\"forest\"]($s,$w,$n,$e);" +
                    ");out center 120;"

                val url = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 7000
                conn.readTimeout = 7000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "PersonalDogWalkTracker/6.0 (Android)")

                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val elements = json.optJSONArray("elements")
                    if (elements != null) {
                        for (i in 0 until elements.length()) {
                            val el = elements.getJSONObject(i)
                            val type = el.optString("type")
                            val tags = el.optJSONObject("tags")
                            val isTree = tags?.optString("natural") == "tree"

                            if (type == "node") {
                                val lat = el.optDouble("lat", Double.NaN)
                                val lon = el.optDouble("lon", Double.NaN)
                                if (!lat.isNaN() && !lon.isNaN()) {
                                    zones.add(ShadeCanopyZone(GeoPoint(lat, lon), if (isTree) 18.0 else 45.0, "Tree Canopy"))
                                }
                            } else {
                                val cObj = el.optJSONObject("center")
                                if (cObj != null) {
                                    val lat = cObj.optDouble("lat", Double.NaN)
                                    val lon = cObj.optDouble("lon", Double.NaN)
                                    if (!lat.isNaN() && !lon.isNaN()) {
                                        val name = tags?.optString("name")?.ifBlank { "Shaded Park / Woods" } ?: "Shaded Park"
                                        zones.add(ShadeCanopyZone(GeoPoint(lat, lon), 85.0, name))
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            zones
        }

    // Scores a compass angle (0..360 deg) by how many tree/park canopy zones lie in that direction
    fun pickShadiestBearingDeg(origin: GeoPoint, zones: List<ShadeCanopyZone>, fallbackIndex: Int): Double {
        val candidateBearings = listOf(45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0, 0.0)
        if (zones.isEmpty()) return candidateBearings[fallbackIndex % candidateBearings.size]

        val scored = candidateBearings.map { bearingDeg ->
            val rad = Math.toRadians(bearingDeg)
            val testLat = origin.latitude + (0.003 * cos(rad))
            val testLon = origin.longitude + (0.004 * sin(rad))
            val score = zones.sumOf { zone ->
                val results = FloatArray(1)
                Location.distanceBetween(testLat, testLon, zone.center.latitude, zone.center.longitude, results)
                val distM = results[0].toDouble()
                if (distM < 350.0) zone.radiusMeters / (distM + 25.0) else 0.0
            }
            bearingDeg to score
        }.sortedByDescending { it.second }

        // Cycle through top shaded directions on repeated taps
        return scored[fallbackIndex % min(3, scored.size)].first
    }
}

data class PlannedLeg(val anchor: GeoPoint, val snappedPoints: List<GeoPoint>)

object RouteSnapper {
    private const val WALKING_ROUTER = "https://routing.openstreetmap.de/routed-foot/route/v1/driving/"
    // The routed-foot instance is pedestrian-configured; the URL profile is an OSRM API label.
    // Never fall back to router.project-osrm.org: its public instance is car-oriented.

    suspend fun snapFootSegment(
        start: GeoPoint,
        end: GeoPoint,
        allowDirectFallback: Boolean = true
    ): List<GeoPoint> = withContext(Dispatchers.IO) {
        val coords = "${start.longitude},${start.latitude};${end.longitude},${end.latitude}"
        try {
            val url = WALKING_ROUTER + coords + "?overview=full&geometries=geojson"
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "PersonalDogWalkTracker/6.0 (Android)")
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val routes = json.optJSONArray("routes")
                    if (routes != null && routes.length() > 0) {
                        val coordinates = routes.getJSONObject(0).getJSONObject("geometry")
                            .getJSONArray("coordinates")
                        val points = (0 until coordinates.length()).map { index ->
                            val pair = coordinates.getJSONArray(index)
                            GeoPoint(pair.getDouble(1), pair.getDouble(0))
                        }
                        if (points.size >= 2) return@withContext points
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            // Let auto-loop reject unroutable legs; manual direct-line mode is explicitly allowed.
        }
        if (allowDirectFallback) listOf(start, end) else emptyList()
    }

    /** Returns a verified pedestrian loop or throws; never silently accepts an oversized route. */
    suspend fun generateCalibratedLoop(
        origin: GeoPoint,
        targetMiles: Double,
        bearingDeg: Double
    ): List<PlannedLeg> {
        require(targetMiles in 0.25..2.50) { "Select a distance between 0.25 and 2.50 miles." }
        val targetMeters = targetMiles * 1609.344
        val tolerance = 0.15
        // Start with a conservative triangle, then calibrate against actual routed length.
        var radiusMeters = (targetMeters / 4.0).coerceIn(55.0, 1100.0)
        var bestError = Double.POSITIVE_INFINITY
        var bestMiles = 0.0
        repeat(4) {
            val legs = buildTriangleLoop(origin, radiusMeters, bearingDeg)
            if (legs.isNotEmpty()) {
                val meters = calculatePolylineMeters(legs.flatMap { it.snappedPoints })
                val error = kotlin.math.abs(meters - targetMeters) / targetMeters
                if (error < bestError) {
                    bestError = error
                    bestMiles = meters / 1609.344
                }
                if (error <= tolerance) return legs
                // Dampen corrections: street-network geometry is discontinuous near junctions.
                val adjustment = (targetMeters / meters).coerceIn(0.55, 1.6)
                radiusMeters = (radiusMeters * adjustment).coerceIn(35.0, 1500.0)
            } else {
                radiusMeters = (radiusMeters * 0.72).coerceAtLeast(35.0)
            }
        }
        val detail = if (bestError.isFinite())
            "Closest walk was ${String.format(Locale.US, "%.2f", bestMiles)} mi. " else ""
        throw IllegalStateException(detail + "No walking loop within 15% of your target. Try another direction or distance.")
    }

    private suspend fun buildTriangleLoop(
        origin: GeoPoint,
        radiusMeters: Double,
        bearingDeg: Double
    ): List<PlannedLeg> {
        val metersPerDegLat = 111320.0
        val metersPerDegLon = (111320.0 * cos(Math.toRadians(origin.latitude))).coerceAtLeast(10000.0)
        // A wider triangle avoids nearly overlapping out-and-back legs.
        val rad1 = Math.toRadians(bearingDeg - 60.0)
        val rad2 = Math.toRadians(bearingDeg + 60.0)
        val p1 = GeoPoint(
            origin.latitude + radiusMeters * cos(rad1) / metersPerDegLat,
            origin.longitude + radiusMeters * sin(rad1) / metersPerDegLon
        )
        val p2 = GeoPoint(
            origin.latitude + radiusMeters * cos(rad2) / metersPerDegLat,
            origin.longitude + radiusMeters * sin(rad2) / metersPerDegLon
        )
        val leg1 = snapFootSegment(origin, p1, allowDirectFallback = false)
        if (leg1.size < 2) return emptyList()
        val leg2 = snapFootSegment(p1, p2, allowDirectFallback = false)
        if (leg2.size < 2) return emptyList()
        val leg3 = snapFootSegment(p2, origin, allowDirectFallback = false)
        if (leg3.size < 2) return emptyList()
        return listOf(
            PlannedLeg(origin, leg1),
            PlannedLeg(p1, leg2.drop(1)),
            PlannedLeg(p2, leg3.drop(1))
        )
    }
}

// ============================================================================
// 3. CUSTOM MAP MARKER ICON FACTORY (SELF, HOME, WAYPOINTS, STROLLER, GHOST)
// ============================================================================

object MapIconFactory {
    fun createSelfIcon(context: Context): BitmapDrawable {
        val size = 68
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = android.graphics.Color.parseColor("#442979FF")
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 3.1f, paint)

        paint.color = android.graphics.Color.parseColor("#2979FF")
        canvas.drawCircle(size / 2f, size / 2f, size / 4.2f, paint)

        return BitmapDrawable(context.resources, bmp)
    }

    fun createHomeIcon(context: Context): BitmapDrawable {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Dark outer badge
        paint.color = android.graphics.Color.parseColor("#121212")
        canvas.drawCircle(size / 2f, size / 2f, size / 2.1f, paint)

        // Emerald green fill
        paint.color = android.graphics.Color.parseColor("#2E7D32")
        canvas.drawCircle(size / 2f, size / 2f, size / 2.4f, paint)

        // Draw white house roof & base
        paint.color = android.graphics.Color.WHITE
        val roofPath = Path().apply {
            moveTo(size * 0.5f, size * 0.24f)
            lineTo(size * 0.25f, size * 0.48f)
            lineTo(size * 0.75f, size * 0.48f)
            close()
        }
        canvas.drawPath(roofPath, paint)
        canvas.drawRect(size * 0.33f, size * 0.46f, size * 0.67f, size * 0.72f, paint)

        // Door cutout
        paint.color = android.graphics.Color.parseColor("#2E7D32")
        canvas.drawRect(size * 0.45f, size * 0.55f, size * 0.55f, size * 0.72f, paint)

        return BitmapDrawable(context.resources, bmp)
    }

    fun createWaypointIcon(context: Context, number: Int, isDestination: Boolean): BitmapDrawable {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = android.graphics.Color.parseColor("#121212")
        canvas.drawCircle(size / 2f, size / 2f, size / 2.2f, paint)

        paint.color = if (isDestination) {
            android.graphics.Color.parseColor("#00E676")
        } else {
            android.graphics.Color.parseColor("#FFD600")
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2.6f, paint)

        paint.color = android.graphics.Color.BLACK
        paint.textSize = 26f
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.CENTER
        val label = if (isDestination) "★" else number.toString()
        val yPos = (size / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(label, size / 2f, yPos, paint)

        return BitmapDrawable(context.resources, bmp)
    }

    fun createModeSwitchIcon(context: Context, switchedToStroller: Boolean): BitmapDrawable {
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = android.graphics.Color.parseColor("#121212")
        canvas.drawCircle(size / 2f, size / 2f, size / 2.1f, paint)

        paint.color = if (switchedToStroller) {
            android.graphics.Color.parseColor("#FF9100")
        } else {
            android.graphics.Color.parseColor("#00E676")
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2.6f, paint)

        paint.color = android.graphics.Color.BLACK
        paint.textSize = 22f
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.CENTER
        val label = if (switchedToStroller) "S" else "P"
        val yPos = (size / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(label, size / 2f, yPos, paint)

        return BitmapDrawable(context.resources, bmp)
    }

    fun createGhostIcon(context: Context): BitmapDrawable {
        val size = 54
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = android.graphics.Color.parseColor("#9900E5FF")
        canvas.drawCircle(size / 2f, size / 2f, size / 2.4f, paint)

        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 5f, paint)

        return BitmapDrawable(context.resources, bmp)
    }
}

// ============================================================================
// 4. SEGMENTED TRACKED POINTS & ROOM SQLITE DATABASE (VERSION 7)
// ============================================================================

data class TrackedPoint(
    val point: GeoPoint,
    val isStroller: Boolean
)

data class RouteSegment(
    val isStroller: Boolean,
    val points: List<GeoPoint>
)

fun buildRouteSegments(trackedPoints: List<TrackedPoint>): List<RouteSegment> {
    if (trackedPoints.size < 2) return emptyList()
    val segments = mutableListOf<RouteSegment>()
    var currentMode = trackedPoints.first().isStroller
    var currentPts = mutableListOf(trackedPoints.first().point)

    for (i in 1 until trackedPoints.size) {
        val tp = trackedPoints[i]
        if (tp.isStroller == currentMode) {
            currentPts.add(tp.point)
        } else {
            if (currentPts.size >= 2) {
                segments.add(RouteSegment(currentMode, currentPts.toList()))
            }
            val bridgePoint = currentPts.last()
            currentMode = tp.isStroller
            currentPts = mutableListOf(bridgePoint, tp.point)
        }
    }
    if (currentPts.size >= 2) {
        segments.add(RouteSegment(currentMode, currentPts.toList()))
    }
    return segments
}

@Entity(tableName = "walk_records")
data class WalkRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeName: String = "",
    val timestampMillis: Long,
    val distanceMeters: Double,
    val pawDistanceMeters: Double = 0.0,
    val usedStroller: Boolean = false,
    val durationSeconds: Long,
    val airTempF: Double,
    val pavementTempF: Double,
    val safetyStatus: String,
    val routeSerialized: String,
    val notes: String = "",
    val didPee: Boolean = false,
    val didPoop: Boolean = false,
    val photoUri: String = ""
) {
    val isPlannedRoute: Boolean
        get() = safetyStatus == "PLANNED"

    fun toTrackedPoints(): List<TrackedPoint> {
        if (routeSerialized.isBlank()) return emptyList()
        return routeSerialized.split(";").mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size >= 2) {
                val lat = parts[0].toDoubleOrNull()
                val lon = parts[1].toDoubleOrNull()
                val strollerFlag = if (parts.size >= 3) parts[2] == "1" else false
                if (lat != null && lon != null) TrackedPoint(GeoPoint(lat, lon), strollerFlag) else null
            } else null
        }
    }

    fun toGeoPoints(): List<GeoPoint> = toTrackedPoints().map { it.point }

    fun formattedDate(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestampMillis))

    fun formattedDuration(): String {
        if (isPlannedRoute) return "Planned Route"
        return String.format(Locale.US, "%02d:%02d", durationSeconds / 60, durationSeconds % 60)
    }

    fun formattedDistanceMiles(): String {
        val totalMi = distanceMeters / 1609.34
        return if (usedStroller && !isPlannedRoute) {
            val pawMi = pawDistanceMeters / 1609.34
            String.format(Locale.US, "%.2f mi (🐾 %.2f mi)", totalMi, pawMi)
        } else {
            String.format(Locale.US, "%.2f mi", totalMi)
        }
    }

    fun formattedPace(): String {
        if (isPlannedRoute) return "${toGeoPoints().size} pts"
        val miles = distanceMeters / 1609.34
        return if (miles >= 0.03) {
            String.format(Locale.US, "%.1f min/mi", (durationSeconds / 60.0) / miles)
        } else "-- min/mi"
    }

    fun toMarkdownRow(): String {
        val title = if (routeName.isNotBlank()) "${formattedDate()} ($routeName)" else formattedDate()
        val modeTag = if (usedStroller) "🛒 Stroller" else "🐾 Walk"
        val potty = buildString {
            if (didPee) append("Pee ")
            if (didPoop) append("Poop")
            if (isEmpty()) append("-")
        }.trim()
        val cleanNotes = notes.replace("|", "/").replace("\n", " ").ifBlank { "-" }
        return "| $title | $modeTag | ${formattedDistanceMiles()} | ${formattedDuration()} | ${formattedPace()} | $potty | $cleanNotes | $safetyStatus |"
    }

    companion object {
        fun serializeRoute(points: List<GeoPoint>): String =
            points.joinToString(";") { "${it.latitude},${it.longitude},0" }

        fun serializeTrackedRoute(points: List<TrackedPoint>): String =
            points.joinToString(";") { "${it.point.latitude},${it.point.longitude},${if (it.isStroller) 1 else 0}" }
    }
}

@Dao
interface WalkDao {
    @Query("SELECT * FROM walk_records ORDER BY timestampMillis DESC")
    fun getAllWalks(): Flow<List<WalkRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWalk(walk: WalkRecord)

    @Delete
    suspend fun deleteWalk(walk: WalkRecord)
}

@Database(entities = [WalkRecord::class], version = 7, exportSchema = false)
abstract class WalkDatabase : RoomDatabase() {
    abstract fun walkDao(): WalkDao

    companion object {
        @Volatile
        private var INSTANCE: WalkDatabase? = null

        fun getInstance(context: Context): WalkDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    WalkDatabase::class.java,
                    "dog_walk_history.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

// ============================================================================
// 5. FOREGROUND GPS SERVICE & SEGMENTED STROLLER STATE HOLDER
// ============================================================================

fun calculatePolylineMeters(points: List<GeoPoint>): Double {
    if (points.size < 2) return 0.0
    var total = 0.0
    val results = FloatArray(1)
    for (i in 0 until points.size - 1) {
        Location.distanceBetween(
            points[i].latitude, points[i].longitude,
            points[i + 1].latitude, points[i + 1].longitude,
            results
        )
        total += results[0]
    }
    return total
}

data class WalkSessionState(
    val isTracking: Boolean = false,
    val isStrollerMode: Boolean = false,
    val usedStrollerInSession: Boolean = false,
    val trackedPoints: List<TrackedPoint> = emptyList(),
    val distanceMeters: Double = 0.0,
    val pawDistanceMeters: Double = 0.0,
    val elapsedSeconds: Long = 0L,
    val currentLocation: GeoPoint? = null
)

object WalkStateHolder {
    private val _state = MutableStateFlow(WalkSessionState())
    val state = _state.asStateFlow()

    fun toggleStrollerMode() {
        val current = _state.value
        val nextStroller = !current.isStrollerMode
        val updatedPoints = if (current.isTracking && current.currentLocation != null) {
            current.trackedPoints + TrackedPoint(current.currentLocation, nextStroller)
        } else {
            current.trackedPoints
        }
        _state.value = current.copy(
            isStrollerMode = nextStroller,
            usedStrollerInSession = current.usedStrollerInSession || nextStroller,
            trackedPoints = updatedPoints
        )
    }

    fun updateLocation(point: GeoPoint, trackRoute: Boolean) {
        val current = _state.value
        if (!trackRoute) {
            _state.value = current.copy(currentLocation = point)
            return
        }
        val pts = current.trackedPoints
        val addedDist = if (pts.isNotEmpty()) {
            val results = FloatArray(1)
            Location.distanceBetween(
                pts.last().point.latitude, pts.last().point.longitude,
                point.latitude, point.longitude,
                results
            )
            results[0].toDouble()
        } else 0.0

        if (pts.isEmpty() || addedDist >= 1.5) {
            val addedPawDist = if (current.isStrollerMode) 0.0 else addedDist
            val newTrackedPoint = TrackedPoint(point, current.isStrollerMode)
            _state.value = current.copy(
                currentLocation = point,
                trackedPoints = pts + newTrackedPoint,
                distanceMeters = current.distanceMeters + addedDist,
                pawDistanceMeters = current.pawDistanceMeters + addedPawDist
            )
        }
    }

    fun setTracking(active: Boolean) {
        val current = _state.value
        _state.value = if (active) {
            val initialPoints = current.currentLocation?.let {
                listOf(TrackedPoint(it, current.isStrollerMode))
            } ?: emptyList()
            WalkSessionState(
                isTracking = true,
                isStrollerMode = current.isStrollerMode,
                usedStrollerInSession = current.isStrollerMode,
                trackedPoints = initialPoints,
                distanceMeters = 0.0,
                pawDistanceMeters = 0.0,
                elapsedSeconds = 0L,
                currentLocation = current.currentLocation
            )
        } else {
            current.copy(isTracking = false)
        }
    }

    fun tickSecond() {
        if (_state.value.isTracking) {
            _state.value = _state.value.copy(elapsedSeconds = _state.value.elapsedSeconds + 1)
        }
    }
}

class WalkTrackerService : Service() {
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var timerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    WalkStateHolder.updateLocation(
                        GeoPoint(loc.latitude, loc.longitude),
                        trackRoute = WalkStateHolder.state.value.isTracking
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                createNotificationChannels()
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Dog Walk Active")
                    .setContentText("Recording GPS route in background...")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setOngoing(true)
                    .build()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                } else {
                    startForeground(1, notification)
                }

                WalkStateHolder.setTracking(true)
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                    .setMinUpdateIntervalMillis(1500L)
                    .setMinUpdateDistanceMeters(2f)
                    .build()
                fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())

                timerJob?.cancel()
                timerJob = serviceScope.launch {
                    while (isActive) {
                        delay(1000L)
                        WalkStateHolder.tickSecond()
                    }
                }
            }
            ACTION_STOP -> {
                WalkStateHolder.setTracking(false)
                timerJob?.cancel()
                fusedClient.removeLocationUpdates(locationCallback)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_REMINDER -> {
                createNotificationChannels()
                val openAppIntent = Intent(this, MainActivity::class.java)
                val pendingOpen = PendingIntent.getActivity(
                    this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE
                )
                val reminderNotif = NotificationCompat.Builder(this, REMINDER_CHANNEL_ID)
                    .setContentTitle("Time for Bean's Walk!")
                    .setContentText("Tap to check live walking windows, shade, and pavement temp.")
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentIntent(pendingOpen)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()

                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(2002, reminderNotif)
                if (!WalkStateHolder.state.value.isTracking) {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Walk GPS Tracking", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(REMINDER_CHANNEL_ID, "Walk Reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    override fun onDestroy() {
        timerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "dog_walk_channel"
        const val REMINDER_CHANNEL_ID = "dog_walk_reminder_channel"
        const val ACTION_START = "ACTION_START_WALK"
        const val ACTION_STOP = "ACTION_STOP_WALK"
        const val ACTION_REMINDER = "ACTION_REMINDER_WALK"
    }
}

// ============================================================================
// 6. MAIN UI, WALKING WINDOWS, HOME PIN & SHADE-AWARE ROUTING
// ============================================================================

val OSM_KEYLESS_SOURCE = XYTileSource(
    "OSM_Keyless_v4", 0, 19, 256, ".png", arrayOf("https://tile.openstreetmap.org/")
)

val ESRI_STREET_SOURCE = object : OnlineTileSourceBase(
    "EsriWorldStreet_v4", 0, 19, 256, "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex)
    }
}

enum class MapStyle(val label: String, val source: OnlineTileSourceBase, val invertForDark: Boolean) {
    DARK("Dark", OSM_KEYLESS_SOURCE, true),
    LIGHT("Light", OSM_KEYLESS_SOURCE, false),
    ESRI("Esri", ESRI_STREET_SOURCE, false);

    fun next(): MapStyle = entries[(ordinal + 1) % entries.size]
}

enum class GhostMode(val label: String) {
    OFF("Ghost: OFF"),
    PINNED("Ghost: PINNED"),
    ALL("Ghost: ALL");

    fun next(): GhostMode = entries[(ordinal + 1) % entries.size]
}

data class PendingWalkSnapshot(
    val totalMeters: Double,
    val pawMeters: Double,
    val usedStroller: Boolean,
    val durationSecs: Long,
    val trackedPoints: List<TrackedPoint>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val osmConfig = Configuration.getInstance()
        val prefs = getSharedPreferences("osmdroid_clean_prefs_v4", Context.MODE_PRIVATE)
        osmConfig.load(applicationContext, prefs)

        val customUa = "PersonalDogWalkTracker/6.0 (Android; PersonalUse)"
        osmConfig.userAgentValue = customUa
        osmConfig.additionalHttpRequestProperties["User-Agent"] = customUa
        osmConfig.additionalHttpRequestProperties["Referer"] = "https://www.openstreetmap.org/"

        val cleanCacheDir = File(cacheDir, "osm_tiles_v4")
        cleanCacheDir.mkdirs()
        osmConfig.osmdroidTileCache = cleanCacheDir

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    DogWalkScreen()
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DogWalkScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val walkState by WalkStateHolder.state.collectAsState()

    val walkDao = remember { WalkDatabase.getInstance(context).walkDao() }
    val savedWalks by walkDao.getAllWalks().collectAsState(initial = emptyList())

    val prefs = remember { context.getSharedPreferences("osmdroid_clean_prefs_v4", Context.MODE_PRIVATE) }
    var mapStyle by remember {
        val savedName = prefs.getString("saved_map_style", MapStyle.DARK.name) ?: MapStyle.DARK.name
        mutableStateOf(runCatching { MapStyle.valueOf(savedName) }.getOrDefault(MapStyle.DARK))
    }

    // Saved Home Location state
    var homeLocation by remember {
        val lat = prefs.getFloat("home_lat", Float.NaN)
        val lon = prefs.getFloat("home_lon", Float.NaN)
        mutableStateOf(if (!lat.isNaN() && !lon.isNaN()) GeoPoint(lat.toDouble(), lon.toDouble()) else null)
    }
    var isPickingHomeOnMap by remember { mutableStateOf(false) }
    var showHomeDialog by remember { mutableStateOf(false) }
    var centerMapTrigger by remember { mutableStateOf<GeoPoint?>(null) }

    // Shade-Aware Mapping state
    var shadeOverlayEnabled by remember { mutableStateOf(true) }
    var shadeZones by remember { mutableStateOf<List<ShadeCanopyZone>>(emptyList()) }
    var isLoadingShade by remember { mutableStateOf(false) }

    var ghostMode by remember { mutableStateOf(GhostMode.PINNED) }
    var pinnedGhostWalk by remember { mutableStateOf<WalkRecord?>(null) }

    // Route Planner & Selectable Auto-Route Distance state
    var isPlanningRoute by remember { mutableStateOf(false) }
    var targetAutoMiles by remember {
        mutableDoubleStateOf(prefs.getFloat("target_auto_miles", 0.75f).toDouble())
    }
    var snapToStreets by remember { mutableStateOf(true) }
    var isSnappingSegment by remember { mutableStateOf(false) }
    var plannedLegs by remember { mutableStateOf<List<PlannedLeg>>(emptyList()) }
    var yorkieDirectionIndex by remember { mutableIntStateOf(0) }
    var autoLoopMessage by remember { mutableStateOf<String?>(null) }

    // Dialog States
    var showSafetyInfoDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    var routeNameInput by remember { mutableStateOf("") }

    var showPostWalkDialog by remember { mutableStateOf(false) }
    var pendingWalkData by remember { mutableStateOf<PendingWalkSnapshot?>(null) }
    var postWalkRouteName by remember { mutableStateOf("") }
    var postWalkNotes by remember { mutableStateOf("") }
    var didPee by remember { mutableStateOf(false) }
    var didPoop by remember { mutableStateOf(false) }
    var attachedPhotoUri by remember { mutableStateOf("") }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            attachedPhotoUri = it.toString()
        }
    }

    val plannedFullGeometry = remember(plannedLegs) { plannedLegs.flatMap { it.snappedPoints } }
    val plannedAnchorWaypoints = remember(plannedLegs) { plannedLegs.map { it.anchor } }

    var activeTab by remember { mutableIntStateOf(0) }
    var weatherReport by remember { mutableStateOf<DogWeatherReport?>(null) }
    var weatherError by remember { mutableStateOf<String?>(null) }
    var isLoadingWeather by remember { mutableStateOf(false) }

    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    fun saveHomeLocation(pt: GeoPoint) {
        homeLocation = pt
        prefs.edit()
            .putFloat("home_lat", pt.latitude.toFloat())
            .putFloat("home_lon", pt.longitude.toFloat())
            .apply()
        Toast.makeText(context, "Home location saved! 🏠", Toast.LENGTH_SHORT).show()
    }

    fun refreshShadeCanopy(anchor: GeoPoint) {
        if (!shadeOverlayEnabled) return
        isLoadingShade = true
        scope.launch {
            shadeZones = ShadeRepository.fetchNearbyShadeZones(anchor)
            isLoadingShade = false
        }
    }

    fun refreshLocationAndWeather() {
        isLoadingWeather = true
        weatherError = null
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            val targetLat = loc?.latitude ?: homeLocation?.latitude ?: 42.375
            val targetLon = loc?.longitude ?: homeLocation?.longitude ?: -122.916
            val currentPt = GeoPoint(targetLat, targetLon)
            WalkStateHolder.updateLocation(currentPt, trackRoute = false)

            scope.launch {
                val res = WeatherRepository.fetchDogWeather(targetLat, targetLon)
                isLoadingWeather = false
                res.onSuccess { weatherReport = it }
                    .onFailure { weatherError = "Weather error: ${it.localizedMessage}" }
            }

            if (shadeZones.isEmpty() && shadeOverlayEnabled) {
                refreshShadeCanopy(homeLocation ?: currentPt)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) refreshLocationAndWeather()
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    val activeGhostRecord = pinnedGhostWalk ?: savedWalks.firstOrNull()
    val ghostTrailsToRender: List<List<GeoPoint>> = remember(ghostMode, activeGhostRecord, savedWalks) {
        when (ghostMode) {
            GhostMode.OFF -> emptyList()
            GhostMode.PINNED -> listOfNotNull(activeGhostRecord?.toGeoPoints()?.takeIf { it.size > 1 })
            GhostMode.ALL -> savedWalks.map { it.toGeoPoints() }.filter { it.size > 1 }
        }
    }

    val ghostPacerPoint: GeoPoint? = remember(
        walkState.isTracking,
        walkState.elapsedSeconds,
        ghostMode,
        activeGhostRecord
    ) {
        if (!walkState.isTracking || ghostMode == GhostMode.OFF || activeGhostRecord == null) {
            null
        } else {
            val pts = activeGhostRecord.toGeoPoints()
            if (pts.size < 2) null
            else {
                val targetDuration = if (activeGhostRecord.durationSeconds > 0) {
                    activeGhostRecord.durationSeconds
                } else {
                    val miles = activeGhostRecord.distanceMeters / 1609.34
                    (miles * 20.0 * 60.0).toLong().coerceAtLeast(60L)
                }
                val fraction = (walkState.elapsedSeconds.toDouble() / targetDuration.toDouble()).coerceIn(0.0, 1.0)
                val index = ((pts.size - 1) * fraction).roundToInt().coerceIn(0, pts.size - 1)
                pts[index]
            }
        }
    }

    // 1. HOME LOCATION MANAGEMENT DIALOG
    if (showHomeDialog) {
        AlertDialog(
            onDismissRequest = { showHomeDialog = false },
            title = { Text("Home Location 🏠", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (homeLocation != null) {
                            String.format(Locale.US, "Saved Home: %.4f, %.4f", homeLocation!!.latitude, homeLocation!!.longitude)
                        } else {
                            "No Home location saved yet. Auto-routes will start from your Home pin once set."
                        },
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )

                    Button(
                        onClick = {
                            val gps = walkState.currentLocation
                            if (gps != null) {
                                saveHomeLocation(gps)
                                centerMapTrigger = gps
                                refreshShadeCanopy(gps)
                                showHomeDialog = false
                            } else {
                                Toast.makeText(context, "Waiting for GPS fix...", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Set Home to Current GPS", color = Color.White)
                    }

                    OutlinedButton(
                        onClick = {
                            isPickingHomeOnMap = true
                            showHomeDialog = false
                            Toast.makeText(context, "Tap anywhere on the map to place your Home pin 🏠", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pick Home by Tapping Map", color = Color.White)
                    }

                    if (homeLocation != null) {
                        OutlinedButton(
                            onClick = {
                                centerMapTrigger = homeLocation
                                showHomeDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Center Map on Home", color = Color(0xFF00E676))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHomeDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // 2. WEATHER, PAW SAFETY & YORKIE EXHAUSTION INFO MODAL
    if (showSafetyInfoDialog) {
        WeatherAndExhaustionDialog(
            report = weatherReport,
            walkState = walkState,
            shadeZoneCount = shadeZones.size,
            onDismiss = { showSafetyInfoDialog = false }
        )
    }

    // 3. POST-WALK SUMMARY DIALOG
    if (showPostWalkDialog && pendingWalkData != null) {
        val snap = pendingWalkData!!
        var confirmUsedStroller by remember(snap) { mutableStateOf(snap.usedStroller) }

        AlertDialog(
            onDismissRequest = {},
            title = { Text("Walk Summary & Notes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val totalMi = snap.totalMeters / 1609.34
                    val pawMi = snap.pawMeters / 1609.34
                    Text(
                        text = String.format(
                            Locale.US,
                            "Total: %.2f mi  •  Paw Walked: %.2f mi  •  Time: %02d:%02d",
                            totalMi,
                            pawMi,
                            snap.durationSecs / 60,
                            snap.durationSecs % 60
                        ),
                        color = Color(0xFF00E676),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )

                    OutlinedTextField(
                        value = postWalkRouteName,
                        onValueChange = { postWalkRouteName = it },
                        label = { Text("Walk Title (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = didPee, onCheckedChange = { didPee = it })
                            Text("Pee 💧", fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = didPoop, onCheckedChange = { didPoop = it })
                            Text("Poop 💩", fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = confirmUsedStroller,
                                onCheckedChange = { confirmUsedStroller = it }
                            )
                            Text("Stroller 🛒", fontSize = 12.sp)
                        }
                    }

                    OutlinedTextField(
                        value = postWalkNotes,
                        onValueChange = { postWalkNotes = it },
                        label = { Text("How did Bean do? (Energy, stroller, notes)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedButton(
                        onClick = { photoPickerLauncher.launch(arrayOf("image/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (attachedPhotoUri.isBlank()) "Attach Walk Photo 📷" else "Photo Attached ✓")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val finalPawMeters = if (confirmUsedStroller && !snap.usedStroller) {
                                0.0
                            } else {
                                snap.pawMeters
                            }
                            walkDao.insertWalk(
                                WalkRecord(
                                    routeName = postWalkRouteName.trim(),
                                    timestampMillis = System.currentTimeMillis(),
                                    distanceMeters = snap.totalMeters,
                                    pawDistanceMeters = finalPawMeters,
                                    usedStroller = confirmUsedStroller,
                                    durationSeconds = snap.durationSecs,
                                    airTempF = weatherReport?.airTempF ?: 0.0,
                                    pavementTempF = weatherReport?.pavementTempF ?: 0.0,
                                    safetyStatus = weatherReport?.status?.name ?: "N/A",
                                    routeSerialized = WalkRecord.serializeTrackedRoute(snap.trackedPoints),
                                    notes = postWalkNotes.trim(),
                                    didPee = didPee,
                                    didPoop = didPoop,
                                    photoUri = attachedPhotoUri
                                )
                            )
                            Toast.makeText(context, "Walk saved to History!", Toast.LENGTH_SHORT).show()
                            showPostWalkDialog = false
                            pendingWalkData = null
                            postWalkRouteName = ""
                            postWalkNotes = ""
                            didPee = false
                            didPoop = false
                            attachedPhotoUri = ""
                        }
                    }
                ) {
                    Text("Save Walk")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPostWalkDialog = false
                        pendingWalkData = null
                    }
                ) {
                    Text("Discard")
                }
            }
        )
    }

    // 4. SAVE PLANNED ROUTE DIALOG
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Name This Route") },
            text = {
                OutlinedTextField(
                    value = routeNameInput,
                    onValueChange = { routeNameInput = it },
                    label = { Text("Route Name (e.g., Shaded 0.75mi Loop)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNameDialog = false
                        val plannedMeters = calculatePolylineMeters(plannedFullGeometry)
                        scope.launch {
                            val newRoute = WalkRecord(
                                routeName = routeNameInput.trim().ifBlank { "Planned Route" },
                                timestampMillis = System.currentTimeMillis(),
                                distanceMeters = plannedMeters,
                                pawDistanceMeters = plannedMeters,
                                usedStroller = false,
                                durationSeconds = 0L,
                                airTempF = weatherReport?.airTempF ?: 0.0,
                                pavementTempF = weatherReport?.pavementTempF ?: 0.0,
                                safetyStatus = "PLANNED",
                                routeSerialized = WalkRecord.serializeRoute(plannedFullGeometry)
                            )
                            walkDao.insertWalk(newRoute)
                            pinnedGhostWalk = newRoute
                            ghostMode = GhostMode.PINNED
                            plannedLegs = emptyList()
                            isPlanningRoute = false
                            routeNameInput = ""
                            Toast.makeText(context, "Route saved & pinned as Ghost!", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Save Route")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("Cancel") }
            }
        )
    }

    // MAIN SCREEN LAYOUT
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // COMPACT TOP NAVIGATION BAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), RoundedCornerShape(10.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = { activeTab = 0 },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeTab == 0) Color(0xFF2E7D32) else Color.Transparent
                ),
                contentPadding = PaddingValues(vertical = 6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("LIVE MAP", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Button(
                onClick = { activeTab = 1 },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeTab == 1) Color(0xFF2E7D32) else Color.Transparent
                ),
                contentPadding = PaddingValues(vertical = 6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("LOGS & ROUTES (${savedWalks.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        if (activeTab == 0) {
            // 1. COLLAPSIBLE WEATHER BANNER
            WeatherBanner(
                report = weatherReport,
                isStrollerMode = walkState.isStrollerMode,
                isLoading = isLoadingWeather,
                error = weatherError,
                onRefresh = { refreshLocationAndWeather() },
                onOpenInfo = { showSafetyInfoDialog = true }
            )

            // 2. TODAY'S WALKING WINDOWS BAR (COLLAPSIBLE HOURLY ANALYZER)
            if (weatherReport != null && weatherReport!!.hourlyWindows.isNotEmpty()) {
                WalkingWindowsStrip(report = weatherReport!!)
            }

            // 3. ROUTE BUILDER & SELECTABLE LENGTH SHADE-AWARE AUTO-ROUTER
            if (isPlanningRoute) {
                val plannedMiles = calculatePolylineMeters(plannedFullGeometry) / 1609.34
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF222A30), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Plan a walking loop", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                if (homeLocation != null) "Starting from Home" else "Starting from current GPS location",
                                color = Color(0xFFB0BEC5), fontSize = 12.sp
                            )
                        }
                        Text(
                            if (isSnappingSegment) "Finding route…" else "Walking route",
                            color = Color(0xFF80CBC4), fontSize = 12.sp
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("TARGET DISTANCE", color = Color(0xFFB0BEC5), fontSize = 10.sp)
                            Text(String.format(Locale.US, "%.2f mi", targetAutoMiles),
                                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                enabled = !isSnappingSegment && targetAutoMiles > 0.25,
                                onClick = {
                                    targetAutoMiles = (targetAutoMiles - 0.25).coerceAtLeast(0.25)
                                    prefs.edit().putFloat("target_auto_miles", targetAutoMiles.toFloat()).apply()
                                    autoLoopMessage = null
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) { Text("−", color = Color.White, fontSize = 18.sp) }
                            OutlinedButton(
                                enabled = !isSnappingSegment && targetAutoMiles < 2.50,
                                onClick = {
                                    targetAutoMiles = (targetAutoMiles + 0.25).coerceAtMost(2.50)
                                    prefs.edit().putFloat("target_auto_miles", targetAutoMiles.toFloat()).apply()
                                    autoLoopMessage = null
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) { Text("+", color = Color.White, fontSize = 18.sp) }
                        }
                    }
                    Text(
                        if (isSnappingSegment) "Checking pedestrian streets and matching your distance…"
                        else if (plannedFullGeometry.size > 1) String.format(Locale.US, "Current route: %.2f mi • %d stops", plannedMiles, plannedLegs.size)
                        else "Choose a distance, then generate a loop.",
                        color = Color(0xFFCFD8DC), fontSize = 12.sp
                    )
                    if (autoLoopMessage != null) {
                        Text(autoLoopMessage!!, color = Color(0xFFFFCC80), fontSize = 12.sp)
                    }

                    // Row B: Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                val startPt = homeLocation ?: walkState.currentLocation
                                if (startPt == null) {
                                    Toast.makeText(context, "Waiting for GPS or Home location...", Toast.LENGTH_SHORT).show()
                                } else if (!isSnappingSegment) {
                                    val dirIdx = yorkieDirectionIndex
                                    yorkieDirectionIndex = (yorkieDirectionIndex + 1) % 8
                                    val bearing = ShadeRepository.pickShadiestBearingDeg(startPt, shadeZones, dirIdx)
                                    isSnappingSegment = true
                                    autoLoopMessage = null
                                    scope.launch {
                                        try {
                                            val generated = RouteSnapper.generateCalibratedLoop(
                                                origin = startPt,
                                                targetMiles = targetAutoMiles,
                                                bearingDeg = bearing
                                            )
                                            plannedLegs = generated
                                            val actualMi = calculatePolylineMeters(generated.flatMap { it.snappedPoints }) / 1609.344
                                            autoLoopMessage = String.format(Locale.US, "Ready: %.2f mi pedestrian loop", actualMi)
                                        } catch (e: Exception) {
                                            autoLoopMessage = e.message ?: "Unable to generate a walking loop. Please try again."
                                            Toast.makeText(context, autoLoopMessage, Toast.LENGTH_LONG).show()
                                        } finally {
                                            isSnappingSegment = false
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00838F)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            Text(if (isSnappingSegment) "Finding…" else "Generate loop", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { snapToStreets = !snapToStreets },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            modifier = Modifier.weight(0.9f)
                        ) {
                            Text(
                                text = if (snapToStreets) "Snap: ON" else "Direct",
                                fontSize = 11.sp,
                                color = if (snapToStreets) Color(0xFF00E676) else Color.LightGray
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                if (plannedLegs.isNotEmpty() && !isSnappingSegment) {
                                    plannedLegs = plannedLegs.dropLast(1)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            modifier = Modifier.weight(0.7f)
                        ) {
                            Text("Undo", fontSize = 11.sp, color = Color.White)
                        }

                        Button(
                            onClick = {
                                if (plannedFullGeometry.size >= 2 && !isSnappingSegment) {
                                    showNameDialog = true
                                } else {
                                    Toast.makeText(context, "Add at least 2 points first", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8F00)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(0.8f)
                        ) {
                            Text("Save", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 4. CLIPPED MAP CONTAINER
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clipToBounds()
            ) {
                OsmMapContainer(
                    currentLocation = walkState.currentLocation,
                    homeLocation = homeLocation,
                    centerMapTrigger = centerMapTrigger,
                    onConsumeCenterTrigger = { centerMapTrigger = null },
                    shadeZones = if (shadeOverlayEnabled) shadeZones else emptyList(),
                    liveTrackedPoints = walkState.trackedPoints,
                    ghostTrails = ghostTrailsToRender,
                    ghostPacerPoint = ghostPacerPoint,
                    plannedGeometry = plannedFullGeometry,
                    plannedAnchors = plannedAnchorWaypoints,
                    isPlanningRoute = isPlanningRoute,
                    isPickingHome = isPickingHomeOnMap,
                    mapStyle = mapStyle,
                    onMapTap = { tappedGeoPoint ->
                        if (isPickingHomeOnMap) {
                            isPickingHomeOnMap = false
                            saveHomeLocation(tappedGeoPoint)
                            refreshShadeCanopy(tappedGeoPoint)
                        } else if (isPlanningRoute && !isSnappingSegment) {
                            if (plannedLegs.isEmpty()) {
                                plannedLegs = listOf(PlannedLeg(tappedGeoPoint, listOf(tappedGeoPoint)))
                            } else {
                                val prev = plannedLegs.last().anchor
                                if (!snapToStreets) {
                                    plannedLegs = plannedLegs + PlannedLeg(tappedGeoPoint, listOf(tappedGeoPoint))
                                } else {
                                    isSnappingSegment = true
                                    scope.launch {
                                        val seg = RouteSnapper.snapFootSegment(prev, tappedGeoPoint)
                                        val cleanSeg = if (seg.size > 1) seg.drop(1) else seg
                                        plannedLegs = plannedLegs + PlannedLeg(tappedGeoPoint, cleanSeg)
                                        isSnappingSegment = false
                                    }
                                }
                            }
                        }
                    }
                )

                // FLOATING TOP MAP CONTROLS (HOME, SHADE, PLANNER, GHOST, STYLE)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = { showHomeDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPickingHomeOnMap) Color(0xFFFF8F00) else Color(0xCC1B5E20)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isPickingHomeOnMap) "Tap Map..." else "🏠 Home",
                            fontSize = 11.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            shadeOverlayEnabled = !shadeOverlayEnabled
                            if (shadeOverlayEnabled && shadeZones.isEmpty()) {
                                val anchor = homeLocation ?: walkState.currentLocation
                                if (anchor != null) refreshShadeCanopy(anchor)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (shadeOverlayEnabled) Color(0xCC2E7D32) else Color(0xCC1E1E1E)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isLoadingShade) "🌳..." else if (shadeOverlayEnabled) "🌳 Shade: ON" else "🌳 Shade: OFF",
                            fontSize = 11.sp,
                            color = Color.White
                        )
                    }

                    Button(
                        onClick = { isPlanningRoute = !isPlanningRoute },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPlanningRoute) Color(0xFFFF8F00) else Color(0xCC1E1E1E)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isPlanningRoute) "Close Plan" else "Plan Route",
                            fontSize = 11.sp,
                            color = if (isPlanningRoute) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = { ghostMode = ghostMode.next() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (ghostMode != GhostMode.OFF) Color(0xCC006064) else Color(0xCC1E1E1E)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(ghostMode.label, fontSize = 10.sp, color = Color.White)
                    }

                    Button(
                        onClick = {
                            val nextStyle = mapStyle.next()
                            mapStyle = nextStyle
                            prefs.edit().putString("saved_map_style", nextStyle.name).apply()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1E1E1E)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(mapStyle.label, fontSize = 10.sp, color = Color.White)
                    }
                }

                // BOTTOM-LEFT LEGEND FOR SHADE & STROLLER SEGMENTS
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(Color(0xCC121212), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (shadeOverlayEnabled) {
                        Text(
                            text = "🌳 ${shadeZones.size} Shade Zones • Shadow ${weatherReport?.shadePercent ?: 50}%",
                            color = Color(0xFF81C784),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (walkState.usedStrollerInSession || walkState.isStrollerMode) {
                        Text("• 🐾 Green / 🛒 Orange", color = Color(0xFFFF9100), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 5. BOTTOM WALK CONTROL BAR
            WalkControlPanel(
                walkState = walkState,
                ghostTargetMiles = if (ghostMode != GhostMode.OFF) {
                    activeGhostRecord?.let { it.distanceMeters / 1609.34 }
                } else null,
                onToggleStroller = {
                    WalkStateHolder.toggleStrollerMode()
                    val modeMsg = if (WalkStateHolder.state.value.isStrollerMode) {
                        "Stroller Mode ON: Route line switched to Orange 🛒"
                    } else {
                        "Stroller Mode OFF: Route line switched to Green 🐾"
                    }
                    Toast.makeText(context, modeMsg, Toast.LENGTH_SHORT).show()
                },
                onSetReminder = {
                    val cal = Calendar.getInstance()
                    TimePickerDialog(
                        context,
                        { _, hourOfDay, minute ->
                            val targetCal = Calendar.getInstance().apply {
                                set(Calendar.HOUR_OF_DAY, hourOfDay)
                                set(Calendar.MINUTE, minute)
                                set(Calendar.SECOND, 0)
                                if (before(Calendar.getInstance())) {
                                    add(Calendar.DAY_OF_YEAR, 1)
                                }
                            }
                            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                            val remindIntent = Intent(context, WalkTrackerService::class.java).apply {
                                action = WalkTrackerService.ACTION_REMINDER
                            }
                            val pendingIntent = PendingIntent.getService(
                                context,
                                1001,
                                remindIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            alarmManager.setAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                targetCal.timeInMillis,
                                pendingIntent
                            )
                            val timeStr = String.format(Locale.US, "%02d:%02d", hourOfDay, minute)
                            Toast.makeText(context, "Walk reminder set for $timeStr", Toast.LENGTH_SHORT).show()
                        },
                        cal.get(Calendar.HOUR_OF_DAY),
                        cal.get(Calendar.MINUTE),
                        false
                    ).show()
                },
                onToggleWalk = {
                    if (walkState.isTracking) {
                        pendingWalkData = PendingWalkSnapshot(
                            totalMeters = walkState.distanceMeters,
                            pawMeters = walkState.pawDistanceMeters,
                            usedStroller = walkState.usedStrollerInSession,
                            durationSecs = walkState.elapsedSeconds,
                            trackedPoints = walkState.trackedPoints
                        )
                        showPostWalkDialog = true
                        val stopIntent = Intent(context, WalkTrackerService::class.java).apply {
                            action = WalkTrackerService.ACTION_STOP
                        }
                        context.startService(stopIntent)
                    } else {
                        val startIntent = Intent(context, WalkTrackerService::class.java).apply {
                            action = WalkTrackerService.ACTION_START
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(startIntent)
                        } else {
                            context.startService(startIntent)
                        }
                    }
                }
            )
        } else {
            WalkHistoryList(
                walks = savedWalks,
                pinnedGhostId = activeGhostRecord?.id,
                onSetGhost = { walk ->
                    pinnedGhostWalk = walk
                    ghostMode = GhostMode.PINNED
                    activeTab = 0
                    Toast.makeText(context, "Pinned to map as Ghost Trail", Toast.LENGTH_SHORT).show()
                },
                onDeleteWalk = { walk ->
                    if (pinnedGhostWalk?.id == walk.id) pinnedGhostWalk = null
                    scope.launch { walkDao.deleteWalk(walk) }
                }
            )
        }
    }
}

// ============================================================================
// 7. TODAY'S WALKING WINDOWS UI STRIP
// ============================================================================

@Composable
fun WalkingWindowsStrip(report: DogWeatherReport) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1A262B))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "☀️ ${report.bestWindowsSummary}",
                color = Color(0xFF80DEEA),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (expanded) "▲ Hide Hours" else "▼ Hourly Windows",
                color = Color.LightGray,
                fontSize = 10.sp
            )
        }

        if (expanded) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(report.hourlyWindows) { win ->
                    val badgeColor = when (win.rating) {
                        WindowRating.PERFECT -> Color(0xFF2E7D32)
                        WindowRating.GOOD -> Color(0xFF00695C)
                        WindowRating.CAUTION -> Color(0xFFE65100)
                        WindowRating.AVOID -> Color(0xFFB71C1C)
                    }
                    Column(
                        modifier = Modifier
                            .background(Color(0xFF121212), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(win.timeLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Box(
                            modifier = Modifier
                                .background(badgeColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(win.rating.label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("Air ${win.airTempF}°F", color = Color.LightGray, fontSize = 10.sp)
                        Text("Pave ${win.pavementTempF}°F", color = Color(0xFFFFD600), fontSize = 10.sp)
                        Text("🌳 Shade ${win.shadePercent}%", color = Color(0xFF81C784), fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

// ============================================================================
// 8. WEATHER, PAW SAFETY & YORKIE EXHAUSTION GUIDE DIALOG
// ============================================================================

@Composable
fun WeatherAndExhaustionDialog(
    report: DogWeatherReport?,
    walkState: WalkSessionState,
    shadeZoneCount: Int,
    onDismiss: () -> Unit
) {
    val pawMiles = walkState.pawDistanceMeters / 1609.34
    val totalMiles = walkState.distanceMeters / 1609.34
    val airF = report?.airTempF ?: 65.0
    val paveF = report?.pavementTempF ?: 75.0

    val heatMultiplier = when {
        airF >= 78.0 -> 1.6
        airF >= 70.0 -> 1.25
        else -> 1.0
    }
    val effectiveStrainMiles = pawMiles * heatMultiplier
    val (exertionLevel, exertionAdvice, exertionColor) = when {
        walkState.isStrollerMode -> Triple(
            "Resting in Stroller 🛒",
            "Paws are off the ground. Ensure canopy ventilation if sunny or a blanket if below 50°F.",
            Color(0xFFFF9100)
        )
        effectiveStrainMiles >= 1.2 -> Triple(
            "High Exertion (Switch to Stroller / Rest)",
            "Bean has reached the upper single-walk threshold for a 12-lb Yorkie given current temperatures. Offer water or switch to Stroller Mode.",
            Color(0xFFEF5350)
        )
        effectiveStrainMiles >= 0.75 -> Triple(
            "Moderate-Optimal Exercise",
            "Right in the healthy 0.8–1.2 mi target zone. Watch for slowing pace or lagging behind.",
            Color(0xFFFFA726)
        )
        else -> Triple(
            "Low / Fresh",
            "Plenty of energy reserve. Ideal single-walk target for a 12-lb Yorkie is 0.8 to 1.2 miles.",
            Color(0xFF66BB6A)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Weather, Shade & Yorkie Guide 🐾", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF263238), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("LIVE EXERTION & STROLLER STATUS", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(exertionLevel, color = exertionColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = String.format(Locale.US, "Paw Distance: %.2f mi  |  Total Route: %.2f mi", pawMiles, totalMiles),
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Text(exertionAdvice, color = Color.LightGray, fontSize = 12.sp)
                }

                if (report != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("CURRENT MICRO-CLIMATE & SHADE", color = Color(0xFFFFD600), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("• Air Temp: ${report.airTempF}°F (Feels Like: ${report.feelsLikeF}°F)", color = Color.White, fontSize = 12.sp)
                        Text("• Sunlit Asphalt Estimate: ${paveF}°F (Solar: ${report.solarRadWm2.toInt()} W/m²)", color = Color.White, fontSize = 12.sp)
                        Text("• Estimated Street Shadow / Shade: ${report.shadePercent}% ($shadeZoneCount canopy zones mapped)", color = Color(0xFF81C784), fontSize = 12.sp)
                        Text("• Wind Gusts: ${report.windGustMph.toInt()} mph  |  Precip: ${report.rainInch} in/h", color = Color.White, fontSize = 12.sp)
                        Text("• Air Quality (US AQI): ${report.usAqi} (Unsafe > 100)", color = Color.White, fontSize = 12.sp)
                    }
                }

                Divider(color = Color.DarkGray)

                Text("12-lb Yorkie Exhaustion & Heat Signs", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "• Early Fatigue: Lagging behind your heel, sitting down mid-walk, or pace dropping slower than 24 min/mi.\n" +
                        "• Overheating (>72°F Air): Heavy open-mouth panting, bright red tongue/gums, warm ears, or seeking shade. Put Bean in the stroller with the mesh open and offer water.\n" +
                        "• Trachea & Harness Care: Yorkies are prone to tracheal collapse—if Bean coughs or honks when pulling, switch to the stroller or loosen the chest harness.",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )

                Divider(color = Color.DarkGray)

                Text("Paw Pad & Cold Weather Thresholds", color = Color(0xFF29B6F6), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "• 104°F–117°F Asphalt (Caution): Stick to green-shaded canopy zones or grass edges.\n" +
                        "• 118°F+ Asphalt (Unsafe): Can blister paw pads in 60 seconds. Use booties or Stroller Mode.\n" +
                        "• Under 50°F Wind Chill: Yorkies have single coats (no undercoat) and lose core heat quickly. Use a jacket below 50°F and limit exposed paw time below 32°F.",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Got It")
            }
        }
    )
}

// ============================================================================
// 9. OSM MAP CONTAINER WITH SHADE CANOPY POLYGONS & HOME MARKER
// ============================================================================

@Composable
fun OsmMapContainer(
    currentLocation: GeoPoint?,
    homeLocation: GeoPoint?,
    centerMapTrigger: GeoPoint?,
    onConsumeCenterTrigger: () -> Unit,
    shadeZones: List<ShadeCanopyZone>,
    liveTrackedPoints: List<TrackedPoint>,
    ghostTrails: List<List<GeoPoint>>,
    ghostPacerPoint: GeoPoint?,
    plannedGeometry: List<GeoPoint>,
    plannedAnchors: List<GeoPoint>,
    isPlanningRoute: Boolean,
    isPickingHome: Boolean,
    mapStyle: MapStyle,
    onMapTap: (GeoPoint) -> Unit
) {
    val context = LocalContext.current
    val currentOnMapTap by rememberUpdatedState(onMapTap)
    val currentIsPlanning by rememberUpdatedState(isPlanningRoute)
    val currentIsPickingHome by rememberUpdatedState(isPickingHome)

    val selfIcon = remember { MapIconFactory.createSelfIcon(context) }
    val homeIcon = remember { MapIconFactory.createHomeIcon(context) }
    val ghostIcon = remember { MapIconFactory.createGhostIcon(context) }
    val strollerSwitchIcon = remember { MapIconFactory.createModeSwitchIcon(context, switchedToStroller = true) }
    val pawSwitchIcon = remember { MapIconFactory.createModeSwitchIcon(context, switchedToStroller = false) }

    val liveSegments = remember(liveTrackedPoints) { buildRouteSegments(liveTrackedPoints) }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
        factory = { ctx ->
            FrameLayout(ctx).apply {
                clipChildren = true
                clipToPadding = true
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRect(0, 0, view.width, view.height)
                    }
                }

                val mapView = MapView(ctx).apply {
                    setTileSource(mapStyle.source)
                    setMultiTouchControls(true)
                    overlayManager.tilesOverlay.loadingBackgroundColor = android.graphics.Color.parseColor("#121212")
                    overlayManager.tilesOverlay.loadingLineColor = android.graphics.Color.parseColor("#262626")
                    controller.setZoom(17.0)
                    controller.setCenter(homeLocation ?: currentLocation ?: GeoPoint(42.375, -122.916))
                }
                addView(
                    mapView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        },
        update = { container ->
            val mapView = container.getChildAt(0) as MapView

            if (mapView.tileProvider.tileSource.name() != mapStyle.source.name()) {
                mapView.setTileSource(mapStyle.source)
            }

            mapView.overlayManager.tilesOverlay.setColorFilter(
                if (mapStyle.invertForDark) TilesOverlay.INVERT_COLORS else null
            )

            mapView.overlays.clear()

            mapView.overlays.add(
                MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                        if ((currentIsPlanning || currentIsPickingHome) && p != null) {
                            currentOnMapTap(p)
                            return true
                        }
                        return false
                    }

                    override fun longPressHelper(p: GeoPoint?): Boolean = false
                })
            )

            // 1. Render Shade Canopy Zones (Translucent Emerald Green Circles)
            shadeZones.forEach { zone ->
                val circlePoly = Polygon().apply {
                    points = Polygon.pointsAsCircle(zone.center, zone.radiusMeters)
                    fillPaint.color = android.graphics.Color.parseColor("#442E7D32")
                    outlinePaint.color = android.graphics.Color.parseColor("#8866BB6A")
                    outlinePaint.strokeWidth = 2f
                    title = zone.label
                }
                mapView.overlays.add(circlePoly)
            }

            // 2. Ghost Trails & Destination Flag
            ghostTrails.forEachIndexed { idx, ghostPoints ->
                if (ghostPoints.size > 1) {
                    val ghostLine = Polyline().apply {
                        setPoints(ghostPoints)
                        outlinePaint.color = if (idx == 0) {
                            android.graphics.Color.parseColor("#9900E5FF")
                        } else {
                            android.graphics.Color.parseColor("#66B388FF")
                        }
                        outlinePaint.strokeWidth = 12f
                    }
                    mapView.overlays.add(ghostLine)

                    if (idx == 0) {
                        val destMarker = Marker(mapView).apply {
                            position = ghostPoints.last()
                            icon = MapIconFactory.createWaypointIcon(context, 0, isDestination = true)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = "Destination"
                        }
                        mapView.overlays.add(destMarker)
                    }
                }
            }

            // 3. Ghost Pacer Marker
            ghostPacerPoint?.let { ghostPt ->
                val ghostMarker = Marker(mapView).apply {
                    position = ghostPt
                    icon = ghostIcon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Ghost Pacer"
                }
                mapView.overlays.add(ghostMarker)
            }

            // 4. Planned Route Line & Numbered Waypoints
            if (plannedGeometry.size > 1) {
                val planLine = Polyline().apply {
                    setPoints(plannedGeometry)
                    outlinePaint.color = android.graphics.Color.parseColor("#FFD600")
                    outlinePaint.strokeWidth = 12f
                }
                mapView.overlays.add(planLine)
            }
            plannedAnchors.forEachIndexed { index, pt ->
                val isLast = index == plannedAnchors.size - 1 && plannedAnchors.size > 1
                val wpMarker = Marker(mapView).apply {
                    position = pt
                    icon = MapIconFactory.createWaypointIcon(context, index + 1, isDestination = isLast)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = if (isLast) "Destination" else "Stop ${index + 1}"
                }
                mapView.overlays.add(wpMarker)
            }

            // 5. Multi-Colored Live Walk Segments (Green = Paws, Orange = Stroller)
            liveSegments.forEachIndexed { idx, segment ->
                val segLine = Polyline().apply {
                    setPoints(segment.points)
                    outlinePaint.color = if (segment.isStroller) {
                        android.graphics.Color.parseColor("#FF9100")
                    } else {
                        android.graphics.Color.parseColor("#00E676")
                    }
                    outlinePaint.strokeWidth = 14f
                }
                mapView.overlays.add(segLine)

                if (idx > 0 && segment.points.isNotEmpty()) {
                    val switchMarker = Marker(mapView).apply {
                        position = segment.points.first()
                        icon = if (segment.isStroller) strollerSwitchIcon else pawSwitchIcon
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = if (segment.isStroller) "Switched to Stroller" else "Switched to Paws"
                    }
                    mapView.overlays.add(switchMarker)
                }
            }

            // 6. Saved Home Marker
            homeLocation?.let { homePt ->
                val homeMarker = Marker(mapView).apply {
                    position = homePt
                    icon = homeIcon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Home"
                }
                mapView.overlays.add(homeMarker)
            }

            // 7. Current GPS Location Marker
            currentLocation?.let { loc ->
                val selfMarker = Marker(mapView).apply {
                    position = loc
                    icon = selfIcon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "You & Bean"
                }
                mapView.overlays.add(selfMarker)
            }

            if (centerMapTrigger != null) {
                mapView.controller.animateTo(centerMapTrigger)
                onConsumeCenterTrigger()
            } else if (!currentIsPlanning && !currentIsPickingHome && liveTrackedPoints.isNotEmpty()) {
                currentLocation?.let { mapView.controller.animateTo(it) }
            }

            mapView.invalidate()
        }
    )
}

@Composable
fun WalkHistoryList(
    walks: List<WalkRecord>,
    pinnedGhostId: Long?,
    onSetGhost: (WalkRecord) -> Unit,
    onDeleteWalk: (WalkRecord) -> Unit
) {
    val context = LocalContext.current
    val completedWalks = walks.filter { !it.isPlannedRoute }
    val totalMiles = completedWalks.sumOf { it.distanceMeters } / 1609.34
    val totalPawMiles = completedWalks.sumOf {
        if (it.usedStroller) it.pawDistanceMeters else it.distanceMeters
    } / 1609.34

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = String.format(Locale.US, "Total: %.2f mi  (🐾 Paw: %.2f mi)", totalMiles, totalPawMiles),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${completedWalks.size} walks • ${walks.size - completedWalks.size} saved routes",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            OutlinedButton(
                onClick = {
                    val header = "| Date / Route | Mode | Distance | Duration | Pace | Potty | Notes | Status |\n|---|---|---|---|---|---|---|---|\n"
                    val rows = walks.joinToString("\n") { it.toMarkdownRow() }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Walk History", header + rows))
                    Toast.makeText(context, "Copied Markdown log table", Toast.LENGTH_SHORT).show()
                },
                enabled = walks.isNotEmpty()
            ) {
                Text("Copy All MD")
            }
        }

        if (walks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No walks or routes saved yet.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(walks, key = { it.id }) { walk ->
                    val isPinned = walk.id == pinnedGhostId
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isPinned) Color(0xFF1A2E35) else Color(0xFF1E1E1E)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = walk.routeName.ifBlank { walk.formattedDate() },
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    if (walk.routeName.isNotBlank()) {
                                        Text(
                                            text = walk.formattedDate(),
                                            color = Color.Gray,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                val badges = buildString {
                                    if (walk.usedStroller) append("🛒 ")
                                    if (walk.didPee) append("💧 ")
                                    if (walk.didPoop) append("💩 ")
                                    append(if (walk.isPlannedRoute) "ROUTE" else walk.safetyStatus)
                                }
                                Text(
                                    text = badges,
                                    color = when (walk.safetyStatus) {
                                        "GO" -> Color(0xFF66BB6A)
                                        "CAUTION" -> Color(0xFFFFA726)
                                        "UNSAFE" -> Color(0xFFEF5350)
                                        "PLANNED" -> Color(0xFFFFD600)
                                        else -> Color.LightGray
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }

                            Text(
                                text = "${walk.formattedDistanceMiles()}  •  ${walk.formattedDuration()}  •  ${walk.formattedPace()}",
                                color = Color(0xFF00E676),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            if (walk.notes.isNotBlank()) {
                                Text(
                                    text = "Notes: ${walk.notes}",
                                    color = Color.White,
                                    fontSize = 13.sp
                                )
                            }

                            if (walk.photoUri.isNotBlank()) {
                                WalkPhotoThumbnail(uriString = walk.photoUri)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onSetGhost(walk) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isPinned) Color(0xFF00838F) else Color(0xFF37474F)
                                    ),
                                    modifier = Modifier.weight(1.2f)
                                ) {
                                    Text(
                                        text = if (isPinned) "Active Ghost" else "Set Ghost",
                                        fontSize = 12.sp,
                                        color = Color.White
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Walk Row", walk.toMarkdownRow()))
                                        Toast.makeText(context, "Copied Markdown row", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Copy MD", fontSize = 12.sp)
                                }
                                TextButton(onClick = { onDeleteWalk(walk) }) {
                                    Text("Delete", color = Color(0xFFEF5350), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WalkPhotoThumbnail(uriString: String) {
    val context = LocalContext.current
    val bitmap = remember(uriString) {
        try {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: Exception) {
            null
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Walk photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(8.dp))
        )
    }
}

@Composable
fun WeatherBanner(
    report: DogWeatherReport?,
    isStrollerMode: Boolean,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onOpenInfo: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    val evaluated = remember(report, isStrollerMode) {
        report?.evaluateForMode(isStrollerMode)
    }
    val activeStatus = evaluated?.first ?: report?.status
    val activeReason = evaluated?.second ?: report?.reason ?: ""

    val bgColor = when (activeStatus) {
        SafetyLevel.GO -> Color(0xFF1B5E20)
        SafetyLevel.CAUTION -> Color(0xFFE65100)
        SafetyLevel.UNSAFE -> Color(0xFFB71C1C)
        null -> Color(0xFF263238)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .clickable { isExpanded = !isExpanded }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!isExpanded && report != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val modePrefix = if (isStrollerMode) "🛒 " else ""
                Text(
                    text = "$modePrefix${activeStatus?.name} • Air ${report.airTempF.toInt()}°F • Pave ${report.pavementTempF.toInt()}°F • Shade ${report.shadePercent}%",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onOpenInfo,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("ℹ️ Info", color = Color.White, fontSize = 10.sp)
                    }
                    Text("▼", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val modeTag = if (isStrollerMode) " (STROLLER)" else ""
                Text(
                    text = when {
                        isLoading -> "CHECKING WEATHER..."
                        error != null -> "WEATHER OFFLINE"
                        activeStatus != null -> "STATUS: ${activeStatus.name}$modeTag"
                        else -> "WAITING FOR GPS..."
                    },
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onOpenInfo,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("ℹ️ Info", color = Color.White, fontSize = 11.sp)
                    }
                    TextButton(
                        onClick = onRefresh,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Refresh", color = Color.White, fontSize = 11.sp)
                    }
                    if (report != null) {
                        Text("▲", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                    }
                }
            }

            if (error != null) {
                Text(text = error, color = Color.White, fontSize = 12.sp)
            } else if (report != null) {
                Text(text = activeReason, color = Color.White, fontSize = 12.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MetricPill("Air / Chill", "${report.airTempF.toInt()}°F / ${report.feelsLikeF.toInt()}°F", Modifier.weight(1f))
                    MetricPill("Pavement", "${report.pavementTempF.toInt()}°F", Modifier.weight(0.9f))
                    MetricPill("Shade", "${report.shadePercent}%", Modifier.weight(0.8f))
                    MetricPill("US AQI", "${report.usAqi}", Modifier.weight(0.7f))
                }
            }
        }
    }
}

@Composable
fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, color = Color.LightGray, fontSize = 10.sp)
        Text(text = value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun WalkControlPanel(
    walkState: WalkSessionState,
    ghostTargetMiles: Double?,
    onToggleStroller: () -> Unit,
    onSetReminder: () -> Unit,
    onToggleWalk: () -> Unit
) {
    val totalMiles = walkState.distanceMeters / 1609.34
    val pawMiles = walkState.pawDistanceMeters / 1609.34
    val durationFormatted = String.format(
        Locale.US,
        "%02d:%02d",
        walkState.elapsedSeconds / 60,
        walkState.elapsedSeconds % 60
    )
    val paceStr = if (totalMiles >= 0.03) {
        String.format(Locale.US, "%.1f min/mi", (walkState.elapsedSeconds / 60.0) / totalMiles)
    } else "-- min/mi"

    val distLabel = when {
        walkState.usedStrollerInSession || walkState.isStrollerMode ->
            String.format(Locale.US, "%.2f mi (🐾 %.2f)", totalMiles, pawMiles)
        ghostTargetMiles != null && ghostTargetMiles > 0.0 ->
            String.format(Locale.US, "%.2f / %.2f mi", totalMiles, ghostTargetMiles)
        else ->
            String.format(Locale.US, "%.2f mi", totalMiles)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatBlock(
                label = if (walkState.usedStrollerInSession || walkState.isStrollerMode) "Total (Paw Mi)" else "Distance / Target",
                value = distLabel
            )
            StatBlock("Duration", durationFormatted)
            StatBlock("Pace", paceStr)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = onToggleStroller,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (walkState.isStrollerMode) Color(0xFFFF9100) else Color(0xFF37474F)
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.weight(1.1f)
            ) {
                Text(
                    text = if (walkState.isStrollerMode) "🛒 Stroller: ON" else "🛒 Stroller: OFF",
                    fontSize = 11.sp,
                    color = if (walkState.isStrollerMode) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = onSetReminder,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                modifier = Modifier.weight(0.8f)
            ) {
                Text("⏰ Remind", fontSize = 11.sp, color = Color.White)
            }

            Button(
                onClick = onToggleWalk,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (walkState.isTracking) Color(0xFFD32F2F) else Color(0xFF2E7D32)
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.weight(1.3f)
            ) {
                Text(
                    text = if (walkState.isTracking) "STOP & LOG" else "START WALK",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun StatBlock(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color.Gray, fontSize = 11.sp)
        Text(text = value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
