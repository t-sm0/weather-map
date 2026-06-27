package dev.weather.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

data class Fix(val latitude: Double, val longitude: Double, val label: String)

private data class CameraState(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val width: Int,
    val height: Int,
    val bounds: GeoBounds? = null,
)

private data class GeoBounds(
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double,
)

private data class WeatherPoint(
    val latitude: Double,
    val longitude: Double,
    val temperature: Double,
    val apparent: Double,
    val code: Int,
    val wind: Double,
    val time: String,
)

private data class ProjectedWeatherPoint(
    val point: WeatherPoint,
    val x: Double,
    val y: Double,
)

private data class TempLabel(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val value: Int,
    val name: String? = null,
)

private data class ProjectedTempLabel(val label: TempLabel, val x: Double, val y: Double)

private data class TempCityAnchor(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val rank: Int,
    val isCapital: Boolean = false,
)

private data class TempLabelPolicy(
    val zoomBand: Int,
    val maxLabels: Int,
    val minLabels: Int,
    val minDistancePx: Double,
    val panHysteresisPx: Double,
)

private data class TempColorStop(val temp: Double, val red: Int, val green: Int, val blue: Int)

private data class ForecastSnapshot(
    val bounds: GeoBounds,
    val zoomBucket: Int,
    val hourOffset: Int,
    val time: String,
    val current: WeatherPoint,
    val points: List<WeatherPoint>,
    val projectedPoints: List<ProjectedWeatherPoint>,
)

private data class ForecastUiState(
    val snapshot: ForecastSnapshot? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val cacheHit: Boolean = false,
)

private data class ForecastResult(val snapshot: ForecastSnapshot, val cacheHit: Boolean)

private data class ForecastCacheKey(
    val hourOffset: Int,
    val zoomBucket: Int,
    val northBucket: Int,
    val southBucket: Int,
    val westBucket: Int,
    val eastBucket: Int,
)

private data class RadarFrame(val path: String, val time: Long)

private enum class WeatherLayer { Temp, Radar }

class MainActivity : ComponentActivity() {
    private var fix by mutableStateOf(Fix(50.7753, 6.0839, "Aachen fallback"))
    private var permissionDenied by mutableStateOf(false)
    private var recenterNonce by mutableIntStateOf(0)

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allowed = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        permissionDenied = !allowed
        if (allowed) readLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WeatherMapApp(
                fix = fix,
                recenterNonce = recenterNonce,
                permissionDenied = permissionDenied,
                onUseLocation = {
                    recenterNonce += 1
                    requestLocation()
                },
            )
        }
        requestLocation()
    }

    private fun requestLocation() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            permissionDenied = false
            readLocation()
        } else {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun readLocation() {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val last = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

        if (last != null) {
            fix = Fix(last.latitude, last.longitude, "aktueller Standort")
            return
        }

        providers.firstOrNull { manager.isProviderEnabled(it) }?.let { provider ->
            manager.requestSingleUpdate(
                provider,
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        fix = Fix(location.latitude, location.longitude, "aktueller Standort")
                    }
                },
                null,
            )
        }
    }
}

@Composable
private fun WeatherMapApp(
    fix: Fix,
    recenterNonce: Int,
    permissionDenied: Boolean,
    onUseLocation: () -> Unit,
) {
    val repository = remember { WeatherRepository() }
    var liveCamera by remember { mutableStateOf(CameraState(fix.latitude, fix.longitude, 11.0, 1, 1)) }
    var renderCamera by remember { mutableStateOf(CameraState(fix.latitude, fix.longitude, 11.0, 1, 1)) }
    var layer by remember { mutableStateOf(WeatherLayer.Temp) }
    var selectedHour by remember { mutableIntStateOf(0) }
    var forecastState by remember { mutableStateOf(ForecastUiState()) }
    var radarFrames by remember { mutableStateOf<List<RadarFrame>>(emptyList()) }
    var radarIndex by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Lade Wetterdaten...") }
    var loadToken by remember { mutableIntStateOf(0) }
    var projectedTempLabels by remember { mutableStateOf<List<ProjectedTempLabel>>(emptyList()) }
    var stableTempLabels by remember { mutableStateOf<List<TempLabel>>(emptyList()) }
    var stableLabelCamera by remember { mutableStateOf<CameraState?>(null) }
    var stableLabelSnapshot by remember { mutableStateOf<ForecastSnapshot?>(null) }
    val labelPolicy = labelPolicyForZoom(liveCamera.zoom)

    LaunchedEffect(forecastState.snapshot, liveCamera.latitude, liveCamera.longitude, liveCamera.zoom, layer) {
        val snapshot = forecastState.snapshot
        if (layer != WeatherLayer.Temp || snapshot == null || liveCamera.width < 32 || liveCamera.height < 32) {
            stableTempLabels = emptyList()
            stableLabelCamera = null
            stableLabelSnapshot = null
            return@LaunchedEffect
        }
        val lastCamera = stableLabelCamera
        if (stableLabelSnapshot !== snapshot || stableTempLabels.isEmpty() || shouldRefreshTempLabels(lastCamera, liveCamera, labelPolicy)) {
            stableTempLabels = visibleTemperatureLabels(snapshot, liveCamera, stableTempLabels)
            stableLabelCamera = liveCamera
            stableLabelSnapshot = snapshot
        }
    }

    LaunchedEffect(renderCamera.latitude, renderCamera.longitude, renderCamera.zoom, selectedHour) {
        if (renderCamera.width < 32 || renderCamera.height < 32) return@LaunchedEffect
        delay(900)
        val existing = forecastState.snapshot
        if (existing != null &&
            existing.hourOffset == selectedHour &&
            existing.zoomBucket == floor(renderCamera.zoom).toInt() &&
            existing.bounds.contains(cameraBounds(renderCamera))
        ) {
            val cacheStatus = "Temp ${formatWeatherTime(existing.time)} · ${existing.points.size} Punkte · Cache"
            if (status != cacheStatus) status = cacheStatus
            return@LaunchedEffect
        }
        loadToken += 1
        val token = loadToken
        forecastState = forecastState.copy(loading = true, error = null, cacheHit = false)
        status = "Lade Vorhersage..."
        repository.fetchForecast(renderCamera, selectedHour) { result ->
            if (token != loadToken) return@fetchForecast
            result.onSuccess {
                forecastState = ForecastUiState(snapshot = it.snapshot, loading = false, error = null, cacheHit = it.cacheHit)
                val source = if (it.cacheHit) "Cache" else "Neu"
                status = "Temp ${formatWeatherTime(it.snapshot.time)} · ${it.snapshot.points.size} Punkte · $source"
            }.onFailure {
                val message = if (it.message?.contains("HTTP 429") == true) {
                    "Temperaturdaten limitiert · Cache wird weiter genutzt"
                } else {
                    it.message?.take(90) ?: "Vorhersage nicht geladen"
                }
                forecastState = forecastState.copy(loading = false, error = message, cacheHit = false)
                status = message
            }
        }
    }

    LaunchedEffect(Unit) {
        repository.fetchRadarFrames { frames ->
            radarFrames = frames
            radarIndex = max(0, frames.lastIndex)
        }
    }

    MaterialTheme {
        Surface(color = Color(0xFF0F172A), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                NativeWeatherMap(
                    fix = fix,
                    recenterNonce = recenterNonce,
                    layer = layer,
                    radarFrame = radarFrames.getOrNull(radarIndex),
                    forecastSnapshot = forecastState.snapshot,
                    tempLabels = stableTempLabels,
                    onCameraMove = { liveCamera = it },
                    onCameraIdle = {
                        liveCamera = it
                        renderCamera = it
                    },
                    onProjectedTempLabels = { projectedTempLabels = it },
                )

                if (layer == WeatherLayer.Temp && forecastState.snapshot == null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(
                            color = if (forecastState.error == null) Color(0x2214B8A6) else Color(0x22EF4444),
                            size = size,
                        )
                    }
                }

                if (layer == WeatherLayer.Temp) projectedTempLabels.forEach { projected ->
                    if (projected.x in -80.0..(liveCamera.width + 80.0) && projected.y in -80.0..(liveCamera.height + 80.0)) {
                        TemperatureLabel(projected.label, projected.x, projected.y)
                    }
                }

                TopControls(
                    layer = layer,
                    status = if (layer == WeatherLayer.Radar) {
                        radarFrames.getOrNull(radarIndex)?.let { "Radar ${formatEpochTime(it.time)} · max z7" } ?: "Radar laedt..."
                    } else {
                        status
                    },
                    permissionDenied = permissionDenied,
                    onLayer = {
                        layer = it
                        if (it == WeatherLayer.Radar) MapController.capZoomForRadar()
                    },
                )

                FloatingActions(
                    onUseLocation = onUseLocation,
                    onZoomIn = { MapController.zoomBy(0.8) },
                    onZoomOut = { MapController.zoomBy(-0.8) },
                )

                if (layer == WeatherLayer.Radar) {
                    RadarControls(
                        canStep = radarFrames.isNotEmpty(),
                        onPrevious = { if (radarFrames.isNotEmpty()) radarIndex = (radarIndex - 1 + radarFrames.size) % radarFrames.size },
                        onNext = { if (radarFrames.isNotEmpty()) radarIndex = (radarIndex + 1) % radarFrames.size },
                    )
                }

                WeatherSheet(
                    forecastState = forecastState,
                    selectedHour = selectedHour,
                    layer = layer,
                    onHour = { selectedHour = it },
                )
            }
        }
    }
}

