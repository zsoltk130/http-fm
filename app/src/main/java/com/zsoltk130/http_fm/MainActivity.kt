package com.zsoltk130.http_fm

import android.os.Bundle
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
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var server: HTTPServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        server = HTTPServer()
        try {
            server?.start()
            Log.d("HTTPServer", "Started on port 8080")
        } catch (e: Exception) {
            Log.e("HTTPServer", "Failed to start server", e)
        }
        setContent {
            DisplayText()
        }
    }
}

@Composable
fun DisplayText() {
    val scrollState = rememberScrollState()
    val lines = remember { mutableStateListOf<String>() }

    // Add text from the bottom up, like an old-school console
    LaunchedEffect(Unit) {
        val sampleLines = listOf(
            "=== HTTP File Manager v1.0 ===",
            "=== (c) zsoltk130 Aug/2025 ===",
            "User Interface initialised..."
        )
        for (line in sampleLines) {
            delay(1000) // Delay between lines
            lines.add(line)
        }
    }

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
