package dev.jamesrich.sofaking.data

import kotlinx.coroutines.flow.StateFlow

/** Where installing a published build from inside the app stands. */
sealed interface InstallState {
    data object Idle : InstallState

    /** The package is downloading; [percent] of it is in, or null while the server gives no size. */
    data class Downloading(val percent: Int?) : InstallState

    /** The package is with the system installer, which may ask the viewer to confirm; the app closes as it installs. */
    data object Installing : InstallState

    /** Nothing was installed; [detail] is the system installer's own message, when it gave one. */
    data class Failed(val failure: InstallFailure, val detail: String? = null) : InstallState
}

/** Why an in-app install stopped. */
enum class InstallFailure {
    /** The download did not finish. */
    DOWNLOAD,

    /** The file's SHA-256 is not the one GitHub records for the asset. */
    CHECKSUM,

    /** The file is not an Android package for this app. */
    NOT_THIS_APP,

    /** The package is signed with another key, so Android would refuse it over this install. */
    DIFFERENT_KEY,

    /** The package's version code is not above the running one, so Android would refuse it as a downgrade. */
    NOT_NEWER,

    /** The system installer refused or failed. */
    INSTALLER,
}

/**
 * Downloads a published build's Android package, checks it and hands it to the system installer, only when the viewer
 * asks. Android builds that no app store installed have one; elsewhere Get the update opens the download instead.
 */
interface UpdateInstaller {
    val state: StateFlow<InstallState>

    /** Starts installing [build]'s package; does nothing while an install runs or when the build has no package. */
    fun install(build: PublishedBuild)
}