@Composable
private fun NativeWeatherMap(
    fix: Fix,
    recenterNonce: Int,
    layer: WeatherLayer,
    radarFrame: RadarFrame?,
    forecastSnapshot: ForecastSnapshot?,
    tempLabels: List<TempLabel>,
    onCameraMove: (CameraState) -> Unit,
    onCameraIdle: (CameraState) -> Unit,
    onProjectedTempLabels: (List<ProjectedTempLabel>) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapHolder = remember { MapHolder() }
    var mapViewForLifecycle by remember { mutableStateOf<MapView?>(null) }
    var handledRecenterNonce by remember { mutableIntStateOf(0) }
    var lastMoveEmitMs by remember { mutableStateOf(0L) }
    val currentTempLabels by rememberUpdatedState(tempLabels)
    val currentOnProjectedTempLabels by rememberUpdatedState(onProjectedTempLabels)

    fun emitProjectedLabels(map: MapLibreMap) {
        currentOnProjectedTempLabels(
            currentTempLabels.map { label ->
                val screen = map.projection.toScreenLocation(LatLng(label.latitude, label.longitude))
                ProjectedTempLabel(label, screen.x.toDouble(), screen.y.toDouble())
            }
        )
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            MapLibre.getInstance(context)
            MapView(context).apply {
                mapViewForLifecycle = this
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                onCreate(null)
                getMapAsync { map ->
                    mapHolder.map = map
                    MapController.map = map
                    map.uiSettings.isLogoEnabled = false
                    map.uiSettings.isAttributionEnabled = true
                    map.uiSettings.isRotateGesturesEnabled = false
                    map.uiSettings.isTiltGesturesEnabled = false
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(fix.latitude, fix.longitude))
                        .zoom(11.0)
                        .build()
                    map.setStyle(Style.Builder().fromJson(baseStyleJson())) { style ->
                        mapHolder.style = style
                        mapHolder.installWeatherLayers()
                        mapHolder.updateRadar(radarFrame, layer)
                        mapHolder.updateTemperature(forecastSnapshot, layer)
                    }
                    map.addOnCameraMoveListener {
                        val now = SystemClock.uptimeMillis()
                        emitProjectedLabels(map)
                        if (now - lastMoveEmitMs >= 90L) {
                            lastMoveEmitMs = now
                            onCameraMove(map.cameraState(width, height))
                        }
                    }
                    map.addOnCameraIdleListener {
                        onCameraIdle(map.cameraState(width, height))
                        emitProjectedLabels(map)
                    }
                    post {
                        val initial = map.cameraState(width, height)
                        onCameraMove(initial)
                        onCameraIdle(initial)
                        emitProjectedLabels(map)
                    }
                }
            }
        },
        update = { mapView ->
            mapHolder.updateRadar(radarFrame, layer)
            mapHolder.updateTemperature(forecastSnapshot, layer)
            mapHolder.map?.let { emitProjectedLabels(it) }
            if (recenterNonce > 0 && recenterNonce != handledRecenterNonce) {
                handledRecenterNonce = recenterNonce
                mapHolder.map?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(fix.latitude, fix.longitude), 11.5),
                    650,
                )
            }
        },
    )
    mapViewForLifecycle?.let { DisposableMapLifecycle(it, lifecycleOwner) }
}

