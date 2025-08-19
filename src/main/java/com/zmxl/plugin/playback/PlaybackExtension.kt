package com.zmxl.plugin.playback

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import org.json.JSONArray
import org.json.JSONObject
import org.pf4j.Extension
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread

@Extension
class SpwPlaybackExtension : PlaybackExtensionPoint {
    // 使用 Workshop API 实例
    private val workshopApi: WorkshopApi
        get() = WorkshopApi.instance

    override fun onStateChanged(state: PlaybackExtensionPoint.State) {
        PlaybackStateHolder.currentState = state
        
        // 打印状态值以帮助调试
        println("播放状态变化: ${state.name}")
        
        // 根据播放状态更新进度计时器
        when (state) {
            PlaybackExtensionPoint.State.Ready -> {
                if (PlaybackStateHolder.isPlaying) {
                    PlaybackStateHolder.startPositionUpdate()
                }
            }
            PlaybackExtensionPoint.State.Ended -> {
                PlaybackStateHolder.stopPositionUpdate()
            }
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

    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
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
    
    override fun onLyricsLineUpdated(lyricsLine: PlaybackExtensionPoint.LyricsLine?) {
        // 可以在这里处理歌词行更新
        lyricsLine?.let {
            println("歌词行更新: ${it.pureMainText} (${it.startTime}-${it.endTime})")
        }
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

    // 音量控制 - 现在使用0-100整数
    fun setVolume(level: Int) {
        if (level in 0..100) {
            PlaybackStateHolder.volume = level
            // 这里可以添加实际的音量控制逻辑
        }
    }

    // 进度跳转
    fun seekTo(position: Long) {
        // 实际跳转需要调用SPW内部API
        PlaybackStateHolder.setPosition(position)
    }

    // 播放/暂停切换
    fun togglePlayback() {
        // 使用 Workshop API 切换播放状态
        // 注意：WorkshopApi 目前没有直接提供播放/暂停方法
        // 这里需要根据实际情况实现
        val newState = !PlaybackStateHolder.isPlaying
        PlaybackStateHolder.isPlaying = newState
    }

    // 下一曲功能实现
    fun next() {
        try {
            println("执行下一曲操作")
            // 使用 Workshop API 进行下一曲操作
            // 注意：WorkshopApi 目前没有直接提供下一曲方法
            // 这里需要根据实际情况实现
            
            // 更新当前媒体信息（假设切换后会自动更新，这里可根据实际情况调整）
            val newMedia = PlaybackStateHolder.currentMedia
            if (newMedia != null) {
                println("切换到下一曲: ${newMedia.title}")
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
            // 使用 Workshop API 进行上一曲操作
            // 注意：WorkshopApi 目前没有直接提供上一曲方法
            // 这里需要根据实际情况实现
            
            // 更新当前媒体信息
            val newMedia = PlaybackStateHolder.currentMedia
            if (newMedia != null) {
                println("切换到上一曲: ${newMedia.title}")
            }
        } catch (e: Exception) {
            println("上一曲操作失败: ${e.message}")
            e.printStackTrace()
        }
    }
    
    // 使用 Workshop API 的独占音频功能
    fun setExclusiveAudio(exclusive: Boolean) {
        try {
            workshopApi.playback.changeExclusive(exclusive)
            println("设置独占音频: $exclusive")
        } catch (e: Exception) {
            println("设置独占音频失败: ${e.message}")
        }
    }
    
    // 使用 Workshop API 显示提示信息
    fun showToast(message: String, type: WorkshopApi.Ui.ToastType = WorkshopApi.Ui.ToastType.Success) {
        try {
            workshopApi.ui.toast(message, type)
            println("显示提示: $message")
        } catch (e: Exception) {
            println("显示提示失败: ${e.message}")
        }
    }
}
