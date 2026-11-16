package com.marco90.mlfilemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.marco90.mlfilemanager.data.AppLog
import com.marco90.mlfilemanager.data.AppSettings
import com.marco90.mlfilemanager.net.Net
import com.marco90.mlfilemanager.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = AppSettings.get(this)
        AppLog.init(this, settings.verboseLog)
        Net.init(this)
        if (savedInstanceState == null && settings.autoUpdateCheck) {
            AppUpdate.checkQuietly(this)
        }
        enableEdgeToEdge()
        setContent { AppRoot() }
    }
}
