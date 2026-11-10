package dev.jamesrich.sofaking.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.LiveBreaks
import dev.jamesrich.sofaking.data.PlaybackSessions
import dev.jamesrich.sofaking.resources.Res
import dev.jamesrich.sofaking.resources.break_screen_title
import dev.jamesrich.sofaking.resources.tuning
import dev.jamesrich.sofaking.ui.appLifecycleOwner
import dev.jamesrich.sofaking.ui.label
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import dev.jamesrich.sofaking.ui.theme.SofaOverlayScrim
import dev.jamesrich.sofaking.ui.theme.SofaScrim
import org.jetbrains.compose.resources.stringResource

/**
 * One pane of multiview: [channel] on a player and a session ([sessions]) of its own, in [modifier]'s bounds. Only
 * the pane with the [sound] is heard, and it has the border when [framed]; a silent one shows its captions whatever
 * the setting. While Settings covers ad breaks, a pane in one goes dark until its break is over. Its name and
 * [program] show while [names] is set, and why it is not playing while it is not.
 */
@Composable
internal fun MultiviewPane(
    channel: Channel,
    program: String?,
    sessions: PlaybackSessions,
    sound: Boolean,
    names: Boolean,
    framed: Boolean,
    look: PaneLook,
    modifier: Modifier = Modifier,
) {
    val captionSync = remember { CaptionSync() }
    val player = rememberLivePlayer(captionSync, pane = true)
    val play = remember(player, sessions) { PanePlay(player, paneSessions(sessions)) }
    val cover = rememberBreakCover(player, look.breaks.covers, channel.guideId)
    val captions = paneCaptions(sound, look.captions)
    // Media3 restarts a live stream whose text track is switched, so its panes read the captions throughout and only
    // draw them when wanted; the other engines draw their own into the picture.
    val readCaptions = captions || player.supportsCaptionLead
    LaunchedEffect(player, readCaptions) { player.setCaptionsEnabled(readCaptions) }
    // Before the first tune, so a channel kept on the software decoder plays on it here too.
    LaunchedEffect(player, look.playback) { player.setPlaybackPreferences(look.playback) }
    PaneVolume(player, silent = paneSilent(sound, cover.up, look.breaks))
    val lifecycle = appLifecycleOwner.lifecycle
    LaunchedEffect(play, channel.guideId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { play.play(channel.guideId) }
    }
    Box(modifier.clipToBounds().background(SofaScrim)) {
        player.Video(Modifier.fillMaxSize(), VideoZoom.FIT) { Box(Modifier.fillMaxSize().background(SofaScrim)) }
        if (cover.up) {
            PaneNote(stringResource(Res.string.break_screen_title), Modifier.fillMaxSize().background(SofaScrim))
        } else if (captions) {
            Captions(player, look.captionStyle, VideoZoom.FIT, bottomFraction = 0f, Modifier.fillMaxSize())
        }
        val failure = play.failure
        when {
            failure != null -> PaneNote(stringResource(failureCopy(failure).reason))
            play.opening -> PaneNote(stringResource(Res.string.tuning))
        }
        if (names || sound) PaneLabel(channel, program, sound, full = names)
        if (framed) Box(Modifier.fillMaxSize().border(SOUND_BORDER, MaterialTheme.colorScheme.primary))
    }
}

/**
 * The pane's loudness: the app's while it is heard, none while [silent]. A silent pane holds no audio focus, and one
 * the system paused when focus moved to another pane plays on.
 */
@Composable
private fun PaneVolume(player: LivePlayer, silent: Boolean) {
    val volume = LocalPlayerVolume.current
    val currentSilent by rememberUpdatedState(silent)
    LaunchedEffect(player, volume) {
        snapshotFlow { if (currentSilent) 0f else volume.gain }.collect { gain ->
            player.volume = gain
            if (gain == 0f) player.playWhenReady = true
        }
    }
    LaunchedEffect(player) {
        player.status.collect { if (!it.playWhenReady && currentSilent) player.playWhenReady = true }
    }
}

/** A line in the middle of the pane: the break's, or why the pane is not playing. */
@Composable
private fun BoxScope.PaneNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
        Text(
            text,
            Modifier.padding(12.dp).background(SofaOverlayScrim, MaterialTheme.shapes.small).padding(12.dp, 8.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The pane's channel and [program], and a speaker on the one whose sound plays; only the speaker unless [full]. */
@Composable
private fun BoxScope.PaneLabel(channel: Channel, program: String?, sound: Boolean, full: Boolean) {
    Row(
        Modifier
            .align(Alignment.TopStart)
            .padding(12.dp)
            .background(SofaOverlayScrim, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sound) {
            Icon(SofaIcons.VolumeUp, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        if (full) {
            Column {
                Text(
                    channel.label(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (program != null) {
                    Text(
                        program,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val SOUND_BORDER = 3.dp
