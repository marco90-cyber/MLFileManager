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
        com.marco90.mlfilemanager.data.AppLog.init(this)
        com.marco90.mlfilemanager.data.AppLog.verbose = com.marco90.mlfilemanager.data.AppSettings.get(this).verboseLog
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            com.marco90.mlfilemanager.data.AppLog.e("Crash", "Errore non gestito in ${t.name}: ${e.stackTraceToString().take(3000)}")
            previous?.uncaughtException(t, e)
        }
        Net.init(this)
        if (savedInstanceState == null && com.marco90.mlfilemanager.data.AppSettings.get(this).autoUpdateCheck) {
            AppUpdate.checkQuietly(this)
        }
        enableEdgeToEdge()
        setContent { AppRoot() }
    }
}
