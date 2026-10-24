package dev.jamesrich.sofaking.ui.guide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.jamesrich.sofaking.data.ProgramKind
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface GuideFilter {
    @Serializable
    data object All : GuideFilter

    @Serializable
    data object Favorites : GuideFilter

    /** The channels watched most recently, newest first. */
    @Serializable
    data object Recent : GuideFilter

    @Serializable
    data class Group(val name: String) : GuideFilter

    /** The channels showing a [kind] of program now. */
    @Serializable
    data class OnNow(val kind: ProgramKind) : GuideFilter
}