@Composable
private fun DisposableMapLifecycle(mapView: MapView, lifecycleOwner: LifecycleOwner) {
    DisposableEffect(mapView, lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = mapView.onStart()
            override fun onResume(owner: LifecycleOwner) = mapView.onResume()
            override fun onPause(owner: LifecycleOwner) = mapView.onPause()
            override fun onStop(owner: LifecycleOwner) = mapView.onStop()
            override fun onDestroy(owner: LifecycleOwner) = mapView.onDestroy()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

private class MapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    private var radarSource: RasterSource? = null
    private var tempSource: ImageSource? = null
    private var lastRadarPath: String? = null
    private var lastTempSnapshot: ForecastSnapshot? = null

    fun installWeatherLayers() {
        // Weather layers are installed lazily once data is available.
    }

    fun updateRadar(frame: RadarFrame?, layer: WeatherLayer) {
        val map = map ?: return
        val style = style ?: return
        val path = frame?.path
        if (path != null && path != lastRadarPath) {
            if (style.getLayer(RADAR_LAYER) != null) style.removeLayer(RADAR_LAYER)
            if (style.getSource(RADAR_SOURCE) != null) style.removeSource(RADAR_SOURCE)
            val tiles = TileSet("2.2.0", "https://tilecache.rainviewer.com$path/256/{z}/{x}/{y}/2/1_1.png")
            tiles.setMinZoom(0f)
            tiles.setMaxZoom(7f)
            radarSource = RasterSource(RADAR_SOURCE, tiles, 256)
            style.addSource(radarSource!!)
            style.addLayer(
                RasterLayer(RADAR_LAYER, RADAR_SOURCE).withProperties(
                    rasterOpacity(if (layer == WeatherLayer.Radar) 0.62f else 0.0f)
                )
            )
            lastRadarPath = path
        }
        map.getStyle {
            it.getLayer(RADAR_LAYER)?.setProperties(
                rasterOpacity(if (layer == WeatherLayer.Radar) 0.62f else 0.0f)
            )
        }
    }

    fun updateTemperature(snapshot: ForecastSnapshot?, layer: WeatherLayer) {
        val style = style ?: return
        if (snapshot == null) {
            style.getLayer(TEMP_LAYER)?.setProperties(rasterOpacity(0.0f))
            return
        }
        if (snapshot !== lastTempSnapshot || style.getSource(TEMP_SOURCE) == null) {
            val quad = snapshot.bounds.toLatLngQuad()
            val bitmap = renderTemperatureBitmap(snapshot)
            val existing = style.getSource(TEMP_SOURCE) as? ImageSource
            if (existing == null) {
                tempSource = ImageSource(TEMP_SOURCE, quad, bitmap)
                style.addSource(tempSource!!)
                val tempLayer = RasterLayer(TEMP_LAYER, TEMP_SOURCE).withProperties(
                    rasterOpacity(if (layer == WeatherLayer.Temp) TEMP_LAYER_OPACITY else 0.0f)
                )
                val firstSymbolLayer = style.getLayers().firstOrNull { it is SymbolLayer }?.id
                if (firstSymbolLayer != null) {
                    style.addLayerBelow(tempLayer, firstSymbolLayer)
                } else {
                    style.addLayer(tempLayer)
                }
            } else {
                tempSource = existing
                existing.setCoordinates(quad)
                existing.setImage(bitmap)
            }
            lastTempSnapshot = snapshot
        }
        style.getLayer(TEMP_LAYER)?.setProperties(
            rasterOpacity(if (layer == WeatherLayer.Temp) TEMP_LAYER_OPACITY else 0.0f)
        )
    }
}

@Composable
private fun TopControls(
    layer: WeatherLayer,
    status: String,
    permissionDenied: Boolean,
    onLayer: (WeatherLayer) -> Unit,
) {
    Column(
        modifier = Modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Temp", layer == WeatherLayer.Temp) { onLayer(WeatherLayer.Temp) }
            Chip("Radar", layer == WeatherLayer.Radar) { onLayer(WeatherLayer.Radar) }
            Text(
                text = status,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xDD111827))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .weight(1f),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        if (permissionDenied) {
            Text(
                text = "Standort verweigert, Aachen wird angezeigt",
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xEFFFF7ED))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = Color(0xFF9A3412),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Color(0xFF0F766E) else Color(0xEEFFFFFF),
            contentColor = if (active) Color.White else Color(0xFF1F2937),
        ),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FloatingActions(onUseLocation: () -> Unit, onZoomIn: () -> Unit, onZoomOut: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(end = 12.dp, top = 102.dp)
            .statusBarsPadding()
            .fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MapButton("⌖", onClick = onUseLocation)
        MapButton("+", onClick = onZoomIn)
        MapButton("-", onClick = onZoomOut)
    }
}

@Composable
private fun RadarControls(canStep: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(start = 12.dp, top = 112.dp)
            .statusBarsPadding(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MapButton("‹", enabled = canStep, onClick = onPrevious)
        MapButton("›", enabled = canStep, onClick = onNext)
    }
}

@Composable
private fun MapButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xF7FFFFFF), contentColor = Color(0xFF111827)),
    ) {
        Text(text, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TemperatureLabel(label: TempLabel, x: Double, y: Double) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset {
                with(density) {
                    IntOffset(x.roundToInt() - 18.dp.roundToPx(), y.roundToInt() - 14.dp.roundToPx())
                }
            }
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xEFFFFFFF))
            .border(1.dp, Color(0x33111827), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text("${label.value}°", color = Color(0xFF111827), fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
    }
}

private fun renderTemperatureBitmap(snapshot: ForecastSnapshot): Bitmap {
    val width = 384
    val height = 384
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val northWest = project(snapshot.bounds.north, snapshot.bounds.west, 0.0)
    val southEast = project(snapshot.bounds.south, snapshot.bounds.east, 0.0)
    val minTemp = snapshot.points.minOfOrNull { it.temperature } ?: snapshot.current.temperature
    val maxTemp = snapshot.points.maxOfOrNull { it.temperature } ?: snapshot.current.temperature
    val cell = 3
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val sampleX = northWest.first + (southEast.first - northWest.first) * ((x + cell / 2.0) / width)
            val sampleY = northWest.second + (southEast.second - northWest.second) * ((y + cell / 2.0) / height)
            val geo = unproject(sampleX, sampleY, 0.0)
            paint.color = tempColor(temperatureAt(geo.first, geo.second, snapshot), minTemp, maxTemp)
            canvas.drawRect(
                x.toFloat(),
                y.toFloat(),
                (x + cell + 1).toFloat(),
                (y + cell + 1).toFloat(),
                paint,
            )
            x += cell
        }
        y += cell
    }
    return bitmap
}

@Composable
private fun WeatherSheet(
    forecastState: ForecastUiState,
    selectedHour: Int,
    layer: WeatherLayer,
    onHour: (Int) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xF7FFFFFF)),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        ) {
            val forecast = forecastState.snapshot
            if (forecast == null) {
                Text(
                    forecastState.error ?: "Lade Wetterdaten...",
                    modifier = Modifier.padding(16.dp),
                    color = if (forecastState.error == null) Color(0xFF475569) else Color(0xFFB91C1C),
                    fontWeight = FontWeight.Bold,
                )
                return@Card
            }
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${forecast.current.temperature.roundToInt()} °C", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Color(0xFF111827))
                        Text(
                            "${codeText(forecast.current.code)} · ${formatWeatherTime(forecast.current.time)}",
                            color = Color(0xFF64748B),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        if (layer == WeatherLayer.Temp) "Temperatur" else "Radar",
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFFE0F2FE))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        color = Color(0xFF075985),
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric("Gefuehlt", "${forecast.current.apparent.roundToInt()} °C", Modifier.weight(1f))
                    Metric("Wind", "${forecast.current.wind.roundToInt()} km/h", Modifier.weight(1f))
                    Metric(if (forecastState.cacheHit) "Cache" else "Daten", forecast.points.size.toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(12) { hour ->
                        Button(
                            onClick = { onHour(hour) },
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hour == selectedHour) Color(0xFF0F766E) else Color.White,
                                contentColor = if (hour == selectedHour) Color.White else Color(0xFF334155),
                            ),
                        ) {
                            Text(if (hour == 0) "Jetzt" else "+${hour}h", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF8FAFC))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Text(label, color = Color(0xFF64748B), style = MaterialTheme.typography.labelMedium)
        Text(value, color = Color(0xFF111827), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
    }
}

