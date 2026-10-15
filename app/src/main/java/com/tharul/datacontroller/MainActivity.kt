package com.tharul.datacontroller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import java.security.SecureRandom
import java.util.Date

private const val PREFS = "data_controller"
private val Navy = Color(0xFF10233F)
private val Cyan = Color(0xFF27B6D7)
private val Page = Color(0xFFF4F7FB)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createAppNotificationChannel()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 120)
        }
        setContent {
            DataControllerApp(
                onEnableBackground = { role ->
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean("background_enabled", true).putString("role", role).apply()
                    if (role == "target") startBackgroundService()
                    openBatterySettings()
                },
                onRoleChanged = { role ->
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("role", role).apply()
                    if (role == "target" && getSharedPreferences(PREFS, MODE_PRIVATE)
                            .getBoolean("background_enabled", false)) startBackgroundService()
                },
                onBatterySettings = { openBatterySettings() }
            )
        }
    }

    private fun createAppNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    BackgroundCommandService.CHANNEL_ID,
                    "Data Controller background service",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Keeps the target phone listening for paired-device requests." }
            )
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    BackgroundCommandService.COMMAND_CHANNEL_ID,
                    "Remote requests",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Requests received from your paired controller phone." }
            )
        }
    }

    private fun startBackgroundService() {
        val intent = Intent(this, BackgroundCommandService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) {
            // Android may block background starts; user can start it from the app.
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataControllerApp(
    onEnableBackground: (String) -> Unit,
    onRoleChanged: (String) -> Unit,
    onBatterySettings: () -> Unit
) {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }
    var uid by remember { mutableStateOf(auth.currentUser?.uid) }
    var role by remember { mutableStateOf(prefs.getString("role", null)) }
    var code by remember { mutableStateOf(prefs.getString("pairingCode", "") ?: "") }
    var enteredCode by remember { mutableStateOf("") }
    var paired by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connecting to Firebase…") }
    var busy by remember { mutableStateOf(false) }
    var showStartupPrompt by remember { mutableStateOf(!prefs.getBoolean("startup_prompt_seen", false)) }
    var backgroundEnabled by remember { mutableStateOf(prefs.getBoolean("background_enabled", false)) }
    var lastCommand by remember { mutableStateOf("No request sent") }

    LaunchedEffect(Unit) {
        if (auth.currentUser != null) {
            uid = auth.currentUser?.uid
            status = "Connected to Firebase"
        } else {
            auth.signInAnonymously().addOnSuccessListener {
                uid = it.user?.uid
                status = "Connected to Firebase"
            }.addOnFailureListener {
                status = "Firebase sign-in failed: ${it.localizedMessage ?: "check Firebase settings"}"
            }
        }
    }

    DisposableEffect(uid, code) {
        var registration: com.google.firebase.firestore.ListenerRegistration? = null
        if (uid != null && code.matches(Regex("\\d{4}"))) {
            registration = db.collection("pairings").document(code).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    status = "Pairing status unavailable: ${error.localizedMessage ?: "permission denied"}"
                } else if (snapshot != null && snapshot.exists()) {
                    val members = snapshot.get("memberUids") as? List<*>
                    paired = snapshot.getString("state") == "active" && members?.size == 2
                    if (paired) status = "Paired securely with Firebase"
                }
            }
        }
        onDispose { registration?.remove() }
    }

    if (showStartupPrompt) {
        AlertDialog(
            onDismissRequest = {
                showStartupPrompt = false
                prefs.edit().putBoolean("startup_prompt_seen", true).apply()
            },
            title = { Text("Enable auto-run and background processing?") },
            text = {
                Text("Data Controller can keep a foreground service running on the target phone, show a persistent notification, and try to restart after the phone reboots. Enable this on the TARGET phone and allow notifications and battery settings. Android or the phone manufacturer may still stop it.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showStartupPrompt = false
                    prefs.edit().putBoolean("startup_prompt_seen", true).apply()
                    backgroundEnabled = true
                    val selectedRole = role ?: "target"
                    role = selectedRole
                    onEnableBackground(selectedRole)
                    status = "Background operation enabled; finish the Android settings prompt"
                }) { Text("Enable background") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showStartupPrompt = false
                    prefs.edit().putBoolean("startup_prompt_seen", true).apply()
                }) { Text("Not now") }
            }
        )
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Navy, secondary = Cyan, background = Page, surface = Color.White)) {
        Scaffold(containerColor = Page) { padding ->
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = Navy, shape = RoundedCornerShape(16.dp), modifier = Modifier.size(54.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Devices, null, tint = Cyan, modifier = Modifier.size(34.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Data Controller", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Navy)
                        Text("Firebase-connected device pairing", color = Color(0xFF63738A), fontSize = 13.sp)
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("DEVICE STATUS", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(if (paired) "Paired" else "Connect your two phones", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(status, color = Color(0xFFD8E4F2), fontSize = 13.sp)
                    }
                }

                Text("1. Choose this phone's role", color = Navy, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoleButton("This is controller", role == "controller", Modifier.weight(1f)) {
                        role = "controller"; paired = false; status = "Enter the target phone's code"; onRoleChanged("controller")
                    }
                    RoleButton("This is target", role == "target", Modifier.weight(1f)) {
                        role = "target"; paired = false; status = "Create a code to share"; onRoleChanged("target")
                    }
                }

                if (role == "target") {
                    Button(
                        onClick = {
                            val currentUid = uid
                            if (currentUid == null) { status = "Waiting for Firebase sign-in"; return@Button }
                            busy = true
                            status = "Creating a short-lived pairing code…"
                            createPairingCode(db, currentUid) { createdCode, error ->
                                busy = false
                                if (createdCode != null) {
                                    code = createdCode
                                    prefs.edit().putString("pairingCode", createdCode).putString("role", "target").apply()
                                    paired = false
                                    status = "Code ready. Share it within 5 minutes."
                                    if (backgroundEnabled) onRoleChanged("target")
                                } else status = "Could not create code: ${error ?: "try again"}"
                            }
                        },
                        enabled = !busy && uid != null,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(if (busy) "Please wait…" else if (code.isNotEmpty() && !paired) "Generate a new 4-digit code" else "Generate 4-digit pairing code") }

                    if (code.matches(Regex("\\d{4}"))) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("SHARE THIS CODE", color = Color(0xFF63738A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(code, color = Navy, fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp)
                                Text(if (paired) "Controller connected" else "Expires after 5 minutes unless paired", color = Color(0xFF63738A), fontSize = 12.sp)
                            }
                        }
                    }
                    BackgroundCard(backgroundEnabled, onBatterySettings) 
                }

                if (role == "controller") {
                    OutlinedTextField(
                        value = enteredCode,
                        onValueChange = { enteredCode = it.filter(Char::isDigit).take(4) },
                        label = { Text("Target's 4-digit code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val currentUid = uid
                            if (currentUid == null) { status = "Waiting for Firebase sign-in"; return@Button }
                            if (!enteredCode.matches(Regex("\\d{4}"))) { status = "Enter all four digits"; return@Button }
                            busy = true
                            val ref = db.collection("pairings").document(enteredCode)
                            ref.get().addOnSuccessListener { doc ->
                                val expiry = doc.getTimestamp("expiresAt")
                                val members = doc.get("memberUids") as? List<*>
                                if (!doc.exists() || doc.getString("state") != "pending" ||
                                    expiry == null || expiry.toDate().before(Date()) ||
                                    members?.size != 1 || doc.getString("ownerUid") == currentUid) {
                                    busy = false
                                    status = "Code invalid, expired, or already used"
                                } else {
                                    ref.update(
                                        mapOf(
                                            "memberUids" to FieldValue.arrayUnion(currentUid),
                                            "controllerUid" to currentUid,
                                            "state" to "active",
                                            "updatedAt" to FieldValue.serverTimestamp()
                                        )
                                    ).addOnSuccessListener {
                                        busy = false
                                        code = enteredCode
                                        paired = true
                                        prefs.edit().putString("pairingCode", enteredCode).putString("role", "controller").apply()
                                        status = "Pairing complete"
                                    }.addOnFailureListener {
                                        busy = false
                                        status = "Pairing rejected by Firestore rules: ${it.localizedMessage ?: "permission denied"}"
                                    }
                                }
                            }.addOnFailureListener {
                                busy = false
                                status = "Cannot read this code. Check Firestore rules and Auth: ${it.localizedMessage ?: "permission denied"}"
                            }
                        },
                        enabled = !busy && uid != null,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Link, null); Spacer(Modifier.width(8.dp))
                        Text(if (busy) "Connecting…" else "Pair with target")
                    }

                    if (paired && code.matches(Regex("\\d{4}"))) {
                        Text("2. Send a request", color = Navy, fontWeight = FontWeight.Bold)
                        Text("The target receives a notification. The phone owner must tap it to open Android settings; airplane mode cannot be changed silently by a normal app.", color = Color(0xFF63738A), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { sendCommand(db, code, uid, "airplane_on") { message -> status = message; lastCommand = "Airplane ON request" } },
                                modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Airplane ON") }
                            OutlinedButton(onClick = { sendCommand(db, code, uid, "airplane_off") { message -> status = message; lastCommand = "Airplane OFF request" } },
                                modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp)) { Text("Airplane OFF") }
                        }
                        Text("Last request: $lastCommand", color = Color(0xFF63738A), fontSize = 13.sp)
                    }
                }

                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.NotificationsActive, null, tint = Cyan)
                            Spacer(Modifier.width(8.dp))
                            Text("Background processing", color = Navy, fontWeight = FontWeight.Bold)
                        }
                        Text(if (backgroundEnabled) "Enabled on this phone. Keep the persistent notification visible and allow unrestricted battery use for best reliability."
                            else "Enable this on the target phone to keep its command listener running after you leave Recents.",
                            color = Color(0xFF63738A), fontSize = 13.sp)
                        Button(onClick = {
                            backgroundEnabled = true
                            prefs.edit().putBoolean("background_enabled", true).apply()
                            if (role == "target") onEnableBackground("target") else onBatterySettings()
                            status = "Background setting requested"
                        }, modifier = Modifier.fillMaxWidth()) { Text("Enable auto-run / background") }
                        OutlinedButton(onClick = onBatterySettings, modifier = Modifier.fillMaxWidth()) { Text("Open battery settings") }
                    }
                }

                Text("Note: the 4-digit code expires in 5 minutes and is single-use. A short code is convenient but is not as strong as a longer pairing secret.", color = Color(0xFF63738A), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BackgroundCard(enabled: Boolean, onBatterySettings: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (enabled) "Background listener enabled" else "Background listener not enabled", color = Navy, fontWeight = FontWeight.Bold)
            Text("The target phone needs its foreground-service notification to keep listening after the app is swiped away.", color = Color(0xFF63738A), fontSize = 13.sp)
            OutlinedButton(onClick = onBatterySettings, modifier = Modifier.fillMaxWidth()) { Text("Battery / background settings") }
        }
    }
}

