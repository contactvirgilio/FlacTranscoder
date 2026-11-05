package com.example.flac2mp3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!ConverterState.state.value.running) ConverterState.restore(this)
        setContent {
            Flac2Mp3Theme { ConverterScreen() }
        }
    }
}
