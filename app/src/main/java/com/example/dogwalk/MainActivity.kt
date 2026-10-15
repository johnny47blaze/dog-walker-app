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
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
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
                val solarRad = weatherJson.optDouble("shortwave_radiation", 0.0) // W/m^2
                val aqi = aqiJson.optInt("us_aqi", 25)

                // Approximate sunlit pavement temp in Fahrenheit: T_air + (0.08 * solarRad)
                val estPavement = ((airTemp + (0.08 * (solarRad / 3.17))) * 10.0).roundToInt() / 10.0

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

        if (rain > 0.1) unsafeReasons.add("Rain (${rain} in/h)")

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
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}

// ============================================================================
// 2. ROOM SQLITE DATABASE FOR WALK HISTORY
// ============================================================================

@Entity(tableName = "walk_records")
data class WalkRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val airTempF: Double,
    val pavementTempF: Double,
    val safetyStatus: String,
    val routeSerialized: String
) {
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
        val mins = durationSeconds / 60
        val secs = durationSeconds % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    fun formattedDistanceMiles(): String {
        val miles = distanceMeters / 1609.34
        return String.format(Locale.US, "%.2f mi", miles)
    }

    fun formattedPace(): String {
        val miles = distanceMeters / 1609.34
        return if (miles >= 0.03) {
            val pace = (durationSeconds / 60.0) / miles
            String.format(Locale.US, "%.1f min/mi", pace)
        } else "-- min/mi"
    }

    fun toMarkdownRow(): String {
        return "| ${formattedDate()} | ${formattedDistanceMiles()} | ${formattedDuration()} | ${formattedPace()} | ${airTempF}°F / ${pavementTempF}°F | $safetyStatus |"
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

@Database(entities = [WalkRecord::class], version = 1, exportSchema = false)
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
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ============================================================================
// 3. FOREGROUND GPS LOCATION SERVICE
// ============================================================================

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
// 4. MAIN ACTIVITY & UI (IMPERIAL + OSM USER AGENT)
// ============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load OSM configuration and set unique user agent to prevent policy block
        val prefs = getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        Configuration.getInstance().load(applicationContext, prefs)
        Configuration.getInstance().userAgentValue = packageName

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

    var activeTab by remember { mutableIntStateOf(0) }
    var previewWalk by remember { mutableStateOf<WalkRecord?>(null) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), RoundedCornerShape(10.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { activeTab = 0 },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeTab == 0) Color(0xFF2E7D32) else Color.Transparent
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("LIVE TRACKER", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { activeTab = 1 },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeTab == 1) Color(0xFF2E7D32) else Color.Transparent
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("HISTORY (${savedWalks.size})", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        if (activeTab == 0) {
            WeatherBanner(
                report = weatherReport,
                isLoading = isLoadingWeather,
                error = weatherError,
                onRefresh = { refreshLocationAndWeather() }
            )

            previewWalk?.let { pastWalk ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0277BD), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Viewing route: ${pastWalk.formattedDate()} (${pastWalk.formattedDistanceMiles()})",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = { previewWalk = null }) {
                        Text("Clear", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            val displayedRoute = previewWalk?.toGeoPoints() ?: walkState.routePoints
            val isHistoricalRoute = previewWalk != null

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                OsmMapContainer(
                    currentLocation = walkState.currentLocation,
                    routePoints = displayedRoute,
                    isHistoricalRoute = isHistoricalRoute
                )
            }

            WalkControlPanel(
                walkState = walkState,
                onToggleWalk = {
                    if (walkState.isTracking) {
                        val snapshotDist = walkState.distanceMeters
                        val snapshotSecs = walkState.elapsedSeconds
                        val snapshotPoints = walkState.routePoints

                        scope.launch {
                            val record = WalkRecord(
                                timestampMillis = System.currentTimeMillis(),
                                distanceMeters = snapshotDist,
                                durationSeconds = snapshotSecs,
                                airTempF = weatherReport?.airTempF ?: 0.0,
                                pavementTempF = weatherReport?.pavementTempF ?: 0.0,
                                safetyStatus = weatherReport?.status?.name ?: "N/A",
                                routeSerialized = WalkRecord.serializeRoute(snapshotPoints)
                            )
                            walkDao.insertWalk(record)
                            Toast.makeText(context, "Walk saved to history", Toast.LENGTH_SHORT).show()
                        }

                        val stopIntent = Intent(context, WalkTrackerService::class.java).apply {
                            action = WalkTrackerService.ACTION_STOP
                        }
                        context.startService(stopIntent)
                    } else {
                        previewWalk = null
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
                onViewRoute = { walk ->
                    previewWalk = walk
                    activeTab = 0
                },
                onDeleteWalk = { walk ->
                    scope.launch { walkDao.deleteWalk(walk) }
                }
            )
        }
    }
}

@Composable
fun WalkHistoryList(
    walks: List<WalkRecord>,
    onViewRoute: (WalkRecord) -> Unit,
    onDeleteWalk: (WalkRecord) -> Unit
) {
    val context = LocalContext.current
    val totalMiles = walks.sumOf { it.distanceMeters } / 1609.34

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    text = "Total Logged: ${String.format(Locale.US, "%.2f mi", totalMiles)}",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${walks.size} recorded walks in SQLite",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            OutlinedButton(
                onClick = {
                    val header = "| Date | Distance | Duration | Pace | Air / Pavement | Status |\n|---|---|---|---|---|---|\n"
                    val rows = walks.joinToString("\n") { it.toMarkdownRow() }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Walk History Table", header + rows))
                    Toast.makeText(context, "Copied full Markdown table", Toast.LENGTH_SHORT).show()
                },
                enabled = walks.isNotEmpty()
            ) {
                Text("Copy All MD")
            }
        }

        if (walks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No walks recorded yet. Start and stop a walk to save it.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(walks, key = { it.id }) { walk ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
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
                                Text(
                                    text = walk.formattedDate(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = walk.safetyStatus,
                                    color = when (walk.safetyStatus) {
                                        "GO" -> Color(0xFF66BB6A)
                                        "CAUTION" -> Color(0xFFFFA726)
                                        "UNSAFE" -> Color(0xFFEF5350)
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

                            Text(
                                text = "Air: ${walk.airTempF}°F  |  Est. Pavement: ${walk.pavementTempF}°F",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onViewRoute(walk) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("View Route", fontSize = 12.sp)
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
fun WeatherBanner(
    report: DogWeatherReport?,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit
) {
    val bgColor = when (report?.status) {
        SafetyLevel.GO -> Color(0xFF1B5E20)
        SafetyLevel.CAUTION -> Color(0xFFE65100)
        SafetyLevel.UNSAFE -> Color(0xFFB71C1C)
        null -> Color(0xFF263238)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    isLoading -> "CHECKING WEATHER..."
                    error != null -> "WEATHER OFFLINE"
                    report != null -> "WALK STATUS: ${report.status.name}"
                    else -> "WAITING FOR GPS..."
                },
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onRefresh) {
                Text("Refresh", color = Color.White)
            }
        }

        if (error != null) {
            Text(text = error, color = Color.White, fontSize = 13.sp)
        } else if (report != null) {
            Text(text = report.reason, color = Color.White, fontSize = 14.sp)

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

@Composable
fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, color = Color.LightGray, fontSize = 11.sp)
        Text(text = value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun OsmMapContainer(
    currentLocation: GeoPoint?,
    routePoints: List<GeoPoint>,
    isHistoricalRoute: Boolean
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(17.5)
                controller.setCenter(currentLocation ?: GeoPoint(42.375, -122.916))
            }
        },
        update = { mapView ->
            mapView.overlays.clear()

            if (routePoints.size > 1) {
                val line = Polyline().apply {
                    setPoints(routePoints)
                    outlinePaint.color = if (isHistoricalRoute) {
                        android.graphics.Color.parseColor("#29B6F6")
                    } else {
                        android.graphics.Color.parseColor("#00E676")
                    }
                    outlinePaint.strokeWidth = 14f
                }
                mapView.overlays.add(line)
            }

            if (isHistoricalRoute && routePoints.isNotEmpty()) {
                mapView.controller.animateTo(routePoints.first())
            } else {
                currentLocation?.let { loc ->
                    val marker = Marker(mapView).apply {
                        position = loc
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = "Current Location"
                    }
                    mapView.overlays.add(marker)
                    mapView.controller.animateTo(loc)
                }
            }

            mapView.invalidate()
        }
    )
}

@Composable
fun WalkControlPanel(
    walkState: WalkSessionState,
    onToggleWalk: () -> Unit
) {
    val distMiles = walkState.distanceMeters / 1609.34
    val mins = walkState.elapsedSeconds / 60
    val secs = walkState.elapsedSeconds % 60
    val durationFormatted = String.format(Locale.US, "%02d:%02d", mins, secs)

    val paceStr = if (distMiles >= 0.03) {
        val paceMinPerMile = (walkState.elapsedSeconds / 60.0) / distMiles
        String.format(Locale.US, "%.1f min/mi", paceMinPerMile)
    } else "-- min/mi"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatBlock("Distance", String.format(Locale.US, "%.2f mi", distMiles))
            StatBlock("Duration", durationFormatted)
            StatBlock("Pace", paceStr)
        }

        Button(
            onClick = onToggleWalk,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (walkState.isTracking) Color(0xFFD32F2F) else Color(0xFF2E7D32)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (walkState.isTracking) "STOP & SAVE WALK" else "START WALK",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun StatBlock(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color.Gray, fontSize = 12.sp)
        Text(text = value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
