package com.tharul.datacontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class BackgroundCommandService : Service() {
    private var commandListener: ListenerRegistration? = null
    private var listenerUid: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.wifi)
            .setContentTitle("Data Controller is active")
            .setContentText("Listening for requests from your paired controller")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val auth = FirebaseAuth.getInstance()
        val current = auth.currentUser
        if (current != null) {
            attachListener(current.uid)
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { result -> result.user?.uid?.let { attachListener(it) } }
                .addOnFailureListener { stopSelf() }
        }
        return START_STICKY
    }

    private fun attachListener(uid: String) {
        if (listenerUid == uid && commandListener != null) return
        commandListener?.remove()
        listenerUid = uid
        commandListener = FirebaseFirestore.getInstance()
            .collection("commands")
            .whereEqualTo("targetUid", uid)
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                for (change in snapshot.documentChanges) {
                    if (change.type != com.google.firebase.firestore.DocumentChange.Type.ADDED) continue
                    val document = change.document
                    if (document.getString("status") != "pending") continue
                    val action = document.getString("action") ?: "request"
                    postCommandNotification(document.id, action)
                    document.reference.update(
                        mapOf("status" to "received", "updatedAt" to FieldValue.serverTimestamp())
                    )
                }
            }
    }

    private fun postCommandNotification(commandId: String, action: String) {
        val title = when (action) {
            "airplane_on" -> "Controller requests Airplane Mode ON"
            "airplane_off" -> "Controller requests Airplane Mode OFF"
            else -> "Request from your paired phone"
        }
        val settingsIntent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        val pending = PendingIntent.getActivity(
            this,
            commandId.hashCode(),
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, COMMAND_CHANNEL_ID)
            .setSmallIcon(R.drawable.wifi)
            .setContentTitle(title)
            .setContentText("Tap to open settings. You remain in control of this phone.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(commandId.hashCode(), notification)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background service", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(COMMAND_CHANNEL_ID, "Remote requests", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the foreground service alive when the app is swiped from Recents.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        commandListener?.remove()
        commandListener = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "data_controller_background"
        const val COMMAND_CHANNEL_ID = "data_controller_commands"
        private const val NOTIFICATION_ID = 701
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("data_controller", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("background_enabled", false) || prefs.getString("role", null) != "target") return
        try {
            val serviceIntent = Intent(context, BackgroundCommandService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
        } catch (_: Exception) {
            // Some Android versions and manufacturers restrict boot-time service starts.
        }
    }
}
