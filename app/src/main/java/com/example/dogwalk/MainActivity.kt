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
import androidx.compose.foundation.lazy.LazyColumn
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
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ============================================================================
// 1. WEATHER & CANINE IMPERIAL THRESHOLD ENGINE (12-LB YORKIE + STROLLER)
// ============================================================================

enum class SafetyLevel { GO, CAUTION, UNSAFE }

data class DogWeatherReport(
    val airTempF: Double,
    val feelsLikeF: Double,
    val pavementTempF: Double,
    val rainInch: Double,
    val windGustMph: Double,
    val solarRadWm2: Double,
    val usAqi: Int,
    val status: SafetyLevel,
    val reason: String
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
                    "&current=temperature_2m,apparent_temperature,precipitation,wind_gusts_10m,shortwave_radiation" +
                    "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch"

                val aqiUrl = "https://air-quality-api.open-meteo.com/v1/air-quality" +
                    "?latitude=$lat&longitude=$lon&current=us_aqi"

                val weatherJson = JSONObject(getUrlText(weatherUrl)).getJSONObject("current")
                val aqiJson = JSONObject(getUrlText(aqiUrl)).getJSONObject("current")

                val airTemp = weatherJson.optDouble("temperature_2m", 60.0)
                val feelsLike = weatherJson.optDouble("apparent_temperature", airTemp)
                val rain = weatherJson.optDouble("precipitation", 0.0)
                val gusts = weatherJson.optDouble("wind_gusts_10m", 0.0)
                val solarRad = weatherJson.optDouble("shortwave_radiation", 0.0)
                val aqi = aqiJson.optInt("us_aqi", 25)

                val estPavement = ((airTemp + (0.045 * solarRad)) * 10.0).roundToInt() / 10.0

                val (status, reason) = evaluateThresholds(
                    airTemp = airTemp,
                    feelsLike = feelsLike,
                    pavement = estPavement,
                    rain = rain,
                    gusts = gusts,
                    aqi = aqi,
                    isStroller = false
                )

                Result.success(
                    DogWeatherReport(
                        airTempF = airTemp,
                        feelsLikeF = feelsLike,
                        pavementTempF = estPavement,
                        rainInch = rain,
                        windGustMph = gusts,
                        solarRadWm2 = solarRad,
                        usAqi = aqi,
                        status = status,
                        reason = reason
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
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

        // 1. Pavement check (bypassed when riding in stroller)
        if (!isStroller) {
            if (pavement >= 118.0) unsafeReasons.add("Pavement burn risk (${pavement}°F)")
            else if (pavement >= 104.0) cautionReasons.add("Warm pavement (${pavement}°F - use grass/stroller)")
        } else if (pavement >= 104.0) {
            cautionReasons.add("Stroller ON: Paws safe from ${pavement}°F asphalt")
        }

        // 2. Air & Wind Chill check
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

        // 3. Rain & Wind
        if (rain > 0.15) unsafeReasons.add("Heavy rain (${rain} in/h)")
        else if (rain > 0.0) cautionReasons.add(if (isStroller) "Light rain (use stroller cover)" else "Light rain (${rain} in/h)")

        if (gusts >= 28.0) unsafeReasons.add("High wind (${gusts.toInt()} mph)")
        else if (gusts >= 16.0) cautionReasons.add("Breezy (${gusts.toInt()} mph)")

        // 4. Air Quality
        if (aqi > 100) unsafeReasons.add("Poor AQI ($aqi)")
        else if (aqi > 50) cautionReasons.add("Moderate AQI ($aqi)")

        return when {
            unsafeReasons.isNotEmpty() -> SafetyLevel.UNSAFE to unsafeReasons.joinToString(" • ")
            cautionReasons.isNotEmpty() -> SafetyLevel.CAUTION to cautionReasons.joinToString(" • ")
            else -> SafetyLevel.GO to if (isStroller) "Stroller Mode ON: Optimal conditions" else "Optimal walking conditions (Target: 1.0–1.2 mi)"
        }
    }

    private fun getUrlText(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "PersonalDogWalkTracker/5.0 (Android)")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}

// ============================================================================
// 2. OSRM FOOT ROUTING & YORKIE MULTI-DIRECTION LOOP GENERATOR
// ============================================================================

data class PlannedLeg(val anchor: GeoPoint, val snappedPoints: List<GeoPoint>)

object RouteSnapper {
    suspend fun snapFootSegment(start: GeoPoint, end: GeoPoint): List<GeoPoint> =
        withContext(Dispatchers.IO) {
            val coords = "${start.longitude},${start.latitude};${end.longitude},${end.latitude}"
            val endpoints = listOf(
                "https://routing.openstreetmap.de/routed-foot/route/v1/foot/$coords?overview=full&geometries=geojson",
                "https://router.project-osrm.org/route/v1/foot/$coords?overview=full&geometries=geojson"
            )
            for (url in endpoints) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "PersonalDogWalkTracker/5.0 (Android)")
                    if (conn.responseCode == 200) {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val routes = json.optJSONArray("routes")
                        if (routes != null && routes.length() > 0) {
                            val coordArray = routes.getJSONObject(0)
                                .getJSONObject("geometry")
                                .getJSONArray("coordinates")
                            val points = ArrayList<GeoPoint>(coordArray.length())
                            for (i in 0 until coordArray.length()) {
                                val pair = coordArray.getJSONArray(i)
                                points.add(GeoPoint(pair.getDouble(1), pair.getDouble(0)))
                            }
                            if (points.isNotEmpty()) return@withContext points
                        }
                    }
                } catch (_: Exception) {
                }
            }
            listOf(start, end)
        }

    suspend fun generateYorkieLoop(
        center: GeoPoint,
        directionIndex: Int,
        isWarmWeather: Boolean
    ): List<PlannedLeg> {
        val scale = if (isWarmWeather) 0.65 else 1.0
        val dLat = 0.0028 * scale
        val dLon = 0.0036 * scale

        val dirLat = if (directionIndex % 2 == 0) 1.0 else -1.0
        val dirLon = if ((directionIndex / 2) % 2 == 0) 1.0 else -1.0

        val p1 = GeoPoint(center.latitude + (dLat * dirLat), center.longitude)
        val p2 = GeoPoint(center.latitude + (dLat * dirLat), center.longitude + (dLon * dirLon))
        val p3 = GeoPoint(center.latitude, center.longitude + (dLon * dirLon))
        val p4 = center

        val leg1 = snapFootSegment(center, p1)
        val leg2 = snapFootSegment(p1, p2)
        val leg3 = snapFootSegment(p2, p3)
        val leg4 = snapFootSegment(p3, p4)

        return listOf(
            PlannedLeg(center, leg1),
            PlannedLeg(p1, if (leg2.size > 1) leg2.drop(1) else leg2),
            PlannedLeg(p2, if (leg3.size > 1) leg3.drop(1) else leg3),
            PlannedLeg(p3, if (leg4.size > 1) leg4.drop(1) else leg4)
        )
    }
}

