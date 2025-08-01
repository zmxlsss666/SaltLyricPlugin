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
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL

class HttpServer(private val port: Int) {
    private lateinit var server: Server
    private val controlHtml = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Salt Player 控制器</title>
            <style>
                body {
                    font-family: Arial, sans-serif;
                    background: #1e293b;
                    color: white;
                    margin: 0;
                    padding: 20px;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    justify-content: center;
                    min-height: 100vh;
                }
                
                .container {
                    background: rgba(255, 255, 255, 0.1);
                    border-radius: 10px;
                    padding: 20px;
                    max-width: 400px;
                    width: 100%;
                    box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
                    text-align: center;
                }
                
                .status {
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    margin-bottom: 20px;
                }
                
                .status-dot {
                    width: 10px;
                    height: 10px;
                    border-radius: 50%;
                    margin-right: 10px;
                }
                
                .connected {
                    background: #10B981;
                }
                
                .disconnected {
                    background: #EF4444;
                    animation: pulse 1.5s infinite;
                }
                
                @keyframes pulse {
                    0% { opacity: 1; }
                    50% { opacity: 0.5; }
                    100% { opacity: 1; }
                }
                
                .track-info {
                    margin-bottom: 20px;
                }
                
                .track-title {
                    font-size: 24px;
                    font-weight: bold;
                    margin-bottom: 5px;
                }
                
                .track-artist {
                    font-size: 18px;
                    color: #94a3b8;
                }
                
                .controls {
                    display: flex;
                    justify-content: space-between;
                    margin-bottom: 20px;
                }
                
                button {
                    background: #3B82F6;
                    color: white;
                    border: none;
                    border-radius: 5px;
                    padding: 10px 15px;
                    cursor: pointer;
                    font-size: 16px;
                }
                
                .play-pause {
                    background: #10B981;
                    font-weight: bold;
                }
                
                .message {
                    margin-top: 20px;
                    min-height: 20px;
                }
                
                .success {
                    color: #10B981;
                }
                
                .error {
                    color: #EF4444;
                }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="status">
                    <div id="status-dot" class="status-dot disconnected"></div>
                    <span id="status-text">未连接到API</span>
                </div>
                
                <div class="track-info">
                    <div id="track-title" class="track-title">等待连接...</div>
                    <div id="track-artist" class="track-artist">未知艺术家</div>
                </div>
                
                <div class="controls">
                    <button id="prev-btn">上一曲</button>
                    <button id="play-pause-btn" class="play-pause">播放/暂停</button>
                    <button id="next-btn">下一曲</button>
                </div>
                
                <div class="controls">
                    <button id="volume-down-btn">音量-</button>
                    <button id="mute-btn">静音</button>
                    <button id="volume-up-btn">音量+</button>
                </div>
                
                <div id="message" class="message"></div>
            </div>
            
            <script>
                const API_BASE = window.location.origin + '/api';
                let isPlaying = false;
                let isMuted = false;
                
                // 更新连接状态
                function updateConnectionStatus(connected) {
                    const statusDot = document.getElementById('status-dot');
                    const statusText = document.getElementById('status-text');
                    
                    if (connected) {
                        statusDot.className = 'status-dot connected';
                        statusText.textContent = '已连接到API';
                    } else {
                        statusDot.className = 'status-dot disconnected';
                        statusText.textContent = '未连接到API';
                    }
                }
                
                // 更新播放信息
                function updateNowPlaying(data) {
                    document.getElementById('track-title').textContent = data.title || '未知标题';
                    document.getElementById('track-artist').textContent = data.artist || '未知艺术家';
                    
                    isPlaying = data.isPlaying;
                    document.getElementById('play-pause-btn').textContent = isPlaying ? '暂停' : '播放';
                }
                
                // 显示消息
                function showMessage(message, isError) {
                    const messageEl = document.getElementById('message');
                    messageEl.textContent = message;
                    messageEl.className = isError ? 'message error' : 'message success';
                    
                    setTimeout(() => {
                        messageEl.textContent = '';
                    }, 3000);
                }
                
