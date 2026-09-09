package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.network.mv.MvSearchManager
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * MTV 音乐视频域 ViewModel（R-1 拆分自 MainViewModel）：
 * MV 搜索/候选切换/连播索引推进/重搜。
 *
 * 依赖：MvSearchManager、PlayerManager（MV 模式控制与队列索引推进）。
 */
class MvSearchViewModel(
    app: Application,
    private val mvSearchManager: MvSearchManager,
    private val playerManager: PlayerManager
) : AndroidViewModel(app) {

    private val _mvState = MutableStateFlow<MvAvailability>(MvAvailability.Idle)
    val mvState: StateFlow<MvAvailability> = _mvState.asStateFlow()

    /** MTV 页面显隐（进入 MTV 页面时为 true） */
    private val _showMv = MutableStateFlow(false)
    val showMv: StateFlow<Boolean> = _showMv.asStateFlow()

    private var mvSearchJob: Job? = null

    /** 当前歌曲的 MV 播放失败是否已重搜过一次（防止死循环；切歌时在 triggerMvSearch 内重置） */
    private var mvRetryDone = false

    /** 预搜的下一首 MV 结果（null = 未搜到或尚未完成预搜） */
    private var pendingNextResult: com.nasmusic.tv.data.model.MvSearchResult? = null

    /** MTV 连播是否已静默推进队列索引（退出时据此决定 syncAndPlay 还是 resume） */
    private var mvAdvanced = false

    /** 静默推进索引时跳过 currentSong.collect 的 triggerMvSearch（避免覆盖预搜结果） */
    private var skipNextMvSearch = false

    /** 当前搜索会话内「切换」已按次数（达到 2×候选总数后触发重搜） */
    private var mvSwitchCount = 0
    /** 重搜次数（上限 2 次，防止无限重搜） */
    private var mvResearchCount = 0
    /** 重搜时排除的 bvid 集合（已展示过的视频不再出现） */
    private val mvExcludedBvids = mutableSetOf<String>()

    /** MTV 页面短暂提示（切换失败/未找到更多视频），2 秒后自动清除 */
    private val _mvMessage = MutableStateFlow<String?>(null)
    val mvMessage: StateFlow<String?> = _mvMessage.asStateFlow()

    /** 切歌时由 MainViewModel 的 currentSong 收集器调用（避免覆盖预搜结果的跳过标记） */
    fun shouldSkipNextSearch(): Boolean {
        val skip = skipNextMvSearch
        skipNextMvSearch = false
        return skip
    }

    /** 当前歌曲为空时重置 MV 状态（由 MainViewModel 的 currentSong 收集器调用） */
    fun resetIdle() {
        _mvState.value = MvAvailability.Idle
    }

    /**
     * 切歌/播放时自动搜索当前歌曲的 MV（MvSearchManager 内部有内存缓存，命中不重复请求）。
     * 置 Searching → searchMvFor → Ready/NotFound；由 UI 按钮消费决定亮/暗。
     */
    fun triggerMvSearch(song: Song) {
        mvRetryDone = false
        pendingNextResult = null // 清除旧预搜
        mvSwitchCount = 0 // 重置切换计数
        mvResearchCount = 0 // 重置重搜计数
        mvExcludedBvids.clear() // 清除排除列表
        mvSearchJob?.cancel()
        _mvState.value = MvAvailability.Searching
        mvSearchJob = viewModelScope.launch {
            val result = try {
                mvSearchManager.searchMvFor(song)
            } catch (e: Exception) {
                AppLog.e("MvSearchViewModel", "triggerMvSearch failed", e)
                null
            }
            _mvState.value = if (result != null) MvAvailability.Ready(result.mv, result.alternatives) else MvAvailability.NotFound
            AppLog.d("MvSearchViewModel", "triggerMvSearch: ${song.title} -> ${if (result != null) "found ${result.mv.title} + ${result.alternatives.size} alts" else "not found"}")
            // MTV 模式下预搜下一首
            if (result != null && _showMv.value) preSearchNextMv()
        }
    }

    /**
     * 进入 MTV 页面：暂停主播放器 + 显示 MTV 页 + 预搜下一首 MV。
     */
    fun enterMvMode() {
        val ready = _mvState.value as? MvAvailability.Ready ?: return
        mvAdvanced = false
        AppLog.d("MvSearchViewModel", "enterMvMode: ${ready.mv.title}")
        playerManager.suppressPlayback = true
        playerManager.pause()
        _showMv.value = true
        preSearchNextMv()
    }

    /**
     * 退出 MTV 页面：隐藏 MTV 页 + 恢复主播放器。
     * 若 MTV 连播已静默推进队列索引（mvAdvanced），用 syncAndPlayCurrent 同步到新歌；
     * 否则 resume 从暂停位置续播。
     */
    fun exitMvMode() {
        AppLog.d("MvSearchViewModel", "exitMvMode: mvAdvanced=$mvAdvanced")
        _showMv.value = false
        pendingNextResult = null
        playerManager.suppressPlayback = false // 恢复播放前先解除限制
        if (mvAdvanced) {
            playerManager.syncAndPlayCurrent()
        } else {
            playerManager.resume()
        }
    }

    /**
     * MV 播放失败回调：清缓存 + 重搜一次（同一首歌只重搜一次防死循环）。
     */
    fun onMvPlaybackError(currentSong: Song?) {
        if (mvRetryDone) {
            AppLog.d("MvSearchViewModel", "onMvPlaybackError: already retried, skip")
            return
        }
        val song = currentSong ?: return
        mvRetryDone = true
        AppLog.d("MvSearchViewModel", "onMvPlaybackError: clearCache + re-search '${song.title}'")
        mvSearchManager.clearCache()
        triggerMvSearch(song)
    }

    /**
     * MV 播放结束回调（连播模式）：
     * - 有预搜结果 -> 静默推进队列索引 + 直接设 Ready（无缝切换，无 Searching 闪烁，无混音）
     * - 无预搜结果 -> 静默推进 + 设 NotFound -> AppRoot 自动 exitMvMode -> syncAndPlayCurrent 播下一首
     */
    fun onMvPlaybackEnded(currentSong: Song?, playMode: PlayMode) {
        // 标记当前 MV 播放完成（用户认可这个版本）-> 持久缓存 playCount++，下次优先用这个 bvid
        val completedSong = currentSong
        val completedMv = (_mvState.value as? MvAvailability.Ready)?.mv
        if (completedSong != null && completedMv != null) {
            mvSearchManager.markCompleted(completedSong.id, completedSong.title, completedSong.artist, completedMv.bvid, completedMv.title)
        }

        val pending = pendingNextResult
        skipNextMvSearch = true // advanceIndexSilently 会更新 currentSong，跳过 collect 的 triggerMvSearch
        playerManager.advanceIndexSilently(playMode)
        mvAdvanced = true

        if (pending != null) {
            _mvState.value = MvAvailability.Ready(pending.mv, pending.alternatives)
            pendingNextResult = null
            AppLog.d("MvSearchViewModel", "onMvPlaybackEnded: seamless switch to '${pending.mv.title}'")
            preSearchNextMv()
        } else {
            _mvState.value = MvAvailability.NotFound // 触发 AppRoot 自动 exitMvMode
            AppLog.d("MvSearchViewModel", "onMvPlaybackEnded: no pre-searched MV, exiting to playback")
        }
    }

    /**
     * MTV 页面"上一首"按钮：回退队列索引 + 搜索前一首的 MV（无预搜，走 Searching）。
     */
    fun onMvPrevious(playMode: PlayMode) {
        skipNextMvSearch = true
        val prevSong = playerManager.advanceIndexBackward(playMode)
        if (prevSong == null) {
            skipNextMvSearch = false
            return
        }
        mvAdvanced = true
        _mvState.value = MvAvailability.Searching
        mvSearchJob?.cancel()
        mvSearchJob = viewModelScope.launch {
            val result = try {
                mvSearchManager.searchMvFor(prevSong)
            } catch (e: Exception) {
                null
            }
            _mvState.value = if (result != null) MvAvailability.Ready(result.mv, result.alternatives) else MvAvailability.NotFound
            AppLog.d("MvSearchViewModel", "onMvPrevious: '${prevSong.title}' -> ${if (result != null) "found" else "not found"}")
            if (result != null) preSearchNextMv()
        }
    }

    /**
     * MTV 页面"下一首"按钮：有预搜则无缝切换，无则同步搜索。
     */
    fun onMvNext(playMode: PlayMode) {
        val pending = pendingNextResult
        if (pending != null) {
            skipNextMvSearch = true
            playerManager.advanceIndexSilently(playMode)
            mvAdvanced = true
            _mvState.value = MvAvailability.Ready(pending.mv, pending.alternatives)
            pendingNextResult = null
            AppLog.d("MvSearchViewModel", "onMvNext: seamless switch to '${pending.mv.title}'")
            preSearchNextMv()
        } else {
            skipNextMvSearch = true
            val nextSong = playerManager.advanceIndexSilently(playMode)
            if (nextSong == null) { skipNextMvSearch = false; return }
            mvAdvanced = true
            _mvState.value = MvAvailability.Searching
            mvSearchJob?.cancel()
            mvSearchJob = viewModelScope.launch {
                val result = try { mvSearchManager.searchMvFor(nextSong) } catch (e: Exception) { null }
                _mvState.value = if (result != null) MvAvailability.Ready(result.mv, result.alternatives) else MvAvailability.NotFound
                AppLog.d("MvSearchViewModel", "onMvNext: '${nextSong.title}' -> ${if (result != null) "found" else "not found"}")
                if (result != null) preSearchNextMv()
            }
        }
    }

    /**
     * 预搜下一首歌曲的 MV（后台协程，不阻塞 UI）。
     * MTV 模式下当前 MV 搜到后调用，结果存入 [pendingNextResult] 供 onMvPlaybackEnded 无缝切换。
     */
    private fun preSearchNextMv() {
        val nextSong = playerManager.peekNextSong(currentPlayMode) ?: run {
            pendingNextResult = null
            return
        }
        viewModelScope.launch {
            val result = try {
                mvSearchManager.searchMvFor(nextSong)
            } catch (e: Exception) {
                AppLog.w("MvSearchViewModel", "preSearchNextMv failed: ${e.message}", e)
                null
            }
            pendingNextResult = result
            AppLog.d("MvSearchViewModel", "preSearchNextMv: '${nextSong.title}' -> ${if (result != null) "found ${result.mv.title}" else "not found"}")
        }
    }

    /**
     * MTV 页面「切换」按钮统一入口：
     * - 无候选 -> 直接重搜（排除当前 bvid）
     * - 有候选，已切换 2 轮 -> 重搜（排除所有已展示 bvid）
     * - 有候选，未满 2 轮 -> 切换到下一个候选
     * - 重搜次数已达上限（2 次）-> 提示"未找到更多视频"
     */
    fun onSwitchOrResearch(currentSong: Song?) {
        val ready = _mvState.value as? MvAvailability.Ready ?: return
        val totalVideos = 1 + ready.alternatives.size

        if (ready.alternatives.isEmpty()) {
            if (mvResearchCount >= 2) { showMvMessage(getApplication<Application>().getString(R.string.mv_no_more_videos)); return }
            researchMv(ready, currentSong)
            return
        }

        mvSwitchCount++
        if (mvSwitchCount > 2 * totalVideos) {
            if (mvResearchCount >= 2) { showMvMessage(getApplication<Application>().getString(R.string.mv_no_more_videos)); return }
            researchMv(ready, currentSong)
        } else {
            switchToNextCandidate(ready)
        }
    }

    /**
     * MTV 页面「搜B站」按钮：当前 MV 来自百度网盘本地文件（source == "baidu"）时，
     * 强制从非百度源（B 站）重新搜索，替换当前 MV 状态。
     */
    fun onSearchBilibili(currentSong: Song?) {
        val song = currentSong ?: return
        _mvState.value = MvAvailability.Searching
        mvSearchJob?.cancel()
        mvSearchJob = viewModelScope.launch {
            val result = try {
                mvSearchManager.searchBilibiliFallback(song)
            } catch (e: Exception) {
                AppLog.e("MvSearchViewModel", "onSearchBilibili failed", e)
                null
            }
            if (result != null) {
                _mvState.value = MvAvailability.Ready(result.mv, result.alternatives)
                showMvMessage(getApplication<Application>().getString(R.string.mv_switched_to_bilibili))
                preSearchNextMv()
            } else {
                _mvState.value = MvAvailability.NotFound
                showMvMessage(getApplication<Application>().getString(R.string.mv_bilibili_not_found))
            }
            AppLog.d("MvSearchViewModel", "onSearchBilibili: '${song.title}' -> ${if (result != null) "found ${result.mv.title}" else "not found"}")
        }
    }

    /** 切换到候选列表中的下一个视频 */
    private fun switchToNextCandidate(ready: MvAvailability.Ready) {
        val targetBvid = ready.alternatives.firstOrNull()?.bvid ?: return
        viewModelScope.launch {
            AppLog.d("MvSearchViewModel", "switchToNextCandidate: bvid=$targetBvid")
            val newMv = mvSearchManager.resolveMv(targetBvid)
            if (newMv == null) {
                AppLog.w("MvSearchViewModel", "switchToNextCandidate: resolve failed")
                showMvMessage(getApplication<Application>().getString(R.string.mv_switch_failed_retry))
                mvSwitchCount-- // 切换未成功，回退计数
                return@launch
            }
            val oldCandidate = com.nasmusic.tv.data.model.MvCandidate(ready.mv.bvid, ready.mv.title, ready.mv.coverUrl)
            val newAlternatives = ready.alternatives.filter { it.bvid != targetBvid } + oldCandidate
            _mvState.value = MvAvailability.Ready(newMv, newAlternatives)
            AppLog.d("MvSearchViewModel", "switchToNextCandidate: switched to '${newMv.title}'")
        }
    }

    /** 重搜：排除已展示 bvid + 降低相似度阈值，后台搜索不打断当前播放 */
    private fun researchMv(ready: MvAvailability.Ready, currentSong: Song?) {
        val song = currentSong ?: return
        mvExcludedBvids.add(ready.mv.bvid)
        ready.alternatives.forEach { mvExcludedBvids.add(it.bvid) }
        mvResearchCount++
        val minSim = when (mvResearchCount) { 1 -> 0.3f; 2 -> 0.1f; else -> 0f }
        AppLog.d("MvSearchViewModel", "researchMv: #${mvResearchCount} exclude=${mvExcludedBvids.size} minSim=$minSim")
        showMvMessage(getApplication<Application>().getString(R.string.mv_searching_more))
        mvSearchJob?.cancel()
        mvSearchJob = viewModelScope.launch {
            val result = try {
                mvSearchManager.searchMvFor(song, forceRefresh = true, excludeBvids = mvExcludedBvids.toSet(), minSimilarity = minSim)
            } catch (e: Exception) {
                AppLog.e("MvSearchViewModel", "researchMv failed", e)
                null
            }
            if (result != null) {
                mvSwitchCount = 0
                _mvState.value = MvAvailability.Ready(result.mv, result.alternatives)
                showMvMessage(getApplication<Application>().getString(R.string.mv_found_new_videos, 1 + result.alternatives.size))
                if (_showMv.value) preSearchNextMv()
            } else {
                mvResearchCount--
                showMvMessage(getApplication<Application>().getString(R.string.mv_no_more_videos))
            }
        }
    }

    /** 清除 MV 持久缓存（设置页"缓存管理"手动清除用） */
    fun clearPersistentCache(onCleared: () -> Unit) {
        viewModelScope.launch {
            mvSearchManager.clearPersistentCache()
            onCleared()
        }
    }

    private var mvMessageJob: Job? = null
    private fun showMvMessage(msg: String) {
        mvMessageJob?.cancel()
        _mvMessage.value = msg
        mvMessageJob = viewModelScope.launch {
            delay(2000)
            _mvMessage.value = null
        }
    }

    // ---- 与 MainViewModel 的共享播放模式（playMode 由 MainViewModel 拥有，经此注入）----
    /** 当前播放模式（由 MainViewModel 设置；PlayerManager 的 playMode 是方法参数语义） */
    var currentPlayMode: PlayMode = PlayMode.SEQUENTIAL
}
