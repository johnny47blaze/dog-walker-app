package com.example.dogwalk

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Outline
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
import androidx.compose.material3.Checkbox
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
        airTemp: Double, feelsLike: Double, pavement: Double,
        rain: Double, gusts: Double, aqi: Int
    ): Pair<SafetyLevel, String> {
        val unsafeReasons = mutableListOf<String>()
        val cautionReasons = mutableListOf<String>()

        if (pavement >= 118.0) unsafeReasons.add("Pavement burn risk (${pavement}°F)")
        else if (pavement >= 104.0) cautionReasons.add("Warm pavement (${pavement}°F)")

        if (feelsLike < 32.0) unsafeReasons.add("Freezing wind chill (${feelsLike}°F)")
        else if (airTemp >= 80.0) unsafeReasons.add("Heat risk (${airTemp}°F)")
        else if (feelsLike < 50.0) cautionReasons.add("Chilly (${feelsLike}°F - coat recommended)")

        if (rain > 0.1) unsafeReasons.add("Heavy rain (${rain} in/h)")
        else if (rain > 0.0) cautionReasons.add("Light rain (${rain} in/h)")

        if (gusts >= 25.0) unsafeReasons.add("High wind gusts (${gusts} mph)")
        if (aqi > 100) unsafeReasons.add("Poor air quality (AQI $aqi)")

        return when {
            unsafeReasons.isNotEmpty() -> SafetyLevel.UNSAFE to unsafeReasons.joinToString(" • ")
            cautionReasons.isNotEmpty() -> SafetyLevel.CAUTION to cautionReasons.joinToString(" • ")
            else -> SafetyLevel.GO to "Optimal 1-mile walking conditions for Bean"
        }
    }

    private fun getUrlText(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "PersonalDogWalkTracker/3.3 (Android)")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}

// ============================================================================
// 2. OSRM FOOT ROUTING & YORKIE 1-MILE LOOP GENERATOR
// ============================================================================

data class PlannedLeg(val anchor: GeoPoint, val snappedPoints: List<GeoPoint>)

object RouteSnapper {
    suspend fun snapFootSegment(start: GeoPoint, end: GeoPoint): List<GeoPoint> =
        withContext(Dispatchers.IO) {
            val coords = "${start.longitude},${start.latitude};${end.longitude},${end.latitude}"
            val endpoint = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/$coords?overview=full&geometries=geojson"
            try {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "PersonalDogWalkTracker/3.3 (Android)")
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val routes = json.optJSONArray("routes")
                    if (routes != null && routes.length() > 0) {
                        val coordArray = routes.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
                        val points = ArrayList<GeoPoint>(coordArray.length())
                        for (i in 0 until coordArray.length()) {
                            val pair = coordArray.getJSONArray(i)
                            points.add(GeoPoint(pair.getDouble(1), pair.getDouble(0)))
                        }
                        if (points.isNotEmpty()) return@withContext points
                    }
                }
            } catch (_: Exception) {}
            listOf(start, end)
        }

    // Generates a 4-waypoint square loop around current location targeting ~1.0 mile for a Yorkie
    suspend fun generateYorkieLoop(center: GeoPoint): List<PlannedLeg> {
        // ~0.25 miles offset in lat/lon (~400 meters)
        val dLat = 0.0036
        val dLon = 0.0048

        val p1 = GeoPoint(center.latitude + dLat, center.longitude - dLon)
        val p2 = GeoPoint(center.latitude + dLat, center.longitude + dLon)
        val p3 = GeoPoint(center.latitude - dLat, center.longitude + dLon)
        val p4 = center

        val leg1 = snapFootSegment(center, p1)
        val leg2 = snapFootSegment(p1, p2)
        val leg3 = snapFootSegment(p2, p3)
        val leg4 = snapFootSegment(p3, p4)

        return listOf(
            PlannedLeg(center, leg1),
            PlannedLeg(p1, leg2.drop(1)),
            PlannedLeg(p2, leg3.drop(1)),
            PlannedLeg(p3, leg4.drop(1))
        )
    }
}

