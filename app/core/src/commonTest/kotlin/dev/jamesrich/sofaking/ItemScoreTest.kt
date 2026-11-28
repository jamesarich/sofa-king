package dev.jamesrich.sofaking

import dev.jamesrich.sofaking.contract.Wire
import dev.jamesrich.sofaking.data.ItemScore
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemScoreTest {
    @Test
    fun `a score out of 10 reads with one decimal under its source's name`() {
        assertEquals("IMDb 7.8", ItemScore(Wire.RatingSource.IMDB, 7.8).label)
        assertEquals("TMDB 7.0", ItemScore(Wire.RatingSource.TMDB, 7.0).label)
        assertEquals("Trakt 8.1", ItemScore(Wire.RatingSource.TRAKT, 8.06).label)
        assertEquals("Letterboxd 3.9", ItemScore(Wire.RatingSource.LETTERBOXD, 3.9, max = 5.0).label)
    }

    @Test
    fun `Rotten Tomatoes reads as a percentage and Metacritic as a whole number`() {
        assertEquals("Rotten Tomatoes 91%", ItemScore(Wire.RatingSource.ROTTEN_TOMATOES, 91.0, 100.0).label)
        assertEquals("RT Audience 85%", ItemScore(Wire.RatingSource.ROTTEN_TOMATOES_AUDIENCE, 8.5, 10.0).label)
        assertEquals("Metacritic 74", ItemScore(Wire.RatingSource.METACRITIC, 74.0, 100.0).label)
    }

    @Test
    fun `a source the app does not know keeps the name the server sent`() {
        assertEquals("douban 8.2", ItemScore("douban", 8.2).label)
    }
}