private class WeatherRepository {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val forecastCache = LinkedHashMap<ForecastCacheKey, ForecastSnapshot>(24, 0.75f, true)
    private val forecastCacheTimes = mutableMapOf<ForecastCacheKey, Long>()
    private val inFlightForecasts = mutableMapOf<ForecastCacheKey, MutableList<(Result<ForecastResult>) -> Unit>>()

    fun fetchForecast(camera: CameraState, hourOffset: Int, callback: (Result<ForecastResult>) -> Unit) {
        val visibleBounds = cameraBounds(camera)
        val quality = qualityForZoom(camera.zoom)
        val zoomBucket = forecastZoomBucket(camera.zoom)
        val bounds = quantizedForecastBounds(visibleBounds.expand(1.8), zoomBucket)
        val key = forecastCacheKey(bounds, hourOffset, zoomBucket)
        val now = SystemClock.elapsedRealtime()
        forecastCache[key]?.let { cached ->
            if (now - (forecastCacheTimes[key] ?: 0L) <= FORECAST_CACHE_TTL_MS && cached.bounds.contains(visibleBounds)) {
                callback(Result.success(ForecastResult(cached, cacheHit = true)))
                return
            }
        }
        inFlightForecasts[key]?.let {
            it += callback
            return
        }
        inFlightForecasts[key] = mutableListOf(callback)
        executor.execute {
            runCatching {
                val grid = buildGrid(bounds, quality.rows, quality.cols, camera.latitude, camera.longitude)
                val latitudes = grid.joinToString(",") { "%.4f".format(Locale.US, it.first) }
                val longitudes = grid.joinToString(",") { "%.4f".format(Locale.US, it.second) }
                val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$latitudes&longitude=$longitudes" +
                    "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                    "&hourly=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                    "&forecast_days=3&timezone=auto"
                ForecastResult(parseForecast(readText(url), grid, bounds, camera, hourOffset), cacheHit = false)
            }.also { result ->
                main.post {
                    result.onSuccess {
                        forecastCache[key] = it.snapshot
                        forecastCacheTimes[key] = SystemClock.elapsedRealtime()
                        trimForecastCache()
                    }
                    val callbacks = inFlightForecasts.remove(key).orEmpty()
                    callbacks.forEach { it(result) }
                }
            }
        }
    }

    fun fetchRadarFrames(callback: (List<RadarFrame>) -> Unit) {
        executor.execute {
            val frames = runCatching {
                val root = JSONObject(readText("https://api.rainviewer.com/public/weather-maps.json"))
                val host = root.optString("host", "https://tilecache.rainviewer.com")
                val past = root.getJSONObject("radar").optJSONArray("past") ?: JSONArray()
                val nowcast = root.getJSONObject("radar").optJSONArray("nowcast") ?: JSONArray()
                (0 until past.length()).map { past.getJSONObject(it) } +
                    (0 until nowcast.length()).map { nowcast.getJSONObject(it) }
            }.getOrDefault(emptyList()).map {
                val path = it.getString("path")
                RadarFrame(path = if (path.startsWith("http")) path.removePrefix("https://tilecache.rainviewer.com") else path, time = it.getLong("time"))
            }
            main.post { callback(frames) }
        }
    }

    private fun parseForecast(
        json: String,
        grid: List<Pair<Double, Double>>,
        bounds: GeoBounds,
        camera: CameraState,
        hourOffset: Int,
    ): ForecastSnapshot {
        val rowsJson = JSONArray(json)
        val points = buildList {
            for (i in 0 until rowsJson.length()) {
                val row = rowsJson.getJSONObject(i)
                val hourly = row.getJSONObject("hourly")
                val current = row.getJSONObject("current")
                val times = hourly.getJSONArray("time")
                val currentTime = current.getString("time")
                val startIndex = (0 until times.length()).firstOrNull { times.getString(it) >= currentTime } ?: 0
                val index = min(times.length() - 1, startIndex + hourOffset)
                add(
                    WeatherPoint(
                        latitude = grid.getOrNull(i)?.first ?: row.getDouble("latitude"),
                        longitude = grid.getOrNull(i)?.second ?: row.getDouble("longitude"),
                        temperature = hourly.getJSONArray("temperature_2m").getDouble(index),
                        apparent = hourly.getJSONArray("apparent_temperature").getDouble(index),
                        code = hourly.getJSONArray("weather_code").getInt(index),
                        wind = hourly.getJSONArray("wind_speed_10m").getDouble(index),
                        time = times.getString(index),
                    )
                )
            }
        }
        val current = points.first()
        val projectedPoints = points.map { point ->
            val projected = project(point.latitude, point.longitude, 0.0)
            ProjectedWeatherPoint(point = point, x = projected.first, y = projected.second)
        }
        return ForecastSnapshot(
            bounds = bounds,
            zoomBucket = floor(camera.zoom).toInt(),
            hourOffset = hourOffset,
            time = current.time,
            current = current,
            points = points,
            projectedPoints = projectedPoints,
        )
    }

    private fun readText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 18_000
        connection.setRequestProperty("User-Agent", "WeatherMap Android prototype")
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: ${body.take(120)}")
        }
        return body
    }

    private fun trimForecastCache() {
        while (forecastCache.size > FORECAST_CACHE_LIMIT) {
            val eldest = forecastCache.keys.firstOrNull() ?: return
            forecastCache.remove(eldest)
            forecastCacheTimes.remove(eldest)
        }
    }
}

private data class RenderQuality(
    val rows: Int,
    val cols: Int,
)

private const val FORECAST_CACHE_TTL_MS = 10 * 60 * 1_000L
private const val FORECAST_CACHE_LIMIT = 24

private fun qualityForZoom(zoom: Double): RenderQuality = when {
    zoom <= 7.0 -> RenderQuality(6, 5)
    zoom <= 10.0 -> RenderQuality(5, 5)
    zoom <= 13.0 -> RenderQuality(6, 6)
    else -> RenderQuality(5, 5)
}

private fun labelPolicyForZoom(zoom: Double): TempLabelPolicy = when {
    zoom <= 7.0 -> TempLabelPolicy(0, maxLabels = 10, minLabels = 8, minDistancePx = 105.0, panHysteresisPx = 150.0)
    zoom <= 10.0 -> TempLabelPolicy(1, maxLabels = 10, minLabels = 7, minDistancePx = 115.0, panHysteresisPx = 125.0)
    zoom <= 13.0 -> TempLabelPolicy(2, maxLabels = 9, minLabels = 6, minDistancePx = 125.0, panHysteresisPx = 105.0)
    else -> TempLabelPolicy(3, maxLabels = 7, minLabels = 5, minDistancePx = 135.0, panHysteresisPx = 110.0)
}

