package com.example.dogwalk

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Outline
import android.location.Location
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ============================================================================
// 1. WEATHER & CANINE IMPERIAL THRESHOLD ENGINE
// ============================================================================

enum class SafetyLevel { GO, CAUTION, UNSAFE }

data class DogWeatherReport(
    val airTempF: Double,
    val feelsLikeF: Double,
    val pavementTempF: Double,
    val rainInch: Double,
    val windGustMph: Double,
    val usAqi: Int,
    val status: SafetyLevel,
    val reason: String
)

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
                    aqi = aqi
                )

                Result.success(
                    DogWeatherReport(
                        airTempF = airTemp,
                        feelsLikeF = feelsLike,
                        pavementTempF = estPavement,
                        rainInch = rain,
                        windGustMph = gusts,
                        usAqi = aqi,
                        status = status,
                        reason = reason
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun evaluateThresholds(
        airTemp: Double,
        feelsLike: Double,
        pavement: Double,
        rain: Double,
        gusts: Double,
        aqi: Int
    ): Pair<SafetyLevel, String> {
        val unsafeReasons = mutableListOf<String>()
        val cautionReasons = mutableListOf<String>()

        if (pavement >= 118.0) unsafeReasons.add("Pavement burn risk (${pavement}°F)")
        else if (pavement >= 104.0) cautionReasons.add("Warm pavement (${pavement}°F - stick to shade/grass)")

        if (feelsLike < 32.0) unsafeReasons.add("Freezing wind chill (${feelsLike}°F)")
        else if (airTemp >= 80.0) unsafeReasons.add("Heat exhaustion risk (${airTemp}°F)")
        else if (feelsLike < 50.0) cautionReasons.add("Chilly (${feelsLike}°F - coat recommended)")
        else if (airTemp >= 72.0) cautionReasons.add("Warm air (${airTemp}°F)")

        if (rain > 0.1) unsafeReasons.add("Heavy rain (${rain} in/h)")
        else if (rain > 0.0) cautionReasons.add("Light rain (${rain} in/h - wet coat heat loss)")

        if (gusts >= 25.0) unsafeReasons.add("High wind gusts (${gusts} mph)")
        else if (gusts >= 15.0) cautionReasons.add("Breezy gusts (${gusts} mph)")

        if (aqi > 100) unsafeReasons.add("Poor air quality (AQI $aqi)")
        else if (aqi > 50) cautionReasons.add("Moderate AQI ($aqi)")

        return when {
            unsafeReasons.isNotEmpty() -> SafetyLevel.UNSAFE to unsafeReasons.joinToString(" • ")
            cautionReasons.isNotEmpty() -> SafetyLevel.CAUTION to cautionReasons.joinToString(" • ")
            else -> SafetyLevel.GO to "Optimal walking conditions"
        }
    }

    private fun getUrlText(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "PersonalDogWalkTracker/3.2 (Android)")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}

// ============================================================================
// 2. OSRM FOOT-ROUTING ENGINE (KEYLESS SIDEWALK & TRAIL SNAPPING)
// ============================================================================

data class PlannedLeg(
    val anchor: GeoPoint,
    val snappedPoints: List<GeoPoint>
)

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
                    conn.setRequestProperty("User-Agent", "PersonalDogWalkTracker/3.2 (Android)")

                    if (conn.responseCode == 200) {
                        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(responseText)
                        val routes = json.optJSONArray("routes")
                        if (routes != null && routes.length() > 0) {
                            val geometry = routes.getJSONObject(0).getJSONObject("geometry")
                            val coordArray = geometry.getJSONArray("coordinates")
                            val points = ArrayList<GeoPoint>(coordArray.length())
                            for (i in 0 until coordArray.length()) {
                                val pair = coordArray.getJSONArray(i)
                                val lon = pair.getDouble(0)
                                val lat = pair.getDouble(1)
                                points.add(GeoPoint(lat, lon))
                            }
                            if (points.isNotEmpty()) {
                                return@withContext points
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            listOf(start, end)
        }
}

// ============================================================================
// 3. ROOM SQLITE DATABASE (VERSION 4 - SUPPORTS ROUTE NAMING)
// ============================================================================

@Entity(tableName = "walk_records")
data class WalkRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeName: String = "",
    val timestampMillis: Long,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val airTempF: Double,
    val pavementTempF: Double,
    val safetyStatus: String,
    val routeSerialized: String
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
        val mins = durationSeconds / 60
        val secs = durationSeconds % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    fun formattedDistanceMiles(): String {
        val miles = distanceMeters / 1609.34
        return String.format(Locale.US, "%.2f mi", miles)
    }

    fun formattedPace(): String {
        if (isPlannedRoute) return "${toGeoPoints().size} path pts"
        val miles = distanceMeters / 1609.34
        return if (miles >= 0.03) {
            val pace = (durationSeconds / 60.0) / miles
            String.format(Locale.US, "%.1f min/mi", pace)
        } else "-- min/mi"
    }

    fun toMarkdownRow(): String {
        val nameCol = if (routeName.isNotBlank()) "($routeName)" else ""
        return "| ${formattedDate()} $nameCol | ${formattedDistanceMiles()} | ${formattedDuration()} | ${formattedPace()} | ${airTempF}°F / ${pavementTempF}°F | $safetyStatus |"
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

@Database(entities = [WalkRecord::class], version = 4, exportSchema = false)
abstract class WalkDatabase : RoomDatabase() {
    abstract fun walkDao(): WalkDao

    companion object {
        @Volatile
        private var INSTANCE: WalkDatabase? = null

        fun getInstance(context: Context): WalkDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WalkDatabase::class.java,
                    "dog_walk_history.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ============================================================================
// 4. FOREGROUND GPS LOCATION SERVICE
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
    val routePoints: List<GeoPoint> = emptyList(),
    val distanceMeters: Double = 0.0,
    val elapsedSeconds: Long = 0L,
    val currentLocation: GeoPoint? = null
)

object WalkStateHolder {
    private val _state = MutableStateFlow(WalkSessionState())
    val state = _state.asStateFlow()

    fun updateLocation(point: GeoPoint, trackRoute: Boolean) {
        val current = _state.value
        if (!trackRoute) {
            _state.value = current.copy(currentLocation = point)
            return
        }
        val pts = current.routePoints
        val addedDist = if (pts.isNotEmpty()) {
            val last = pts.last()
            val results = FloatArray(1)
            Location.distanceBetween(
                last.latitude, last.longitude,
                point.latitude, point.longitude,
                results
            )
            results[0].toDouble()
        } else 0.0

        if (pts.isEmpty() || addedDist >= 1.5) {
            _state.value = current.copy(
                currentLocation = point,
                routePoints = pts + point,
                distanceMeters = current.distanceMeters + addedDist
            )
        }
    }

    fun setTracking(active: Boolean) {
        if (active) {
            _state.value = WalkSessionState(
                isTracking = true,
                routePoints = listOfNotNull(_state.value.currentLocation),
                distanceMeters = 0.0,
                elapsedSeconds = 0L,
                currentLocation = _state.value.currentLocation
            )
        } else {
            _state.value = _state.value.copy(isTracking = false)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startWalkTracking()
            ACTION_STOP -> stopWalkTracking()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startWalkTracking() {
        createNotificationChannel()
        val notification = buildNotification()

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

    private fun stopWalkTracking() {
        WalkStateHolder.setTracking(false)
        timerJob?.cancel()
        fusedClient.removeLocationUpdates(locationCallback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Walk GPS Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dog Walk Active")
            .setContentText("Recording GPS route in background...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        timerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "dog_walk_channel"
        const val ACTION_START = "ACTION_START_WALK"
        const val ACTION_STOP = "ACTION_STOP_WALK"
    }
}

// ============================================================================
// 5. MAP UI & COMPACT WEATHER
// ============================================================================

val OSM_KEYLESS_SOURCE = XYTileSource("OSM_Keyless_v3", 0, 19, 256, ".png", arrayOf("https://tile.openstreetmap.org/"))
val ESRI_STREET_SOURCE = object : OnlineTileSourceBase(
    "EsriWorldStreet_v3", 0, 19, 256, "", arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        return baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
    }
}

enum class MapStyle(val label: String, val source: OnlineTileSourceBase, val invertForDark: Boolean) {
    DARK("Dark", OSM_KEYLESS_SOURCE, true),
    LIGHT("Light", OSM_KEYLESS_SOURCE, false),
    ESRI("Esri", ESRI_STREET_SOURCE, false);
    fun next(): MapStyle {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }
}

enum class GhostMode(val label: String) {
    OFF("Ghost: OFF"), PINNED("Ghost: PINNED"), ALL("Ghost: ALL");
    fun next(): GhostMode {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val osmConfig = Configuration.getInstance()
        val prefs = getSharedPreferences("osmdroid_clean_prefs_v3", Context.MODE_PRIVATE)
        osmConfig.load(applicationContext, prefs)
        val customUa = "PersonalDogWalkTracker/3.2 (Android; PersonalUse)"
        osmConfig.userAgentValue = customUa
        osmConfig.additionalHttpRequestProperties["User-Agent"] = customUa
        osmConfig.additionalHttpRequestProperties["Referer"] = "https://www.openstreetmap.org/"
        val cleanCacheDir = File(cacheDir, "osm_tiles_v3")
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

    val prefs = remember { context.getSharedPreferences("osmdroid_clean_prefs_v3", Context.MODE_PRIVATE) }
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
    
    // Name Route Dialog State
    var showNameDialog by remember { mutableStateOf(false) }
    var routeNameInput by remember { mutableStateOf("") }

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
                    .onFailure { weatherError = "Weather fetch failed: ${it.localizedMessage}" }
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

    val ghostPacerPoint: GeoPoint? = remember(walkState.isTracking, walkState.elapsedSeconds, ghostMode, activeGhostRecord) {
        if (!walkState.isTracking || ghostMode == GhostMode.OFF || activeGhostRecord == null) null
        else {
            val pts = activeGhostRecord.toGeoPoints()
            if (pts.size < 2) null
            else {
                val targetDuration = if (activeGhostRecord.durationSeconds > 0) activeGhostRecord.durationSeconds
                else ((activeGhostRecord.distanceMeters / 1609.34) * 20.0 * 60.0).toLong().coerceAtLeast(60L)
                val fraction = (walkState.elapsedSeconds.toDouble() / targetDuration.toDouble()).coerceIn(0.0, 1.0)
                val index = ((pts.size - 1) * fraction).roundToInt().coerceIn(0, pts.size - 1)
                pts[index]
            }
        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Save Route") },
            text = {
                OutlinedTextField(
                    value = routeNameInput,
                    onValueChange = { routeNameInput = it },
                    label = { Text("Route Name (Optional)") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    showNameDialog = false
                    val plannedMeters = calculatePolylineMeters(plannedFullGeometry)
                    scope.launch {
                        val newRoute = WalkRecord(
                            timestampMillis = System.currentTimeMillis(),
                            routeName = routeNameInput.trim(),
                            distanceMeters = plannedMeters,
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
                        Toast.makeText(context, "Route saved & set as Ghost Trail!", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Save Route")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E), RoundedCornerShape(10.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { activeTab = 0 },
                colors = ButtonDefaults.buttonColors(containerColor = if (activeTab == 0) Color(0xFF2E7D32) else Color.Transparent),
                modifier = Modifier.weight(1f)
            ) { Text("LIVE MAP", color = Color.White, fontWeight = FontWeight.Bold) }
            Button(
                onClick = { activeTab = 1 },
                colors = ButtonDefaults.buttonColors(containerColor = if (activeTab == 1) Color(0xFF2E7D32) else Color.Transparent),
                modifier = Modifier.weight(1f)
            ) { Text("ROUTES & LOGS (${savedWalks.size})", color = Color.White, fontWeight = FontWeight.Bold) }
        }

        if (activeTab == 0) {
            WeatherBanner(report = weatherReport, isLoading = isLoadingWeather, error = weatherError, onRefresh = { refreshLocationAndWeather() })

            if (isPlanningRoute) {
                val plannedMeters = calculatePolylineMeters(plannedFullGeometry)
                val plannedMiles = plannedMeters / 1609.34
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF3E2723), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isSnappingSegment) "Snapping to trail..." else String.format(Locale.US, "Plan: %.2f mi (%d stops)", plannedMiles, plannedLegs.size),
                            color = Color(0xFFFFD600), fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                        Text(text = if (snapToStreets) "OSRM Foot Routing: ON" else "Straight Line Mode", color = Color.LightGray, fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = { snapToStreets = !snapToStreets },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) { Text(if (snapToStreets) "Snap: ON" else "Snap: OFF", fontSize = 10.sp, color = if (snapToStreets) Color(0xFF00E676) else Color.LightGray) }
                        OutlinedButton(
                            onClick = { if (plannedLegs.isNotEmpty() && !isSnappingSegment) plannedLegs = plannedLegs.dropLast(1) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) { Text("Undo", fontSize = 10.sp, color = Color.White) }
                        Button(
                            onClick = {
                                if (plannedFullGeometry.size >= 2 && !isSnappingSegment) showNameDialog = true
                                else Toast.makeText(context, "Tap at least 2 points", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8F00)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text("Save", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).clipToBounds()) {
                OsmMapContainer(
                    currentLocation = walkState.currentLocation, liveRoutePoints = walkState.routePoints,
                    ghostTrails = ghostTrailsToRender, ghostPacerPoint = ghostPacerPoint,
                    plannedGeometry = plannedFullGeometry, plannedAnchors = plannedAnchorWaypoints,
                    isPlanningRoute = isPlanningRoute, mapStyle = mapStyle,
                    onMapTap = { tappedGeoPoint ->
                        if (isPlanningRoute && !isSnappingSegment) {
                            if (plannedLegs.isEmpty()) {
                                plannedLegs = listOf(PlannedLeg(anchor = tappedGeoPoint, snappedPoints = listOf(tappedGeoPoint)))
                            } else {
                                val prevAnchor = plannedLegs.last().anchor
                                if (!snapToStreets) {
                                    plannedLegs = plannedLegs + PlannedLeg(anchor = tappedGeoPoint, snappedPoints = listOf(tappedGeoPoint))
                                } else {
                                    isSnappingSegment = true
                                    scope.launch {
                                        val segmentPoints = RouteSnapper.snapFootSegment(prevAnchor, tappedGeoPoint)
                                        val cleanedSegment = if (segmentPoints.size > 1) segmentPoints.drop(1) else segmentPoints
                                        plannedLegs = plannedLegs + PlannedLeg(anchor = tappedGeoPoint, snappedPoints = cleanedSegment)
                                        isSnappingSegment = false
                                    }
                                }
                            }
                        }
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = { ghostMode = ghostMode.next() },
                        colors = ButtonDefaults.buttonColors(containerColor = if (ghostMode != GhostMode.OFF) Color(0xCC006064) else Color(0xCC1E1E1E)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text(text = ghostMode.label, color = Color.White, fontSize = 11.sp) }

                    Button(
                        onClick = { isPlanningRoute = !isPlanningRoute },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isPlanningRoute) Color(0xFFFF8F00) else Color(0xCC1E1E1E)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text(text = if (isPlanningRoute) "Planning: ON" else "Plan Route", color = if (isPlanningRoute) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp) }

                    Button(
                        onClick = {
                            val nextStyle = mapStyle.next()
                            mapStyle = nextStyle
                            prefs.edit().putString("saved_map_style", nextStyle.name).apply()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1E1E1E)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text(text = "Map: ${mapStyle.label}", color = Color.White, fontSize = 11.sp) }
                }
            }

            WalkControlPanel(
                walkState = walkState,
                ghostTargetMiles = if (ghostMode != GhostMode.OFF) activeGhostRecord?.let { it.distanceMeters / 1609.34 } else null,
                onToggleWalk = {
                    if (walkState.isTracking) {
                        val snapshotDist = walkState.distanceMeters
                        val snapshotSecs = walkState.elapsedSeconds
                        val snapshotPoints = walkState.routePoints
                        scope.launch {
                            val record = WalkRecord(
                                timestampMillis = System.currentTimeMillis(), distanceMeters = snapshotDist, durationSeconds = snapshotSecs,
                                airTempF = weatherReport?.airTempF ?: 0.0, pavementTempF = weatherReport?.pavementTempF ?: 0.0,
                                safetyStatus = weatherReport?.status?.name ?: "N/A", routeSerialized = WalkRecord.serializeRoute(snapshotPoints)
                            )
                            walkDao.insertWalk(record)
                            Toast.makeText(context, "Walk saved to history", Toast.LENGTH_SHORT).show()
                        }
                        context.startService(Intent(context, WalkTrackerService::class.java).apply { action = WalkTrackerService.ACTION_STOP })
                    } else {
                        val startIntent = Intent(context, WalkTrackerService::class.java).apply { action = WalkTrackerService.ACTION_START }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(startIntent)
                        else context.startService(startIntent)
                    }
                }
            )
        } else {
            WalkHistoryList(
                walks = savedWalks, pinnedGhostId = activeGhostRecord?.id,
                onSetGhost = { walk ->
                    pinnedGhostWalk = walk
                    ghostMode = GhostMode.PINNED
                    activeTab = 0
                    Toast.makeText(context, "Ghost Trail pinned to map", Toast.LENGTH_SHORT).show()
                },
                onDeleteWalk = { walk ->
                    if (pinnedGhostWalk?.id == walk.id) pinnedGhostWalk = null
                    scope.launch { walkDao.deleteWalk(walk) }
                }
            )
        }
    }
}

@Composable
fun OsmMapContainer(
    currentLocation: GeoPoint?, liveRoutePoints: List<GeoPoint>, ghostTrails: List<List<GeoPoint>>, ghostPacerPoint: GeoPoint?,
    plannedGeometry: List<GeoPoint>, plannedAnchors: List<GeoPoint>, isPlanningRoute: Boolean, mapStyle: MapStyle, onMapTap: (GeoPoint) -> Unit
) {
    val currentOnMapTap by androidx.compose.runtime.rememberUpdatedState(onMapTap)
    val currentIsPlanning by androidx.compose.runtime.rememberUpdatedState(isPlanningRoute)

    AndroidView(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        factory = { ctx ->
            FrameLayout(ctx).apply {
                clipChildren = true; clipToPadding = true; clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRect(0, 0, view.width, view.height) } }
                val mapView = MapView(ctx).apply {
                    setTileSource(mapStyle.source); setMultiTouchControls(true)
                    overlayManager.tilesOverlay.loadingBackgroundColor = android.graphics.Color.parseColor("#121212")
                    overlayManager.tilesOverlay.loadingLineColor = android.graphics.Color.parseColor("#262626")
                    controller.setZoom(17.0); controller.setCenter(currentLocation ?: GeoPoint(42.375, -122.916))
                }
                addView(mapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        },
        update = { container ->
            val mapView = container.getChildAt(0) as MapView
            if (mapView.tileProvider.tileSource.name() != mapStyle.source.name()) mapView.setTileSource(mapStyle.source)
            mapView.overlayManager.tilesOverlay.setColorFilter(if (mapStyle.invertForDark) TilesOverlay.INVERT_COLORS else null)
            mapView.overlays.clear()

            mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                    if (currentIsPlanning && p != null) { currentOnMapTap(p); return true }
                    return false
                }
                override fun longPressHelper(p: GeoPoint?): Boolean = false
            }))

            ghostTrails.forEachIndexed { idx, ghostPoints ->
                if (ghostPoints.size > 1) {
                    mapView.overlays.add(Polyline().apply { setPoints(ghostPoints); outlinePaint.color = if (idx == 0) android.graphics.Color.parseColor("#9900E5FF") else android.graphics.Color.parseColor("#66B388FF"); outlinePaint.strokeWidth = 12f })
                }
            }

            ghostPacerPoint?.let { ghostPt -> mapView.overlays.add(Marker(mapView).apply { position = ghostPt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); title = "Ghost Pacer"; alpha = 0.8f }) }

            if (plannedGeometry.size > 1) mapView.overlays.add(Polyline().apply { setPoints(plannedGeometry); outlinePaint.color = android.graphics.Color.parseColor("#FFD600"); outlinePaint.strokeWidth = 12f })
            plannedAnchors.forEachIndexed { index, pt -> mapView.overlays.add(Marker(mapView).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Stop ${index + 1}" }) }

            if (liveRoutePoints.size > 1) mapView.overlays.add(Polyline().apply { setPoints(liveRoutePoints); outlinePaint.color = android.graphics.Color.parseColor("#00E676"); outlinePaint.strokeWidth = 14f })

            currentLocation?.let { loc ->
                mapView.overlays.add(Marker(mapView).apply { position = loc; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Current Location" })
                if (!currentIsPlanning) mapView.controller.animateTo(loc)
            }
            mapView.invalidate()
        }
    )
}

@Composable
fun WalkHistoryList(
    walks: List<WalkRecord>, pinnedGhostId: Long?, onSetGhost: (WalkRecord) -> Unit, onDeleteWalk: (WalkRecord) -> Unit
) {
    val context = LocalContext.current
    val completedWalks = walks.filter { !it.isPlannedRoute }
    val totalMiles = completedWalks.sumOf { it.distanceMeters } / 1609.34

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp)).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "Total Walked: ${String.format(Locale.US, "%.2f mi", totalMiles)}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(text = "${completedWalks.size} walks • ${walks.size - completedWalks.size} planned routes", color = Color.Gray, fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    val header = "| Date | Distance | Duration | Pace | Air / Pavement | Status |\n|---|---|---|---|---|---|\n"
                    val rows = walks.joinToString("\n") { it.toMarkdownRow() }
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Walk History Table", header + rows))
                    Toast.makeText(context, "Copied full Markdown table", Toast.LENGTH_SHORT).show()
                },
                enabled = walks.isNotEmpty()
            ) { Text("Copy All MD") }
        }

        if (walks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No walks or planned routes saved yet.", color = Color.Gray) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(walks, key = { it.id }) { walk ->
                    val isPinned = walk.id == pinnedGhostId
                    Card(
                        colors = CardDefaults.cardColors(containerColor = if (isPinned) Color(0xFF1A2E35) else Color(0xFF1E1E1E)),
                        shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (walk.routeName.isNotBlank()) walk.routeName else walk.formattedDate(),
                                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
                                )
                                Text(
                                    text = if (walk.isPlannedRoute) "PLANNED ROUTE" else walk.safetyStatus,
                                    color = when (walk.safetyStatus) {
                                        "GO" -> Color(0xFF66BB6A)
                                        "CAUTION" -> Color(0xFFFFA726)
                                        "UNSAFE" -> Color(0xFFEF5350)
                                        "PLANNED" -> Color(0xFFFFD600)
                                        else -> Color.LightGray
                                    },
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp
                                )
                            }
                            Text(text = "${walk.formattedDistanceMiles()}  •  ${walk.formattedDuration()}  •  ${walk.formattedPace()}", color = Color(0xFF00E676), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            if (!walk.isPlannedRoute) Text(text = "Air: ${walk.airTempF}°F  |  Est. Pavement: ${walk.pavementTempF}°F", color = Color.LightGray, fontSize = 12.sp)

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onSetGhost(walk) },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (isPinned) Color(0xFF00838F) else Color(0xFF37474F)),
                                    modifier = Modifier.weight(1.2f)
                                ) { Text(text = if (isPinned) "Active Ghost" else "Set Ghost", fontSize = 12.sp, color = Color.White) }
                                OutlinedButton(
                                    onClick = {
                                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Walk Row", walk.toMarkdownRow()))
                                        Toast.makeText(context, "Copied Markdown row", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Copy MD", fontSize = 12.sp) }
                                TextButton(onClick = { onDeleteWalk(walk) }) { Text("Delete", color = Color(0xFFEF5350), fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WeatherBanner(report: DogWeatherReport?, isLoading: Boolean, error: String?, onRefresh: () -> Unit) {
    var isExpanded by remember { mutableStateOf(true) }
    val bgColor = when (report?.status) {
        SafetyLevel.GO -> Color(0xFF1B5E20)
        SafetyLevel.CAUTION -> Color(0xFFE65100)
        SafetyLevel.UNSAFE -> Color(0xFFB71C1C)
        null -> Color(0xFF263238)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { isExpanded = !isExpanded }
            .padding(10.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!isExpanded && report != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = "${report.status.name}  •  ${report.airTempF}°F  •  AQI ${report.usAqi}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(text = "▼ Expand", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        isLoading -> "CHECKING WEATHER..."
                        error != null -> "WEATHER OFFLINE"
                        report != null -> "WALK STATUS: ${report.status.name}"
                        else -> "WAITING FOR GPS..."
                    },
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold
                )
                if (report != null) Text(text = "▲ Shrink", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                else TextButton(onClick = onRefresh) { Text("Refresh", color = Color.White) }
            }
            if (error != null) Text(text = error, color = Color.White, fontSize = 13.sp)
            else if (report != null) {
                Text(text = report.reason, color = Color.White, fontSize = 13.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
    Column(modifier = modifier.background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color.LightGray, fontSize = 11.sp)
        Text(text = value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun WalkControlPanel(walkState: WalkSessionState, ghostTargetMiles: Double?, onToggleWalk: () -> Unit) {
    val distMiles = walkState.distanceMeters / 1609.34
    val mins = walkState.elapsedSeconds / 60
    val secs = walkState.elapsedSeconds % 60
    val durationFormatted = String.format(Locale.US, "%02d:%02d", mins, secs)
    val paceStr = if (distMiles >= 0.03) String.format(Locale.US, "%.1f min/mi", (walkState.elapsedSeconds / 60.0) / distMiles) else "-- min/mi"
    val distLabel = if (ghostTargetMiles != null && ghostTargetMiles > 0.0) String.format(Locale.US, "%.2f / %.2f mi", distMiles, ghostTargetMiles) else String.format(Locale.US, "%.2f mi", distMiles)

    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatBlock("Distance / Ghost", distLabel)
            StatBlock("Duration", durationFormatted)
            StatBlock("Pace", paceStr)
        }
        Button(
            onClick = onToggleWalk,
            colors = ButtonDefaults.buttonColors(containerColor = if (walkState.isTracking) Color(0xFFD32F2F) else Color(0xFF2E7D32)),
            modifier = Modifier.fillMaxWidth()
        ) { Text(text = if (walkState.isTracking) "STOP & SAVE WALK" else "START WALK", color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

@Composable
fun StatBlock(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color.Gray, fontSize = 12.sp)
        Text(text = value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}
