package dev.jamesrich.sofaking

/**
 * The words the SwiftUI Apple TV app shares with the Compose screens, which Compose reads from its string resources
 * and Swift cannot. Each [twin] names the resource it copies; `TvCopyParityTest` in `ui` compares them, so the two
 * sides cannot drift apart unnoticed. Text with arguments is a function here, never a template for Swift to fill.
 * The Recordings tab's words are [TvRecordingCopy].
 */
object TvCopy {
    private val table = CopyTable()

    /** Resource name to the text this object holds for it. */
    val twins: Map<String, String> get() = table.twins

    private fun twin(key: String, text: String) = table.twin(key, text)

    /** Text only the Apple TV app shows, so no Compose string backs it; a function keeps Swift reading a property. */
    private fun own(text: String) = text

    private fun fill(template: String, vararg arguments: Any) = table.fill(template, *arguments)

    val appName = twin("app_name", "Sofa King")

    // Tabs
    val tabLive = twin("nav_live", "Live")
    val tabLibrary = twin("nav_library", "Library")
    val tabDvr = twin("nav_dvr", "DVR")
    val tabSettings = twin("nav_settings", "Settings")

    // Channels
    val loading = twin("loading", "Sofa King close, loading channels…")
    val loadFailed = twin("load_failed", "Couldn't reach the Sofa King server")
    val retry = twin("retry", "Try again")
    val noChannels = twin("no_channels", "No channels yet")
    val noChannelMatches = twin("guide_search_none", "No channels match")
    val changeFailed = twin("change_failed", "Couldn't save that change. Try again.")

    /** The favorites filter's empty state; the Siri Remote has a touch surface where the other apps have OK. */
    val favoritesNone = twin(
        "guide_favorites_none",
        "No favorites yet. Hold the touch surface on a program and choose Add to favorites.",
    )
    val filterAll = twin("filter_all", "All")
    val filterFavorites = twin("filter_favorites", "Favorites")
    val filterRecent = twin("filter_recent", "Recent")
    val search = twin("guide_search", "Search")
    val onLater = twin("on_later", "On later")
    val favoriteAdd = twin("guide_details_add_favorite", "Add to favorites")
    val favoriteRemove = twin("guide_details_remove_favorite", "Remove from favorites")

    // Pairing
    private val pairingCode = twin(
        "settings_pairing_failed_code",
        "That code didn't work. Codes last ten minutes and work once; show a new one in the admin portal.",
    )
    private val pairingServer = twin(
        "settings_pairing_failed_server",
        "This server is too old to pair with a code; enter its API token instead.",
    )
    private val pairingUnreachable = twin(
        "settings_pairing_failed_unreachable",
        "No Sofa King server answered at that address. Check the address and that this device is on the same network.",
    )

    fun pairingFailure(failure: PairingFailure): String = when (failure) {
        PairingFailure.INVALID_CODE -> pairingCode
        PairingFailure.OLDER_SERVER -> pairingServer
        PairingFailure.UNREACHABLE -> pairingUnreachable
    }

    // Settings
    val settingsTitle = twin("settings_title", "Settings")
    val settingsSave = twin("settings_save", "Save & reload")
    val serverSection = twin("settings_section_server", "Server")
    val serverUrl = twin("settings_server_url", "Server address")
    val discoveredServers = twin("settings_discovered_servers", "Servers on this network")
    val apiToken = twin("settings_api_token", "API token")
    val apiTokenHelp = twin(
        "settings_api_token_help",
        "Only needed without a pairing code. The admin portal shows it under Settings › Advanced.",
    )
    val pairingCodeLabel = twin("settings_pairing_code", "Pairing code")
    val pair = twin("settings_pair", "Pair")
    val pairing = twin("settings_pairing", "Pairing…")
    val liveBreaks = twin("settings_live_breaks", "During ads")
    val liveBreaksPlay = twin("settings_live_breaks_play", "Show them")
    val liveBreaksScreenMuted = twin("settings_live_breaks_screen_muted", "Logo, muted")
    val liveBreaksScreen = twin("settings_live_breaks_screen", "Logo, sound on")

