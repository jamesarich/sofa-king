package dev.jamesrich.sofaking.backend.library

import java.nio.file.Path

/** The digits a disc's and a track's number are padded to, so that they sort as numbers. */
private const val DISC_SORT_DIGITS = 3
private const val TRACK_SORT_DIGITS = 4

/**
 * A disc's own folder inside its album's: `CD1`, `Disc 2`, or a medium and its number as MusicBrainz Picard names
 * them, `12" Vinyl 01`, `Digital Media 02`.
 */
private val DISC_FOLDER = Regex(
    """(?i)^(?:\d{1,2}"?\s*)?(?:cd|disc|disk|vinyl|lp|dvd|sacd|cassette|digital media)[\s._-]*(\d{1,2})$""",
)

/** `Artist - Album - 03 - Title`, as Picard and Lidarr name files. */
private val NAMED_IN_FULL = Regex("""^.+ - .+ - (?<track>\d{1,3}) - (?<title>.+)$""")

/** A track number before the title in a file name: `03 - Title`, `03. Title`, `1-03 Title`. */
private val NUMBERED = Regex("""^(?:(?<disc>\d)[-.])?(?<track>\d{1,3})(?:\s*[-.]\s*|[\s_]+)(?<title>.+)$""")

internal const val UNKNOWN_ARTIST = "Unknown artist"
internal const val UNKNOWN_ALBUM = "Unknown album"
internal const val VARIOUS_ARTISTS = "Various Artists"

/** The track in [file] before its tags are read: its file, to probe. */
internal fun trackItem(libraryId: String, file: FoundFile): LibraryItemRecord {
    val stem = file.relative.substringAfterLast('/').substringBeforeLast('.')
    return LibraryItemRecord(
        id = itemId(libraryId, "file:${file.relative}"),
        libraryId = libraryId,
        kind = ItemKind.TRACK,
        path = file.relative,
        title = stem,
        size = file.size,
        modifiedAt = file.modifiedAt,
        addedAt = "",
    )
}

/**
 * The artists, albums and tracks of a music library at [root], from the tags its [items] tracks were probed for.
 * A track belongs to its album artist (else its artist) and album; one without those tags to the folders it is in,
 * `Artist/Album/Track.ext` (a `CD1` or `Disc 2` folder in the album's being its disc). Artists and albums are keyed by
 * their names, so a rescan, or files moved between folders, keeps their ids.
 */
internal class MusicItems(private val libraryId: String, private val root: Path) {
    private val artists = LinkedHashMap<String, LibraryItemRecord>()
    private val albums = LinkedHashMap<String, Album>()

    private class Album(var record: LibraryItemRecord, val tracks: MutableList<LibraryItemRecord> = ArrayList())

    /** The album artist of each album folder and album title whose tracks have no album artist tag. */
    private var untaggedAlbums: Map<Pair<String, String>, String> = emptyMap()

    /** The artists, then the albums, then the tracks. */
    fun items(items: List<LibraryItemRecord>): List<LibraryItemRecord> {
        val tracks = items.filter { it.kind == ItemKind.TRACK && it.path != null }
        untaggedAlbums =
            tracks.filter { it.media?.tags?.let { tags -> tags.album != null && tags.albumArtist == null } == true }
                .groupBy { albumFolderOf(it.path!!) to key(it.media!!.tags!!.album!!) }
                .mapValues { (_, group) -> albumArtistOf(group.map { it.media!!.tags!! }) }
        tracks.forEach(::add)
        val grouped = albums.values.map { album -> album.record to album.tracks }
        return artists.values + grouped.map { (album, tracks) -> summed(album, tracks) } + grouped.flatMap { it.second }
    }

    private fun add(probed: LibraryItemRecord) {
        val relative = probed.path ?: return
        val tags = probed.media?.tags
        val place = placeOf(relative, tags)
        val artistKey = key(place.artist)
        val artist = artists.getOrPut(artistKey) { artist(artistKey, place.artist, place.albumFolder) }
        val albumKey = "$artistKey\u0000${key(place.album)}"
        val album = albums.getOrPut(albumKey) { Album(album(albumKey, place.album, artist, place.albumFolder)) }
        val named = TrackName.of(relative.substringAfterLast('/').substringBeforeLast('.'), tags, place.disc)
        album.tracks += probed.copy(
            parentId = album.record.id,
            title = named.title,
            sortTitle = named.order,
            year = yearOf(tags?.date),
            details = ItemDetails(
                genres = tags?.genres.orEmpty(),
                artist = tags?.artist?.takeIf { key(it) != artistKey },
                track = named.track,
                disc = named.disc,
            ),
        )
        // The first track with a picture beside it gives its album the cover, wherever the album's tracks are.
        if (album.record.poster == null) {
            album.record = album.record.copy(poster = cover(root.resolve(relative).parent))
        }
    }

    /** Where a track belongs: its artist and album, the folder holding the album and the disc its folder names. */
    private class Place(val artist: String, val album: String, val albumFolder: Path?, val disc: Int?)

