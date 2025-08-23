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
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

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
        
        // 注册两个歌词API
        context.addServlet(ServletHolder(Lyric163Servlet()), "/api/lyric163") // 网络歌词API
        context.addServlet(ServletHolder(LyricSpwServlet()), "/api/lyricspw") // SPW歌词API
        
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
                val lyricUrl = "https://music.163.com/api/song/lyric?id=$songId&lv=1"
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
