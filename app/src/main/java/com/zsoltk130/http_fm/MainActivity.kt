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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat

class MainActivity : ComponentActivity() {
    private var server: HTTPServer? = null
    private val logs = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Display initial text
        logs += listOf(
            "******************************",
            "=== HTTP File Manager v1.3.1 ===",
            "=== (c) zsoltk130   Nov/2025 ===",
            "******************************"
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
                onStartServer = {
                    try {
                        server = HTTPServer(this, homeDir) { message ->
                            logs += message
                        }
                        server?.start()
                        logs += "Server started on port 8080"
                    } catch (e: Exception) {
                        logs += "ERROR: Server failed to start: ${e.message}"
                    }
                },
                onExitApp = {
                    server?.closeAllConnections()
                    server?.stop()
                    finish()
                }
            )
        }
    }
}

// Define mobile UI
@Composable
fun FileManagerUI(
    logs: List<String>,
    onStartServer: () -> Unit,
    onExitApp: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF002C00))
            .padding(16.dp)
    ) {
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
                    fontSize = 16.sp,
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
                onClick = onStartServer,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = Color(0xFF008000)
                ),
                border = BorderStroke(1.dp, Color(0xFF008000))
            ) {
                Text("Start")
            }

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