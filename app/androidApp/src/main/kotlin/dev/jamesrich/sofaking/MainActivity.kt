package dev.jamesrich.sofaking

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.jamesrich.sofaking.data.AppStorage
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/** Starts the shared graph once per process, over the files directory the app has always used. */
class SofaKingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initSofaKing(AppStorage.inDirectory(filesDir.absolutePath))
    }
}

/** Draws edge to edge on every API level, as API 35 and later require; the screens keep clear of the bars themselves. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so the bars keep light icons whatever the system theme.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
