/*
 * Copyright 2025 zmxl
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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

    // 添加一个标志来检测Workshop API是否可用
    private val workshopApiAvailable by lazy {
        try {
            // 尝试访问Workshop API的方法来检查是否可用
            WorkshopApi.instance.playback
            true
        } catch (e: Exception) {
            false
        }
    }

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

    // 实现已弃用的 updateLyrics 方法
    override fun updateLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        return onBeforeLoadLyrics(mediaItem)
    }

override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
    PlaybackStateHolder.currentMedia = mediaItem
    
    // 生成当前歌曲的唯一ID
    val songId = "${mediaItem.title}-${mediaItem.artist}-${mediaItem.album}"
    PlaybackStateHolder.setCurrentSongId(songId)
    
    // 清除之前的歌词缓存
    PlaybackStateHolder.clearCurrentLyrics()
    
    // 重置播放位置
    PlaybackStateHolder.resetPosition()
    
    // 不再尝试获取歌词，由LyricServlet专门处理
    // 只获取封面图片
    thread {
        try {
            // 构建搜索URL
            val searchQuery = "${mediaItem.title}-${mediaItem.artist}"
            val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
            val searchUrl = "https://music.163.com/api/search/get?type=1&offset=0&limit=1&s=$encodedQuery"
            
            // 执行搜索请求
            val searchResult = getUrlContent(searchUrl)
            val searchJson = JSONObject(searchResult)
            
            if (searchJson.has("result") && !searchJson.isNull("result")) {
                val result = searchJson.getJSONObject("result")
                if (result.has("songs") && !result.isNull("songs")) {
                    val songs = result.getJSONArray("songs")
                    
                    if (songs.length() > 0) {
                        val songId = songs.getJSONObject(0).getInt("id")
                        
                        // 获取歌曲详细信息
                        val songInfoUrl = "https://api.injahow.cn/meting/?type=song&id=$songId"
                        val songInfoResult = getUrlContent(songInfoUrl)
                        val songInfoArray = JSONArray(songInfoResult)
                        
                        if (songInfoArray.length() > 0) {
                            val songInfo = songInfoArray.getJSONObject(0)
                            PlaybackStateHolder.coverUrl = songInfo.getString("pic")
                            println("获取封面成功: coverUrl=${PlaybackStateHolder.coverUrl}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("获取封面失败: ${e.message}")
        }
    }
    
    return null // 使用默认歌词逻辑
}

    override fun onLyricsLineUpdated(lyricsLine: PlaybackExtensionPoint.LyricsLine?) {
        // 处理歌词行更新
        lyricsLine?.let { line ->
            println("歌词行更新: ${line.pureMainText} (${line.startTime}-${line.endTime})")
            
            // 修复：避免对来自不同模块的属性进行智能转换
            val pureSubText = line.pureSubText
            val combinedText = if (pureSubText != null && pureSubText.isNotEmpty()) {
                "${line.pureMainText}\n${pureSubText}"
            } else {
                line.pureMainText
            }
            
            // 将歌词行添加到缓存
            val lyricLine = PlaybackStateHolder.LyricLine(
                line.startTime,
                combinedText
            )
            
            PlaybackStateHolder.addLyricLine(lyricLine)
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

    // 播放/暂停切换 - 使用WorkshopApi
    fun togglePlayback() {
        try {
            if (workshopApiAvailable) {
                if (PlaybackStateHolder.isPlaying) {
                    WorkshopApi.instance.playback.pause()
                } else {
                    WorkshopApi.instance.playback.play()
                }
            } else {
                // 回退到旧的实现方式
                val newState = !PlaybackStateHolder.isPlaying
                PlaybackStateHolder.isPlaying = newState
                // 这里可以添加实际的播放/暂停控制逻辑
            }
            // 状态会在回调中自动更新
        } catch (e: Exception) {
            println("播放/暂停操作失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // 下一曲功能实现 - 使用WorkshopApi
    fun next() {
        try {
            println("执行下一曲操作")
            if (workshopApiAvailable) {
                WorkshopApi.instance.playback.next()
            } else {
                // 回退到旧的实现方式
                // 这里可以添加实际的下一曲控制逻辑
                println("下一曲操作（旧方式）")
            }
        } catch (e: Exception) {
            println("下一曲操作失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // 上一曲功能实现 - 使用WorkshopApi
    fun previous() {
        try {
            println("执行上一曲操作")
            if (workshopApiAvailable) {
                WorkshopApi.instance.playback.previous()
            } else {
                // 回退到旧的实现方式
                // 这里可以添加实际的上一曲控制逻辑
                println("上一曲操作（旧方式）")
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