package com.sarthak.geofencence

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.sarthak.geofencence.geofence.GeofenceCalculator
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.UUID
import java.util.Locale
import kotlin.math.roundToInt

private enum class Page { GROUPS, NOTIFICATIONS, ACTIONS, GROUP_DETAIL, MAP }
private enum class GroupAction { CREATE, JOIN }
private data class MemberRow(val name: String, val status: String)
private data class GroupSummary(val id: String, val name: String)

@Composable
private fun NavGlyph(kind: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(24.dp)) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
        val stroke = Stroke(width = 1.8f * sx)
        when (kind) {
            "groups" -> {
                drawCircle(color, 2.3f * sx, p(8.5f, 7f), style = stroke)
                drawCircle(color, 2f * sx, p(16f, 8f), style = stroke)
                val left = Path().apply { moveTo(3f * sx, 19f * sy); cubicTo(3f * sx, 15f * sy, 5f * sx, 13f * sy, 9f * sx, 13f * sy); cubicTo(13f * sx, 13f * sy, 15f * sx, 15f * sy, 15f * sx, 19f * sy) }
                val right = Path().apply { moveTo(14f * sx, 14f * sy); cubicTo(18f * sx, 13f * sy, 21f * sx, 15f * sy, 21f * sx, 19f * sy) }
                drawPath(left, color, style = stroke); drawPath(right, color, style = stroke)
            }
            "notifications" -> {
                val bell = Path().apply {
                    moveTo(5f * sx, 17f * sy); lineTo(6.5f * sx, 15.5f * sy); lineTo(6.5f * sx, 10f * sy)
                    cubicTo(6.5f * sx, 6f * sy, 8.5f * sx, 4f * sy, 12f * sx, 4f * sy); cubicTo(15.5f * sx, 4f * sy, 17.5f * sx, 6f * sy, 17.5f * sx, 10f * sy)
                    lineTo(17.5f * sx, 15.5f * sy); lineTo(19f * sx, 17f * sy); close()
                }
                drawPath(bell, color, style = stroke)
                drawLine(color, p(9f, 20f), p(15f, 20f), stroke.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            else -> {
                drawLine(color, p(12f, 5f), p(12f, 19f), stroke.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, p(5f, 12f), p(19f, 12f), stroke.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}
data class LocalAlert(val memberName: String, val groupName: String, val groupId: String, val timestamp: Long)

object TrackingState {
    var active by mutableStateOf(false)
}

object NotificationHistory {
    val entries = mutableStateListOf<LocalAlert>()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val saved = context.getSharedPreferences("geofence_notifications", Context.MODE_PRIVATE).getString("entries", "[]")
        runCatching {
            val array = JSONArray(saved)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                entries.add(LocalAlert(item.optString("member"), item.optString("group"), item.optString("groupId"), item.optLong("time")))
            }
        }
    }

    fun add(context: Context, alert: LocalAlert) {
        load(context)
        entries.add(0, alert)
        while (entries.size > 100) entries.removeAt(entries.lastIndex)
        val array = JSONArray()
        entries.forEach { item ->
            array.put(JSONObject().put("member", item.memberName).put("group", item.groupName).put("groupId", item.groupId).put("time", item.timestamp))
        }
        context.getSharedPreferences("geofence_notifications", Context.MODE_PRIVATE).edit().putString("entries", array.toString()).apply()
    }
}

class MainActivity : ComponentActivity() {
    private var pendingTrackingGroup: String? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val locationGranted = hasLocationPermission()
        val groupId = pendingTrackingGroup
        pendingTrackingGroup = null
        if (locationGranted && groupId != null) startTracking(groupId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.createChannel(this)
        setContent { GeofenceApp(onPermissions = { groupId -> requestPermissionsForTracking(groupId) }) }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsForTracking(groupId: String) {
        val missing = mutableListOf<String>()
        if (!hasLocationPermission()) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isEmpty()) {
            startTracking(groupId)
        } else {
            pendingTrackingGroup = groupId
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startTracking(groupId: String) {
        if (!hasLocationPermission()) return
        ContextCompat.startForegroundService(this, Intent(this, LocationForegroundService::class.java).putExtra("groupId", groupId))
    }
}

@Composable
private fun GeofenceApp(onPermissions: (String) -> Unit) {
    val context = LocalContext.current
    val localSettings = remember { context.getSharedPreferences("geofence_app", Context.MODE_PRIVATE) }
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    var uid by remember { mutableStateOf(auth.currentUser?.uid) }
    var page by remember { mutableStateOf(Page.GROUPS) }
    var mapReturnPage by remember { mutableStateOf(Page.ACTIONS) }
    var groupAction by remember { mutableStateOf(GroupAction.CREATE) }
    var myGroups by remember { mutableStateOf<List<GroupSummary>>(emptyList()) }
    var displayName by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }
    var groupId by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf("28.4595") }
    var longitude by remember { mutableStateOf("77.0266") }
    var radius by remember { mutableStateOf("500") }
    var isCreator by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val tracking = TrackingState.active
    var members by remember { mutableStateOf<List<MemberRow>>(emptyList()) }
    var memberListener by remember { mutableStateOf<ListenerRegistration?>(null) }
    var groupListener by remember { mutableStateOf<ListenerRegistration?>(null) }

    fun watchGroup(id: String) {
        memberListener?.remove(); groupListener?.remove()
        groupId = id.uppercase()
        localSettings.edit().putString("last_group_id", groupId).apply()
        groupListener = db.collection("groups").document(groupId).addSnapshotListener { group, error ->
            if (error != null) { message = "Could not load group: ${error.message}"; return@addSnapshotListener }
            if (group == null || !group.exists()) { message = "This group is no longer available."; return@addSnapshotListener }
            groupName = group.getString("groupName").orEmpty()
            val summary = GroupSummary(groupId, groupName.ifBlank { "Group $groupId" })
            myGroups = (myGroups.filterNot { it.id == groupId } + summary).sortedBy { it.name.lowercase() }
            val storedIds = (localSettings.getStringSet("my_group_ids", emptySet()) ?: emptySet()).toMutableSet()
            storedIds.add(groupId)
            localSettings.edit().putStringSet("my_group_ids", storedIds).putString("group_name_$groupId", summary.name).apply()
            latitude = group.getDouble("centerLatitude")?.toString().orEmpty()
            longitude = group.getDouble("centerLongitude")?.toString().orEmpty()
            radius = group.getDouble("radiusMeters")?.toString().orEmpty()
            isCreator = group.getString("createdBy") == uid
            memberListener?.remove()
            memberListener = db.collection("groups").document(groupId).collection("members")
                .addSnapshotListener { snapshot, memberError ->
                    if (memberError != null) { message = "Could not load members: ${memberError.message}"; return@addSnapshotListener }
                    val centerLat = latitude.toDoubleOrNull(); val centerLon = longitude.toDoubleOrNull(); val radiusValue = radius.toDoubleOrNull()
                    members = snapshot?.documents?.map { doc ->
                        val lat = doc.getDouble("latitude"); val lon = doc.getDouble("longitude")
                        val isInside = if (lat != null && lon != null && centerLat != null && centerLon != null && radiusValue != null)
                            GeofenceCalculator.isInside(lat, lon, centerLat, centerLon, radiusValue) else null
                        MemberRow(doc.getString("displayName") ?: "Member", when (isInside) {
                            true -> "Inside"; false -> "Outside"; null -> "Waiting for location"
                        })
                    }.orEmpty()
                }
        }
    }

    LaunchedEffect(Unit) {
        NotificationHistory.load(context)
        try {
            uid = auth.currentUser?.uid ?: auth.signInAnonymously().await().user?.uid
            val savedIds = localSettings.getStringSet("my_group_ids", emptySet()).orEmpty()
            myGroups = savedIds.map { id -> GroupSummary(id, localSettings.getString("group_name_$id", "Group $id") ?: "Group $id") }
                .sortedBy { it.name.lowercase() }
            // Migrate the previously saved single group into the new local group list.
            localSettings.getString("last_group_id", null)?.let { savedId ->
                if (myGroups.none { it.id == savedId }) {
                    val ids = savedIds.toMutableSet().apply { add(savedId) }
                    localSettings.edit().putStringSet("my_group_ids", ids).apply()
                    myGroups = myGroups + GroupSummary(savedId, localSettings.getString("group_name_$savedId", "Group $savedId") ?: "Group $savedId")
                }
            }
        } catch (e: Exception) { message = "Sign-in failed: ${e.message ?: "Check Firebase setup."}" }
    }

    fun rememberGroup(id: String, name: String) {
        val normalizedId = id.uppercase()
        val summary = GroupSummary(normalizedId, name.ifBlank { "Group $normalizedId" })
        myGroups = (myGroups.filterNot { it.id == normalizedId } + summary).sortedBy { it.name.lowercase() }
        val ids = (localSettings.getStringSet("my_group_ids", emptySet()) ?: emptySet()).toMutableSet()
        ids.add(normalizedId)
        localSettings.edit().putStringSet("my_group_ids", ids).putString("group_name_$normalizedId", summary.name).apply()
    }

    fun validateGeofence(): Triple<Double, Double, Double>? {
        val lat = latitude.toDoubleOrNull() ?: return null
        val lon = longitude.toDoubleOrNull() ?: return null
        val rad = radius.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || rad <= 0 || rad > 100_000) return null
        return Triple(lat, lon, rad)
    }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                if (page != Page.MAP) NavigationBar {
                    NavigationBarItem(selected = page == Page.GROUPS || page == Page.GROUP_DETAIL,
                        onClick = { page = Page.GROUPS }, icon = { NavGlyph("groups") }, label = { Text("Groups") })
                    NavigationBarItem(selected = page == Page.NOTIFICATIONS,
                        onClick = { page = Page.NOTIFICATIONS }, icon = { NavGlyph("notifications") }, label = { Text("Notifications") })
                    NavigationBarItem(selected = page == Page.ACTIONS,
                        onClick = { page = Page.ACTIONS }, icon = { NavGlyph("add") }, label = { Text("Create / Join") })
                }
            }
        ) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (page == Page.MAP || page == Page.GROUP_DETAIL) {
                TextButton(onClick = { page = if (page == Page.MAP) mapReturnPage else Page.GROUPS }) { Text("← Back") }
            }

            when (page) {
                Page.GROUPS -> {
                    Text("Your groups", style = MaterialTheme.typography.headlineSmall)
                    Text("Tap a group to see its members and status.", style = MaterialTheme.typography.bodyMedium)
                    if (myGroups.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("No groups yet. Use Create / Join to get started.")
                    } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(myGroups, key = { it.id }) { group ->
                            Card(onClick = {
                                message = ""
                                watchGroup(group.id)
                                page = Page.GROUP_DETAIL
                            }, modifier = Modifier.fillMaxWidth()) {
                                ListItem(headlineContent = { Text(group.name) }, supportingContent = { Text("Group code: ${group.id}") }, trailingContent = { Text("›") })
                            }
                        }
                    }
                }
                Page.ACTIONS -> {
                    Text("Create or join a group", style = MaterialTheme.typography.headlineSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = groupAction == GroupAction.CREATE, onClick = { groupAction = GroupAction.CREATE }, label = { Text("Create") })
                        FilterChip(selected = groupAction == GroupAction.JOIN, onClick = { groupAction = GroupAction.JOIN }, label = { Text("Join") })
                    }
                    if (groupAction == GroupAction.CREATE) {
                    Text("Create a group", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(displayName, { displayName = it }, label = { Text("Your name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(groupName, { groupName = it }, label = { Text("Group name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("Geofence center: ${latitude}, ${longitude} · ${radius} m")
                    OutlinedButton(onClick = { mapReturnPage = Page.ACTIONS; page = Page.MAP }, modifier = Modifier.fillMaxWidth()) { Text("Choose pin and radius") }
                    Button(enabled = !busy, onClick = {
                        val name = displayName.trim(); val nameOfGroup = groupName.trim(); val geo = validateGeofence(); val userId = uid
                        if (name.isBlank() || name.length > 60 || nameOfGroup.isBlank() || nameOfGroup.length > 60) { message = "Enter your name and a group name (1–60 characters each)."; return@Button }
                        if (geo == null || userId == null) { message = "Choose a valid center and radius first."; return@Button }
                        val id = UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
                        busy = true
                        scope.launch { try {
                            val group = db.collection("groups").document(id)
                            val batch = db.batch()
                            batch.set(group, mapOf("groupId" to id, "groupName" to nameOfGroup, "createdBy" to userId, "centerLatitude" to geo.first, "centerLongitude" to geo.second, "radiusMeters" to geo.third, "createdAt" to FieldValue.serverTimestamp()))
                            batch.set(db.collection("joinCodes").document(id), mapOf("groupId" to id))
                            batch.set(group.collection("members").document(userId), mapOf("userId" to userId, "displayName" to name, "isTracking" to false))
                            batch.commit().await()
                            rememberGroup(id, nameOfGroup); watchGroup(id); page = Page.GROUP_DETAIL; message = "Group created. Share code $id with your members."
                        } catch (e: FirebaseFirestoreException) {
                            message = if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                "Firebase denied group creation. Publish this app's firestore.rules to project geofence-e93a8 (Firestore → Rules), then retry."
                            } else "Could not create group (${e.code}): ${e.message}"
                        } catch (e: Exception) { message = "Could not create group: ${e.message}" } finally { busy = false } }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Create group") }
                    } else {
                    Text("Join a group", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(displayName, { displayName = it }, label = { Text("Your name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(groupId, { groupId = it.uppercase() }, label = { Text("12-character group code") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(enabled = !busy && uid != null, onClick = {
                        val name = displayName.trim(); val id = groupId.trim().uppercase(); val userId = uid
                        if (name.isBlank() || name.length > 60 || userId == null || !id.matches(Regex("[A-F0-9]{12}"))) { message = "Enter your name (up to 60 characters) and a valid group code."; return@Button }
                        busy = true; scope.launch { try {
                            val code = db.collection("joinCodes").document(id).get().await()
                            val targetId = code.getString("groupId") ?: error("Group code not found")
                            db.collection("groups").document(targetId).collection("members").document(userId)
                                .set(mapOf("userId" to userId, "displayName" to name, "isTracking" to false), SetOptions.merge()).await()
                            val joinedGroup = db.collection("groups").document(targetId).get().await()
                            val joinedName = joinedGroup.getString("groupName") ?: "Group $targetId"
                            rememberGroup(targetId, joinedName); watchGroup(targetId); page = Page.GROUP_DETAIL; message = "Joined ${joinedName}."
                        } catch (e: Exception) { message = "Could not join group: ${e.message}" } finally { busy = false } }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Join group") }
                }
                }
                Page.GROUP_DETAIL -> {
                    Text(groupName.ifBlank { "Your group" }, style = MaterialTheme.typography.headlineSmall)
                    Text("Group code: $groupId")
                    Text("Geofence: ${latitude}, ${longitude} · ${radius} m")
                    if (isCreator) OutlinedButton(onClick = { mapReturnPage = Page.GROUP_DETAIL; page = Page.MAP }, modifier = Modifier.fillMaxWidth()) { Text("Adjust pin or radius") }
                    Text("Tracking: ${if (tracking) "Active" else "Stopped"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !tracking, onClick = {
                            onPermissions(groupId)
                            message = "Starting location sharing for your saved groups."
                        }, modifier = Modifier.weight(1f)) { Text("Start tracking") }
                        OutlinedButton(enabled = tracking, onClick = { context.stopService(Intent(context, LocationForegroundService::class.java)); message = "Tracking stopped." }, modifier = Modifier.weight(1f)) { Text("Stop") }
                    }
                    Text("Members", style = MaterialTheme.typography.titleMedium)
                    if (members.isEmpty()) Text("No members yet.") else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(members) { member -> ListItem(headlineContent = { Text(member.name) }, supportingContent = { Text(member.status) }) }
                    }
                }
                Page.MAP -> {
                    Text("Pin the geofence center", style = MaterialTheme.typography.headlineSmall)
                    val lat = latitude.toDoubleOrNull() ?: 28.4595
                    val lon = longitude.toDoubleOrNull() ?: 77.0266
                    val radiusValue = radius.toDoubleOrNull() ?: 500.0
                    GeofenceMapPicker(lat, lon, radiusValue, onPinChange = { newLat, newLon -> latitude = String.format(Locale.US, "%.6f", newLat); longitude = String.format(Locale.US, "%.6f", newLon) }, modifier = Modifier.fillMaxWidth().weight(1f))
                    MapPickerInstructions(radiusValue)
                    OutlinedTextField(radius, { input -> if (input.isEmpty() || input.toDoubleOrNull() != null) radius = input }, label = { Text("Radius in meters") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Slider(value = radiusValue.coerceIn(100.0, 5_000.0).toFloat(), onValueChange = { radius = it.roundToInt().toString() }, valueRange = 100f..5_000f)
                    Button(onClick = {
                        if (validateGeofence() == null) { message = "Enter valid coordinates and a positive radius."; return@Button }
                        if (mapReturnPage == Page.GROUP_DETAIL) {
                            val geo = validateGeofence()!!
                            busy = true; scope.launch { try {
                                db.collection("groups").document(groupId).update(mapOf("centerLatitude" to geo.first, "centerLongitude" to geo.second, "radiusMeters" to geo.third)).await()
                                message = "Geofence updated."; page = Page.GROUP_DETAIL
                            } catch (e: Exception) { message = "Could not save settings: ${e.message}" } finally { busy = false } }
                        } else page = mapReturnPage
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (mapReturnPage == Page.GROUP_DETAIL) "Save geofence" else "Use this location") }
                }
                Page.NOTIFICATIONS -> {
                    Text("Notifications", style = MaterialTheme.typography.headlineSmall)
                    Text("Alerts received on this device")
                    if (NotificationHistory.entries.isEmpty()) Text("No exit alerts yet.") else LazyColumn(Modifier.weight(1f)) {
                        items(NotificationHistory.entries) { alert ->
                            ListItem(
                                headlineContent = { Text("${alert.memberName} left ${alert.groupName}") },
                                supportingContent = { Text(DateFormat.getDateTimeInstance().format(alert.timestamp) + " · " + alert.groupId) }
                            )
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        }
    }
}
