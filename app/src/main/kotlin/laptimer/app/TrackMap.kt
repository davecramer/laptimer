package laptimer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import laptimer.core.Bounds
import laptimer.core.LatLon
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * OpenFreeMap's street style: free, no API key, commercial use allowed.
 * MapLibre shows the attribution it requires.
 */
const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** A line to draw on the map. Later lines are drawn on top. */
data class MapLine(val points: List<LatLon>, val color: String, val widthPx: Float)

/** A dot to draw on the map. */
data class MapDot(val point: LatLon, val color: String)

/** A request to move the map. [seq] changes on every request, so the same target can be shown twice. */
data class CameraMove(val seq: Int, val bounds: Bounds? = null, val center: LatLon? = null, val zoom: Double = 15.0)

private const val SOURCE_ID = "laptimer-overlay"

@Composable
fun TrackMap(
    lines: List<MapLine>,
    dots: List<MapDot>,
    camera: CameraMove?,
    onTap: (LatLon) -> Unit,
    onVisibleBoundsChanged: (Bounds) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnBounds by rememberUpdatedState(onVisibleBoundsChanged)

    DisposableEffect(lifecycleOwner) {
        mapView.onCreate(null)
        mapView.getMapAsync { m ->
            m.uiSettings.isTiltGesturesEnabled = false
            m.addOnMapClickListener { p ->
                currentOnTap(LatLon(p.latitude, p.longitude))
                true
            }
            m.addOnCameraIdleListener {
                val b = m.projection.visibleRegion.latLngBounds
                currentOnBounds(Bounds(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast))
            }
            m.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { s ->
                addOverlay(s)
                style = s
            }
            map = m
        }
        // Adding the observer replays the events up to the current state,
        // so a map created while the screen is already resumed starts too.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val state = lifecycleOwner.lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (state.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(style, lines, dots) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(toFeatures(lines, dots))
    }

    LaunchedEffect(map, camera) {
        val m = map ?: return@LaunchedEffect
        val c = camera ?: return@LaunchedEffect
        when {
            c.bounds != null -> {
                val b = c.bounds
                // A single point has no extent, which newLatLngBounds cannot fit.
                if (b.north - b.south < 1e-6 && b.east - b.west < 1e-6) {
                    m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(b.north, b.east), c.zoom))
                } else {
                    m.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(b.north, b.east, b.south, b.west), 60))
                }
            }
            c.center != null -> m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(c.center.lat, c.center.lon), c.zoom))
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun addOverlay(style: Style) {
    style.addSource(GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(emptyList())))
    style.addLayer(
        LineLayer("laptimer-lines", SOURCE_ID)
            .withFilter(Expression.eq(Expression.geometryType(), Expression.literal("LineString")))
            .withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.lineWidth(Expression.get("width")),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ),
    )
    style.addLayer(
        CircleLayer("laptimer-dots", SOURCE_ID)
            .withFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point")))
            .withProperties(
                PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
    )
}

private fun toFeatures(lines: List<MapLine>, dots: List<MapDot>): FeatureCollection {
    val features = ArrayList<Feature>(lines.size + dots.size)
    for (l in lines) {
        if (l.points.size < 2) continue
        val f = Feature.fromGeometry(LineString.fromLngLats(l.points.map { Point.fromLngLat(it.lon, it.lat) }))
        f.addStringProperty("color", l.color)
        f.addNumberProperty("width", l.widthPx)
        features += f
    }
    for (d in dots) {
        val f = Feature.fromGeometry(Point.fromLngLat(d.point.lon, d.point.lat))
        f.addStringProperty("color", d.color)
        features += f
    }
    return FeatureCollection.fromFeatures(features)
}
