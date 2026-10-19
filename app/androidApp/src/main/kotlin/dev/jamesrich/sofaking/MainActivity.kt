package dev.jamesrich.sofaking

import android.app.Application
import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.ClientInfo
import dev.jamesrich.sofaking.data.PairingRequest
import dev.jamesrich.sofaking.data.inDirectory
import dev.jamesrich.sofaking.data.parsePairingLink

/** Starts the shared graph once per process, over the files directory the app has always used. */
class SofaKingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val television = (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val client = ClientInfo(platform = if (television) "android-tv" else "android", deviceName = Build.MODEL)
        initSofaKing(AppStorage.inDirectory(filesDir.absolutePath, client))
    }
}

/** Draws edge to edge on every API level, as API 35 and later require; the screens keep clear of the bars themselves. */
class MainActivity : ComponentActivity() {
    /** A `sofaking://pair` link this activity was opened with, until the app has claimed it. */
    private val pairing = mutableStateOf<PairingRequest?>(null)

    /** Set once the app has chosen its first page, so the splash screen gives way to it and not to a loading frame. */
    @Volatile
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // The splash screen stays until the first page is chosen (the last channel, a tab or the guide), at most
        // SPLASH_MAX_MS, after which the loading page shows while a slow server answers.
        val splashSince = SystemClock.uptimeMillis()
        installSplashScreen().setKeepOnScreenCondition { !ready && SystemClock.uptimeMillis() - splashSince < SPLASH_MAX_MS }
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        // The app is always dark, so the bars keep light icons whatever the system theme.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        // A restored activity keeps its intent, but the claim it carried was made the first time.
        if (savedInstanceState == null) takePairing(intent)
        setContent { App(pairing = pairing.value, onPairingHandled = { pairing.value = null }, onReady = { ready = true }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takePairing(intent)
    }

    private fun takePairing(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) pairing.value = intent.dataString?.let(::parsePairingLink)
    }
}

private const val SPLASH_MAX_MS = 2_000L
