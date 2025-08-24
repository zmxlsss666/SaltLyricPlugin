package com.zmxl.plugin.server

import com.google.gson.Gson
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.zmxl.plugin.control.SmtcController
import com.zmxl.plugin.playback.PlaybackStateHolder
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Base64
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import com.moriafly.salt.saltAudioTag.AudioFileIO
import com.moriafly.salt.saltAudioTag.tag.FieldKey

class HttpServer(private val port: Int) {
    private lateinit var server: Server

    init {
        SmtcController.init()
    }

    fun start() {
        server = Server(port)
        val context = ServletContextHandler(ServletContextHandler.SESSIONS)
        context.contextPath = "/"
        server.handler = context
        
        // 将HttpServer实例存入ServletContext，以便在Servlet中访问
        context.setAttribute("httpServer", this)

        // 创建ServletHolder并注册API端点
        context.addServlet(ServletHolder(NowPlayingServlet()), "/api/now-playing")
        context.addServlet(ServletHolder(PlayPauseServlet()), "/api/play-pause")
        context.addServlet(ServletHolder(NextTrackServlet()), "/api/next-track")
        context.addServlet(ServletHolder(PreviousTrackServlet()), "/api/previous-track")
        context.addServlet(ServletHolder(VolumeUpServlet()), "/api/volume/up")
        context.addServlet(ServletHolder(VolumeDownServlet()), "/api/volume/down")
        context.addServlet(ServletHolder(MuteServlet()), "/api/mute")
        
        // 注册歌词API
        context.addServlet(ServletHolder(Lyric163Servlet()), "/api/lyric163") // 网易云歌词API
        context.addServlet(ServletHolder(LyricQQServlet()), "/api/lyricqq")   // QQ音乐歌词API
        context.addServlet(ServletHolder(LyricKugouServlet()), "/api/lyrickugou") // 酷狗音乐歌词API
        context.addServlet(ServletHolder(LyricSpwServlet()), "/api/lyricspw") // SPW歌词API
        context.addServlet(ServletHolder(LyricFileServlet()), "/api/lyric")   // 本地FLAC文件歌词API
        
        context.addServlet(ServletHolder(PicServlet()), "/api/pic")
        context.addServlet(ServletHolder(CurrentPositionServlet()), "/api/current-position")

        // 处理所有其他请求，返回控制界面
        context.addServlet(ServletHolder(object : HttpServlet() {
            @Throws(IOException::class)
            override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
                // 从资源文件加载HTML内容
                val htmlStream: InputStream? = javaClass.getResourceAsStream("/web/index.html")
                if (htmlStream != null) {
                    resp.contentType = "text/html;charset=UTF-8"
                    resp.characterEncoding = "UTF-8"
                    htmlStream.copyTo(resp.outputStream)
                } else {
                    resp.status = HttpServletResponse.SC_NOT_FOUND
                    resp.writer.write("HTML resource not found")
                }
            }
        }), "/*")

