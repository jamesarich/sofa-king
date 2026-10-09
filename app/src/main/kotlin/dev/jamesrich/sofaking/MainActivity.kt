package dev.jamesrich.sofaking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import dev.jamesrich.sofaking.ui.ChannelListScreen
import dev.jamesrich.sofaking.ui.MessageScreen
import dev.jamesrich.sofaking.ui.PlayerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val vm: LineupViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                var playing by rememberSaveable { mutableStateOf<Int?>(null) }
                when (val s = state) {
                    LineupState.Loading -> MessageScreen(stringResource(R.string.loading))
                    is LineupState.Failed -> MessageScreen(stringResource(R.string.load_failed, s.message), onRetry = vm::refresh)
                    is LineupState.Ready -> {
                        val index = playing
                        if (index == null || s.lineup.channels.isEmpty()) {
                            ChannelListScreen(s.lineup, onPlay = { playing = s.lineup.channels.indexOf(it) })
                        } else {
                            PlayerScreen(s.lineup, index, onExit = { playing = null })
                        }
                    }
                }
            }
        }
    }
}