    // The Apple TV's footers: its own controls (Show picture, the touch surface) are not the other apps' words.
    val liveBreaksHelp = own(
        "On streaming channels that mark their ads, such as Pluto TV and Tubi, a bouncing logo covers them. Show " +
            "picture brings the picture back.",
    )
    val autoOpenHelp = own("A channel that keeps focus opens full screen after this long.")
    val previewSoundHelp = own(
        "The guide's preview plays the focused channel muted and turns its sound on after 5 seconds.",
    )
    val captionsHelp = own(
        "Shows a channel's or a recording's closed captions, in the look set in the Apple TV's Settings › " +
            "Accessibility › Subtitles and Captioning. The player's Subtitles panel turns them on and off too.",
    )
    val skipIntrosHelp = own(
        "Automatically, Skip intro counts down 5 seconds and then skips; pausing stops it. With a button, it waits " +
            "for a press. Off, intros play.",
    )
    val skipCreditsHelp = own(
        "Automatically, Skip credits counts down 5 seconds and then skips; pausing stops it. With a button, it " +
            "waits for a press. Off, credits play.",
    )
    val autoOpen = twin("settings_auto_open", "Open focused channel automatically")
    val autoOpenOff = twin("settings_auto_open_off", "Off")
    val autoOpen10 = twin("settings_auto_open_10", "After 10 seconds")
    val autoOpen30 = twin("settings_auto_open_30", "After 30 seconds")
    val previewSound = twin("settings_preview_sound", "Play preview sound")
    val livePage = twin("settings_page_live_tv", "Live TV")
    val channelsSection = twin("settings_channels", "Channels")
    val shareFavorites = twin("channels_share", "Share favorites across devices")
    val shareFavoritesHelp = twin(
        "channels_share_help",
        "Keeps the favorites, their order and the hidden channels the same on every device of the household that " +
            "has this on. Turning it on takes the household's list over when another device saved one.",
    )
    val libraryPage = twin("settings_page_library", "Library")
    val skipIntros = twin("settings_skip_intros", "Skip intros")
    val skipCredits = twin("settings_skip_credits", "Skip credits")
    val skipModeAuto = twin("settings_skip_mode_auto", "Automatically")
    val skipModeButton = twin("settings_skip_mode_button", "With a button")
    val skipModeOff = twin("settings_skip_mode_off", "Off")
    val hideSpoilers = twin("settings_hide_spoilers", "Hide spoilers")
    val hideSpoilersHelp = twin(
        "settings_hide_spoilers_help",
        "Hides the summaries of unwatched episodes and blurs their pictures.",
    )
    val aboutSection = twin("settings_section_about", "About")
    val tmdbNotice = twin(
        "settings_tmdb_notice",
        "Movie and show details and images can come from TMDB. This product uses the TMDB API but is not endorsed " +
            "or certified by TMDB.",
    )

    /** The Pair section's title; the other apps have no header over their pairing code. */
    val pairWithCode = own("Pair with a code")

    private val discoveredServer = twin("settings_discovered_server", "%1\$s at %2\$s")

    /** A server found on the network, as its button reads; [address] is shown without the scheme. */
    fun discoveredServer(name: String, address: String): String = fill(discoveredServer, name, address)