// ============================================================================
// 3. ROOM SQLITE DATABASE (VERSION 5 - NOTES, POTTY & PHOTO URI)
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
    val routeSerialized: String,
    val notes: String = "",
    val didPee: Boolean = false,
    val didPoop: Boolean = false,
    val photoUri: String = ""
) {
    val isPlannedRoute: Boolean get() = safetyStatus == "PLANNED"

    fun toGeoPoints(): List<GeoPoint> {
        if (routeSerialized.isBlank()) return emptyList()
        return routeSerialized.split(";").mapNotNull { pair ->
            val parts = pair.split(",")
            if (parts.size == 2) parts[0].toDoubleOrNull()?.let { lat -> parts[1].toDoubleOrNull()?.let { lon -> GeoPoint(lat, lon) } } else null
        }
    }

    fun formattedDate(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestampMillis))
    fun formattedDuration(): String {
        if (isPlannedRoute) return "Planned Route"
        return String.format(Locale.US, "%02d:%02d", durationSeconds / 60, durationSeconds % 60)
    }
    fun formattedDistanceMiles(): String = String.format(Locale.US, "%.2f mi", distanceMeters / 1609.34)
    fun formattedPace(): String {
        if (isPlannedRoute) return "${toGeoPoints().size} pts"
        val miles = distanceMeters / 1609.34
        return if (miles >= 0.03) String.format(Locale.US, "%.1f min/mi", (durationSeconds / 60.0) / miles) else "-- min/mi"
    }

    fun toMarkdownRow(): String {
        val nameCol = if (routeName.isNotBlank()) "($routeName)" else ""
        val potty = buildString {
            if (didPee) append(" 💧")
            if (didPoop) append(" 💩")
        }
        return "| ${formattedDate()} $nameCol | ${formattedDistanceMiles()} | ${formattedDuration()} | ${formattedPace()} |${potty} | $safetyStatus |"
    }

    companion object {
        fun serializeRoute(points: List<GeoPoint>): String = points.joinToString(";") { "${it.latitude},${it.longitude}" }
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

@Database(entities = [WalkRecord::class], version = 5, exportSchema = false)
abstract class WalkDatabase : RoomDatabase() {
    abstract fun walkDao(): WalkDao
    companion object {
        @Volatile private var INSTANCE: WalkDatabase? = null
        fun getInstance(context: Context): WalkDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, WalkDatabase::class.java, "dog_walk_history.db")
                    .fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}

// ============================================================================
// 4. GPS TRACKER SERVICE & REMINDER SCHEDULER
// ============================================================================

fun calculatePolylineMeters(points: List<GeoPoint>): Double {
    if (points.size < 2) return 0.0
    var total = 0.0
    val results = FloatArray(1)
    for (i in 0 until points.size - 1) {
        Location.distanceBetween(points[i].latitude, points[i].longitude, points[i+1].latitude, points[i+1].longitude, results)
        total += results[0]
    }
    return total
}

data class WalkSessionState(val isTracking: Boolean = false, val routePoints: List<GeoPoint> = emptyList(), val distanceMeters: Double = 0.0, val elapsedSeconds: Long = 0L, val currentLocation: GeoPoint? = null)

object WalkStateHolder {
    private val _state = MutableStateFlow(WalkSessionState())
    val state = _state.asStateFlow()

    fun updateLocation(point: GeoPoint, trackRoute: Boolean) {
        val current = _state.value
        if (!trackRoute) { _state.value = current.copy(currentLocation = point); return }
        val pts = current.routePoints
        val addedDist = if (pts.isNotEmpty()) {
            val results = FloatArray(1)
            Location.distanceBetween(pts.last().latitude, pts.last().longitude, point.latitude, point.longitude, results)
            results[0].toDouble()
        } else 0.0

        if (pts.isEmpty() || addedDist >= 1.5) {
            _state.value = current.copy(currentLocation = point, routePoints = pts + point, distanceMeters = current.distanceMeters + addedDist)
        }
    }
    fun setTracking(active: Boolean) {
        _state.value = if (active) WalkSessionState(isTracking = true, routePoints = listOfNotNull(_state.value.currentLocation), currentLocation = _state.value.currentLocation)
        else _state.value.copy(isTracking = false)
    }
    fun tickSecond() {
        if (_state.value.isTracking) _state.value = _state.value.copy(elapsedSeconds = _state.value.elapsedSeconds + 1)
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
                result.locations.forEach { WalkStateHolder.updateLocation(GeoPoint(it.latitude, it.longitude), WalkStateHolder.state.value.isTracking) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_START" -> {
                val channelId = "dog_walk_channel"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = NotificationChannel(channelId, "Walk GPS", NotificationManager.IMPORTANCE_LOW)
                    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
                }
                val notification = NotificationCompat.Builder(this, channelId)
                    .setContentTitle("Bean's Walk Active").setContentText("Recording GPS route...").setSmallIcon(android.R.drawable.ic_menu_mylocation).build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                else startForeground(1, notification)

                WalkStateHolder.setTracking(true)
                val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L).setMinUpdateDistanceMeters(2f).build()
                fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
                timerJob?.cancel()
                timerJob = serviceScope.launch { while (isActive) { delay(1000L); WalkStateHolder.tickSecond() } }
            }
            "ACTION_STOP" -> {
                WalkStateHolder.setTracking(false)
                timerJob?.cancel()
                fusedClient.removeLocationUpdates(locationCallback)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

// ============================================================================
// 5. MAIN UI & POST-WALK DIALOG
// ============================================================================

val OSM_KEYLESS_SOURCE = XYTileSource("OSM_Keyless_v4", 0, 19, 256, ".png", arrayOf("https://tile.openstreetmap.org/"))
val ESRI_STREET_SOURCE = object : OnlineTileSourceBase("EsriWorldStreet_v4", 0, 19, 256, "", arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/")) {
    override fun getTileURLString(pMapTileIndex: Long): String = baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
}

enum class MapStyle(val label: String, val source: OnlineTileSourceBase, val invertForDark: Boolean) {
    DARK("Dark", OSM_KEYLESS_SOURCE, true), LIGHT("Light", OSM_KEYLESS_SOURCE, false), ESRI("Esri", ESRI_STREET_SOURCE, false);
    fun next(): MapStyle = entries[(ordinal + 1) % entries.size]
}

enum class GhostMode(val label: String) {
    OFF("Ghost: OFF"), PINNED("Ghost: PINNED"), ALL("Ghost: ALL");
    fun next(): GhostMode = entries[(ordinal + 1) % entries.size]
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val osmConfig = Configuration.getInstance()
        osmConfig.load(applicationContext, getSharedPreferences("osmdroid_clean_prefs_v4", Context.MODE_PRIVATE))
        osmConfig.userAgentValue = "PersonalDogWalkTracker/3.3 (Android)"
        osmConfig.osmdroidTileCache = File(cacheDir, "osm_tiles_v4").apply { mkdirs() }

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
    var mapStyle by remember { mutableStateOf(MapStyle.valueOf(prefs.getString("saved_map_style", MapStyle.DARK.name) ?: MapStyle.DARK.name)) }

    var ghostMode by remember { mutableStateOf(GhostMode.PINNED) }
    var pinnedGhostWalk by remember { mutableStateOf<WalkRecord?>(null) }

    var isPlanningRoute by remember { mutableStateOf(false) }
    var snapToStreets by remember { mutableStateOf(true) }
    var isSnappingSegment by remember { mutableStateOf(false) }
    var plannedLegs by remember { mutableStateOf<List<PlannedLeg>>(emptyList()) }

    // Dialog States
    var showNameDialog by remember { mutableStateOf(false) }
    var routeNameInput by remember { mutableStateOf("") }
    var showPostWalkDialog by remember { mutableStateOf(false) }
    var pendingWalkData by remember { mutableStateOf<Triple<Double, Long, List<GeoPoint>>?>(null) }
    var postWalkNotes by remember { mutableStateOf("") }
    var didPee by remember { mutableStateOf(false) }
    var didPoop by remember { mutableStateOf(false) }
    var attachedPhotoUri by remember { mutableStateOf("") }

    val photoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { attachedPhotoUri = it.toString() }
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
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            val targetLat = loc?.latitude ?: 42.375
            val targetLon = loc?.longitude ?: -122.916
            WalkStateHolder.updateLocation(GeoPoint(targetLat, targetLon), false)
            scope.launch {
                WeatherRepository.fetchDogWeather(targetLat, targetLon).onSuccess { weatherReport = it }.onFailure { weatherError = it.localizedMessage }
                isLoadingWeather = false
            }
        }
    }

    LaunchedEffect(Unit) {
        ActivityResultContracts.RequestMultiplePermissions().let {
            // Permissions check handled in UI
        }
        refreshLocationAndWeather()
    }

    val activeGhostRecord = pinnedGhostWalk ?: savedWalks.firstOrNull()
    val ghostTrails = remember(ghostMode, activeGhostRecord, savedWalks) {
        when (ghostMode) {
            GhostMode.OFF -> emptyList()
            GhostMode.PINNED -> listOfNotNull(activeGhostRecord?.toGeoPoints()?.takeIf { it.size > 1 })
            GhostMode.ALL -> savedWalks.map { it.toGeoPoints() }.filter { it.size > 1 }
        }
    }

    // POST-WALK SUMMARY DIALOG
    if (showPostWalkDialog && pendingWalkData != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Bean's Walk Summary 🐾") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Distance: ${String.format(Locale.US, "%.2f mi", pendingWalkData!!.first / 1609.34)}")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = didPee, onCheckedChange = { didPee = it })
                        Text("Pee 💧")
                        Checkbox(checked = didPoop, onCheckedChange = { didPoop = it })
                        Text("Poop 💩")
                    }
                    OutlinedTextField(
                        value = postWalkNotes,
                        onValueChange = { postWalkNotes = it },
                        label = { Text("How did Bean do? (Behavior, energy)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(onClick = { photoPickerLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (attachedPhotoUri.isBlank()) "Attach Photo 📷" else "Photo Attached! ✅")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val (dist, secs, pts) = pendingWalkData!!
                    scope.launch {
                        walkDao.insertWalk(WalkRecord(
                            timestampMillis = System.currentTimeMillis(),
                            distanceMeters = dist, durationSeconds = secs,
                            airTempF = weatherReport?.airTempF ?: 0.0, pavementTempF = weatherReport?.pavementTempF ?: 0.0,
                            safetyStatus = weatherReport?.status?.name ?: "N/A",
                            routeSerialized = WalkRecord.serializeRoute(pts),
                            notes = postWalkNotes, didPee = didPee, didPoop = didPoop, photoUri = attachedPhotoUri
                        ))
                        Toast.makeText(context, "Walk saved with notes!", Toast.LENGTH_SHORT).show()
                        showPostWalkDialog = false
                        pendingWalkData = null
                        postWalkNotes = ""
                        didPee = false; didPoop = false; attachedPhotoUri = ""
                    }
                }) { Text("Save to History") }
            }
        )
    }

    // SAVE PLANNED ROUTE DIALOG
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Save Planned Route") },
            text = { OutlinedTextField(value = routeNameInput, onValueChange = { routeNameInput = it }, label = { Text("Route Name (e.g. Park Loop)") }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    showNameDialog = false
                    val plannedMeters = calculatePolylineMeters(plannedFullGeometry)
                    scope.launch {
                        val newRoute = WalkRecord(
                            timestampMillis = System.currentTimeMillis(), routeName = routeNameInput.trim(),
                            distanceMeters = plannedMeters, durationSeconds = 0L, airTempF = 0.0, pavementTempF = 0.0,
                            safetyStatus = "PLANNED", routeSerialized = WalkRecord.serializeRoute(plannedFullGeometry)
                        )
                        walkDao.insertWalk(newRoute)
                        pinnedGhostWalk = newRoute
                        ghostMode = GhostMode.PINNED
                        plannedLegs = emptyList()
                        isPlanningRoute = false
                        routeNameInput = ""
                        Toast.makeText(context, "Route saved & pinned!", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showNameDialog = false }) { Text("Cancel") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E), RoundedCornerShape(10.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { activeTab = 0 }, colors = ButtonDefaults.buttonColors(containerColor = if (activeTab == 0) Color(0xFF2E7D32) else Color.Transparent), modifier = Modifier.weight(1f)) { Text("LIVE MAP", color = Color.White, fontWeight = FontWeight.Bold) }
            Button(onClick = { activeTab = 1 }, colors = ButtonDefaults.buttonColors(containerColor = if (activeTab == 1) Color(0xFF2E7D32) else Color.Transparent), modifier = Modifier.weight(1f)) { Text("HISTORY (${savedWalks.size})", color = Color.White, fontWeight = FontWeight.Bold) }
        }

        if (activeTab == 0) {
            WeatherBanner(report = weatherReport, isLoading = isLoadingWeather, error = weatherError, onRefresh = { refreshLocationAndWeather() })

            // ROUTE BUILDER & YORKIE AUTO-LOOP BAR
            if (isPlanningRoute) {
                val plannedMiles = calculatePolylineMeters(plannedFullGeometry) / 1609.34
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF3E2723), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (isSnappingSegment) "Snapping..." else String.format(Locale.US, "Plan: %.2f mi", plannedMiles), color = Color(0xFFFFD600), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(if (snapToStreets) "OSRM Snapping ON" else "Straight Line", color = Color.LightGray, fontSize = 10.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        OutlinedButton(onClick = {
                            walkState.currentLocation?.let { loc ->
                                scope.launch {
                                    isSnappingSegment = true
                                    plannedLegs = RouteSnapper.generateYorkieLoop(loc)
                                    isSnappingSegment = false
                                    Toast.makeText(context, "Yorkie 1-mi loop generated!", Toast.LENGTH_SHORT).show()
                                }
                            } ?: Toast.Android("Waiting for GPS...", Toast.LENGTH_SHORT).show()
                        }, contentPadding = PaddingValues(4.dp)) { Text("Yorkie Loop", fontSize = 10.sp, color = Color(0xFFFFD600)) }

                        OutlinedButton(onClick = { snapToStreets = !snapToStreets }, contentPadding = PaddingValues(4.dp)) { Text(if (snapToStreets) "Snap" else "Direct", fontSize = 10.sp, color = Color.White) }
                        OutlinedButton(onClick = { if (plannedLegs.isNotEmpty() && !isSnappingSegment) plannedLegs = plannedLegs.dropLast(1) }, contentPadding = PaddingValues(4.dp)) { Text("Undo", fontSize = 10.sp, color = Color.White) }
                        Button(onClick = { if (plannedFullGeometry.size >= 2 && !isSnappingSegment) showNameDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8F00)), contentPadding = PaddingValues(6.dp)) { Text("Save", fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).clipToBounds()) {
                OsmMapContainer(
                    currentLocation = walkState.currentLocation, liveRoutePoints = walkState.routePoints,
                    ghostTrails = ghostTrails, ghostPacerPoint = null,
                    plannedGeometry = plannedFullGeometry, plannedAnchors = plannedAnchorWaypoints,
                    isPlanningRoute = isPlanningRoute, mapStyle = mapStyle,
                    onMapTap = { tappedGeoPoint ->
                        if (isPlanningRoute && !isSnappingSegment) {
                            if (plannedLegs.isEmpty()) plannedLegs = listOf(PlannedLeg(tappedGeoPoint, listOf(tappedGeoPoint)))
                            else {
                                val prev = plannedLegs.last().anchor
                                if (!snapToStreets) plannedLegs = plannedLegs + PlannedLeg(tappedGeoPoint, listOf(tappedGeoPoint))
                                else {
                                    isSnappingSegment = true
                                    scope.launch {
                                        val seg = RouteSnapper.snapFootSegment(prev, tappedGeoPoint)
                                        plannedLegs = plannedLegs + PlannedLeg(tappedGeoPoint, if (seg.size > 1) seg.drop(1) else seg)
                                        isSnappingSegment = false
                                    }
                                }
                            }
                        }
                    }
                )

                // MAP OVERLAYS (Ghost, Plan, Style)
                Row(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(onClick = { ghostMode = ghostMode.next() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC006064)), contentPadding = PaddingValues(6.dp)) { Text(ghostMode.label, fontSize = 10.sp, color = Color.White) }
                    Button(onClick = { isPlanningRoute = !isPlanningRoute }, colors = ButtonDefaults.buttonColors(containerColor = if (isPlanningRoute) Color(0xFFFF8F00) else Color(0xCC1E1E1E)), contentPadding = PaddingValues(6.dp)) { Text(if (isPlanningRoute) "Planning ON" else "Plan Route", fontSize = 10.sp, color = if (isPlanningRoute) Color.Black else Color.White, fontWeight = FontWeight.Bold) }
                    Button(onClick = {
                        mapStyle = mapStyle.next()
                        prefs.edit().putString("saved_map_style", mapStyle.name).apply()
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1E1E1E)), contentPadding = PaddingValues(6.dp)) { Text("Map: ${mapStyle.label}", fontSize = 10.sp, color = Color.White) }
                }
            }

            WalkControlPanel(
                walkState = walkState,
                ghostTargetMiles = if (ghostMode != GhostMode.OFF) activeGhostRecord?.let { it.distanceMeters / 1609.34 } else null,
                onSetReminder = {
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    val intent = Intent(context, MainActivity::class.java)
                    val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                    val triggerTime = System.currentTimeMillis() + (30 * 60 * 1000) // 30 minutes from now
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                    } else {
                        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                    }
                    Toast.makeText(context, "Walk Reminder set for 30 minutes from now!", Toast.LENGTH_SHORT).show()
                },
                onToggleWalk = {
                    if (walkState.isTracking) {
                        pendingWalkData = Triple(walkState.distanceMeters, walkState.elapsedSeconds, walkState.routePoints)
                        showPostWalkDialog = true
                        context.startService(Intent(context, WalkTrackerService::class.java).apply { action = "ACTION_STOP" })
                    } else {
                        val intent = Intent(context, WalkTrackerService::class.java).apply { action = "ACTION_START" }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                    }
                }
            )
        } else {
            WalkHistoryList(
                walks = savedWalks, pinnedGhostId = activeGhostRecord?.id,
                onSetGhost = { walk -> pinnedGhostWalk = walk; ghostMode = GhostMode.PINNED; activeTab = 0 },
                onDeleteWalk = { walk -> scope.launch { walkDao.deleteWalk(walk) } }
            )
        }
    }
}