private fun shouldRefreshTempLabels(previous: CameraState?, current: CameraState, policy: TempLabelPolicy): Boolean {
    previous ?: return true
    if (labelPolicyForZoom(previous.zoom).zoomBand != policy.zoomBand) return true
    if (abs(previous.zoom - current.zoom) > 0.25) return true
    val previousCenter = project(previous.latitude, previous.longitude, current.zoom)
    val currentCenter = project(current.latitude, current.longitude, current.zoom)
    return hypot(previousCenter.first - currentCenter.first, previousCenter.second - currentCenter.second) > policy.panHysteresisPx * 0.72
}

private fun forecastZoomBucket(zoom: Double): Int = floor(zoom).toInt()

private fun forecastStepForZoomBucket(zoomBucket: Int): Double = when {
    zoomBucket <= 7 -> 2.0
    zoomBucket <= 10 -> 0.8
    zoomBucket <= 13 -> 0.35
    else -> 0.18
}

private fun quantizedForecastBounds(bounds: GeoBounds, zoomBucket: Int): GeoBounds {
    val step = forecastStepForZoomBucket(zoomBucket)
    return GeoBounds(
        north = min(85.0, ceil(bounds.north / step) * step),
        south = max(-85.0, floor(bounds.south / step) * step),
        west = max(-180.0, floor(bounds.west / step) * step),
        east = min(180.0, ceil(bounds.east / step) * step),
    )
}

private fun forecastCacheKey(bounds: GeoBounds, hourOffset: Int, zoomBucket: Int): ForecastCacheKey {
    val step = forecastStepForZoomBucket(zoomBucket)
    return ForecastCacheKey(
        hourOffset = hourOffset,
        zoomBucket = zoomBucket,
        northBucket = roundToBucket(bounds.north, step),
        southBucket = roundToBucket(bounds.south, step),
        westBucket = roundToBucket(bounds.west, step),
        eastBucket = roundToBucket(bounds.east, step),
    )
}

private fun roundToBucket(value: Double, step: Double): Int = (value / step).roundToInt()

private fun buildGrid(bounds: GeoBounds, rows: Int, cols: Int, lat: Double, lon: Double): List<Pair<Double, Double>> {
    val points = mutableListOf(lat to lon)
    repeat(rows) { iy ->
        val latitude = bounds.north - (bounds.north - bounds.south) * (iy.toDouble() / (rows - 1))
        repeat(cols) { ix ->
            val longitude = bounds.west + (bounds.east - bounds.west) * (ix.toDouble() / (cols - 1))
            points += latitude to longitude
        }
    }
    return points
}

private fun tempColor(temp: Double, minTemp: Double, maxTemp: Double): Int {
    val actualSpan = max(0.1, maxTemp - minTemp)
    val center = (minTemp + maxTemp) / 2.0
    val adjustedTemp = if (actualSpan < MIN_VISIBLE_TEMP_SPAN_C) {
        center + (temp - center) * (MIN_VISIBLE_TEMP_SPAN_C / actualSpan)
    } else {
        temp
    }
    val lower = TEMP_COLOR_STOPS.lastOrNull { adjustedTemp >= it.temp } ?: TEMP_COLOR_STOPS.first()
    val upper = TEMP_COLOR_STOPS.firstOrNull { adjustedTemp <= it.temp } ?: TEMP_COLOR_STOPS.last()
    if (lower.temp == upper.temp) return AndroidColor.argb(138, lower.red, lower.green, lower.blue)
    val t = ((adjustedTemp - lower.temp) / (upper.temp - lower.temp)).coerceIn(0.0, 1.0)
    return AndroidColor.argb(
        TEMP_PIXEL_ALPHA,
        lerpColorChannel(lower.red, upper.red, t),
        lerpColorChannel(lower.green, upper.green, t),
        lerpColorChannel(lower.blue, upper.blue, t),
    )
}

private fun lerpColorChannel(start: Int, end: Int, t: Double): Int =
    (start + (end - start) * t).roundToInt().coerceIn(0, 255)

private fun visibleTemperatureLabels(snapshot: ForecastSnapshot?, camera: CameraState, previousLabels: List<TempLabel>): List<TempLabel> {
    snapshot ?: return emptyList()
    val policy = labelPolicyForZoom(camera.zoom)
    val candidates = mutableListOf<LabelCandidate>()

    fun addCandidate(
        id: String,
        name: String?,
        latitude: Double,
        longitude: Double,
        priority: Int,
        scoreBoost: Double = 0.0,
        rank: Int = 10_000,
        isCapital: Boolean = false,
    ) {
        val screen = screenPoint(latitude, longitude, camera)
        if (!isUsableLabelScreenPoint(screen.first, screen.second, camera)) return
        val temp = temperatureAt(latitude, longitude, snapshot)
        val centerDistance = hypot(screen.first - camera.width / 2.0, screen.second - camera.height / 2.0)
        val edgePenalty = edgePenalty(screen.first, screen.second, camera)
        val localGradient = localTemperatureGradient(latitude, longitude, snapshot)
        val previousBoost = if (previousLabels.any { it.id == id }) 4.0 else 0.0
        val score = scoreBoost + previousBoost + localGradient * 1.8 - centerDistance / max(camera.width, camera.height) - edgePenalty - rank / 10_000.0
        candidates += LabelCandidate(id, name, latitude, longitude, temp, screen.first, screen.second, priority, score, isCapital)
    }

    val visibleCities = TEMP_CITY_ANCHORS.mapNotNull { city ->
        val screen = screenPoint(city.latitude, city.longitude, camera)
        if (!isUsableLabelScreenPoint(screen.first, screen.second, camera)) {
            null
        } else {
            city to screen
        }
    }

    visibleCities.forEach { (city, _) ->
        addCandidate(
            city.id,
            city.name,
            city.latitude,
            city.longitude,
            priority = if (city.isCapital) 0 else 1,
            scoreBoost = if (city.isCapital) 50.0 else 8.0,
            rank = city.rank,
            isCapital = city.isCapital,
        )
    }

    midpointCandidates(visibleCities, policy, camera).forEachIndexed { index, midpoint ->
        addCandidate(
            id = midpoint.id,
            name = null,
            latitude = midpoint.latitude,
            longitude = midpoint.longitude,
            priority = 2,
            scoreBoost = 5.2 - index * 0.1,
            rank = midpoint.rank,
        )
    }

    fixedGeoAnchors(camera, policy).forEachIndexed { index, anchor ->
        addCandidate(
            id = anchor.id,
            name = null,
            latitude = anchor.latitude,
            longitude = anchor.longitude,
            priority = 3,
            scoreBoost = 3.2 - index * 0.03,
            rank = anchor.rank,
        )
    }

    previousLabels.forEach {
        addCandidate(it.id, it.name, it.latitude, it.longitude, priority = 0, scoreBoost = 10.0)
    }

    if (candidates.isEmpty()) {
        addCandidate("current-location", null, snapshot.current.latitude, snapshot.current.longitude, priority = 9, scoreBoost = 1.0)
    }

    val selected = selectTemperatureLabels(candidates, policy, camera)
    return selected.map { TempLabel(it.id, it.latitude, it.longitude, it.temperature.roundToInt(), it.name) }
}

