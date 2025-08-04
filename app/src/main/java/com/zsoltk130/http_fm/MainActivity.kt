package com.zsoltk130.http_fm

import android.Manifest
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    private val logLines = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Display initial text
        logLines += listOf(
            "=== HTTP File Manager v1.0 ===",
            "=== (c) zsoltk130 Aug/2025 ===",
            "User Interface initialised...",
            "Starting server on port 8080..."
        )

        // Request permission at runtime for Android 6+
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            123
        )

        val homeDir = Environment.getExternalStorageDirectory()

        // Launch HTTP server
        server = HTTPServer(this, homeDir)
        try {
            server?.start()
            Log.d("HTTPServer", "Started on port 8080")
            logLines += "Server started successfully on port 8080"
        } catch (e: Exception) {
            Log.e("HTTPServer", "Failed to start server", e)
            logLines += "ERROR: Server failed to start: ${e.message}"
        }
        setContent {
            DisplayText(logLines)
        }
    }
}

// Sets background and text colour + text alignment from bottom to top
@Composable
fun DisplayText(lines: List<String>) {
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF002C00)) // Dark green background
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.Start
        ) {
            for (line in lines) {
                Text(
                    text = line,
                    color = Color(0xFF008000), // Bright green text
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