@Composable
fun OsmMapContainer(
    currentLocation: GeoPoint?, liveRoutePoints: List<GeoPoint>, ghostTrails: List<List<GeoPoint>>, ghostPacerPoint: GeoPoint?,
    plannedGeometry: List<GeoPoint>, plannedAnchors: List<GeoPoint>, isPlanningRoute: Boolean, mapStyle: MapStyle, onMapTap: (GeoPoint) -> Unit
) {
    val currentOnMapTap by rememberUpdatedState(onMapTap)
    val currentIsPlanning by rememberUpdatedState(isPlanningRoute)

    AndroidView(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        factory = { ctx ->
            FrameLayout(ctx).apply {
                clipChildren = true; clipToPadding = true; clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() { override fun getOutline(view: View, outline: Outline) { outline.setRect(0, 0, view.width, view.height) } }
                val mapView = MapView(ctx).apply {
                    setTileSource(mapStyle.source); setMultiTouchControls(true)
                    overlayManager.tilesOverlay.loadingBackgroundColor = android.graphics.Color.parseColor("#121212")
                    controller.setZoom(17.0); controller.setCenter(currentLocation ?: GeoPoint(42.375, -122.916))
                }
                addView(mapView, FrameLayout.LayoutParams(-1, -1))
            }
        },
        update = { container ->
            val mapView = container.getChildAt(0) as MapView
            if (mapView.tileProvider.tileSource.name() != mapStyle.source.name()) mapView.setTileSource(mapStyle.source)
            mapView.overlayManager.tilesOverlay.setColorFilter(if (mapStyle.invertForDark) TilesOverlay.INVERT_COLORS else null)
            mapView.overlays.clear()

            mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean { if (currentIsPlanning && p != null) { currentOnMapTap(p); return true }; return false }
                override fun longPressHelper(p: GeoPoint?): Boolean = false
            }))

            ghostTrails.forEach { pts -> if (pts.size > 1) mapView.overlays.add(Polyline().apply { setPoints(pts); outlinePaint.color = android.graphics.Color.parseColor("#9900E5FF"); outlinePaint.strokeWidth = 12f }) }
            if (plannedGeometry.size > 1) mapView.overlays.add(Polyline().apply { setPoints(plannedGeometry); outlinePaint.color = android.graphics.Color.parseColor("#FFD600"); outlinePaint.strokeWidth = 12f })
            plannedAnchors.forEach { pt -> mapView.overlays.add(Marker(mapView).apply { position = pt; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Stop" }) }
            if (liveRoutePoints.size > 1) mapView.overlays.add(Polyline().apply { setPoints(liveRoutePoints); outlinePaint.color = android.graphics.Color.parseColor("#00E676"); outlinePaint.strokeWidth = 14f })

            currentLocation?.let { loc ->
                mapView.overlays.add(Marker(mapView).apply { position = loc; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Bean & You" })
                if (!currentIsPlanning) mapView.controller.animateTo(loc)
            }
            mapView.invalidate()
        }
    )
}

@Composable
fun WalkHistoryList(walks: List<WalkRecord>, pinnedGhostId: Long?, onSetGhost: (WalkRecord) -> Unit, onDeleteWalk: (WalkRecord) -> Unit) {
    val context = LocalContext.current
    val completed = walks.filter { !it.isPlannedRoute }
    val totalMiles = completed.sumOf { it.distanceMeters } / 1609.34

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp)).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Total Walked: ${String.format(Locale.US, "%.2f mi", totalMiles)}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${completed.size} walks logged", color = Color.Gray, fontSize = 12.sp)
            }
            OutlinedButton(onClick = {
                val header = "| Date | Distance | Duration | Pace | Potty | Status |\n|---|---|---|---|---|---|\n"
                val rows = walks.joinToString("\n") { it.toMarkdownRow() }
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Walk History", header + rows))
                Toast.makeText(context, "Copied Markdown table", Toast.LENGTH_SHORT).show()
            }) { Text("Copy MD") }
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(walks, key = { it.id }) { walk ->
                val isPinned = walk.id == pinnedGhostId
                Card(colors = CardDefaults.cardColors(containerColor = if (isPinned) Color(0xFF1A2E35) else Color(0xFF1E1E1E)), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (walk.routeName.isNotBlank()) walk.routeName else walk.formattedDate(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            val pottyStr = buildString { if (walk.didPee) append("💧 "); if (walk.didPoop) append("💩") }
                            Text(pottyStr, fontSize = 14.sp)
                        }
                        Text("${walk.formattedDistanceMiles()} • ${walk.formattedDuration()} • ${walk.formattedPace()}", color = Color(0xFF00E676), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        if (walk.notes.isNotBlank()) Text("Notes: ${walk.notes}", color = Color.LightGray, fontSize = 12.sp)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onSetGhost(walk) }, colors = ButtonDefaults.buttonColors(containerColor = if (isPinned) Color(0xFF00838F) else Color(0xFF37474F)), modifier = Modifier.weight(1f)) { Text(if (isPinned) "Active Ghost" else "Set Ghost", fontSize = 11.sp) }
                            TextButton(onClick = { onDeleteWalk(walk) }) { Text("Delete", color = Color(0xFFEF5350), fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WeatherBanner(report: DogWeatherReport?, isLoading: Boolean, error: String?, onRefresh: () -> Unit) {
    var isExpanded by remember { mutableStateOf(false) }
    val bgColor = when (report?.status) {
        SafetyLevel.GO -> Color(0xFF1B5E20)
        SafetyLevel.CAUTION -> Color(0xFFE65100)
        SafetyLevel.UNSAFE -> Color(0xFFB71C1C)
        null -> Color(0xFF263238)
    }

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bgColor).clickable { isExpanded = !isExpanded }.padding(10.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!isExpanded && report != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${report.status.name} • ${report.airTempF}°F • Pavement: ${report.pavementTempF}°F", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("▼", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(when { isLoading -> "CHECKING WEATHER..." ; error != null -> "OFFLINE" ; report != null -> "STATUS: ${report.status.name}" ; else -> "GPS..." }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (report != null) Text("▲", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
            }
            if (report != null) {
                Text(report.reason, color = Color.White, fontSize = 12.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MetricPill("Air", "${report.airTempF}°F", Modifier.weight(1f))
                    MetricPill("Pavement", "${report.pavementTempF}°F", Modifier.weight(1f))
                    MetricPill("Rain", "${report.rainInch}in", Modifier.weight(1f))
                    MetricPill("AQI", "${report.usAqi}", Modifier.weight(0.7f))
                }
            }
        }
    }
}

