package dev.jamesrich.sofaking.data

// The household's taste in music, as the repository's models.

/** Likes or unlikes the song, album or artist [id]. */
suspend fun LibraryRepository.setFavorite(id: String, favorite: Boolean): MediaItem =
    api.mediaItem(api.updateFavorite(id, favorite))

/** Counts one play of the song [id]. */
suspend fun LibraryRepository.recordPlay(id: String): MediaItem = api.mediaItem(api.recordPlay(id))
