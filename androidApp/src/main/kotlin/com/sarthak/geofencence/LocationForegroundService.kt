package com.sarthak.geofencence

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.sarthak.geofencence.geofence.GeofenceCalculator

class LocationForegroundService : Service() {
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var db: FirebaseFirestore
    private var groupIds: List<String> = emptyList()
    private var locationCallback: LocationCallback? = null
    private val groupListeners = mutableMapOf<String, ListenerRegistration>()
    private val membersListeners = mutableMapOf<String, ListenerRegistration>()
    private val baselineRequiredByGroup = mutableMapOf<String, Boolean>()
    private val geofenceSignatures = mutableMapOf<String, String>()

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        db = FirebaseFirestore.getInstance()
        NotificationHelper.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val servicePrefs = getSharedPreferences("geofence_app", Context.MODE_PRIVATE)
        val requestedGroup = intent?.getStringExtra("groupId") ?: servicePrefs.getString("last_group_id", null)
        groupIds = (servicePrefs.getStringSet("my_group_ids", emptySet()).orEmpty() + listOfNotNull(requestedGroup))
            .filter(String::isNotBlank).distinct()
        if (groupIds.isEmpty() || FirebaseAuth.getInstance().currentUser == null) { stopSelf(); return START_NOT_STICKY }
        requestedGroup?.let { servicePrefs.edit().putString("last_group_id", it).apply() }
        TrackingState.active = true
        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setContentTitle("Geofence tracking active")
            .setContentText("Location sharing and local group exit alerts are active")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(101, notification)
        startUpdates()
        observeGroupAndMembers()
        return START_STICKY
    }

    @Suppress("MissingPermission")
    private fun startUpdates() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L).setMinUpdateDistanceMeters(30f).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val updates = mapOf("latitude" to location.latitude, "longitude" to location.longitude,
                    "lastLocationAt" to FieldValue.serverTimestamp(), "isTracking" to true)
                groupIds.forEach { id ->
                    db.collection("groups").document(id).collection("members").document(uid).update(updates)
                }
            }
        }
        fused.requestLocationUpdates(req, locationCallback!!, mainLooper).addOnFailureListener { stopSelf() }
    }

    private fun observeGroupAndMembers() {
        groupListeners.values.forEach { it.remove() }; groupListeners.clear()
        membersListeners.values.forEach { it.remove() }; membersListeners.clear()
        groupIds.forEach { groupId ->
        val groupListener = db.collection("groups").document(groupId).addSnapshotListener { group, error ->
            if (error != null || group == null || !group.exists()) return@addSnapshotListener
            val centerLat = group.getDouble("centerLatitude") ?: return@addSnapshotListener
            val centerLon = group.getDouble("centerLongitude") ?: return@addSnapshotListener
            val radius = group.getDouble("radiusMeters") ?: return@addSnapshotListener
            val signature = "$centerLat|$centerLon|$radius"
            if (signature != geofenceSignatures[groupId]) {
                geofenceSignatures[groupId] = signature
                val prefs = getSharedPreferences("geofence_member_status", Context.MODE_PRIVATE)
                val signatureKey = "boundary_$groupId"
                baselineRequiredByGroup[groupId] = prefs.getString(signatureKey, null) != signature
                prefs.edit().putString(signatureKey, signature).apply()
            }
            membersListeners.remove(groupId)?.remove()
            val memberListener = db.collection("groups").document(groupId).collection("members")
                .addSnapshotListener { members, memberError ->
                    if (memberError != null || members == null) return@addSnapshotListener
                    val self = FirebaseAuth.getInstance().currentUser?.uid
                    val prefs = getSharedPreferences("geofence_member_status", Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    for (member in members.documents) {
                        val lat = member.getDouble("latitude") ?: continue
                        val lon = member.getDouble("longitude") ?: continue
                        val nowInside = GeofenceCalculator.isInside(lat, lon, centerLat, centerLon, radius)
                        val key = "${groupId}_${member.id}"
                        val hadStatus = prefs.contains(key)
                        val wasInside = prefs.getBoolean(key, nowInside)
                        if (baselineRequiredByGroup[groupId] != true && hadStatus && wasInside && !nowInside && member.id != self) {
                            NotificationHelper.showExit(this, member.getString("displayName") ?: "A member", group.getString("groupName") ?: "your group", groupId)
                        }
                        editor.putBoolean(key, nowInside)
                    }
                    editor.apply()
                    baselineRequiredByGroup[groupId] = false
                }
            membersListeners[groupId] = memberListener
        }
        groupListeners[groupId] = groupListener
        }
    }

    override fun onDestroy() {
        locationCallback?.let { fused.removeLocationUpdates(it) }
        groupListeners.values.forEach { it.remove() }; membersListeners.values.forEach { it.remove() }
        TrackingState.active = false
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

object NotificationHelper {
    const val CHANNEL_ID = "geofence_tracking"
    private const val ALERT_CHANNEL_ID = "geofence_alerts"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Geofence tracking", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID, "Geofence alerts", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    fun showExit(context: Context, displayName: String, groupName: String, groupId: String) {
        NotificationHistory.add(context, LocalAlert(displayName, groupName, groupId, System.currentTimeMillis()))
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Geofence Alert")
            .setContentText("$displayName has left the geofence for $groupName.")
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
