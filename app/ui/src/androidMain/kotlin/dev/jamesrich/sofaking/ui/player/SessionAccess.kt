package dev.jamesrich.sofaking.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession

/**
 * Which controllers a media session lets in. The music service is exported so the system's media controls can bind
 * to it, which any installed app could otherwise do too, so only the system's own controllers (the notification,
 * Assistant, a headset) and this app's are accepted, and none of them may change what the session plays.
 */
@OptIn(UnstableApi::class)
internal class SessionAccess(private val ownPackage: String) : MediaSession.Callback {
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult = if (admits(controller.isTrusted, controller.packageName, ownPackage)) {
        MediaSession.ConnectionResult.accept(
            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
            controllerCommands(),
        )
    } else {
        MediaSession.ConnectionResult.reject()
    }
}

/** Whether a controller from [controllerPackage] may connect: the system trusts it, or it is [ownPackage]'s. */
internal fun admits(trusted: Boolean, controllerPackage: String, ownPackage: String): Boolean =
    trusted || controllerPackage == ownPackage

/** The commands no controller gets: the ones that change what plays or how the player is set up, or release it. */
internal val WITHHELD_COMMANDS = intArrayOf(
    Player.COMMAND_SET_MEDIA_ITEM,
    Player.COMMAND_CHANGE_MEDIA_ITEMS,
    Player.COMMAND_SET_PLAYLIST_METADATA,
    Player.COMMAND_SET_VIDEO_SURFACE,
    Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
    Player.COMMAND_SET_AUDIO_ATTRIBUTES,
    Player.COMMAND_RELEASE,
)

/** What a connected controller may do: everything but [WITHHELD_COMMANDS], so play, pause, seek and step stay. */
internal fun controllerCommands(): Player.Commands =
    Player.Commands.Builder().addAllCommands().removeAll(*WITHHELD_COMMANDS).build()
