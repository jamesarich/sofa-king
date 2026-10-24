package dev.jamesrich.sofaking.data

// Which audio and subtitle tracks play, the Compose app's and the Apple TV app's alike.

/** The subtitle tracks the server serves as text, which a player can draw; picture tracks (PGS, DVD) are left out. */
fun textSubtitles(detail: MediaItemDetail): List<MediaTrackInfo> = detail.subtitles.filterNot(::isPictureSubtitle)

/**
 * True for a picture subtitle track (PGS, DVD, DVB), which has no text: the server draws it into the picture when
 * the session asks for it, so picking one plays the item again.
 */
fun isPictureSubtitle(track: MediaTrackInfo): Boolean = track.codec !in TEXT_SUBTITLE_CODECS

/**
 * Where the server numbers the subtitle files beside a video, such as the ones Bazarr
 * downloads, after the file's own streams.
 */
const val SUBTITLE_FILE_BASE = 1000

/** True for a subtitle file beside the video rather than a stream inside it. */
fun isSubtitleFile(track: MediaTrackInfo): Boolean = track.index >= SUBTITLE_FILE_BASE

/**
 * The subtitles on when an item starts: a forced track, which carries only the dialogue the audio does not, text
 * before pictures (which the server has to draw in), else none.
 */
fun initialSubtitle(detail: MediaItemDetail): MediaTrackInfo? =
    detail.subtitles.filter { it.forced }.minByOrNull { isPictureSubtitle(it) }

/** The audio track playing when none is picked: the file's default, else its first. */
fun defaultAudio(detail: MediaItemDetail): MediaTrackInfo? =
    detail.audio.firstOrNull { it.default } ?: detail.audio.firstOrNull()

/**
 * The audio track an item starts with: one in the language the viewer picked in its show ([choice]), the file's
 * default among them first, else the file's default.
 */
fun initialAudio(detail: MediaItemDetail, choice: TrackChoice?): MediaTrackInfo? {
    val language = choice?.audioLanguage ?: return defaultAudio(detail)
    val matching = detail.audio.filter { sameLanguage(it.language, language) }
    return matching.firstOrNull { it.default } ?: matching.firstOrNull() ?: defaultAudio(detail)
}

/**
 * The subtitles on when an item starts: with subtitles on in the viewer's [choice] for its show, a full track in
 * that language (text before pictures, and a subtitle file before the file's own stream, since one beside the video
 * was usually fetched to replace it); otherwise as [initialSubtitle] decides.
 */
fun initialSubtitle(detail: MediaItemDetail, choice: TrackChoice?): MediaTrackInfo? {
    if (choice == null || !choice.subtitles) return initialSubtitle(detail)
    return detail.subtitles.filter { !it.forced && sameLanguage(it.language, choice.subtitleLanguage) }
        .minWithOrNull(compareBy<MediaTrackInfo> { isPictureSubtitle(it) }.thenBy { !isSubtitleFile(it) })
        ?: initialSubtitle(detail)
}

/**
 * What the viewer picked, by language: the audio track [audioIndex] and the subtitle track [subtitleIndex], or none.
 */
fun trackChoice(detail: MediaItemDetail, audioIndex: Int?, subtitleIndex: Int?): TrackChoice {
    val subtitle = subtitleIndex?.let { index -> detail.subtitles.firstOrNull { it.index == index } }
    return TrackChoice(
        audioLanguage = detail.audio.firstOrNull { it.index == audioIndex }?.language?.lowercase(),
        subtitles = subtitle != null,
        subtitleLanguage = subtitle?.language?.lowercase(),
    )
}

/** Subtitle codecs the server converts to WebVTT. */
private val TEXT_SUBTITLE_CODECS = setOf("subrip", "srt", "ass", "ssa", "mov_text", "webvtt", "text")
