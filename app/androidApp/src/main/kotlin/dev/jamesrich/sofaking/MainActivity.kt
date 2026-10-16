package dev.jamesrich.sofaking

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.ClientInfo
import dev.jamesrich.sofaking.data.PairingRequest
import dev.jamesrich.sofaking.data.inDirectory
import dev.jamesrich.sofaking.data.parsePairingLink
import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf

/** Starts the shared graph once per process, over the files directory the app has always used. */
class SofaKingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val tv = getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        initSofaKing(AppStorage.inDirectory(filesDir.absolutePath, ClientInfo(if (tv) "android-tv" else "android", Build.MODEL)))
    }
}

/** Draws edge to edge on every API level, as API 35 and later require; the screens keep clear of the bars themselves. */
class MainActivity : ComponentActivity() {
    /** A `sofaking://pair` link this activity was opened with, until the app has claimed it. */
    private val pairing = mutableStateOf<PairingRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so the bars keep light icons whatever the system theme.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // A restored activity keeps its intent, but the claim it carried was made the first time.
        if (savedInstanceState == null) takePairing(intent)
        setContent { App(pairing = pairing.value, onPairingHandled = { pairing.value = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takePairing(intent)
    }

    private fun takePairing(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) pairing.value = intent.dataString?.let(::parsePairingLink)
    }
}
