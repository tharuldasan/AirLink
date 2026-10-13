package com.tharul.datacontroller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.SecureRandom

private val Navy = Color(0xFF10233F)
private val Cyan = Color(0xFF27B6D7)
private val Page = Color(0xFFF4F7FB)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DataControllerApp(
            onBatterySettings = {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
            onAirplaneSettings = {
                // Android does not provide a universal API for changing airplane mode.
                // Open the device settings so the target owner can make the change.
                val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                try { startActivity(intent) } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        ) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataControllerApp(
    onBatterySettings: () -> Unit,
    onAirplaneSettings: () -> Unit
) {
    var role by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var paired by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Not paired") }
    var lastCommand by remember { mutableStateOf("No commands sent") }
    var showHelp by remember { mutableStateOf(false) }
    var showStartupPrompt by remember { mutableStateOf(true) }
    val localPairCode = remember { (1000 + SecureRandom().nextInt(9000)).toString() }

    if (showStartupPrompt) {
        AlertDialog(
            onDismissRequest = { showStartupPrompt = false },
            title = { Text("Allow background operation?") },
            text = {
                Text("Android may pause background activity to save battery. Review battery settings and your phone manufacturer's Auto-start or Background activity settings. These can improve reliability but cannot guarantee uninterrupted operation.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showStartupPrompt = false
                    onBatterySettings()
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showStartupPrompt = false }) { Text("Later") }
            },
            properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
        )
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Navy,
            secondary = Cyan,
            background = Page,
            surface = Color.White
        )
    ) {
        Scaffold(containerColor = Page) { padding ->
            Column(
                modifier = Modifier.padding(padding).fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
                        Text("Remote device companion", color = Color(0xFF63738A), fontSize = 13.sp)
                    }
                    Icon(Icons.Rounded.Devices, null, tint = Navy, modifier = Modifier.size(28.dp))
                }

                Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("CONTROL YOUR PAIRED DEVICE", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(if (paired) "Device paired" else "Connect your two phones",
                            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (paired) "Pairing is currently saved only in this demo UI."
                            else "Install this same app on both phones and select their roles below.",
                            color = Color(0xFFD8E4F2), fontSize = 14.sp
                        )
                    }
                }

                SectionTitle("1", "Choose this phone's role")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoleButton("This is controller", role == "controller", Modifier.weight(1f)) {
                        role = "controller"; paired = false; status = "Enter the target phone's code"
                    }
                    RoleButton("This is target", role == "target", Modifier.weight(1f)) {
                        role = "target"; paired = false; status = "Share this code with your controller"
                    }
                }

                if (role == "target") {
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("YOUR PAIRING CODE", color = Color(0xFF63738A), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(localPairCode, color = Navy, fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp)
                            Text("Demo code only: it changes after restart and is not yet stored in Firebase.",
                                color = Color(0xFF63738A), fontSize = 12.sp)
                        }
                    }
                }

                if (role == "controller") {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(4); status = "Code entered: ${code.length}/4 digits" },
                        label = { Text("Enter target's 4-digit code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Button(
                    onClick = {
                        if (role == null) status = "Choose Controller or Target first"
                        else if (role == "controller" && code.length != 4) status = "Enter all four digits"
                        else {
                            paired = true
                            status = if (role == "controller")
                                "Demo paired. Firebase setup is still required."
                            else "Target role selected. Firebase setup is still required."
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Link, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (paired) "Paired (demo)" else "Pair device", fontWeight = FontWeight.Bold)
                }

                SectionTitle("2", "Airplane-mode requests")
                Text(
                    "These buttons are a safe demo until Firebase is configured. A normal Android app cannot silently switch airplane mode across all phone brands.",
                    color = Color(0xFF63738A), fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { lastCommand = "ON request"; status = "Opening settings for owner confirmation"; onAirplaneSettings() },
                        modifier = Modifier.weight(1f).height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Navy),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Airplane ON") }
                    OutlinedButton(
                        onClick = { lastCommand = "OFF request"; status = "Opening settings for owner confirmation"; onAirplaneSettings() },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Airplane OFF") }
                }

                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.NotificationsActive, null, tint = Cyan)
                            Spacer(Modifier.width(8.dp))
                            Text("Background operation", fontWeight = FontWeight.Bold, color = Navy)
                        }
                        Text("Android may pause background work to save battery. You can open battery settings, but the system prompt must be approved by the phone owner.",
                            fontSize = 13.sp, color = Color(0xFF63738A))
                        OutlinedButton(onClick = onBatterySettings, modifier = Modifier.fillMaxWidth()) {
                            Text("Configure battery settings")
                        }
                    }
                }

                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Current status", fontWeight = FontWeight.Bold, color = Navy)
                        Text(status, color = Color(0xFF63738A), fontSize = 13.sp)
                        Text("Last request: $lastCommand", color = Color(0xFF63738A), fontSize = 13.sp)
                    }
                }

                TextButton(onClick = { showHelp = !showHelp }) {
                    Icon(Icons.Rounded.Settings, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (showHelp) "Hide setup notes" else "Cloud setup notes")
                }
                if (showHelp) {
                    Text(
                        "Create a Firebase project, register Android package com.tharul.datacontroller, add google-services.json, enable Authentication, create Firestore, then deploy restrictive Firestore Rules and a trusted pairing-code backend. Never use public read/write rules or put Admin SDK credentials in the app. See README.md.",
                        fontSize = 13.sp, color = Color(0xFF63738A)
                    )
                }
                Text("Data Controller • Android starter build", fontSize = 11.sp, color = Color(0xFF8995A5), modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun SectionTitle(number: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = Cyan.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp), modifier = Modifier.size(34.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(number, color = Navy, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(title, color = Navy, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RoleButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.height(62.dp), shape = RoundedCornerShape(14.dp)) {
            Text(text, fontSize = 12.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(62.dp), shape = RoundedCornerShape(14.dp)) {
            Text(text, fontSize = 12.sp)
        }
    }
}