        try {
            server.start()
            println("HTTP服务器已启动，端口: $port")
        } catch (e: Exception) {
            println("HTTP服务器启动失败: ${e.message}")
            e.printStackTrace()
        }
    }

    fun stop() {
        try {
            server.stop()
            SmtcController.shutdown()
            println("HTTP服务器已停止")
        } catch (e: Exception) {
            println("HTTP服务器停止失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 获取当前播放信息API
     */
    class NowPlayingServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "application/json;charset=UTF-8"
            
            val responseData = mapOf(
                "status" to "success",
                "title" to PlaybackStateHolder.currentMedia?.title,
                "artist" to PlaybackStateHolder.currentMedia?.artist,
                "album" to PlaybackStateHolder.currentMedia?.album,
                "isPlaying" to PlaybackStateHolder.isPlaying,
                "position" to PlaybackStateHolder.currentPosition,
                "volume" to PlaybackStateHolder.volume, // 返回0-100整数音量
                "timestamp" to System.currentTimeMillis()
            )
            
            PrintWriter(resp.writer).use { out ->
                out.print(Gson().toJson(responseData))
            }
        }
    }

    /**
     * 播放/暂停切换API
     */
    class PlayPauseServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "application/json;charset=UTF-8"
            
            try {
                // 从ServletContext获取HttpServer实例
                val httpServer = req.servletContext.getAttribute("httpServer") as HttpServer
                httpServer.sendMediaKeyEvent(0xB3)
                
                Thread.sleep(100)
                
                val response = mapOf(
                    "status" to "success",
                    "action" to "play_pause_toggled",
                    "isPlaying" to PlaybackStateHolder.isPlaying,
                    "message" to if (PlaybackStateHolder.isPlaying) "已开始播放" else "已暂停"
                )
                resp.writer.write(Gson().toJson(response))
            } catch (e: Exception) {
                resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                resp.writer.write(Gson().toJson(mapOf(
                    "status" to "error",
                    "message" to "播放/暂停操作失败: ${e.message}"
                )))
            }
        }
    }

    /**
     * 下一曲API
     */
    class NextTrackServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "application/json;charset=UTF-8"
            
            try {
                SmtcController.handleNextTrack()
                
                // 从ServletContext获取HttpServer实例
                val httpServer = req.servletContext.getAttribute("httpServer") as HttpServer
                httpServer.sendMediaKeyEvent(0xB0)
                
                Thread.sleep(100)
                
                val newMedia = PlaybackStateHolder.currentMedia
                val response = mapOf(
                    "status" to "success",
                    "action" to "next_track",
                    "newTrack" to (newMedia?.title ?: "未知曲目"),
                    "message" to "已切换到下一曲"
                )
                resp.writer.write(Gson().toJson(response))
            } catch (e: Exception) {
                resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                resp.writer.write(Gson().toJson(mapOf(
                    "status" to "error",
                    "message" to "下一曲操作失败: ${e.message}"
                )))
            }
        }
    }

    /**
     * 上一曲API
     */
    class PreviousTrackServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "application/json;charset=UTF-8"
            
            try {
                SmtcController.handlePreviousTrack()
                
                // 从ServletContext获取HttpServer实例
                val httpServer = req.servletContext.getAttribute("httpServer") as HttpServer
                httpServer.sendMediaKeyEvent(0xB1)
                
                Thread.sleep(100)
                
                val newMedia = PlaybackStateHolder.currentMedia
                val response = mapOf(
                    "status" to "success",
                    "action" to "previous_track",
                    "newTrack" to (newMedia?.title ?: "未知曲目"),
                    "message" to "已切换到上一曲"
                )
                resp.writer.write(Gson().toJson(response))
            } catch (e: Exception) {
                resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                resp.writer.write(Gson().toJson(mapOf(
                    "status" to "error",
                    "message" to "上一曲操作失败: ${e.message}"
                )))
            }
        }
    }

    /**
     * 音量增加API
     */
    class VolumeUpServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "application/json;charset=UTF-8"
            
            try {
                SmtcController.handleVolumeUp()
                
                // 从ServletContext获取HttpServer实例
                val httpServer = req.servletContext.getAttribute("httpServer") as HttpServer
                httpServer.sendMediaKeyEvent(0xAF)
                
                Thread.sleep(50)
                
                val response = mapOf(
                    "status" to "success",
                    "action" to "volume_up",
                    "currentVolume" to PlaybackStateHolder.volume,
                    "message" to "音量已增加"
                )
                resp.writer.write(Gson().toJson(response))
            } catch (e: Exception) {
                resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                resp.writer.write(Gson().toJson(mapOf(
                    "status" to "error",
                    "message" to "音量增加操作失败: ${e.message}"
                )))
            }
        }
    }

    /**
     * 音量减少API
     */
    class VolumeDownServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "application/json;charset=UTF-8"
            
            try {
                SmtcController.handleVolumeDown()
                
                // 从ServletContext获取HttpServer实例
                val httpServer = req.servletContext.getAttribute("httpServer") as HttpServer
                httpServer.sendMediaKeyEvent(0xAE)
                
                Thread.sleep(50)
                
                val response = mapOf(
                    "status" to "success",
                    "action" to "volume_down",
                    "currentVolume" to PlaybackStateHolder.volume,
                    "message" to "音量已减少"
                )
                resp.writer.write(Gson().toJson(response))
            } catch (e: Exception) {
                resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                resp.writer.write(Gson().toJson(mapOf(
                    "status" to "error",
                    "message" to "音量减少操作失败: ${e.message}"
                )))
            }
        }
    }

    /**
     * 静音切换API
     */
    class MuteServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "application/json;charset=UTF-8"
            
            try {
                SmtcController.handleMute()
                
                // 从ServletContext获取HttpServer实例
                val httpServer = req.servletContext.getAttribute("httpServer") as HttpServer
                httpServer.sendMediaKeyEvent(0xAD)
                
                Thread.sleep(50)
                
                val isMuted = PlaybackStateHolder.volume == 0
                val response = mapOf(
                    "status" to "success",
                    "action" to "mute_toggle",
                    "isMuted" to isMuted,
                    "message" to if (isMuted) "已静音" else "已取消静音"
                )
                resp.writer.write(Gson().toJson(response))
            } catch (e: Exception) {
                resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                resp.writer.write(Gson().toJson(mapOf(
                    "status" to "error",
                    "message" to "静音操作失败: ${e.message}"
                )))
            }
        }
    }

    /**
     * 本地FLAC文件歌词API
     */
    class LyricFileServlet : HttpServlet() {
        private val gson = Gson()
        
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "application/json;charset=UTF-8"
            
            // 获取媒体文件路径
            val filePath = req.getParameter("filePath")
            if (filePath.isNullOrBlank()) {
                resp.status = HttpServletResponse.SC_BAD_REQUEST
                resp.writer.write(gson.toJson(mapOf(
                    "status" to "error",
                    "message" to "文件路径参数(filePath)不能为空"
                )))
                return
            }
            
            // 检查文件是否为FLAC格式
            if (!filePath.endsWith(".flac", ignoreCase = true)) {
                resp.status = HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE
                resp.writer.write(gson.toJson(mapOf(
                    "status" to "error",
                    "message" to "仅支持FLAC格式的音频文件",
                    "filePath" to filePath
                )))
                return
            }
            
            try {
                // 使用SaltAudioTag库读取FLAC文件的元数据
                val file = File(filePath)
                if (!file.exists()) {
                    resp.status = HttpServletResponse.SC_NOT_FOUND
                    resp.writer.write(gson.toJson(mapOf(
                        "status" to "error",
                        "message" to "文件不存在",
                        "filePath" to filePath
                    )))
                    return
                }
                
                if (!file.isFile) {
                    resp.status = HttpServletResponse.SC_BAD_REQUEST
                    resp.writer.write(gson.toJson(mapOf(
                        "status" to "error",
                        "message" to "路径不是有效的文件",
                        "filePath" to filePath
                    )))
                    return
                }
                
                // 读取音频文件
                val audioFile = AudioFileIO.read(file)
                val tag = audioFile.tag
                
                // 尝试从元数据中获取歌词
                var lyricContent: String? = null
                
                if (tag != null) {
                    // 尝试获取标准歌词字段
                    lyricContent = tag.getFirst(FieldKey.LYRICS)
                    
                    // 如果没有找到专门的歌词字段，尝试从注释中获取
                    if (lyricContent.isNullOrBlank()) {
                        lyricContent = tag.getFirst(FieldKey.COMMENT)
                    }
                    
                    // 尝试其他可能存储歌词的字段
                    if (lyricContent.isNullOrBlank()) {
                        lyricContent = tag.getFirst(FieldKey.DESCRIPTION)
                    }
                }
                
                if (lyricContent != null && lyricContent.isNotBlank()) {
                    val response = mapOf(
                        "status" to "success",
                        "lyric" to lyricContent,
                        "source" to "flac_metadata",
                        "filePath" to filePath
                    )
                    resp.writer.write(gson.toJson(response))
                } else {
                    resp.status = HttpServletResponse.SC_NOT_FOUND
                    resp.writer.write(gson.toJson(mapOf(
                        "status" to "error",
                        "message" to "未在FLAC文件的元数据中找到歌词",
                        "filePath" to filePath
                    )))
                }
            } catch (e: Exception) {
                resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                resp.writer.write(gson.toJson(mapOf(
                    "status" to "error",
                    "message" to "读取FLAC文件元数据失败: ${e.message}",
                    "filePath" to filePath
                )))
            }
        }
    }