private fun selectTemperatureLabels(
    candidates: List<LabelCandidate>,
    policy: TempLabelPolicy,
    camera: CameraState,
): List<LabelCandidate> {
    val ranked = candidates
        .distinctBy { it.id }
        .sortedWith(
            compareBy<LabelCandidate> { it.priority }
                .thenByDescending { it.score }
                .thenBy { hypot(it.x - camera.width / 2.0, it.y - camera.height / 2.0) }
                .thenByDescending { it.latitude }
                .thenBy { it.longitude }
        )
    val capitals = ranked.filter { it.isCapital }

    fun pick(distance: Double, targetCount: Int): List<LabelCandidate> {
        val chosen = capitals.toMutableList()
        val d2 = distance * distance
        ranked.forEach { candidate ->
            if (candidate.isCapital) return@forEach
            val targetWithCapitals = max(targetCount, capitals.size)
            val maxWithCapitals = max(policy.maxLabels, capitals.size)
            if (chosen.size >= targetWithCapitals || chosen.size >= maxWithCapitals) return@forEach
            if (chosen.none { !it.isCapital && (it.x - candidate.x) * (it.x - candidate.x) + (it.y - candidate.y) * (it.y - candidate.y) < d2 }) {
                chosen += candidate
            }
        }
        return chosen
    }

    listOf(1.0, 0.82, 0.68, 0.56, 0.42, 0.32, 0.24).forEach { factor ->
        val selected = pick(policy.minDistancePx * factor, policy.maxLabels)
        if (selected.size >= policy.minLabels) return selected
    }

    val chosen = pick(36.0, policy.minLabels).toMutableList()
    ranked.forEach { candidate ->
        if (candidate.isCapital) return@forEach
        val maxWithCapitals = max(policy.maxLabels, capitals.size)
        if (chosen.size >= policy.minLabels && chosen.size >= capitals.size) return@forEach
        if (chosen.size >= maxWithCapitals) return@forEach
        if (chosen.none { geoDistanceScore(it.latitude, it.longitude, candidate.latitude, candidate.longitude) < 0.000001 }) {
            chosen += candidate
        }
    }
    return chosen
}

private data class MidpointCandidate(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val rank: Int,
)

private data class FixedGeoAnchor(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val rank: Int,
)

private fun fixedGeoAnchors(camera: CameraState, policy: TempLabelPolicy): List<FixedGeoAnchor> {
    val bounds = cameraBounds(camera).expand(1.05)
    val step = fixedGeoAnchorStep(policy.zoomBand)
    val minLatBucket = floor(bounds.south / step).toInt() - 1
    val maxLatBucket = ceil(bounds.north / step).toInt() + 1
    val minLonBucket = floor(bounds.west / step).toInt() - 1
    val maxLonBucket = ceil(bounds.east / step).toInt() + 1
    val anchors = mutableListOf<Pair<FixedGeoAnchor, Double>>()
    for (latBucket in minLatBucket..maxLatBucket) {
        val latitude = (latBucket * step).coerceIn(-84.0, 84.0)
        for (lonBucket in minLonBucket..maxLonBucket) {
            val longitude = normalizeLongitude(lonBucket * step)
            val screen = screenPoint(latitude, longitude, camera)
            if (!isUsableLabelScreenPoint(screen.first, screen.second, camera)) continue
            val centerDistance = hypot(screen.first - camera.width / 2.0, screen.second - camera.height / 2.0)
            val id = "geo:${(latitude * 100).roundToInt()}:${(longitude * 100).roundToInt()}"
            anchors += FixedGeoAnchor(id, latitude, longitude, 20_000 + abs(latBucket) + abs(lonBucket)) to centerDistance
        }
    }
    return anchors
        .sortedWith(compareBy<Pair<FixedGeoAnchor, Double>> { it.second }.thenBy { it.first.id })
        .map { it.first }
        .take(policy.maxLabels * 6)
}

private fun fixedGeoAnchorStep(zoomBand: Int): Double = when (zoomBand) {
    0 -> 1.0
    1 -> 0.5
    2 -> 0.25
    else -> 0.125
}

private fun isUsableLabelScreenPoint(x: Double, y: Double, camera: CameraState): Boolean {
    val horizontalPad = 12.0
    val topPad = 72.0
    val bottomPad = 92.0
    return x in horizontalPad..(camera.width - horizontalPad) &&
        y in topPad..(camera.height - bottomPad)
}

private fun midpointCandidates(
    visibleCities: List<Pair<TempCityAnchor, Pair<Double, Double>>>,
    policy: TempLabelPolicy,
    camera: CameraState,
): List<MidpointCandidate> {
    val threshold = if (policy.zoomBand <= 0) 260.0 else 300.0
    val candidates = mutableListOf<Pair<MidpointCandidate, Double>>()
    visibleCities.forEachIndexed { leftIndex, left ->
        for (rightIndex in leftIndex + 1 until visibleCities.size) {
            val right = visibleCities[rightIndex]
            val distance = hypot(left.second.first - right.second.first, left.second.second - right.second.second)
            if (distance <= threshold) continue
            val midLat = (left.first.latitude + right.first.latitude) / 2.0
            val midLon = midpointLongitude(left.first.longitude, right.first.longitude)
            val screen = screenPoint(midLat, midLon, camera)
            if (screen.first !in -80.0..(camera.width + 80.0) || screen.second !in -80.0..(camera.height + 80.0)) continue
            val id = "mid:${left.first.id}:${right.first.id}"
            val rank = min(left.first.rank, right.first.rank) + 5_000
            candidates += MidpointCandidate(id, midLat, midLon, rank) to distance
        }
    }
    return candidates
        .sortedWith(compareByDescending<Pair<MidpointCandidate, Double>> { it.second }.thenBy { it.first.id })
        .map { it.first }
        .take(policy.maxLabels)
}

private fun midpointLongitude(left: Double, right: Double): Double {
    val delta = right - left
    return when {
        delta > 180.0 -> (left + right - 360.0) / 2.0
        delta < -180.0 -> (left + right + 360.0) / 2.0
        else -> (left + right) / 2.0
    }.let {
        when {
            it < -180.0 -> it + 360.0
            it > 180.0 -> it - 360.0
            else -> it
        }
    }
}

private fun normalizeLongitude(value: Double): Double {
    var longitude = value
    while (longitude < -180.0) longitude += 360.0
    while (longitude > 180.0) longitude -= 360.0
    return longitude
}

private fun edgePenalty(x: Double, y: Double, camera: CameraState): Double {
    val edge = min(min(x, camera.width - x), min(y, camera.height - y))
    return if (edge >= 72.0) 0.0 else (72.0 - edge) / 72.0 * 3.0
}

private fun localTemperatureGradient(latitude: Double, longitude: Double, snapshot: ForecastSnapshot): Double {
    val temp = temperatureAt(latitude, longitude, snapshot)
    val near = snapshot.points
        .asSequence()
        .sortedBy { geoDistanceScore(latitude, longitude, it.latitude, it.longitude) }
        .take(3)
        .toList()
    return near.maxOfOrNull { abs(it.temperature - temp) } ?: 0.0
}

