package dev.jamesrich.sofaking.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import dev.jamesrich.sofaking.ui.theme.SofaKingTheme
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LibrarySearchScreenTest {
    private val loader = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test
    fun `clearing the query mid-search takes the loader down`() =
        runSkikoComposeUiTest(Size(1920f, 1080f), Density(2f)) {
            val queries = mutableListOf<String>()
            val actions = LibrarySearchActions(
                search = {
                    queries += it
                    awaitCancellation()
                },
                onOpenItem = {},
                onPlayItem = {},
            )
            setContent {
                SofaKingTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        LibrarySearchScreen(actions, voice = false, returnedFrom = null, initialQuery = "star")
                    }
                }
            }
            waitUntil { queries.isNotEmpty() }
            assertEquals(listOf("star"), queries)
            onAllNodes(loader, useUnmergedTree = true).assertCountEquals(1)

            onNode(hasSetTextAction()).performTextClearance()
            waitForIdle()
            onAllNodes(loader, useUnmergedTree = true).assertCountEquals(0)
        }
}
