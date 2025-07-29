package com.zmxl.plugin.playback

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.State
import org.pf4j.Extension
import java.lang.reflect.Method

object PlaybackStateHolder {
    @Volatile
    var currentMedia: MediaItem? = null
    @Volatile
    var isPlaying: Boolean = false
    @Volatile
    var currentState: State = State.Idle
    @Volatile
    var currentPosition: Long = 0L
    @Volatile
    var volume: Float = 1.0f
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
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        PlaybackStateHolder.isPlaying = isPlaying
    }

    override fun onSeekTo(position: Long) {
        PlaybackStateHolder.currentPosition = position
    }

    override fun updateLyrics(mediaItem: MediaItem): String? {
        PlaybackStateHolder.currentMedia = mediaItem
        return null // 使用默认歌词逻辑
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
        PlaybackStateHolder.currentPosition = position
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