    private fun placeOf(relative: String, tags: AudioTags?): Place {
        val folders = relative.split('/').dropLast(1)
        val discFolder = folders.lastOrNull()?.let { DISC_FOLDER.matchEntire(it) }
        val albumFolders = if (discFolder != null) folders.dropLast(1) else folders
        val artist = tags?.albumArtist
            ?: VARIOUS_ARTISTS.takeIf { tags?.compilation == true }
            ?: tags?.album?.let { untaggedAlbums[albumFolders.joinToString("/") to key(it)] }
            ?: tags?.artist
            ?: albumFolders.getOrNull(albumFolders.size - 2)
            ?: UNKNOWN_ARTIST
        return Place(
            artist,
            tags?.album ?: albumFolders.lastOrNull() ?: UNKNOWN_ALBUM,
            albumFolders.takeIf { it.isNotEmpty() }?.let { root.resolve(it.joinToString("/")) },
            discFolder?.groupValues?.get(1)?.toIntOrNull(),
        )
    }

    private fun artist(key: String, name: String, albumFolder: Path?): LibraryItemRecord {
        // The folder above an album's is its artist's when it is not the library's own.
        val folder = albumFolder?.parent?.takeIf { it != root && it.startsWith(root) }
        return LibraryItemRecord(
            id = itemId(libraryId, "artist:$key"),
            libraryId = libraryId,
            kind = ItemKind.ARTIST,
            title = name,
            poster = folder?.let { art(root, it.resolve("artist"), it.resolve("folder")) },
            addedAt = "",
        )
    }

    private fun album(key: String, name: String, artist: LibraryItemRecord, folder: Path?) = LibraryItemRecord(
        id = itemId(libraryId, "album:$key"),
        libraryId = libraryId,
        kind = ItemKind.ALBUM,
        parentId = artist.id,
        title = name,
        poster = folder?.let(::cover),
        addedAt = "",
    )

    /**
     * A cover image in [folder]: `cover`, `folder`, `front` or `album`, as music players name them. The library's own
     * folder holds loose tracks of any album, so none of its pictures is one album's.
     */
    private fun cover(folder: Path): String? = if (folder == root || !folder.startsWith(root)) {
        null
    } else {
        art(root, folder.resolve("cover"), folder.resolve("folder"), folder.resolve("front"), folder.resolve("album"))
    }

    /** [album] with its tracks' earliest year, length and genres. */
    private fun summed(album: LibraryItemRecord, tracks: List<LibraryItemRecord>): LibraryItemRecord {
        val genres = tracks.flatMap { it.details?.genres.orEmpty() }.distinct()
        return album.copy(
            year = tracks.mapNotNull { it.year }.minOrNull(),
            durationMs = tracks.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum(),
            details = genres.takeIf { it.isNotEmpty() }?.let { ItemDetails(genres = it) },
        )
    }
}

/** A track's title and numbers, from its tags, else its file name's [stem]; [order] sorts it in its album. */
private class TrackName(val title: String, val track: Int?, val disc: Int?) {
    val order = listOf(
        (disc ?: 1).toString().padStart(DISC_SORT_DIGITS, '0'),
        (track ?: 0).toString().padStart(TRACK_SORT_DIGITS, '0'),
        sortTitle(title),
    ).joinToString(" ")

    companion object {
        /** [folderDisc] is the disc a `CD2` folder names. */
        fun of(stem: String, tags: AudioTags?, folderDisc: Int?): TrackName {
            val full = NAMED_IN_FULL.matchEntire(stem)?.groups
            val short = if (full == null) NUMBERED.matchEntire(stem)?.groups else null
            val numbered = full ?: short
            return TrackName(
                tags?.title ?: numbered?.get("title")?.value?.trim() ?: stem,
                tags?.track ?: numbered?.get("track")?.value?.toIntOrNull(),
                tags?.disc ?: folderDisc ?: short?.get("disc")?.value?.toIntOrNull(),
            )
        }
    }
}

/** The folder holding [relative]'s album: its own, or the one above a disc folder. */
private fun albumFolderOf(relative: String): String {
    val folders = relative.split('/').dropLast(1)
    return (if (folders.lastOrNull()?.let(DISC_FOLDER::matches) == true) folders.dropLast(1) else folders)
        .joinToString("/")
}

/**
 * Who an album whose tracks name no album artist is by: the artist of most of its tracks, as when one has a guest
 * (`Band feat. Guest`), or Various Artists when no artist has most of them or a track says it is a compilation.
 */
private fun albumArtistOf(tracks: List<AudioTags>): String {
    if (tracks.any { it.compilation }) return VARIOUS_ARTISTS
    val (top, count) = tracks.mapNotNull { it.artist }.groupingBy(::key).eachCount().maxByOrNull { it.value }
        ?: return UNKNOWN_ARTIST
    return if (count * 2 >
        tracks.size
    ) {
        tracks.first { it.artist != null && key(it.artist) == top }.artist!!
    } else {
        VARIOUS_ARTISTS
    }
}

/** A name as it groups: case, spacing and a leading "The" ignored, so `The Beatles` and `Beatles` are one artist. */
private fun key(name: String): String = sortTitle(name.replace(Regex("""\s+"""), " "))
