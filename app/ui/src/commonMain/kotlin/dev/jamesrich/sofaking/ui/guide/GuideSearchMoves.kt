package dev.jamesrich.sofaking.ui.guide

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd

/** The channel the player was left on is hidden: the filter and search give way to the whole lineup. */
internal fun GuideState.showAll() {
    filter = GuideFilter.All
    searchOpen = false
    searchText.setTextAndPlaceCursorAtEnd("")
}

internal fun GuideState.closeSearch() {
    searchOpen = false
    searchText.setTextAndPlaceCursorAtEnd("")
    returnToSearchChip = true
}

internal fun GuideState.toggleSearch() {
    if (searchOpen) closeSearch() else searchOpen = true
}

/**
 * The recognizer answered: the [spoken] words become the query and its first row takes focus;
 * none leaves the field to type in.
 */
internal fun GuideState.voiceSearched(spoken: String?) {
    searchOpen = true
    if (spoken != null) {
        searchText.setTextAndPlaceCursorAtEnd(spoken)
        focusResults = true
    }
    searchFocusRequest++
}

/** Search without a recognizer: the text field opens and takes focus. */
internal fun GuideState.openSearchField() {
    searchOpen = true
    searchFocusRequest++
}