/**
 * 网易云音乐网络歌词API
 */
class Lyric163Servlet : HttpServlet() {
    private val gson = Gson()
    
    @Throws(IOException::class)
    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        resp.contentType = "application/json;charset=UTF-8"
        
        val media = PlaybackStateHolder.currentMedia
        if (media == null) {
            resp.status = HttpServletResponse.SC_NOT_FOUND
            resp.writer.write(gson.toJson(mapOf(
                "status" to "error",
                "message" to "没有当前媒体信息"
            )))
            return
        }
        
        try {
            // 尝试多种方式获取歌词
            val lyricContent = tryGetLyricFromMultipleSources(media.title, media.artist)
            
            if (lyricContent != null && lyricContent.isNotBlank()) {
                val response = mapOf(
                    "status" to "success",
                    "lyric" to lyricContent,
                    "source" to "network"
                )
                resp.writer.write(gson.toJson(response))
            } else {
                resp.status = HttpServletResponse.SC_NOT_FOUND
                resp.writer.write(gson.toJson(mapOf(
                    "status" to "error",
                    "message" to "未找到网络歌词"
                )))
            }
        } catch (e: Exception) {
            resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            resp.writer.write(gson.toJson(mapOf(
                "status" to "error",
                "message" to "获取网络歌词失败: ${e.message}"
            )))
        }
    }
    
    // 尝试从多个来源获取歌词
    private fun tryGetLyricFromMultipleSources(title: String?, artist: String?): String? {
        if (title.isNullOrBlank()) return null
        
        val lyric1 = getLyricFromNeteaseOfficial(title, artist)
        if (lyric1 != null) return lyric1
        
        return null
    }
    
    // 从网易云音乐官方API获取歌词
    private fun getLyricFromNeteaseOfficial(title: String?, artist: String?): String? {
        try {
            // 构建搜索URL
            val searchQuery = if (!artist.isNullOrBlank()) "$title $artist" else title
            val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
            val searchUrl = "https://music.163.com/api/search/get?type=1&offset=0&limit=1&s=$encodedQuery"
            
            // 执行搜索请求
            val searchResult = getUrlContentWithHeaders(searchUrl, mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "Referer" to "https://music.163.com/"
            ))
            
            val searchJson = JSONObject(searchResult)
            
            // 检查是否有结果
            if (!searchJson.has("result") || searchJson.isNull("result")) {
                return null
            }
            
            val result = searchJson.getJSONObject("result")
            if (!result.has("songs") || result.isNull("songs")) {
                return null
            }
            
            val songs = result.getJSONArray("songs")
            
            if (songs.length() > 0) {
                // 获取第一首歌曲的ID
                val songId = songs.getJSONObject(0).getInt("id")
                
                // 使用网易云音乐官方歌词API
                val lyricUrl = "https://music.163.com/api/song/lyric?id=$songId&lv=1&tv=-1"
                val lyricResult = getUrlContentWithHeaders(lyricUrl, mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                    "Referer" to "https://music.163.com/"
                ))
                
                val lyricObj = JSONObject(lyricResult)
                
                if (lyricObj.has("lrc") && !lyricObj.isNull("lrc") && 
                    lyricObj.getJSONObject("lrc").has("lyric")) {
                    return lyricObj.getJSONObject("lrc").getString("lyric")
                }
            }
        } catch (e: Exception) {
            println("从网易云官方API获取歌词失败: ${e.message}")
        }
        
        return null
    }
    
    // 辅助方法：获取URL内容（带请求头）
    private fun getUrlContentWithHeaders(urlString: String, headers: Map<String, String>): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        
        // 添加请求头
        headers.forEach { (key, value) ->
            conn.setRequestProperty(key, value)
        }
        
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
    
    // 辅助方法：获取URL内容
    private fun getUrlContent(urlString: String): String {
        return getUrlContentWithHeaders(urlString, emptyMap())
    }
}

