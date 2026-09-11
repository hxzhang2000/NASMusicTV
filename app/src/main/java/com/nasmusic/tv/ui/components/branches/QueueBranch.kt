package com.nasmusic.tv.ui.components.branches

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nasmusic.tv.data.model.Screen
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.data.model.*
import com.nasmusic.tv.ui.screens.*
import com.nasmusic.tv.ui.screens.library.*
import com.nasmusic.tv.ui.screens.settings.*
import com.nasmusic.tv.ui.screens.netdisk.*
import com.nasmusic.tv.ui.screens.stats.*
import com.nasmusic.tv.ui.viewmodel.*

/**
 * Queue 分支提取自 AppRoot（Method too large 根治：分支下沉 branches/）。
 * 分支体逐行搬迁，外层共享状态经参数注入。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun QueueBranch(
    viewModel: MainViewModel,
    currentSong: Song?,
    coverCandidates: List<String>,
    isPlaying: Boolean,
    playMode: com.nasmusic.tv.data.model.PlayMode
) {
                    val queue by viewModel.playerVM.queue.collectAsState(initial = emptyList())
                    val currentIndex by viewModel.playerVM.currentIndex.collectAsState(initial = 0)
                    QueueScreen(
                        queue = queue,
                        currentIndex = currentIndex,
                        currentSong = currentSong,
                        coverCandidates = coverCandidates,
                        isPlaying = isPlaying,
                        playMode = playMode,
                        onPlaySong = { index ->
                            if (index in queue.indices) {
                                viewModel.playQueue(queue, index)
                                viewModel.navVM.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onRemoveSong = { index -> viewModel.removeFromQueue(index) },
                        onClearQueue = { viewModel.clearQueue() },
                        onPlayPause = { viewModel.playerVM.playPause() },
                        onNext = { viewModel.playerVM.next() },
                        onPrevious = { viewModel.playerVM.previous() },
                        onMoveItem = { from, to -> viewModel.moveQueueItem(from, to) }
                    )
}
