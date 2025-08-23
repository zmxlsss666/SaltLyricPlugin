package com.zmxl.plugin.playback

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint
import org.pf4j.Extension
import java.util.*
import java.util.concurrent.ConcurrentHashMap

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
    
    // 存储从SPW获取的歌词行，按歌曲ID分组
    private val lyricsCache = ConcurrentHashMap<String, MutableList<LyricLine>>()
    
    // 当前歌曲ID
    @Volatile
    private var currentSongId: String? = null
    
    // 歌词访问同步锁
    private val lyricsLock = Any()
    
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
    
    // 添加方法：设置当前歌曲ID
    fun setCurrentSongId(songId: String) {
        currentSongId = songId
        // 如果缓存中没有这首歌的歌词，初始化一个空列表
        lyricsCache.putIfAbsent(songId, mutableListOf())
    }
    
    // 添加方法：添加歌词行
    fun addLyricLine(line: LyricLine) {
        currentSongId?.let { songId ->
            synchronized(lyricsLock) {
                val lines = lyricsCache.getOrPut(songId) { mutableListOf() }
                
                // 检查是否已存在相同时间的歌词行
                val existingIndex = lines.indexOfFirst { it.time == line.time }
                
                if (existingIndex >= 0) {
                    // 更新现有行
                    lines[existingIndex] = line
                } else {
                    // 添加新行并保持按时间排序
                    lines.add(line)
                    // 使用更安全的排序方法
                    val sortedLines = lines.sortedBy { it.time }.toMutableList()
                    lines.clear()
                    lines.addAll(sortedLines)
                }
            }
        }
    }
    
    // 添加方法：获取当前行和下一行歌词
    fun getCurrentAndNextLyrics(currentPosition: Long): Pair<LyricLine?, LyricLine?> {
        currentSongId?.let { songId ->
            synchronized(lyricsLock) {
                val lines = lyricsCache[songId] ?: return Pair(null, null)
                
                // 找到当前时间对应的歌词行
                var currentLine: LyricLine? = null
                var nextLine: LyricLine? = null
                
                for (i in lines.indices) {
                    if (lines[i].time > currentPosition) {
                        nextLine = lines[i]
                        if (i > 0) {
                            currentLine = lines[i - 1]
                        }
                        break
                    }
                    
                    // 如果是最后一行
                    if (i == lines.size - 1 && lines[i].time <= currentPosition) {
                        currentLine = lines[i]
                    }
                }
                
                return Pair(currentLine, nextLine)
            }
        }
        
        return Pair(null, null)
    }
    
    // 添加方法：清除当前歌曲的歌词缓存
    fun clearCurrentLyrics() {
        currentSongId?.let { songId ->
            synchronized(lyricsLock) {
                lyricsCache.remove(songId)
            }
        }
    }
    
    // 添加方法：获取当前歌曲的所有歌词（用于调试）
    fun getAllLyrics(): List<LyricLine> {
        currentSongId?.let { songId ->
            synchronized(lyricsLock) {
                return lyricsCache[songId] ?: emptyList()
            }
        }
        return emptyList()
    }
    
    // 歌词行数据类
    data class LyricLine(val time: Long, val text: String)
}
