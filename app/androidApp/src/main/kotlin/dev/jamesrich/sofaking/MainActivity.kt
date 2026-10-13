package dev.jamesrich.sofaking

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.jamesrich.sofaking.data.AppStorage

/** Starts the shared graph once per process, over the files directory the app has always used. */
class SofaKingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initSofaKing(AppStorage.inDirectory(filesDir.absolutePath))
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