@Composable
fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(6.dp)).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.LightGray, fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun WalkControlPanel(walkState: WalkSessionState, ghostTargetMiles: Double?, onSetReminder: () -> Unit, onToggleWalk: () -> Unit) {
    val distMiles = walkState.distanceMeters / 1609.34
    val durationFormatted = String.format(Locale.US, "%02d:%02d", walkState.elapsedSeconds / 60, walkState.elapsedSeconds % 60)
    val paceStr = if (distMiles >= 0.03) String.format(Locale.US, "%.1f min/mi", (walkState.elapsedSeconds / 60.0) / distMiles) else "-- min/mi"
    val distLabel = if (ghostTargetMiles != null && ghostTargetMiles > 0.0) String.format(Locale.US, "%.2f / %.2f mi", distMiles, ghostTargetMiles) else String.format(Locale.US, "%.2f mi", distMiles)

    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatBlock("Distance", distLabel)
            StatBlock("Duration", durationFormatted)
            StatBlock("Pace", paceStr)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSetReminder, modifier = Modifier.weight(1f)) { Text("Set Reminder ⏰", fontSize = 12.sp) }
            Button(
                onClick = onToggleWalk,
                colors = ButtonDefaults.buttonColors(containerColor = if (walkState.isTracking) Color(0xFFD32F2F) else Color(0xFF2E7D32)),
                modifier = Modifier.weight(1.5f)
            ) { Text(if (walkState.isTracking) "STOP & SAVE" else "START WALK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
    }
}

@Composable
fun StatBlock(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.Gray, fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
