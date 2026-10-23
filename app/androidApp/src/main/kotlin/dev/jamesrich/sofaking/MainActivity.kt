package dev.jamesrich.sofaking

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat
import dev.jamesrich.sofaking.data.AppStorage
import dev.jamesrich.sofaking.data.ClientInfo
import dev.jamesrich.sofaking.data.NsdServerDiscovery
import dev.jamesrich.sofaking.data.PairingRequest
import dev.jamesrich.sofaking.data.inDirectory
import dev.jamesrich.sofaking.data.parsePairingLink
import dev.jamesrich.sofaking.ui.isTelevision

/** Starts the shared graph once per process, over the files directory the app has always used. */
class SofaKingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A debuggable build logs disk and network work on the main thread and leaked resources, which the TV emulator
        // monkey (app/scripts/tv-monkey.py) collects.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedRegistrationObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build(),
            )
        }
        val client = ClientInfo(platform = if (isTelevision()) "android-tv" else "android", deviceName = Build.MODEL)
        initSofaKing(AppStorage.inDirectory(filesDir.absolutePath, client), NsdServerDiscovery(this))
    }
}

/** Draws edge to edge on every API level, as API 35 and later require; the screens keep clear of the bars themselves. */
class MainActivity : ComponentActivity() {
    /** A `sofaking://pair` link this activity was opened with, until the app has claimed it. */
    private val pairing = mutableStateOf<PairingRequest?>(null)

    // A denial leaves the app as it was: the server cannot be reached until the permission is given in system settings.
    private val localNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

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
        // Android 17 blocks the local network, where the server is, until the viewer allows it.
        if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK && checkSelfPermission(ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            localNetwork.launch(ACCESS_LOCAL_NETWORK)
        }
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

/** The API level that asks for [ACCESS_LOCAL_NETWORK] before any traffic to the local network. */
private const val LOCAL_NETWORK_SDK = 37
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