private fun geoDistanceScore(latA: Double, lonA: Double, latB: Double, lonB: Double): Double =
    (latA - latB) * (latA - latB) + (lonA - lonB) * (lonA - lonB)

private fun temperatureAt(latitude: Double, longitude: Double, snapshot: ForecastSnapshot): Double {
    val target = project(latitude, longitude, 0.0)
    var weightSum = 0.0
    var temp = 0.0
    var nearestDistance = Double.MAX_VALUE
    var nearest = snapshot.current.temperature
    snapshot.projectedPoints.forEach { projected ->
        val d2 = (projected.x - target.first) * (projected.x - target.first) + (projected.y - target.second) * (projected.y - target.second)
        if (d2 < nearestDistance) {
            nearestDistance = d2
            nearest = projected.point.temperature
        }
        val weight = 1.0 / max(0.00000002, d2)
        weightSum += weight
        temp += projected.point.temperature * weight
    }
    return if (weightSum == 0.0) nearest else temp / weightSum
}

private data class LabelCandidate(
    val id: String,
    val name: String?,
    val latitude: Double,
    val longitude: Double,
    val temperature: Double,
    val x: Double,
    val y: Double,
    val priority: Int,
    val score: Double,
    val isCapital: Boolean,
)

private fun MapLibreMap.cameraState(width: Int, height: Int): CameraState {
    val target = cameraPosition.target ?: LatLng(50.7753, 6.0839)
    val visibleBounds = projection.visibleRegion.latLngBounds
    return CameraState(
        latitude = target.latitude,
        longitude = target.longitude,
        zoom = cameraPosition.zoom,
        width = max(1, width),
        height = max(1, height),
        bounds = GeoBounds(
            north = visibleBounds.getLatNorth(),
            south = visibleBounds.getLatSouth(),
            west = visibleBounds.getLonWest(),
            east = visibleBounds.getLonEast(),
        ),
    )
}

private fun cameraBounds(camera: CameraState): GeoBounds {
    camera.bounds?.let { return it }
    val center = project(camera.latitude, camera.longitude, camera.zoom)
    val nw = unproject(center.first - camera.width / 2.0, center.second - camera.height / 2.0, camera.zoom)
    val se = unproject(center.first + camera.width / 2.0, center.second + camera.height / 2.0, camera.zoom)
    return GeoBounds(
        north = max(nw.first, se.first),
        south = min(nw.first, se.first),
        west = nw.second,
        east = se.second,
    )
}

private fun GeoBounds.expand(factor: Double): GeoBounds {
    val latSpan = max(0.02, north - south)
    val lonSpan = max(0.02, east - west)
    val latPad = latSpan * (factor - 1.0) / 2.0
    val lonPad = lonSpan * (factor - 1.0) / 2.0
    return GeoBounds(
        north = min(85.0, north + latPad),
        south = max(-85.0, south - latPad),
        west = max(-180.0, west - lonPad),
        east = min(180.0, east + lonPad),
    )
}

private fun GeoBounds.contains(inner: GeoBounds): Boolean =
    north >= inner.north &&
        south <= inner.south &&
        west <= inner.west &&
        east >= inner.east

private fun GeoBounds.contains(latitude: Double, longitude: Double): Boolean =
    latitude <= north &&
        latitude >= south &&
        longitude >= west &&
        longitude <= east

private fun GeoBounds.toLatLngQuad(): LatLngQuad =
    LatLngQuad(
        LatLng(north, west),
        LatLng(north, east),
        LatLng(south, east),
        LatLng(south, west),
    )

private fun screenPoint(latitude: Double, longitude: Double, camera: CameraState): Pair<Double, Double> {
    val p = project(latitude, longitude, camera.zoom)
    val center = project(camera.latitude, camera.longitude, camera.zoom)
    return (p.first - center.first + camera.width / 2.0) to (p.second - center.second + camera.height / 2.0)
}

private fun project(latitude: Double, longitude: Double, zoom: Double): Pair<Double, Double> {
    val sin = sin(latitude * PI / 180.0).coerceIn(-0.9999, 0.9999)
    val scale = 256.0 * 2.0.pow(zoom)
    return ((longitude + 180.0) / 360.0 * scale) to ((0.5 - ln((1.0 + sin) / (1.0 - sin)) / (4.0 * PI)) * scale)
}

private fun unproject(x: Double, y: Double, zoom: Double): Pair<Double, Double> {
    val scale = 256.0 * 2.0.pow(zoom)
    val longitude = x / scale * 360.0 - 180.0
    val n = PI - 2.0 * PI * y / scale
    val latitude = 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
    return latitude to longitude
}

private fun baseStyleJson(): String = """
{
  "version": 8,
  "name": "WeatherMapOSM",
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "attribution": "© OpenStreetMap"
    }
  },
  "layers": [
    {"id": "osm", "type": "raster", "source": "osm"}
  ]
}
""".trimIndent()

private fun codeText(code: Int): String = when (code) {
    0 -> "klar"
    1, 2, 3 -> "bewolkt"
    45, 48 -> "Nebel"
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "Regen"
    71, 73, 75, 77, 85, 86 -> "Schnee"
    95, 96, 99 -> "Gewitter"
    else -> "wechselhaft"
}

private fun formatWeatherTime(value: String): String = runCatching {
    LocalDateTime.parse(value).format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.GERMAN))
}.getOrDefault(value)

private fun formatEpochTime(value: Long): String = java.text.SimpleDateFormat("EEE HH:mm", Locale.GERMAN)
    .format(java.util.Date(value * 1000))

private object MapController {
    var map: MapLibreMap? = null
    fun zoomBy(delta: Double) {
        val map = map ?: return
        val current = map.cameraPosition
        map.animateCamera(CameraUpdateFactory.zoomTo((current.zoom + delta).coerceIn(3.0, 16.0)), 220)
    }

    fun capZoomForRadar() {
        val map = map ?: return
        if (map.cameraPosition.zoom > 7.0) {
            map.animateCamera(CameraUpdateFactory.zoomTo(7.0), 280)
        }
    }
}

private const val RADAR_SOURCE = "radar-source"
private const val RADAR_LAYER = "radar-layer"
private const val TEMP_SOURCE = "temperature-source"
private const val TEMP_LAYER = "temperature-layer"
private const val TEMP_LAYER_OPACITY = 0.46f
private const val TEMP_PIXEL_ALPHA = 155
private const val MIN_VISIBLE_TEMP_SPAN_C = 6.0

private val TEMP_COLOR_STOPS = listOf(
    TempColorStop(-8.0, 37, 99, 235),
    TempColorStop(0.0, 14, 165, 233),
    TempColorStop(8.0, 20, 184, 166),
    TempColorStop(16.0, 34, 197, 94),
    TempColorStop(24.0, 234, 179, 8),
    TempColorStop(30.0, 249, 115, 22),
    TempColorStop(38.0, 220, 38, 38),
)

