package com.zmxl.plugin.playback

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension
import java.util.Timer
import java.util.TimerTask

object PlaybackStateHolder {
    @Volatile
    var currentMedia: PlaybackExtensionPoint.MediaItem? = null
    @Volatile
    var isPlaying: Boolean = false
    @Volatile
    var currentState: PlaybackExtensionPoint.State = PlaybackExtensionPoint.State.Idle
    @Volatile
    var volume: Int = 100 // 音量范围改为0-100整数
    @Volatile
    var lyricUrl: String? = null
    @Volatile
    var coverUrl: String? = null
    
    // 播放进度跟踪
    @Volatile
    var currentPosition: Long = 0L
    private var positionUpdateTimer: Timer? = null
    private var lastPositionUpdateTime: Long = 0
    private var previousVolumeBeforeMute: Int = 100 // 静音前的音量
    
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
    
    // 静音/取消静音
    fun toggleMute() {
        if (volume > 0) {
            // 保存当前音量并静音
            previousVolumeBeforeMute = volume
            volume = 0
        } else {
            // 恢复静音前的音量
            volume = previousVolumeBeforeMute
        }
    }
}