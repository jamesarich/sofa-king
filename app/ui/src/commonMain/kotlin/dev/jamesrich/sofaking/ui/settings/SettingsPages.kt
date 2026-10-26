package dev.jamesrich.sofaking.ui.settings

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import dev.jamesrich.sofaking.data.AppSettings
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.captions_off
import dev.jamesrich.sofaking.resources.captions_on
import dev.jamesrich.sofaking.resources.settings_page_captions
import dev.jamesrich.sofaking.resources.settings_page_captions_subtitles
import dev.jamesrich.sofaking.resources.settings_page_dvr
import dev.jamesrich.sofaking.resources.settings_page_library
import dev.jamesrich.sofaking.resources.settings_page_library_dvr
import dev.jamesrich.sofaking.resources.settings_page_library_summary
import dev.jamesrich.sofaking.resources.settings_page_live_tv
import dev.jamesrich.sofaking.resources.settings_page_live_tv_summary
import dev.jamesrich.sofaking.resources.settings_page_lock
import dev.jamesrich.sofaking.resources.settings_page_lock_kids
import dev.jamesrich.sofaking.resources.settings_page_lock_off
import dev.jamesrich.sofaking.resources.settings_page_lock_on
import dev.jamesrich.sofaking.resources.settings_page_playback
import dev.jamesrich.sofaking.resources.settings_page_playback_night
import dev.jamesrich.sofaking.resources.settings_page_playback_summary
import dev.jamesrich.sofaking.ui.supportsPlaybackPreferences
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import dev.jamesrich.sofaking.ui.theme.tvFocus
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource

/** Settings' pages, each a row on its list: the settings grouped by what they change. */
@Serializable
enum class SettingsPage {
    /** What the app opens on, the guide, live ad breaks and the channel manager. */
    LIVE_TV,

    /** Skipping intros, credits and commercials, the seek steps and spoilers, on a server with a library or a DVR. */
    LIBRARY,

    /** Captions, and the library's subtitles on a server with a library. */
    CAPTIONS,

    /** Night listening, the frame rate, the decoder and the buffer, where the platform takes them. */
    PLAYBACK,

    /** The PIN and kids mode. */
    LOCK,
    ;

    /** What Settings' `returnedFrom` holds as the viewer comes back from this page; its row takes focus. */
    val focusKey: String get() = "settings-page:${name.lowercase()}"
}

/** What the server offers and the platform takes, which decides the pages Settings lists and what they hold. */
internal class SettingsOffer(val library: Boolean, val recordings: Boolean, val playback: Boolean) {
    val pages: List<SettingsPage>
        get() = SettingsPage.entries.filter {
            when (it) {
                SettingsPage.LIBRARY -> library || recordings
                SettingsPage.PLAYBACK -> playback
                else -> true
            }
        }
}

/** The page's name, as its row and its title show it. */
@Composable
internal fun SettingsPage.title(offer: SettingsOffer): String = stringResource(
    when (this) {
        SettingsPage.LIVE_TV -> Res.string.settings_page_live_tv

        SettingsPage.LIBRARY -> when {
            offer.library && offer.recordings -> Res.string.settings_page_library_dvr
            offer.library -> Res.string.settings_page_library
            else -> Res.string.settings_page_dvr
        }

        SettingsPage.CAPTIONS -> if (offer.library) {
            Res.string.settings_page_captions_subtitles
        } else {
            Res.string.settings_page_captions
        }

        SettingsPage.PLAYBACK -> Res.string.settings_page_playback

        SettingsPage.LOCK -> Res.string.settings_page_lock
    },
)

/** The line under the page's row: what it holds, or where it stands when that is the more useful thing to know. */
@Composable
private fun SettingsPage.summary(settings: AppSettings): String = stringResource(
    when (this) {
        SettingsPage.LIVE_TV -> Res.string.settings_page_live_tv_summary

        SettingsPage.LIBRARY -> Res.string.settings_page_library_summary

        SettingsPage.CAPTIONS -> if (settings.captionsEnabled) Res.string.captions_on else Res.string.captions_off

        SettingsPage.PLAYBACK -> if (supportsPlaybackPreferences) {
            Res.string.settings_page_playback_summary
        } else {
            Res.string.settings_page_playback_night
        }

        SettingsPage.LOCK -> when {
            settings.kidsMode -> Res.string.settings_page_lock_kids
            settings.settingsLocked -> Res.string.settings_page_lock_on
            else -> Res.string.settings_page_lock_off
        }
    },
)

private val SettingsPage.icon: ImageVector
    get() = when (this) {
        SettingsPage.LIVE_TV -> SofaIcons.LiveTv
        SettingsPage.LIBRARY -> SofaIcons.VideoLibrary
        SettingsPage.CAPTIONS -> SofaIcons.ClosedCaption
        SettingsPage.PLAYBACK -> SofaIcons.Speed
        SettingsPage.LOCK -> Icons.Filled.Lock
    }

/** A row for each page [offer] lists; [focus] gives each row the requester that takes focus on the way back. */
internal fun LazyListScope.pageRows(
    settings: AppSettings,
    offer: SettingsOffer,
    focus: (SettingsPage) -> FocusRequester,
    onOpen: (SettingsPage) -> Unit,
) {
    offer.pages.forEach { page ->
        item(key = page.focusKey) {
            LinkRow(
                label = page.title(offer),
                summary = page.summary(settings),
                icon = page.icon,
                onClick = { onOpen(page) },
                modifier = Modifier.focusRequester(focus(page)),
            )
        }
    }
}

/**
 * A row that opens another page, Material's clickable list item: an icon, the label with a [summary] under it and an
 * arrow, the whole row the focus and touch target.
 */
@Composable
internal fun LinkRow(
    label: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shapes = settingRowShapes()
    val interactions = remember { MutableInteractionSource() }
    ListItem(
        onClick = onClick,
        modifier = modifier.width(FIELD_WIDTH).tvFocus(focusedScale = 1f),
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        supportingContent = { Text(summary, style = MaterialTheme.typography.bodySmall) },
        shapes = shapes,
        colors = settingRowColors(interactions),
        interactionSource = interactions,
    ) { Text(label, style = MaterialTheme.typography.titleSmall) }
}
