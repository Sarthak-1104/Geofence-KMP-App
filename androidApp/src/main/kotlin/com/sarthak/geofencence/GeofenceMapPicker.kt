package com.sarthak.geofencence

import android.graphics.Color
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

private data class PickerMap(val view: MapView, val marker: Marker, val circle: Polygon)

@Composable
fun GeofenceMapPicker(
    latitude: Double,
    longitude: Double,
    radiusMeters: Double,
    onPinChange: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentOnPinChange = rememberUpdatedState(onPinChange)
    val pickerMap = remember(context) {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = context.packageName
        val map = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(latitude, longitude))
        }
        val marker = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setDraggable(true)
            icon = context.getDrawable(android.R.drawable.ic_menu_mylocation)
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(marker: Marker) = Unit
                override fun onMarkerDragStart(marker: Marker) = Unit
                override fun onMarkerDragEnd(marker: Marker) {
                    currentOnPinChange.value(marker.position.latitude, marker.position.longitude)
                }
            })
        }
        val circle = Polygon(map).apply {
            fillPaint.color = Color.argb(45, 30, 120, 210)
            outlinePaint.color = Color.rgb(25, 105, 185)
            outlinePaint.strokeWidth = 3f
        }
        map.overlays.add(circle)
        map.overlays.add(marker)
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                currentOnPinChange.value(point.latitude, point.longitude)
                return true
            }
            override fun longPressHelper(point: GeoPoint): Boolean = false
        }))
        PickerMap(map, marker, circle)
    }
    DisposableEffect(pickerMap.view) {
        pickerMap.view.onResume()
        onDispose { pickerMap.view.onPause(); pickerMap.view.onDetach() }
    }
    Box(modifier) {
        AndroidView(
            factory = { pickerMap.view },
            modifier = Modifier.fillMaxSize(),
            update = { map ->
                val center = GeoPoint(latitude, longitude)
                pickerMap.marker.position = center
                pickerMap.circle.points = Polygon.pointsAsCircle(center, radiusMeters.coerceAtLeast(1.0))
                map.invalidate()
            }
        )
        Text("© OpenStreetMap contributors", modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
            .background(ComposeColor.White.copy(alpha = 0.88f)).padding(horizontal = 5.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun MapPickerInstructions(radiusMeters: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Tap the map or drag the pin to choose the center.", style = MaterialTheme.typography.bodyMedium)
        Text("The circle previews the ${radiusMeters.toInt()} m radius.", style = MaterialTheme.typography.bodySmall)
    }
}