/**
 * QQ音乐歌词API
 */
class LyricQQServlet : HttpServlet() {
    private val gson = Gson()
    
    @Throws(IOException::class)
    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        resp.contentType = "application/json;charset=UTF-8"
        
        val media = PlaybackStateHolder.currentMedia
        if (media == null) {
            resp.status = HttpServletResponse.SC_NOT_FOUND
            resp.writer.write(gson.toJson(mapOf(
                "status" to "error",
                "message" to "没有当前媒体信息"
            )))
            return
        }
        
        try {
            // 获取歌词
            val lyricContent = getLyricFromQQMusic(media.title, media.artist)
            
            if (lyricContent != null && lyricContent.isNotBlank()) {
                val response = mapOf(
                    "status" to "success",
                    "lyric" to lyricContent,
                    "source" to "qqmusic"
                )
                resp.writer.write(gson.toJson(response))
            } else {
                resp.status = HttpServletResponse.SC_NOT_FOUND
                resp.writer.write(gson.toJson(mapOf(
                    "status" to "error",
                    "message" to "未找到QQ音乐歌词"
                )))
            }
        } catch (e: Exception) {
            resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            resp.writer.write(gson.toJson(mapOf(
                "status" to "error",
                "message" to "获取QQ音乐歌词失败: ${e.message}"
            )))
        }
    }
    
    // 从QQ音乐获取歌词
    private fun getLyricFromQQMusic(title: String?, artist: String?): String? {
        if (title.isNullOrBlank()) return null
        
        try {
            // 构建搜索URL
            val searchQuery = if (!artist.isNullOrBlank()) "$title $artist" else title
            val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
            val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/music_search_new_platform?format=json&p=1&n=1&w=$encodedQuery"
            
            // 执行搜索请求
            val searchResult = getUrlContentWithHeaders(searchUrl, mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "Referer" to "https://y.qq.com/"
            ))
            
            val searchJson = JSONObject(searchResult)
            
            // 检查是否有结果
            if (!searchJson.has("data") || searchJson.isNull("data") || 
                !searchJson.getJSONObject("data").has("song") || 
                searchJson.getJSONObject("data").isNull("song") || 
                searchJson.getJSONObject("data").getJSONObject("song").getJSONArray("list").length() == 0) {
                return null
            }
            
            // 获取歌曲列表
            val songList = searchJson.getJSONObject("data").getJSONObject("song").getJSONArray("list")
            
            if (songList.length() > 0) {
                // 获取第一首歌曲的f字段
                val fField = songList.getJSONObject(0).getString("f")
                val fParts = fField.split("|")
                
                if (fParts.size > 0) {
                    // 获取歌曲ID（songid）
                    val songId = fParts[0]
                    
                    // 使用歌曲ID获取真实的mid
                    val mid = getSongMidFromId(songId)
                    if (mid != null) {
                        // 使用QQ音乐歌词API获取歌词
                        val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&songmid=$mid"
                        val lyricResult = getUrlContentWithHeaders(lyricUrl, mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                            "Referer" to "https://y.qq.com/portal/player.html"
                        ))
                        
                        val lyricObj = JSONObject(lyricResult)
                        
                        if (lyricObj.has("lyric") && !lyricObj.isNull("lyric")) {
                            return lyricObj.getString("lyric")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("从QQ音乐API获取歌词失败: ${e.message}")
            e.printStackTrace()
        }
        
        return null
    }
    
    // 通过歌曲ID获取真实的mid
    private fun getSongMidFromId(songId: String): String? {
        try {
            val songDetailUrl = "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg?tpl=yqq_song_detail&format=jsonp&callback=getOneSongInfoCallback&songid=$songId"
            
            // 获取歌曲详情
            val songDetailResult = getUrlContentWithHeaders(songDetailUrl, mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "Referer" to "https://y.qq.com/"
            ))
            
            // 处理JSONP响应，提取JSON部分
            val jsonStart = songDetailResult.indexOf('{')
            val jsonEnd = songDetailResult.lastIndexOf('}') + 1
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = songDetailResult.substring(jsonStart, jsonEnd)
                val songDetailJson = JSONObject(jsonStr)
                
                // 检查是否有数据
                if (songDetailJson.has("data") && !songDetailJson.isNull("data")) {
                    val data = songDetailJson.getJSONArray("data")
                    if (data.length() > 0) {
                        val songInfo = data.getJSONObject(0)
                        if (songInfo.has("singer") && !songInfo.isNull("singer")) {
                            val singers = songInfo.getJSONArray("singer")
                            if (singers.length() > 0) {
                                val singer = singers.getJSONObject(0)
                                if (singer.has("mid") && !singer.isNull("mid")) {
                                    return singer.getString("mid")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("获取歌曲mid失败: ${e.message}")
            e.printStackTrace()
        }
        
        return null
    }
    
    // 辅助方法：获取URL内容（带请求头)
    private fun getUrlContentWithHeaders(urlString: String, headers: Map<String, String>): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        
        // 添加请求头
        headers.forEach { (key, value) ->
            conn.setRequestProperty(key, value)
        }
        
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}

/**
 * 酷狗音乐歌词API
 */
class LyricKugouServlet : HttpServlet() {
    private val gson = Gson()
    
    @Throws(IOException::class)
    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        resp.contentType = "application/json;charset=UTF-8"
        
        val media = PlaybackStateHolder.currentMedia
        if (media == null) {
            resp.status = HttpServletResponse.SC_NOT_FOUND
            resp.writer.write(gson.toJson(mapOf(
                "status" to "error",
                "message" to "没有当前媒体信息"
            )))
            return
        }
        
        try {
            // 获取歌词
            val lyricContent = getLyricFromKugou(media.title, media.artist)
            
            if (lyricContent != null && lyricContent.isNotBlank()) {
                val response = mapOf(
                    "status" to "success",
                    "lyric" to lyricContent,
                    "source" to "kugou"
                )
                resp.writer.write(gson.toJson(response))
            } else {
                resp.status = HttpServletResponse.SC_NOT_FOUND
                resp.writer.write(gson.toJson(mapOf(
                    "status" to "error",
                    "message" to "未找到酷狗音乐歌词"
                )))
            }
        } catch (e: Exception) {
            resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            resp.writer.write(gson.toJson(mapOf(
                "status" to "error",
                "message" to "获取酷狗音乐歌词失败: ${e.message}"
            )))
        }
    }
    
    // 从酷狗音乐获取歌词
    private fun getLyricFromKugou(title: String?, artist: String?): String? {
        if (title.isNullOrBlank()) return null
        
        try {
            // 构建搜索URL
            val searchQuery = if (!artist.isNullOrBlank()) "$title $artist" else title
            val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
            val searchUrl = "http://ioscdn.kugou.com/api/v3/search/song?page=1&pagesize=1&version=7910&keyword=$encodedQuery"
            
            // 执行搜索请求
            val searchResult = getUrlContentWithHeaders(searchUrl, mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
            ))
            
            val searchJson = JSONObject(searchResult)
            
            // 检查是否有结果
            if (!searchJson.has("data") || searchJson.isNull("data") || 
                !searchJson.getJSONObject("data").has("info") || 
                searchJson.getJSONObject("data").isNull("info") || 
                searchJson.getJSONObject("data").getJSONArray("info").length() == 0) {
                return null
            }
            
            // 获取歌曲列表
            val songList = searchJson.getJSONObject("data").getJSONArray("info")
            
            if (songList.length() > 0) {
                // 获取第一首歌曲的hash字段
                val songInfo = songList.getJSONObject(0)
                if (songInfo.has("hash") && !songInfo.isNull("hash")) {
                    val hash = songInfo.getString("hash")
                    
                    // 使用hash获取歌词信息
                    val lyricInfo = getLyricInfoFromHash(hash)
                    if (lyricInfo != null) {
                        val id = lyricInfo.first
                        val accesskey = lyricInfo.second
                        
                        // 使用id和accesskey下载歌词
                        return getLyricFromKugouWithIdAndKey(id, accesskey)
                    }
                }
            }
        } catch (e: Exception) {
            println("从酷狗音乐API获取歌词失败: ${e.message}")
            e.printStackTrace()
        }
        
        return null
    }
    
    // 通过hash获取歌词信息（id和accesskey）
    private fun getLyricInfoFromHash(hash: String): Pair<String, String>? {
        try {
            val lyricInfoUrl = "http://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=%20-%20&duration=139039&hash=$hash"
            
            // 获取歌词信息
            val lyricInfoResult = getUrlContentWithHeaders(lyricInfoUrl, mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
            ))
            
            val lyricInfoJson = JSONObject(lyricInfoResult)
            
            // 检查是否有结果
            if (lyricInfoJson.has("candidates") && !lyricInfoJson.isNull("candidates") && 
                lyricInfoJson.getJSONArray("candidates").length() > 0) {
                
                val candidate = lyricInfoJson.getJSONArray("candidates").getJSONObject(0)
                if (candidate.has("id") && !candidate.isNull("id") && 
                    candidate.has("accesskey") && !candidate.isNull("accesskey")) {
                    
                    val id = candidate.getString("id")
                    val accesskey = candidate.getString("accesskey")
                    return Pair(id, accesskey)
                }
            }
        } catch (e: Exception) {
            println("获取歌词信息失败: ${e.message}")
            e.printStackTrace()
        }
        
        return null
    }
    
    // 使用id和accesskey获取歌词
    private fun getLyricFromKugouWithIdAndKey(id: String, accesskey: String): String? {
        try {
            val lyricUrl = "http://lyrics.kugou.com/download?ver=1&client=pc&fmt=lrc&charset=utf8&id=$id&accesskey=$accesskey"
            
            // 获取歌词
            val lyricResult = getUrlContentWithHeaders(lyricUrl, mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
            ))
            
            val lyricJson = JSONObject(lyricResult)
            
            // 检查是否有歌词内容
            if (lyricJson.has("content") && !lyricJson.isNull("content")) {
                val base64Content = lyricJson.getString("content")
                
                // 解码base64歌词
                return String(Base64.getDecoder().decode(base64Content), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            println("获取歌词内容失败: ${e.message}")
            e.printStackTrace()
        }
        
        return null
    }
    
    // 辅助方法：获取URL内容（带请求头）
    private fun getUrlContentWithHeaders(urlString: String, headers: Map<String, String>): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        
        // 添加请求头
        headers.forEach { (key, value) ->
            conn.setRequestProperty(key, value)
        }
        
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}

    /**
     * SPW内置歌词API
     */
    class LyricSpwServlet : HttpServlet() {
        private val gson = Gson()
        
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "application/json;charset=UTF-8"
            
            try {
                val currentPosition = PlaybackStateHolder.currentPosition
                val (currentLine, nextLine) = PlaybackStateHolder.getCurrentAndNextLyrics(currentPosition)
                
                if (currentLine != null || nextLine != null) {
                    // 构建简化的LRC格式歌词，只包含当前行和下一行
                    val simplifiedLyrics = buildString {
                        if (currentLine != null) {
                            append(formatTimeTag(currentLine.time))
                            append(currentLine.text)
                            append("\n")
                        }
                        
                        if (nextLine != null) {
                            append(formatTimeTag(nextLine.time))
                            append(nextLine.text)
                        }
                    }
                    
                    val response = mapOf(
                        "status" to "success",
                        "lyric" to simplifiedLyrics,
                        "source" to "spw",
                        "simplified" to true
                    )
                    resp.writer.write(gson.toJson(response))
                } else {
                    resp.status = HttpServletResponse.SC_NOT_FOUND
                    resp.writer.write(gson.toJson(mapOf(
                        "status" to "error",
                        "message" to "未找到SPW歌词"
                    )))
                }
            } catch (e: Exception) {
                resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                resp.writer.write(gson.toJson(mapOf(
                    "status" to "error",
                    "message" to "获取SPW歌词失败: ${e.message}"
                )))
            }
        }
        
        // 格式化时间标签
        private fun formatTimeTag(timeMs: Long): String {
            val minutes = timeMs / 60000
            val seconds = (timeMs % 60000) / 1000
            val millis = timeMs % 1000
            return String.format("[%02d:%02d.%03d]", minutes, seconds, millis)
        }
    }

    /**
     * 封面图片API
     */
    class PicServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            val coverUrl = PlaybackStateHolder.coverUrl
            if (coverUrl == null) {
                resp.status = HttpServletResponse.SC_NOT_FOUND
                resp.writer.write("封面地址未找到")
                return
            }
            
            try {
                // 获取图片内容
                val url = URL(coverUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                
                // 设置正确的Content-Type
                val contentType = conn.contentType ?: "image/jpeg"
                resp.contentType = contentType
                
                // 将图片数据写入响应
                conn.inputStream.copyTo(resp.outputStream)
            } catch (e: Exception) {
                resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                resp.writer.write("获取封面失败: ${e.message}")
            }
        }
    }
    
    /**
     * 当前播放位置API
     */
    class CurrentPositionServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "application/json;charset=UTF-8"
            
            val position = PlaybackStateHolder.currentPosition
            val formatted = formatPosition(position)
            
            val response = mapOf(
                "status" to "success",
                "position" to position,
                "formatted" to formatted
            )
            
            resp.writer.write(Gson().toJson(response))
        }
        
        // 格式化位置为分钟:秒:毫秒
        private fun formatPosition(position: Long): String {
            val totalSeconds = position / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val millis = position % 1000
            
            return String.format("%02d:%02d:%03d", minutes, seconds, millis)
        }
    }

    /**
     * 发送系统媒体键事件
     */
    fun sendMediaKeyEvent(virtualKeyCode: Int) {
        try {
            val user32 = User32Ex.INSTANCE
            user32.keybd_event(virtualKeyCode.toByte(), 0, 0, 0)
            Thread.sleep(10)
            user32.keybd_event(virtualKeyCode.toByte(), 0, 2, 0)
        } catch (e: Exception) {
            println("发送媒体键事件失败: ${e.message}")
        }
    }

    // JNA接口
    interface User32Ex : com.sun.jna.Library {
        fun keybd_event(bVk: Byte, bScan: Byte, dwFlags: Int, dwExtraInfo: Int)

        companion object {
            val INSTANCE: User32Ex by lazy {
                Native.load("user32", User32Ex::class.java) as User32Ex
            }
        }
    }
}
