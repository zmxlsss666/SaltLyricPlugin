package com.zmxl.plugin.playback

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.State
import org.json.JSONArray
import org.json.JSONObject
import org.pf4j.Extension
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread

object PlaybackStateHolder {
    @Volatile
    var currentMedia: MediaItem? = null
    @Volatile
    var isPlaying: Boolean = false
    @Volatile
    var currentState: State = State.Idle
    @Volatile
    var volume: Float = 1.0f
    @Volatile
    var lyricUrl: String? = null
    @Volatile
    var coverUrl: String? = null
    
    // 播放进度跟踪
    @Volatile
    var currentPosition: Long = 0L
    private var positionUpdateTimer: Timer? = null
    private var lastPositionUpdateTime: Long = 0
    
    // 开始更新播放进度
    fun startPositionUpdate() {
        stopPositionUpdate() // 确保之前的定时器已停止
        
        positionUpdateTimer = Timer(true) // 使用守护线程
        lastPositionUpdateTime = System.currentTimeMillis()
        
        positionUpdateTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (isPlaying) {
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastPositionUpdateTime
                    currentPosition += elapsed
                    lastPositionUpdateTime = now
                }
            }
        }, 0, 100) // 每100毫秒更新一次
    }
    
    // 停止更新播放进度
    fun stopPositionUpdate() {
        positionUpdateTimer?.cancel()
        positionUpdateTimer = null
    }
    
    // 设置播放位置（如跳转时）
    fun setPosition(position: Long) {
        currentPosition = position
        lastPositionUpdateTime = System.currentTimeMillis()
    }
    
    // 重置播放位置
    fun resetPosition() {
        currentPosition = 0L
        lastPositionUpdateTime = System.currentTimeMillis()
    }
}

@Extension
class SpwPlaybackExtension : PlaybackExtensionPoint {
    // 使用反射获取SPW内部API的方法
    private val nextTrackMethod: Method? by lazy {
        try {
            WorkshopApi::class.java.getDeclaredMethod("nextTrack")
        } catch (e: Exception) {
            println("无法获取nextTrack方法: ${e.message}")
            null
        }
    }
    
    private val previousTrackMethod: Method? by lazy {
        try {
            WorkshopApi::class.java.getDeclaredMethod("previousTrack")
        } catch (e: Exception) {
            println("无法获取previousTrack方法: ${e.message}")
            null
        }
    }

    override fun onStateChanged(state: State) {
        PlaybackStateHolder.currentState = state
        
        // 根据播放状态更新进度计时器
        when (state) {
            State.PLAYING -> PlaybackStateHolder.startPositionUpdate()  // 使用大写枚举值
            State.PAUSED, State.STOPPED -> PlaybackStateHolder.stopPositionUpdate()  // 使用大写枚举值
            else -> {}
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        PlaybackStateHolder.isPlaying = isPlaying
        
        // 根据播放状态更新进度计时器
        if (isPlaying) {
            PlaybackStateHolder.startPositionUpdate()
        } else {
            PlaybackStateHolder.stopPositionUpdate()
        }
    }

    override fun onSeekTo(position: Long) {
        // 设置新的播放位置
        PlaybackStateHolder.setPosition(position)
    }

    override fun updateLyrics(mediaItem: MediaItem): String? {
        PlaybackStateHolder.currentMedia = mediaItem
        
        // 重置歌词和封面URL
        PlaybackStateHolder.lyricUrl = null
        PlaybackStateHolder.coverUrl = null
        
        // 重置播放位置
        PlaybackStateHolder.resetPosition()
        
        // 启动后台线程获取歌词和封面信息
        thread {
            try {
                // 构建搜索URL
                val searchQuery = "${mediaItem.title}-${mediaItem.artist}"
                val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
                val searchUrl = "https://music.163.com/api/search/get?type=1&offset=0&limit=1&s=$encodedQuery"
                
                // 执行搜索请求
                val searchResult = getUrlContent(searchUrl)
                val searchJson = JSONObject(searchResult)
                val songs = searchJson.getJSONObject("result").getJSONArray("songs")
                
                if (songs.length() > 0) {
                    val songId = songs.getJSONObject(0).getInt("id")
                    
                    // 获取歌曲详细信息
                    val songInfoUrl = "https://api.injahow.cn/meting/?type=song&id=$songId"
                    val songInfoResult = getUrlContent(songInfoUrl)
                    val songInfoArray = JSONArray(songInfoResult)
                    
                    if (songInfoArray.length() > 0) {
                        val songInfo = songInfoArray.getJSONObject(0)
                        PlaybackStateHolder.lyricUrl = songInfo.getString("lrc")
                        PlaybackStateHolder.coverUrl = songInfo.getString("pic")
                        println("获取歌词和封面成功: lyricUrl=${PlaybackStateHolder.lyricUrl}, coverUrl=${PlaybackStateHolder.coverUrl}")
                    }
                }
            } catch (e: Exception) {
                println("获取歌词/封面失败: ${e.message}")
            }
        }
        
        return null // 使用默认歌词逻辑
    }
    
    // 辅助方法：获取URL内容
    private fun getUrlContent(urlString: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    // 音量控制
    fun setVolume(level: Float) {
        if (level in 0.0f..1.0f) {
            PlaybackStateHolder.volume = level
            // 实际音量控制可能需要通过反射调用SPW内部方法
            // 这里仅做状态更新示例
        }
    }

    // 进度跳转
    fun seekTo(position: Long) {
        // 实际跳转需要调用SPW内部API
        PlaybackStateHolder.setPosition(position)
    }

    // 播放/暂停切换
    fun togglePlayback() {
        val newState = !PlaybackStateHolder.isPlaying
        PlaybackStateHolder.isPlaying = newState
        // 实际控制需要调用SPW内部API
    }

    // 下一曲功能实现
    fun next() {
        try {
            println("执行下一曲操作")
            nextTrackMethod?.invoke(null) ?: run {
                println("警告: nextTrack方法未找到，使用模拟实现")
                // 模拟下一曲操作 - 更新当前媒体信息
                PlaybackStateHolder.currentMedia?.let { current ->
                    // 使用MediaItem的copy方法创建新实例
                    val newMedia = current.copy(
                        title = "下一曲: ${current.title}",
                        // 其他属性保持不变
                    )
                    PlaybackStateHolder.currentMedia = newMedia
                }
            }
        } catch (e: Exception) {
            println("下一曲操作失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // 上一曲功能实现
    fun previous() {
        try {
            println("执行上一曲操作")
            previousTrackMethod?.invoke(null) ?: run {
                println("警告: previousTrack方法未找到，使用模拟实现")
                // 模拟上一曲操作 - 更新当前媒体信息
                PlaybackStateHolder.currentMedia?.let { current ->
                    // 使用MediaItem的copy方法创建新实例
                    val newMedia = current.copy(
                        title = "上一曲: ${current.title}",
                        // 其他属性保持不变
                    )
                    PlaybackStateHolder.currentMedia = newMedia
                }
            }
        } catch (e: Exception) {
            println("上一曲操作失败: ${e.message}")
            e.printStackTrace()
        }
    }
}