                // API请求
                async function apiRequest(endpoint) {
                    try {
                        const response = await fetch(API_BASE + endpoint);
                        if (!response.ok) {
                            throw new Error('请求失败: ' + response.status);
                        }
                        return await response.json();
                    } catch (error) {
                        showMessage(error.message, true);
                        updateConnectionStatus(false);
                        return null;
                    }
                }
                
                // 获取当前播放信息
                async function fetchNowPlaying() {
                    const data = await apiRequest('/now-playing');
                    if (data) {
                        updateConnectionStatus(true);
                        updateNowPlaying(data);
                    }
                }
                
                // 播放/暂停
                async function togglePlayPause() {
                    const data = await apiRequest('/play-pause');
                    if (data) {
                        updateNowPlaying(data);
                        showMessage(data.message);
                    }
                }
                
                // 下一曲
                async function nextTrack() {
                    const data = await apiRequest('/next-track');
                    if (data) {
                        fetchNowPlaying();
                        showMessage(data.message);
                    }
                }
                
                // 上一曲
                async function previousTrack() {
                    const data = await apiRequest('/previous-track');
                    if (data) {
                        fetchNowPlaying();
                        showMessage(data.message);
                    }
                }
                
                // 音量增加
                async function volumeUp() {
                    const data = await apiRequest('/volume/up');
                    if (data) {
                        showMessage(data.message);
                    }
                }
                
                // 音量减少
                async function volumeDown() {
                    const data = await apiRequest('/volume/down');
                    if (data) {
                        showMessage(data.message);
                    }
                }
                
                // 静音
                async function toggleMute() {
                    const data = await apiRequest('/mute');
                    if (data) {
                        isMuted = data.isMuted;
                        document.getElementById('mute-btn').textContent = isMuted ? '取消静音' : '静音';
                        showMessage(data.message);
                    }
                }
                
                // 初始化
                function init() {
                    // 绑定按钮事件
                    document.getElementById('play-pause-btn').addEventListener('click', togglePlayPause);
                    document.getElementById('prev-btn').addEventListener('click', previousTrack);
                    document.getElementById('next-btn').addEventListener('click', nextTrack);
                    document.getElementById('volume-up-btn').addEventListener('click', volumeUp);
                    document.getElementById('volume-down-btn').addEventListener('click', volumeDown);
                    document.getElementById('mute-btn').addEventListener('click', toggleMute);
                    
                    // 初始获取播放信息
                    fetchNowPlaying();
                    
                    // 每5秒刷新一次
                    setInterval(fetchNowPlaying, 5000);
                }
                
                // 启动应用
                document.addEventListener('DOMContentLoaded', init);
            </script>
        </body>
        </html>
    """.trimIndent()

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
        context.addServlet(ServletHolder(LyricServlet()), "/api/lyric")
        context.addServlet(ServletHolder(PicServlet()), "/api/pic")
        context.addServlet(ServletHolder(CurrentPositionServlet()), "/api/current-position")

        // 处理所有其他请求，返回控制界面
        context.addServlet(ServletHolder(object : HttpServlet() {
            @Throws(IOException::class)
            override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
                resp.contentType = "text/html;charset=UTF-8"
                resp.characterEncoding = "UTF-8"
                resp.writer.write(controlHtml)
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
                "volume" to PlaybackStateHolder.volume,
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
                
                val isMuted = PlaybackStateHolder.volume == 0.0f
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
     * 歌词API
     */
    class LyricServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "application/json;charset=UTF-8"
            
            val lyricUrl = PlaybackStateHolder.lyricUrl
            if (lyricUrl == null) {
                resp.status = HttpServletResponse.SC_NOT_FOUND
                resp.writer.write(Gson().toJson(mapOf(
                    "status" to "error",
                    "message" to "歌词地址未找到"
                )))
                return
            }
            
            try {
                // 获取歌词内容
                val lyricContent = getUrlContent(lyricUrl)
                
                // 返回歌词
                val response = mapOf(
                    "status" to "success",
                    "lyric" to lyricContent
                )
                resp.writer.write(Gson().toJson(response))
            } catch (e: Exception) {
                resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                resp.writer.write(Gson().toJson(mapOf(
                    "status" to "error",
                    "message" to "获取歌词失败: ${e.message}"
                )))
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