private val TEMP_CITY_ANCHORS = listOf(
    TempCityAnchor("berlin", "Berlin", 52.5200, 13.4050, 10, isCapital = true),
    TempCityAnchor("hamburg", "Hamburg", 53.5511, 9.9937, 20),
    TempCityAnchor("munich", "Muenchen", 48.1351, 11.5820, 30),
    TempCityAnchor("cologne", "Koeln", 50.9375, 6.9603, 40),
    TempCityAnchor("frankfurt", "Frankfurt", 50.1109, 8.6821, 50),
    TempCityAnchor("stuttgart", "Stuttgart", 48.7758, 9.1829, 60),
    TempCityAnchor("duesseldorf", "Duesseldorf", 51.2277, 6.7735, 70),
    TempCityAnchor("dortmund", "Dortmund", 51.5136, 7.4653, 80),
    TempCityAnchor("leipzig", "Leipzig", 51.3397, 12.3731, 90),
    TempCityAnchor("dresden", "Dresden", 51.0504, 13.7373, 100),
    TempCityAnchor("hannover", "Hannover", 52.3759, 9.7320, 110),
    TempCityAnchor("bremen", "Bremen", 53.0793, 8.8017, 120),
    TempCityAnchor("nuremberg", "Nuernberg", 49.4521, 11.0767, 130),
    TempCityAnchor("paris", "Paris", 48.8566, 2.3522, 10, isCapital = true),
    TempCityAnchor("london", "London", 51.5072, -0.1276, 10, isCapital = true),
    TempCityAnchor("madrid", "Madrid", 40.4168, -3.7038, 10, isCapital = true),
    TempCityAnchor("rome", "Rom", 41.9028, 12.4964, 10, isCapital = true),
    TempCityAnchor("vienna", "Wien", 48.2082, 16.3738, 20, isCapital = true),
    TempCityAnchor("prague", "Prag", 50.0755, 14.4378, 30, isCapital = true),
    TempCityAnchor("warsaw", "Warschau", 52.2297, 21.0122, 20, isCapital = true),
    TempCityAnchor("amsterdam", "Amsterdam", 52.3676, 4.9041, 30, isCapital = true),
    TempCityAnchor("brussels", "Bruessel", 50.8503, 4.3517, 40, isCapital = true),
    TempCityAnchor("copenhagen", "Kopenhagen", 55.6761, 12.5683, 40, isCapital = true),
    TempCityAnchor("stockholm", "Stockholm", 59.3293, 18.0686, 30, isCapital = true),
    TempCityAnchor("oslo", "Oslo", 59.9139, 10.7522, 40, isCapital = true),
    TempCityAnchor("helsinki", "Helsinki", 60.1699, 24.9384, 50, isCapital = true),
    TempCityAnchor("zurich", "Zuerich", 47.3769, 8.5417, 50),
    TempCityAnchor("bern", "Bern", 46.9480, 7.4474, 45, isCapital = true),
    TempCityAnchor("athens", "Athen", 37.9838, 23.7275, 30, isCapital = true),
    TempCityAnchor("lisbon", "Lissabon", 38.7223, -9.1393, 40, isCapital = true),
    TempCityAnchor("dublin", "Dublin", 53.3498, -6.2603, 50, isCapital = true),
    TempCityAnchor("budapest", "Budapest", 47.4979, 19.0402, 40, isCapital = true),
    TempCityAnchor("istanbul", "Istanbul", 41.0082, 28.9784, 20),
    TempCityAnchor("ankara", "Ankara", 39.9334, 32.8597, 25, isCapital = true),
    TempCityAnchor("moscow", "Moskau", 55.7558, 37.6173, 20, isCapital = true),
    TempCityAnchor("washington-dc", "Washington", 38.9072, -77.0369, 15, isCapital = true),
    TempCityAnchor("new-york", "New York", 40.7128, -74.0060, 10),
    TempCityAnchor("los-angeles", "Los Angeles", 34.0522, -118.2437, 20),
    TempCityAnchor("chicago", "Chicago", 41.8781, -87.6298, 30),
    TempCityAnchor("ottawa", "Ottawa", 45.4215, -75.6972, 25, isCapital = true),
    TempCityAnchor("toronto", "Toronto", 43.6532, -79.3832, 30),
    TempCityAnchor("mexico-city", "Mexico City", 19.4326, -99.1332, 20, isCapital = true),
    TempCityAnchor("brasilia", "Brasilia", -15.7939, -47.8828, 25, isCapital = true),
    TempCityAnchor("sao-paulo", "Sao Paulo", -23.5558, -46.6396, 10),
    TempCityAnchor("buenos-aires", "Buenos Aires", -34.6037, -58.3816, 20, isCapital = true),
    TempCityAnchor("santiago", "Santiago", -33.4489, -70.6693, 30, isCapital = true),
    TempCityAnchor("lima", "Lima", -12.0464, -77.0428, 30, isCapital = true),
    TempCityAnchor("bogota", "Bogota", 4.7110, -74.0721, 30, isCapital = true),
    TempCityAnchor("cairo", "Kairo", 30.0444, 31.2357, 20, isCapital = true),
    TempCityAnchor("lagos", "Lagos", 6.5244, 3.3792, 30),
    TempCityAnchor("johannesburg", "Johannesburg", -26.2041, 28.0473, 40),
    TempCityAnchor("pretoria", "Pretoria", -25.7479, 28.2293, 35, isCapital = true),
    TempCityAnchor("nairobi", "Nairobi", -1.2921, 36.8219, 35, isCapital = true),
    TempCityAnchor("riyadh", "Riyadh", 24.7136, 46.6753, 30, isCapital = true),
    TempCityAnchor("abu-dhabi", "Abu Dhabi", 24.4539, 54.3773, 25, isCapital = true),
    TempCityAnchor("dubai", "Dubai", 25.2048, 55.2708, 30),
    TempCityAnchor("tehran", "Tehran", 35.6892, 51.3890, 25, isCapital = true),
    TempCityAnchor("delhi", "Delhi", 28.6139, 77.2090, 10),
    TempCityAnchor("mumbai", "Mumbai", 19.0760, 72.8777, 20),
    TempCityAnchor("beijing", "Beijing", 39.9042, 116.4074, 10, isCapital = true),
    TempCityAnchor("shanghai", "Shanghai", 31.2304, 121.4737, 20),
    TempCityAnchor("tokyo", "Tokyo", 35.6762, 139.6503, 10, isCapital = true),
    TempCityAnchor("seoul", "Seoul", 37.5665, 126.9780, 20, isCapital = true),
    TempCityAnchor("bangkok", "Bangkok", 13.7563, 100.5018, 30, isCapital = true),
    TempCityAnchor("singapore", "Singapore", 1.3521, 103.8198, 30, isCapital = true),
    TempCityAnchor("jakarta", "Jakarta", -6.2088, 106.8456, 20, isCapital = true),
    TempCityAnchor("canberra", "Canberra", -35.2809, 149.1300, 25, isCapital = true),
    TempCityAnchor("sydney", "Sydney", -33.8688, 151.2093, 20),
    TempCityAnchor("melbourne", "Melbourne", -37.8136, 144.9631, 30),
    TempCityAnchor("wellington", "Wellington", -41.2865, 174.7762, 35, isCapital = true),
)
