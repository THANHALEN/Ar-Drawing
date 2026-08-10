package com.example.ardrawing.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.ardrawing.ui.screen.ArDrawingScreen
import com.example.ardrawing.ui.theme.ArDrawingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArDrawingTheme {
                ArDrawingScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
