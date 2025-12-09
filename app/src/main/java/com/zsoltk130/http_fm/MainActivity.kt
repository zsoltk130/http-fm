package com.zsoltk130.http_fm

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private var server: HTTPServer? = null
    private val logs = mutableStateListOf<String>()

    // Status indicators
    private var isServerRunning by mutableStateOf(false)
    private var isPasswordedEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Display initial text
        logs += listOf(
            "=== HTTP File Manager v1.5.4 ===",
            "=== (c) zsoltk130   Dec/2025 ==="
        )

        // Permissions check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                1
            )
        }

        // Start at /storage/emulated/0
        val homeDir = Environment.getExternalStorageDirectory()

        // Launch HTTP server
        setContent {
            FileManagerUI(
                logs = logs,
                onStartServer = { newRunState ->
                    if (newRunState && !isServerRunning) {
                        try {
                            server = HTTPServer(
                                this,
                                homeDir,
                                isPasswordProtected = isPasswordedEnabled
                            ) { message ->
                                logs += message
                            }
                            server?.start()
                            isServerRunning = true
                            logs += "[${nowTimestamp()}] Server started on port 8080"

                        } catch (e: Exception) {
                            logs += "[${nowTimestamp()}] ERROR: ${e.message}"
                        }

                    } else if (!newRunState && isServerRunning) {
                        server?.closeAllConnections()
                        server?.stop()
                        isServerRunning = false
                        logs += "[${nowTimestamp()}] Server stopped"
                    }
                },
                onExitApp = {
                    server?.closeAllConnections()
                    server?.stop()
                    isServerRunning = false

                    finish()
                },
                onPasswordToggle = { newPasswordState ->
                    isPasswordedEnabled = newPasswordState // Update the state variable
                    val status = if (newPasswordState) "enabled" else "disabled"
                    logs += "[${nowTimestamp()}] Password protection $status"
                },
                isEnabled = isServerRunning,
                isPassworded = isPasswordedEnabled
            )
        }
    }
}

private fun nowTimestamp(): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    return ZonedDateTime.now().format(formatter)
}

// Define mobile UI
@Composable
fun FileManagerUI(
    logs: List<String>,
    onStartServer: (Boolean) -> Unit,
    onExitApp: () -> Unit,
    onPasswordToggle: (Boolean) -> Unit,
    isEnabled: Boolean,
    isPassworded: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF002C00))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween // This pushes the items to opposite ends
            ) {
                StatusIndicator("Enabled", isEnabled)
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onStartServer, // Call the lambda when toggled
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00FF00),
                        checkedTrackColor = Color(0xFF008000),
                        checkedBorderColor = Color(0xFF008000),
                        uncheckedThumbColor = Color(0xFF004C00),
                        uncheckedTrackColor = Color(0xFF111711),
                        uncheckedBorderColor = Color(0xFF111711)
                    )
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween // This pushes the items to opposite ends
            ) {
                StatusIndicator("Passworded", isPassworded)
                Switch(
                    checked = isPassworded,
                    onCheckedChange = onPasswordToggle, // Call the lambda when toggled
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00FF00),
                        checkedTrackColor = Color(0xFF008000),
                        checkedBorderColor = Color(0xFF008000),
                        uncheckedThumbColor = Color(0xFF004C00),
                        uncheckedTrackColor = Color(0xFF111711),
                        uncheckedBorderColor = Color(0xFF111711)
                    )
                )
            }
        }

        // Text display area
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.Start
        ) {
            for (line in logs) {
                Text(
                    text = line,
                    color = Color(0xFF008000),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Button row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onExitApp,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color(0xFF008000)
                ),
                border = BorderStroke(1.dp, Color(0xFF008000))
            ) {
                Text("Exit")
            }
        }
    }
}

@Composable
fun StatusIndicator(label: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(
                    if (active) Color(0xFF00FF00) else Color(0xFFFF0000),
                    shape = CircleShape
                )
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = Color(0xFF008000),
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp
        )
    }
}

@Preview(showBackground = true, name = "File Manager UI Preview")
@Composable
fun FileManagerUIPreview() {

    val time = ZonedDateTime.now()
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val formattedTime = time.format(formatter)

    val sampleLogs = remember {
        mutableStateListOf(
            "=== HTTP File Manager v1.5.3 ===",
            "=== (c) zsoltk130   Dec/2025 ===",
            "[$formattedTime] Server started on port 8080",
            "[$formattedTime] GET / - 200 OK",
            "[$formattedTime] GET /styles.css - 200 OK",
            "[$formattedTime] GET /some-image.jpg - 200 OK",
            "[$formattedTime] POST /upload - 401 Unauthorized"
        )
    }
    
    var isServerRunning by remember { mutableStateOf(true) }
    var isPasswordedPreview by remember { mutableStateOf(true) }

    FileManagerUI(
        logs = sampleLogs,
        onStartServer = {
            if (!isServerRunning) {
                sampleLogs.add("Server started on port 8080")
                isServerRunning = true
            }
        },
        onExitApp = {
            sampleLogs.add("Server stopped.")
            isServerRunning = false
        },
        onPasswordToggle = {
            isPasswordedPreview = it // 'it' is the new boolean state
        },
        isEnabled = isServerRunning,
        isPassworded = isPasswordedPreview
    )
}