@Composable
private fun RoleButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier.height(54.dp), shape = RoundedCornerShape(14.dp)) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier.height(54.dp), shape = RoundedCornerShape(14.dp)) { Text(label) }
}

private fun createPairingCode(db: FirebaseFirestore, uid: String, callback: (String?, String?) -> Unit) {
    val random = SecureRandom()
    fun attempt(remaining: Int) {
        if (remaining <= 0) { callback(null, "No free code found; try again"); return }
        val code = (1000 + random.nextInt(9000)).toString()
        val now = Date()
        val data = hashMapOf<String, Any?>(
            "ownerUid" to uid,
            "controllerUid" to null,
            "memberUids" to listOf(uid),
            "state" to "pending",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "expiresAt" to Timestamp(Date(now.time + 5 * 60 * 1000))
        )
        db.collection("pairings").document(code).set(data)
            .addOnSuccessListener { callback(code, null) }
            .addOnFailureListener {
                // A collision is possible because codes are only four digits.
                attempt(remaining - 1)
            }
    }
    attempt(15)
}

private fun sendCommand(db: FirebaseFirestore, pairingCode: String, senderUid: String?, action: String, callback: (String) -> Unit) {
    if (senderUid == null) { callback("Not signed in"); return }
    val pairRef = db.collection("pairings").document(pairingCode)
    pairRef.get().addOnSuccessListener { pair ->
        val ownerUid = pair.getString("ownerUid")
        val controllerUid = pair.getString("controllerUid")
        if (!pair.exists() || pair.getString("state") != "active" || controllerUid != senderUid || ownerUid == null) {
            callback("Pairing is no longer active"); return@addOnSuccessListener
        }
        val data = hashMapOf<String, Any>(
            "pairingId" to pairingCode,
            "senderUid" to senderUid,
            "targetUid" to ownerUid,
            "action" to action,
            "status" to "pending",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        db.collection("commands").add(data)
            .addOnSuccessListener { callback("Request sent. The target phone should show a notification.") }
            .addOnFailureListener { callback("Request failed: ${it.localizedMessage ?: "check Firestore rules"}") }
    }.addOnFailureListener { callback("Could not read pairing: ${it.localizedMessage ?: "permission denied"}") }
}
