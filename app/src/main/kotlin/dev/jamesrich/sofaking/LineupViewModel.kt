package dev.jamesrich.sofaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jamesrich.sofaking.data.DispatcharrRepository
import dev.jamesrich.sofaking.data.Lineup
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours

sealed interface LineupState {
    data object Loading : LineupState
    data class Ready(val lineup: Lineup) : LineupState
    data class Failed(val message: String) : LineupState
}

class LineupViewModel(
    private val repository: DispatcharrRepository = DispatcharrRepository(BuildConfig.DISPATCHARR_URL, BuildConfig.DISPATCHARR_PROFILE),
) : ViewModel() {
    private val _state = MutableStateFlow<LineupState>(LineupState.Loading)
    val state: StateFlow<LineupState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refresh()
    }

    /** Loads now, then reloads every few hours so new channels and guide data appear without a restart. */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                if (_state.value !is LineupState.Ready) _state.value = LineupState.Loading
                _state.value = runCatching { LineupState.Ready(repository.load()) }
                    .getOrElse { error ->
                        val previous = _state.value
                        if (previous is LineupState.Ready) previous else LineupState.Failed(error.message ?: error.toString())
                    }
                delay(REFRESH_INTERVAL)
            }
        }
    }

    private companion object {
        val REFRESH_INTERVAL = 4.hours
    }
}
