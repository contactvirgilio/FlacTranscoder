package com.example.flac2mp3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!ConverterState.state.value.running) ConverterState.restore(this)
        setContent {
            Flac2Mp3Theme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                label = { Text("Convert") },
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                label = { Text("Tags") },
                            )
                        }
                    },
                ) { padding ->
                    // The screens are Scaffolds too: give them the room under the bar and mark
                    // the bottom inset as used so they do not add it a second time.
                    val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
                    Box(Modifier.padding(bottom).consumeWindowInsets(bottom)) {
                        if (tab == 0) ConverterScreen() else TagsScreen()
                    }
                }
            }
        }
    }
}
