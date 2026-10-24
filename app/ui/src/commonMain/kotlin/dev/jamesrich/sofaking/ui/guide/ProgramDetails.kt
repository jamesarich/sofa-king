package dev.jamesrich.sofaking.ui.guide

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.jamesrich.sofaking.data.Channel
import dev.jamesrich.sofaking.data.Program

/** What the details sheet shows: a channel and the program focused in its row, which may be a gap. */
@Immutable
internal data class ProgramDetails(val channel: Channel, val program: Program?)
