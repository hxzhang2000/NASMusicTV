package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.backend.weather.WeatherApi
import com.nasmusic.tv.backend.weather.WeatherRadioManager
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.WeatherData
import com.nasmusic.tv.data.model.WeatherForecast
import com.nasmusic.tv.data.model.WeatherMood
import com.nasmusic.tv.data.model.WeatherRadioQueue
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 天气电台域 ViewModel（R-1 拆分自 MainViewModel）。
 *
 * 关键语义（W0 冻结）：[weatherRadioManager] 为 **可空延迟创建**——
 * 无 NAS 连接时不实例化（天气电台允许在无后端时工作），不得改为构造期固定依赖。
 */
class WeatherRadioViewModel(
    app: Application,
    private val playerManager: PlayerManager,
    private val networkMusicManager: NetworkMusicManager
) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val backendRegistry = nasMusicApp.backendRegistry
    private val prefs = nasMusicApp.appPreferences

    val weatherApi = WeatherApi()
    var weatherRadioManager: WeatherRadioManager? = null
        private set

    private val _weatherData = MutableStateFlow<WeatherData?>(null)
    val weatherData: StateFlow<WeatherData?> = _weatherData.asStateFlow()

    private val _weatherRadioQueue = MutableStateFlow<WeatherRadioQueue?>(null)
    val weatherRadioQueue: StateFlow<WeatherRadioQueue?> = _weatherRadioQueue.asStateFlow()

    private val _currentWeatherMood = MutableStateFlow(WeatherMood.SUNNY)
    val currentWeatherMood: StateFlow<WeatherMood> = _currentWeatherMood.asStateFlow()

    private val _weatherLoading = MutableStateFlow(false)
    val weatherLoading: StateFlow<Boolean> = _weatherLoading.asStateFlow()

    private val _weatherError = MutableStateFlow<String?>(null)
    val weatherError: StateFlow<String?> = _weatherError.asStateFlow()

    private val _weatherForecast = MutableStateFlow<List<WeatherForecast>>(emptyList())
    val weatherForecast: StateFlow<List<WeatherForecast>> = _weatherForecast.asStateFlow()

    private val _weatherIconCode = MutableStateFlow<String?>(null)
    val weatherIconCode: StateFlow<String?> = _weatherIconCode.asStateFlow()

    // ---- 跨域事件（W0 冻结契约）----
    private val _events = MutableSharedFlow<WeatherRadioEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<WeatherRadioEvent> = _events.asSharedFlow()

    /** 天气电台「换一批」已展示歌曲（歌手, 歌名）集合：跨构建去重，mood 变化时重置 */
    private val weatherSeenKeys = mutableSetOf<Pair<String, String>>()

    /** 换一批统一逻辑的参数（与 MainViewModel.pickBestFreshBatch 对齐） */
    private val maxShuffleAttemptsPerClick = 6
    private val minNewResultsForShuffle = 5

    /**
     * 获取当前天气并构建天气电台
     */
    fun fetchWeather() {
        viewModelScope.launch {
            _weatherLoading.value = true
            _weatherError.value = null
            try {
                val apiKey = prefs.getWeatherApiKeySync()
                val weather = weatherApi.fetchCurrentWeather(
                    openWeatherMapApiKey = apiKey.ifBlank { null }
                )
                if (weather != null) {
                    _weatherData.value = weather
                    // 获取天气图标代码（从 OpenWeatherMap）
                    _weatherIconCode.value = null // 由 Open-Meteo 数据时无图标

                    // 获取天气预报（需要 API Key）
                    if (apiKey.isNotBlank()) {
                        val forecast = weatherApi.fetchForecast(apiKey.ifBlank { null })
                        _weatherForecast.value = forecast
                    }

                    // 延迟初始化 WeatherRadioManager（需要 BackendAdapter 和 NetworkMusicManager）
                    val adapter = backendRegistry.getAdapter()
                    if (weatherRadioManager == null) {
                        weatherRadioManager = WeatherRadioManager(adapter, networkMusicManager)
                    }
                    weatherRadioManager?.let { mgr ->
                        // 天气变化 = 新上下文，重置电台已见集合（跨构建去重从头开始）
                        weatherSeenKeys.clear()
                        val queue = buildWeatherRadioDeduped(mgr, WeatherMood.fromWeather(weather), weather)
                        _weatherRadioQueue.value = queue
                        _currentWeatherMood.value = queue.mood
                    }
                } else {
                    _weatherError.value = if (apiKey.isBlank()) {
                        getApplication<Application>().getString(R.string.weather_error_no_api_key)
                    } else {
                        getApplication<Application>().getString(R.string.weather_error_check_network)
                    }
                    // 即使天气获取失败，仍按默认心情（阳光）加载歌曲
                    loadRadioForDefaultMood()
                }
            } catch (e: Exception) {
                AppLog.e("WeatherRadioViewModel", "fetchWeather failed", e)
                _weatherError.value = getApplication<Application>().getString(R.string.weather_fetch_failed, e.message?.take(50) ?: "")
                // 即使天气获取失败，仍按默认心情（阳光）加载歌曲
                loadRadioForDefaultMood()
            } finally {
                _weatherLoading.value = false
            }
        }
    }

    /**
     * 切换天气电台 mood
     */
    fun switchWeatherMood(mood: WeatherMood) {
        if (_currentWeatherMood.value == mood) return
        _currentWeatherMood.value = mood
        // mood 变化 = 新上下文，重置电台已见集合（跨构建去重从头开始）
        weatherSeenKeys.clear()
        viewModelScope.launch {
            _weatherLoading.value = true
            try {
                // 延迟初始化（可能在无后端连接时通过 fetchWeather() 创建）
                val mgr = weatherRadioManager ?: run {
                    val adapter = backendRegistry.getAdapter()
                    WeatherRadioManager(adapter, networkMusicManager).also { weatherRadioManager = it }
                }
                val queue = buildWeatherRadioDeduped(mgr, mood, _weatherData.value)
                _weatherRadioQueue.value = queue
            } catch (e: Exception) {
                AppLog.e("WeatherRadioViewModel", "switchWeatherMood failed", e)
                _weatherError.value = getApplication<Application>().getString(R.string.weather_switch_mood_error, e.message?.take(50))
            } finally {
                _weatherLoading.value = false
            }
        }
    }

    /**
     * 播放天气电台全部歌曲（经事件路由到 PlayerViewModel，导航由消费方处理）
     */
    fun playWeatherRadioAll() {
        val songs = _weatherRadioQueue.value?.songs ?: return
        if (songs.isEmpty()) return
        _events.tryEmit(WeatherRadioEvent.PlayRequested(songs))
    }

    /**
     * 构建天气电台并跨构建去重（「换一批」）。
     *
     * 每次候选都重新构建一次电台（NAS 匹配与网络搜索结果已打乱，故每次基础集合不同），
     * 在多个候选中挑选新歌最多的展示，保证同一 mood 下反复「换一批」只出新歌。
     *
     * mood 变化（新上下文）时调用方负责清空 [weatherSeenKeys]。
     */
    private suspend fun buildWeatherRadioDeduped(
        mgr: WeatherRadioManager,
        mood: WeatherMood,
        weather: WeatherData?
    ): WeatherRadioQueue {
        val (chosen, shown) = pickBestFreshBatch(
            seenKeys = weatherSeenKeys,
            produce = { mgr.buildRadioWithMood(mood, weather) },
            songsOf = { it.songs }
        )
        return chosen.copy(songs = shown)
    }

    /**
     * 天气获取失败时，按默认心情（阳光）加载歌曲。
     */
    private fun loadRadioForDefaultMood() {
        viewModelScope.launch {
            try {
                val mgr = weatherRadioManager ?: run {
                    val adapter = backendRegistry.getAdapter()
                    WeatherRadioManager(adapter, networkMusicManager).also { weatherRadioManager = it }
                }
                // 天气获取失败也走同一套跨构建去重：反复「换一批」仍只出新歌
                val queue = buildWeatherRadioDeduped(mgr, WeatherMood.SUNNY, null)
                _weatherRadioQueue.value = queue
                _currentWeatherMood.value = queue.mood
            } catch (e: Exception) {
                AppLog.e("WeatherRadioViewModel", "loadRadioForDefaultMood failed", e)
            }
        }
    }

    /**
     * 统一的「换一批」核心逻辑（从 MainViewModel 迁入，天气电台场景专用）。
     * 语义与 MainViewModel.pickBestFreshBatch 一致：反复调用 produce 生成候选（最多
     * [maxShuffleAttemptsPerClick] 次），过滤已见歌曲，返回新歌最多的候选；已见集合
     * 饱和时清空从头再来。M-9 硬上限 4000 防集合无限增长。
     */
    private suspend fun <T> pickBestFreshBatch(
        seenKeys: MutableSet<Pair<String, String>>,
        maxAttempts: Int = maxShuffleAttemptsPerClick,
        minNewResults: Int = minNewResultsForShuffle,
        produce: suspend () -> T,
        songsOf: (T) -> List<Song>
    ): Pair<T, List<Song>> {
        var best: T? = null
        var bestFresh: List<Song> = emptyList()
        var attempts = 0
        while (attempts < maxAttempts) {
            attempts++
            val candidate = produce()
            val fresh = songsOf(candidate).filterNot { (it.artist.trim() to it.title.trim()) in seenKeys }
            if (fresh.size > bestFresh.size) {
                best = candidate
                bestFresh = fresh
            }
            if (fresh.size >= minNewResults) break
        }
        val chosen = best
        val result = if (chosen == null || bestFresh.isEmpty()) {
            // 所有候选都没有新歌：已见集合饱和，从头再来一批
            seenKeys.clear()
            val freshProduce = produce()
            freshProduce to songsOf(freshProduce)
        } else {
            chosen to bestFresh
        }
        // 修复（M-9）：硬上限防长期挂机场景集合无限增长
        if (seenKeys.size >= 4000) seenKeys.clear()
        result.second.forEach { seenKeys.add(it.artist.trim() to it.title.trim()) }
        return result
    }
}
