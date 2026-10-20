package dev.jamesrich.sofaking

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat
import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.ClientInfo
import dev.jamesrich.sofaking.data.PairingRequest
import dev.jamesrich.sofaking.data.inDirectory
import dev.jamesrich.sofaking.data.parsePairingLink
import dev.jamesrich.sofaking.ui.isTelevision

/** Starts the shared graph once per process, over the files directory the app has always used. */
class SofaKingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val client = ClientInfo(platform = if (isTelevision()) "android-tv" else "android", deviceName = Build.MODEL)
        initSofaKing(AppStorage.inDirectory(filesDir.absolutePath, client))
    }
}

/** Draws edge to edge on every API level, as API 35 and later require; the screens keep clear of the bars themselves. */
class MainActivity : ComponentActivity() {
    /** A `sofaking://pair` link this activity was opened with, until the app has claimed it. */
    private val pairing = mutableStateOf<PairingRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        // The app is always dark, so the bars keep light icons whatever the system theme.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        // A restored activity keeps its intent, but the claim it carried was made the first time.
        if (savedInstanceState == null) takePairing(intent)
        setContent {
            App(
                // Test tags become resource ids, so the Baseline Profile generator can wait for the guide or player.
                modifier = Modifier.semantics { testTagsAsResourceId = true },
                pairing = pairing.value,
                onPairingHandled = { pairing.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takePairing(intent)
    }

    private fun takePairing(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) pairing.value = intent.dataString?.let(::parsePairingLink)
    }
}