    // Parental controls and the settings lock
    val parentalControls = twin("settings_page_lock", "Parental controls")
    val lockSet = twin("settings_lock_set", "Set a PIN")
    val pin = twin("settings_lock_pin", "PIN")
    val pinAgain = twin("settings_lock_pin_again", "PIN again")
    val pinInvalid = twin("settings_lock_pin_invalid", "Enter four digits.")
    val pinMismatch = twin("settings_lock_pin_mismatch", "The PINs don't match.")
    val lockConfirm = twin("settings_lock_confirm", "Lock settings")
    val lockCancel = twin("settings_lock_cancel", "Cancel")
    val lockRemove = twin("settings_lock_remove", "Remove the PIN")
    val kidsTurnOn = twin("settings_kids_turn_on", "Turn on kids mode")
    val kidsTurnOff = twin("settings_kids_turn_off", "Turn off kids mode")
    val kidsOn = twin("settings_kids_on", "Kids mode is on. Turn it off here to show every channel and title again.")
    val lockTitle = twin("lock_title", "Settings are locked")
    val lockHelp = twin("lock_help", "Enter the PIN to open Settings.")
    val lockUnlock = twin("lock_unlock", "Unlock")
    val lockWrong = twin("lock_wrong", "That's not the PIN.")
    val lockForgot = twin("lock_forgot", "Forgot the PIN")
    val lockForgotHelp = twin(
        "lock_forgot_help",
        "Enter the server's API token instead. The admin portal shows it under Settings › Advanced: Reveal the API " +
            "token. The right token removes the PIN.",
    )
    val lockForgotWrong = twin("lock_forgot_wrong", "That's not the server's API token.")

    val lockHelpFooter = twin(
        "settings_lock_help",
        "A PIN keeps Settings closed until it is entered. Watching and changing channels are not affected.",
    )

    // tvOS cannot clear an app's data, so it has its own words for a server with no token.
    val lockForgotNoToken = twin(
        "lock_forgot_no_token_tvos",
        "This server runs without an API token, so the only way to remove the PIN is to delete the app and install " +
            "it again.",
    )
    val pairingHelp = twin(
        "settings_pairing_help",
        "In the admin portal, open Devices and choose Show a pairing code, then enter the server address " +
            "above and the code here. No token needed.",
    )

    private val lockOn = twin(
        "settings_lock_on",
        "Settings now asks for the PIN. Forgot it? Choose Forgot the PIN on the lock screen and enter the server's " +
            "API token.",
    )
    private val kidsHelp = twin(
        "settings_kids_help",
        "Kids mode shows only kids' channels in the guide, and only titles rated G, PG, TV-Y, TV-Y7, TV-Y7-FV, TV-G " +
            "or TV-PG in the library. It hides the DVR tab. Turning it off takes the PIN.",
    )

    // Library
    val libraryEmpty = twin("library_empty", "Nothing here yet")
    val libraryLoadFailed = twin("library_load_failed", "The library didn't load")
    val libraryWatched = twin("library_watched", "Watched")
    val extraTrailer = twin("library_extra_trailer", "Trailer")
    val extraFeaturette = twin("library_extra_featurette", "Featurette")
    val extraBehindTheScenes = twin("library_extra_behind_the_scenes", "Behind the scenes")
    val extraDeletedScene = twin("library_extra_deleted_scene", "Deleted scene")
    val extraInterview = twin("library_extra_interview", "Interview")
    val extraScene = twin("library_extra_scene", "Scene")
    val extraShort = twin("library_extra_short", "Short")
    val extraOther = twin("library_extra_other", "Extra")

    // The players' sleep timer
    val sleepTimer = twin("player_control_sleep", "Sleep timer")
    val sleepEndProgram = twin("player_sleep_end_program", "Sleep after this program")
    val sleepEndItem = twin("player_sleep_end_item", "Sleep after this one")
    private val sleepMinutes = twin("player_sleep_minutes", "Sleep in %1\$d min")

    /** The sleep timer menu's note while a number of minutes runs. */
    fun sleepIn(minutes: Int): String = fill(sleepMinutes, minutes)

    private val lockWait = twin("lock_wait", "Too many tries; wait %1\$d seconds.")

    /** The wait shown while the PIN pad is throttled. */
    fun lockWait(seconds: Int): String = fill(lockWait, seconds)

    /** The note under the parental controls: what a PIN does, and with one set, how to get past it. */
    fun lockFooter(locked: Boolean, kidsMode: Boolean): String = when {
        !locked -> lockHelpFooter
        kidsMode -> kidsOn
        else -> "$lockOn $kidsHelp"
    }
}