// ============================================================================
// 3. CUSTOM MAP MARKER ICON FACTORY
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
// 4. ROOM SQLITE DATABASE (VERSION 7 - STROLLER & PAW DISTANCE SUPPORT)
// ============================================================================

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

    fun toGeoPoints(): List<GeoPoint> {
        if (routeSerialized.isBlank()) return emptyList()
        return routeSerialized.split(";").mapNotNull { pair ->
            val parts = pair.split(",")
            if (parts.size == 2) {
                val lat = parts[0].toDoubleOrNull()
                val lon = parts[1].toDoubleOrNull()
                if (lat != null && lon != null) GeoPoint(lat, lon) else null
            } else null
        }
    }

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
            points.joinToString(";") { "${it.latitude},${it.longitude}" }
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
// 5. FOREGROUND GPS SERVICE & STROLLER-AWARE STATE HOLDER
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
    val routePoints: List<GeoPoint> = emptyList(),
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
        _state.value = current.copy(
            isStrollerMode = nextStroller,
            usedStrollerInSession = current.usedStrollerInSession || nextStroller
        )
    }

    fun updateLocation(point: GeoPoint, trackRoute: Boolean) {
        val current = _state.value
        if (!trackRoute) {
            _state.value = current.copy(currentLocation = point)
            return
        }
        val pts = current.routePoints
        val addedDist = if (pts.isNotEmpty()) {
            val results = FloatArray(1)
            Location.distanceBetween(
                pts.last().latitude, pts.last().longitude,
                point.latitude, point.longitude,
                results
            )
            results[0].toDouble()
        } else 0.0

        if (pts.isEmpty() || addedDist >= 1.5) {
            val addedPawDist = if (current.isStrollerMode) 0.0 else addedDist
            _state.value = current.copy(
                currentLocation = point,
                routePoints = pts + point,
                distanceMeters = current.distanceMeters + addedDist,
                pawDistanceMeters = current.pawDistanceMeters + addedPawDist
            )
        }
    }

    fun setTracking(active: Boolean) {
        val current = _state.value
        _state.value = if (active) {
            WalkSessionState(
                isTracking = true,
                isStrollerMode = current.isStrollerMode,
                usedStrollerInSession = current.isStrollerMode,
                routePoints = listOfNotNull(current.currentLocation),
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
                    .setContentText("Tap to check live pavement temperature and start route tracking.")
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
// 6. MAIN UI, SAFETY/EXHAUSTION GUIDE & STROLLER TRACKING
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
    val points: List<GeoPoint>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val osmConfig = Configuration.getInstance()
        val prefs = getSharedPreferences("osmdroid_clean_prefs_v4", Context.MODE_PRIVATE)
        osmConfig.load(applicationContext, prefs)

        val customUa = "PersonalDogWalkTracker/5.0 (Android; PersonalUse)"
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

    var ghostMode by remember { mutableStateOf(GhostMode.PINNED) }
    var pinnedGhostWalk by remember { mutableStateOf<WalkRecord?>(null) }

    var isPlanningRoute by remember { mutableStateOf(false) }
    var snapToStreets by remember { mutableStateOf(true) }
    var isSnappingSegment by remember { mutableStateOf(false) }
    var plannedLegs by remember { mutableStateOf<List<PlannedLeg>>(emptyList()) }
    var yorkieDirectionIndex by remember { mutableIntStateOf(0) }

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

    fun refreshLocationAndWeather() {
        isLoadingWeather = true
        weatherError = null
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            val targetLat = loc?.latitude ?: 42.375
            val targetLon = loc?.longitude ?: -122.916
            WalkStateHolder.updateLocation(GeoPoint(targetLat, targetLon), trackRoute = false)

            scope.launch {
                val res = WeatherRepository.fetchDogWeather(targetLat, targetLon)
                isLoadingWeather = false
                res.onSuccess { weatherReport = it }
                    .onFailure { weatherError = "Weather error: ${it.localizedMessage}" }
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

    // 1. WEATHER, PAW SAFETY & YORKIE EXHAUSTION INFO MODAL
    if (showSafetyInfoDialog) {
        WeatherAndExhaustionDialog(
            report = weatherReport,
            walkState = walkState,
            onDismiss = { showSafetyInfoDialog = false }
        )
    }

    // 2. POST-WALK SUMMARY DIALOG (NOTES, PEE/POOP, STROLLER, PHOTO)
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
                                    routeSerialized = WalkRecord.serializeRoute(snap.points),
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

    // 3. SAVE PLANNED ROUTE DIALOG
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Name This Route") },
            text = {
                OutlinedTextField(
                    value = routeNameInput,
                    onValueChange = { routeNameInput = it },
                    label = { Text("Route Name (e.g., Morning 1.2mi Loop)") },
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
            // COLLAPSIBLE WEATHER BANNER WITH INFO BUTTON & STROLLER-AWARE STATUS
            WeatherBanner(
                report = weatherReport,
                isStrollerMode = walkState.isStrollerMode,
                isLoading = isLoadingWeather,
                error = weatherError,
                onRefresh = { refreshLocationAndWeather() },
                onOpenInfo = { showSafetyInfoDialog = true }
            )

            // ROUTE BUILDER & YORKIE AUTO-LOOP BAR
            if (isPlanningRoute) {
                val plannedMiles = calculatePolylineMeters(plannedFullGeometry) / 1609.34
                val quadrants = listOf("NE", "SE", "SW", "NW")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2D1E18), RoundedCornerShape(10.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isSnappingSegment) {
                                "Calculating sidewalk route..."
                            } else {
                                String.format(Locale.US, "Planned: %.2f mi (%d stops)", plannedMiles, plannedLegs.size)
                            },
                            color = Color(0xFFFFD600),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "12-lb Yorkie ideal: 0.8–1.2 mi",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                val loc = walkState.currentLocation
                                if (loc == null) {
                                    Toast.makeText(context, "Waiting for GPS fix...", Toast.LENGTH_SHORT).show()
                                } else if (!isSnappingSegment) {
                                    val dirToUse = yorkieDirectionIndex
                                    yorkieDirectionIndex = (yorkieDirectionIndex + 1) % 4
                                    val warm = (weatherReport?.airTempF ?: 65.0) >= 75.0 ||
                                        (weatherReport?.pavementTempF ?: 75.0) >= 100.0
                                    isSnappingSegment = true
                                    scope.launch {
                                        plannedLegs = RouteSnapper.generateYorkieLoop(loc, dirToUse, warm)
                                        isSnappingSegment = false
                                        Toast.makeText(
                                            context,
                                            "Generated ${quadrants[dirToUse]} Yorkie Loop!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00838F)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1.4f)
                        ) {
                            Text("New Yorkie Loop 🐾", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
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

            // CLIPPED MAP CONTAINER
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clipToBounds()
            ) {
                OsmMapContainer(
                    currentLocation = walkState.currentLocation,
                    liveRoutePoints = walkState.routePoints,
                    ghostTrails = ghostTrailsToRender,
                    ghostPacerPoint = ghostPacerPoint,
                    plannedGeometry = plannedFullGeometry,
                    plannedAnchors = plannedAnchorWaypoints,
                    isPlanningRoute = isPlanningRoute,
                    mapStyle = mapStyle,
                    onMapTap = { tappedGeoPoint ->
                        if (isPlanningRoute && !isSnappingSegment) {
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

                // FLOATING TOP MAP CONTROLS
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = { ghostMode = ghostMode.next() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (ghostMode != GhostMode.OFF) Color(0xCC006064) else Color(0xCC1E1E1E)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(ghostMode.label, fontSize = 11.sp, color = Color.White)
                    }

                    Button(
                        onClick = { isPlanningRoute = !isPlanningRoute },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPlanningRoute) Color(0xFFFF8F00) else Color(0xCC1E1E1E)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isPlanningRoute) "Close Planner" else "Plan / Auto-Route",
                            fontSize = 11.sp,
                            color = if (isPlanningRoute) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            val nextStyle = mapStyle.next()
                            mapStyle = nextStyle
                            prefs.edit().putString("saved_map_style", nextStyle.name).apply()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1E1E1E)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Map: ${mapStyle.label}", fontSize = 11.sp, color = Color.White)
                    }
                }
            }

            // BOTTOM WALK CONTROL BAR (WITH STROLLER MODE BUTTON)
            WalkControlPanel(
                walkState = walkState,
                ghostTargetMiles = if (ghostMode != GhostMode.OFF) {
                    activeGhostRecord?.let { it.distanceMeters / 1609.34 }
                } else null,
                onToggleStroller = {
                    WalkStateHolder.toggleStrollerMode()
                    val modeMsg = if (WalkStateHolder.state.value.isStrollerMode) {
                        "Stroller Mode ON: Tracking route while Bean rides 🛒"
                    } else {
                        "Stroller Mode OFF: Tracking Bean's paw distance 🐾"
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
                            points = walkState.routePoints
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
// 7. WEATHER, PAW SAFETY & YORKIE EXHAUSTION GUIDE DIALOG
// ============================================================================

@Composable
fun WeatherAndExhaustionDialog(
    report: DogWeatherReport?,
    walkState: WalkSessionState,
    onDismiss: () -> Unit
) {
    val pawMiles = walkState.pawDistanceMeters / 1609.34
    val totalMiles = walkState.distanceMeters / 1609.34
    val airF = report?.airTempF ?: 65.0
    val paveF = report?.pavementTempF ?: 75.0

    // Calculate live exhaustion risk for a 12-lb Yorkie based on paw miles + heat
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
            Color(0xFF29B6F6)
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
            Text("Weather & Yorkie Safety Guide 🐾", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. LIVE EXERTION CARD
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

                // 2. LIVE WEATHER DETAILS
                if (report != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("CURRENT MICRO-CLIMATE BREAKDOWN", color = Color(0xFFFFD600), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("• Air Temp: ${report.airTempF}°F (Feels Like: ${report.feelsLikeF}°F)", color = Color.White, fontSize = 12.sp)
                        Text("• Sunlit Asphalt Estimate: ${paveF}°F (Solar: ${report.solarRadWm2.toInt()} W/m²)", color = Color.White, fontSize = 12.sp)
                        Text("• Wind Gusts: ${report.windGustMph.toInt()} mph  |  Precip: ${report.rainInch} in/h", color = Color.White, fontSize = 12.sp)
                        Text("• Air Quality (US AQI): ${report.usAqi} (Unsafe > 100)", color = Color.White, fontSize = 12.sp)
                    }
                }

                Divider(color = Color.DarkGray)

                // 3. SIGNS OF EXHAUSTION & OVERHEATING
                Text("12-lb Yorkie Exhaustion & Heat Signs", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "• Early Fatigue: Lagging behind your heel, sitting down mid-walk, or pace dropping slower than 24 min/mi.\n" +
                        "• Overheating (>72°F Air): Heavy open-mouth panting, bright red tongue/gums, warm ears, or seeking shade. Put Bean in the stroller with the mesh open and offer water.\n" +
                        "• Trachea & Harness Care: Yorkies are prone to tracheal collapse—if Bean coughs or honks when pulling, switch to the stroller or loosen the chest harness.",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )

                Divider(color = Color.DarkGray)

                // 4. PAW BURN & COLD THRESHOLDS
                Text("Paw Pad & Cold Weather Thresholds", color = Color(0xFF29B6F6), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "• 104°F–117°F Asphalt (Caution): Stick to grass edges or shaded sidewalks.\n" +
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

@Composable
fun OsmMapContainer(
    currentLocation: GeoPoint?,
    liveRoutePoints: List<GeoPoint>,
    ghostTrails: List<List<GeoPoint>>,
    ghostPacerPoint: GeoPoint?,
    plannedGeometry: List<GeoPoint>,
    plannedAnchors: List<GeoPoint>,
    isPlanningRoute: Boolean,
    mapStyle: MapStyle,
    onMapTap: (GeoPoint) -> Unit
) {
    val context = LocalContext.current
    val currentOnMapTap by rememberUpdatedState(onMapTap)
    val currentIsPlanning by rememberUpdatedState(isPlanningRoute)

    val selfIcon = remember { MapIconFactory.createSelfIcon(context) }
    val ghostIcon = remember { MapIconFactory.createGhostIcon(context) }

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
                    controller.setCenter(currentLocation ?: GeoPoint(42.375, -122.916))
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
                        if (currentIsPlanning && p != null) {
                            currentOnMapTap(p)
                            return true
                        }
                        return false
                    }

                    override fun longPressHelper(p: GeoPoint?): Boolean = false
                })
            )

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

            ghostPacerPoint?.let { ghostPt ->
                val ghostMarker = Marker(mapView).apply {
                    position = ghostPt
                    icon = ghostIcon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Ghost Pacer"
                }
                mapView.overlays.add(ghostMarker)
            }

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

            if (liveRoutePoints.size > 1) {
                val liveLine = Polyline().apply {
                    setPoints(liveRoutePoints)
                    outlinePaint.color = android.graphics.Color.parseColor("#00E676")
                    outlinePaint.strokeWidth = 14f
                }
                mapView.overlays.add(liveLine)
            }

            currentLocation?.let { loc ->
                val selfMarker = Marker(mapView).apply {
                    position = loc
                    icon = selfIcon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "You & Bean"
                }
                mapView.overlays.add(selfMarker)
                if (!currentIsPlanning) {
                    mapView.controller.animateTo(loc)
                }
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
                    text = "$modePrefix${activeStatus?.name} • Air ${report.airTempF.toInt()}°F • Pave ${report.pavementTempF.toInt()}°F",
                    color = Color.White,
                    fontSize = 13.sp,
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
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("ℹ️ Info", color = Color.White, fontSize = 11.sp)
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
                    MetricPill("Air / Chill", "${report.airTempF}°F / ${report.feelsLikeF}°F", Modifier.weight(1f))
                    MetricPill("Pavement", "${report.pavementTempF}°F", Modifier.weight(1f))
                    MetricPill("Rain / Wind", "${report.rainInch}in / ${report.windGustMph.toInt()}mph", Modifier.weight(1f))
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
                    containerColor = if (walkState.isStrollerMode) Color(0xFF0277BD) else Color(0xFF37474F)
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.weight(1.1f)
            ) {
                Text(
                    text = if (walkState.isStrollerMode) "🛒 Stroller: ON" else "🛒 Stroller: OFF",
                    fontSize = 11.sp,
                    color = Color.White,
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
