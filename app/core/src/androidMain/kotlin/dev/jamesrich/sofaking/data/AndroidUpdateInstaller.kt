package dev.jamesrich.sofaking.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.app.PendingIntentCompat
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** Installers of record that keep the app up to date themselves; an install from one of them never updates in-app. */
internal val STORE_INSTALLERS = setOf("com.android.vending", "org.fdroid.fdroid", "com.amazon.venezia")

/**
 * Why a downloaded package must not go to the installer, or null when it may. [sameKey] is null when Android could
 * not read the package's signers, and the installer's own check then decides.
 */
internal fun packageVerdict(expectedSha256: String?, sha256: String, packageName: String?, appPackage: String, versionCode: Long, installedVersionCode: Long, sameKey: Boolean?): InstallFailure? =
    when {
        expectedSha256 != null && expectedSha256 != sha256 -> InstallFailure.CHECKSUM
        packageName != appPackage -> InstallFailure.NOT_THIS_APP
        sameKey == false -> InstallFailure.DIFFERENT_KEY
        versionCode <= installedVersionCode -> InstallFailure.NOT_NEWER
        else -> null
    }

private val sharedLock = Any()
private var shared: AndroidUpdateInstaller? = null

/**
 * The process's one installer, so a download outlives the Settings page that started it, or null when an app store
 * installed this app and keeps it up to date.
 */
fun appUpdateInstaller(context: Context): UpdateInstaller? {
    val app = context.applicationContext
    if (installerOf(app) in STORE_INSTALLERS) return null
    return synchronized(sharedLock) { shared ?: AndroidUpdateInstaller(app).also { shared = it } }
}

private fun installerOf(context: Context): String? {
    val packageManager = context.packageManager
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(context.packageName)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

/**
 * Downloads the package into the cache, checks it against GitHub's digest, this app's package name, signing key and
 * version code, then commits it through a [PackageInstaller] session. The system asks the viewer to confirm the first
 * time (and, once, to let the app install apps); on Android 12 and later an update to an app that installed itself
 * needs no confirmation, since the viewer has just asked for it.
 */
private class AndroidUpdateInstaller(private val app: Context) : UpdateInstaller {
    private val log = Logger.withTag("SofaKing")
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    override val state: StateFlow<InstallState> = _state.asStateFlow()
    private var job: Job? = null
    private val statusAction = "${app.packageName}.UPDATE_INSTALL_STATUS"

    init {
        ContextCompat.registerReceiver(app, StatusReceiver(), IntentFilter(statusAction), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun install(build: PublishedBuild) {
        val url = build.apkUrl ?: return
        if (job?.isActive == true || _state.value == InstallState.Installing) return
        job = scope.launch {
            _state.value = InstallState.Downloading(null)
            run(url, build.apkSha256)?.let { _state.value = it }
        }
    }

    /** The install's outcome, or null once the session is committed and [StatusReceiver] reports the rest. */
    private suspend fun run(url: String, expectedSha256: String?): InstallState? {
        val file = File(app.cacheDir, "update.apk")
        val sha256 = try {
            downloadPackage(apiHttpClient, url, file) { _state.value = InstallState.Downloading(it) }
        } catch (e: IOException) {
            log.w { "update download failed: ${e.message}" }
            return InstallState.Failed(InstallFailure.DOWNLOAD)
        }
        try {
            val packageManager = app.packageManager
            val archive = packageManager.archiveInfo(file.path)
            val installed = packageManager.installedInfo(app.packageName)
            val signers = archive?.signingInfo?.apkContentsSigners
            val sameKey = signers?.takeIf { it.isNotEmpty() }?.let { certificates ->
                PackageInfoCompat.hasSignatures(
                    packageManager,
                    app.packageName,
                    certificates.associate { it.toByteArray() to PackageManager.CERT_INPUT_RAW_X509 },
                    true,
                )
            }
            val failure = packageVerdict(
                expectedSha256 = expectedSha256,
                sha256 = sha256,
                packageName = archive?.packageName,
                appPackage = app.packageName,
                versionCode = archive?.let(PackageInfoCompat::getLongVersionCode) ?: 0,
                installedVersionCode = installed?.let(PackageInfoCompat::getLongVersionCode) ?: 0,
                sameKey = sameKey,
            )
            if (failure != null) return InstallState.Failed(failure)
            // Before the commit, since the receiver can hear back before it returns.
            _state.value = InstallState.Installing
            commit(file)
            return null
        } catch (e: IOException) {
            log.w { "update install failed: ${e.message}" }
            return InstallState.Failed(InstallFailure.INSTALLER, e.message)
        } catch (e: SecurityException) {
            log.w { "update install refused: ${e.message}" }
            return InstallState.Failed(InstallFailure.INSTALLER, e.message)
        } finally {
            file.delete()
        }
    }

    /** The package file's name, version and signers, or null when it is not a package. */
    private fun PackageManager.archiveInfo(path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun PackageManager.installedInfo(packageName: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            getPackageInfo(packageName, 0)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun commit(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(statusSender())
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        }
    }

    /** The installer reports to [StatusReceiver]; it fills the intent's extras, so the pending intent is mutable. */
    private fun statusSender(): IntentSender {
        val intent = Intent(statusAction).setPackage(app.packageName)
        return checkNotNull(PendingIntentCompat.getBroadcast(app, 0, intent, 0, true)).intentSender
    }

    private inner class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                // The system's confirmation, and the one-time "install unknown apps" grant before it when needed.
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    try {
                        checkNotNull(confirm) { "no confirmation intent" }
                        app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: RuntimeException) {
                        log.w { "update confirmation did not open: ${e.message}" }
                        _state.value = InstallState.Failed(InstallFailure.INSTALLER, e.message)
                    }
                }
                // Installing this app ends its process first, so success is seldom heard.
                PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = InstallState.Idle
                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    log.w { "update install failed with status $status: $message" }
                    _state.value = InstallState.Failed(InstallFailure.INSTALLER, message)
                }
            }
        }
    }
}
