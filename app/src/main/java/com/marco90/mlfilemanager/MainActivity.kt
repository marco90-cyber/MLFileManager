package com.marco90.mlfilemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.marco90.mlfilemanager.net.Net
import com.marco90.mlfilemanager.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Net.init(this)
        enableEdgeToEdge()
        setContent { AppRoot() }
    }
}
