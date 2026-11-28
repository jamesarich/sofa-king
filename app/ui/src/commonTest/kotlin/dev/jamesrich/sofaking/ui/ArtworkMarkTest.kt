package dev.jamesrich.sofaking.ui

import dev.jamesrich.sofaking.data.ItemKind
import dev.jamesrich.sofaking.ui.theme.SofaIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtworkMarkTest {
    @Test
    fun musicWithoutACoverShowsANote() {
        listOf(ItemKind.ARTIST, ItemKind.ALBUM, ItemKind.TRACK).forEach {
            assertEquals(SofaIcons.Audiotrack, artworkMark(it), it.name)
        }
    }

    @Test
    fun aMovieAndAShowHaveTheirOwnMarks() {
        assertEquals(SofaIcons.Movie, artworkMark(ItemKind.MOVIE))
        listOf(ItemKind.SHOW, ItemKind.SEASON, ItemKind.EPISODE).forEach {
            assertEquals(SofaIcons.Tv, artworkMark(it), it.name)
        }
    }

    @Test
    fun anythingElseKeepsTheLibraryMark() {
        listOf(ItemKind.VIDEO, ItemKind.EXTRA, ItemKind.OTHER).forEach {
            assertEquals(SofaIcons.VideoLibrary, artworkMark(it), it.name)
        }
    }
}
