package com.zmxl.plugin.server

import com.google.gson.Gson
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.zmxl.plugin.control.SmtcController
import com.zmxl.plugin.playback.PlaybackStateHolder
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.*
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import java.io.PrintWriter

class HttpServer(private val port: Int) {
    private lateinit var server: Server
    private val htmlContent: String = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Salt Player 控制器</title>
            <script src="https://cdn.tailwindcss.com"></script>
            <link href="https://cdn.jsdelivr.net/npm/font-awesome@4.7.0/css/font-awesome.min.css" rel="stylesheet">
            <script>
                tailwind.config = {
                    theme: {
                        extend: {
                            colors: {
                                primary: '#3B82F6',
                                secondary: '#10B981',
                                dark: '#1E293B',
                                light: '#F8FAFC',
                                accent: '#8B5CF6'
                            },
                            fontFamily: {
                                sans: ['Inter', 'system-ui', 'sans-serif'],
                            },
                        }
                    }
                }
            </script>
            <style type="text/tailwindcss">
                @layer utilities {
                    .content-auto {
                        content-visibility: auto;
                    }
                    .backdrop-blur {
                        backdrop-filter: blur(8px);
                    }
                    .text-shadow {
                        text-shadow: 0 2px 4px rgba(0,0,0,0.1);
                    }
                    .btn-hover {
                        transition: all 0.3s;
                    }
                    .btn-hover:hover {
                        transform: scale(1.1);
                    }
                    .btn-hover:active {
                        transform: scale(0.95);
                    }
                    .card-effect {
                        background: rgba(255, 255, 255, 0.1);
                        backdrop-filter: blur(8px);
                        border-radius: 1rem;
                        box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05);
                        border: 1px solid rgba(255, 255, 255, 0.2);
                    }
                }
            </style>
        </head>
        <body class="bg-gradient-to-br from-gray-900 to-slate-900 min-h-screen text-gray-100 font-sans flex flex-col items-center justify-center p-4">
            <div class="max-w-md w-full card-effect p-6 md:p-8 mb-6">
                <!-- 连接状态指示器 -->
                <div id="connection-status" class="flex items-center justify-center mb-6">
                    <div class="w-3 h-3 rounded-full bg-red-500 mr-2 animate-pulse"></div>
                    <span class="text-sm text-gray-300">连接中...</span>
                </div>

                <!-- 专辑封面 -->
                <div class="relative w-full aspect-square rounded-xl overflow-hidden mb-6 mx-auto bg-gradient-to-br from-blue-400/30 to-purple-600/30 flex items-center justify-center">
                    <i class="fa fa-music text-6xl text-white/30"></i>
                    <div id="album-cover" class="absolute inset-0 bg-cover bg-center opacity-0 transition-opacity duration-500"></div>
                </div>

                <!-- 当前播放信息 -->
                <div class="text-center mb-8 space-y-2">
                    <h1 id="track-title" class="text-2xl font-bold text-shadow truncate">等待连接...</h1>
                    <p id="track-artist" class="text-gray-300 text-xl">未知艺术家</p>
                    <p id="track-album" class="text-gray-400 text-sm">未知专辑</p>
                    
                    <!-- 进度条 -->
                    <div class="mt-4 h-1 bg-gray-700/50 rounded-full overflow-hidden">
                        <div id="progress-bar" class="h-full bg-blue-500 w-0 transition-all duration-300"></div>
                    </div>
                    
                    <div class="flex justify-between text-xs text-gray-400 mt-1">
                        <span id="current-time">00:00</span>
                        <span id="volume-display">--</span>
                    </div>
                </div>

                <!-- 主要控制按钮 -->
                <div class="flex items-center justify-between mb-8 px-4">
                    <button id="prev-btn" class="text-gray-300 hover:text-white btn-hover disabled:opacity-50 disabled:cursor-not-allowed">
                        <i class="fa fa-step-backward text-2xl md:text-3xl"></i>
                    </button>
                    
                    <button id="play-pause-btn" class="bg-blue-500 hover:bg-blue-400 text-white rounded-full w-16 h-16 md:w-20 md:h-20 flex items-center justify-center shadow-lg shadow-blue-500/20 btn-hover disabled:opacity-50 disabled:cursor-not-allowed">
                        <i id="play-pause-icon" class="fa fa-play text-2xl md:text-3xl"></i>
                    </button>
                    
                    <button id="next-btn" class="text-gray-300 hover:text-white btn-hover disabled:opacity-50 disabled:cursor-not-allowed">
                        <i class="fa fa-step-forward text-2xl md:text-3xl"></i>
                    </button>
                </div>

                <!-- 音量控制 -->
                <div class="flex items-center justify-center space-x-6">
                    <button id="volume-down-btn" class="text-gray-300 hover:text-white btn-hover disabled:opacity-50 disabled:cursor-not-allowed">
                        <i class="fa fa-volume-down text-xl"></i>
                    </button>
                    
                    <button id="mute-btn" class="text-gray-300 hover:text-white btn-hover disabled:opacity-50 disabled:cursor-not-allowed">
                        <i id="mute-icon" class="fa fa-volume-up text-xl"></i>
                    </button>
                    
                    <button id="volume-up-btn" class="text-gray-300 hover:text-white btn-hover disabled:opacity-50 disabled:cursor-not-allowed">
                        <i class="fa fa-volume-up text-xl"></i>
                    </button>
                </div>
            </div>

            <!-- 状态消息 -->
            <div id="status-message" class="text-sm text-gray-400 max-w-md w-full text-center mb-4 opacity-0 transition-opacity duration-300"></div>

            <!-- 错误提示卡片 -->
            <div id="error-card" class="max-w-md w-full card-effect p-4 mb-6 hidden">
                <h3 class="font-bold text-red-400 mb-2 flex items-center">
                    <i class="fa fa-exclamation-circle mr-2"></i>连接问题帮助
                </h3>
                <p class="text-sm text-gray-300 mb-2">如果看到"Failed to fetch"错误，可能是由于跨域限制(CORS)导致的。</p>
                <p class="text-sm text-gray-300">请确保API服务器已配置正确的CORS头信息，允许当前域名的请求。</p>
            </div>

            <!-- 当前API域名显示 -->
            <div id="api-domain-info" class="text-xs text-gray-500 max-w-md w-full text-center">
                API 域名: <span id="current-api-domain">检测中...</span>
            </div>

            <script>
                // 动态获取当前域名作为API基础地址
                const API_BASE = `${window.location.origin}/api`;
                let isConnected = false;
                let isPlaying = false;
                let isMuted = false;
                let corsErrorShown = false;
                
                // DOM元素
                const elements = {
                    connectionStatus: document.getElementById('connection-status'),
                    trackTitle: document.getElementById('track-title'),
                    trackArtist: document.getElementById('track-artist'),
                    trackAlbum: document.getElementById('track-album'),
                    albumCover: document.getElementById('album-cover'),
                    progressBar: document.getElementById('progress-bar'),
                    currentTime: document.getElementById('current-time'),
                    volumeDisplay: document.getElementById('volume-display'),
                    playPauseBtn: document.getElementById('play-pause-btn'),
                    playPauseIcon: document.getElementById('play-pause-icon'),
                    prevBtn: document.getElementById('prev-btn'),
                    nextBtn: document.getElementById('next-btn'),
                    volumeUpBtn: document.getElementById('volume-up-btn'),
                    volumeDownBtn: document.getElementById('volume-down-btn'),
                    muteBtn: document.getElementById('mute-btn'),
                    muteIcon: document.getElementById('mute-icon'),
                    statusMessage: document.getElementById('status-message'),
                    errorCard: document.getElementById('error-card'),
                    currentApiDomain: document.getElementById('current-api-domain')
                };

                // 显示当前API域名
                elements.currentApiDomain.textContent = API_BASE;

                // 格式化时间（毫秒转分:秒）
                function formatTime(ms) {
                    if (isNaN(ms)) return "00:00";
                    const totalSeconds = Math.floor(ms / 1000);
                    const minutes = Math.floor(totalSeconds / 60);
                    const seconds = totalSeconds % 60;
                    return `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
                }

                // 显示状态消息
                function showMessage(message, isError = false) {
                    elements.statusMessage.textContent = message;
                    elements.statusMessage.className = `text-sm max-w-md w-full text-center mb-4 transition-opacity duration-300 ${isError ? 'text-red-400' : 'text-green-400'}`;
                    elements.statusMessage.style.opacity = '1';
                    
                    // 如果是跨域错误，显示帮助卡片
                    if (isError && message.includes('Failed to fetch') && !corsErrorShown) {
                        elements.errorCard.classList.remove('hidden');
                        corsErrorShown = true;
                    }
                    
                    setTimeout(() => {
                        elements.statusMessage.style.opacity = '0';
                    }, 3000);
                }

                // 更新连接状态显示
                function updateConnectionStatus(connected) {
                    isConnected = connected;
                    if (connected) {
                        elements.connectionStatus.innerHTML = `
                            <div class="w-3 h-3 rounded-full bg-green-500 mr-2"></div>
                            <span class="text-sm text-gray-300">已连接到 API</span>
                        `;
                        // 隐藏错误卡片
                        elements.errorCard.classList.add('hidden');
                        corsErrorShown = false;
                        
                        // 启用所有按钮
                        document.querySelectorAll('button').forEach(btn => {
                            btn.removeAttribute('disabled');
                        });
                    } else {
                        elements.connectionStatus.innerHTML = `
                            <div class="w-3 h-3 rounded-full bg-red-500 mr-2 animate-pulse"></div>
                            <span class="text-sm text-gray-300">未连接到 API</span>
                        `;
                        // 禁用所有按钮
                        document.querySelectorAll('button').forEach(btn => {
                            btn.setAttribute('disabled', 'true');
                        });
                    }
                }

                // 更新播放信息
                function updateNowPlaying(data) {
                    elements.trackTitle.textContent = data.title || "未知标题";
                    elements.trackArtist.textContent = data.artist || "未知艺术家";
                    elements.trackAlbum.textContent = data.album || "未知专辑";
                    elements.currentTime.textContent = formatTime(data.position);
                    elements.volumeDisplay.textContent = data.volume !== undefined ? `${Math.round(data.volume * 100)}%` : "--";
                    
                    // 更新进度条
                    elements.progressBar.style.width = data.position ? `${(data.position % 300000) / 3000}%` : "0%";
                    
                    // 更新播放状态
                    isPlaying = data.isPlaying;
                    elements.playPauseIcon.className = isPlaying ? "fa fa-pause text-2xl md:text-3xl" : "fa fa-play text-2xl md:text-3xl";
                    
                    // 专辑封面效果
                    if (data.title) {
                        const hash = Array.from(data.title).reduce((acc, char) => acc + char.charCodeAt(0), 0);
                        const color1 = `hsl(${hash % 360}, 70%, 60%)`;
                        const color2 = `hsl(${(hash + 120) % 360}, 70%, 60%)`;
                        elements.albumCover.style.background = `linear-gradient(135deg, ${color1}, ${color2})`;
                        elements.albumCover.style.opacity = '1';
                    }
                }

                // API请求函数
                async function apiRequest(endpoint) {
                    try {
                        const response = await fetch(`${API_BASE}${endpoint}`, {
                            mode: 'cors',
                            headers: {
                                'Content-Type': 'application/json',
                            }
                        });
                        
                        if (!response.ok) {
                            throw new Error(`HTTP错误: ${response.status} (${response.statusText})`);
                        }
                        
                        const data = await response.json();
                        
                        if (data.status === "error") {
                            showMessage(data.message, true);
                            return null;
                        }
                        
                        return data;
                    } catch (error) {
                        // 更详细的错误信息
                        let errorMsg = `请求失败: ${error.message}`;
                        if (error.message.includes('Failed to fetch')) {
                            errorMsg += " - 可能是跨域限制(CORS)问题";
                        }
                        showMessage(errorMsg, true);
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
                        showMessage("已获取播放信息");
                    } else {
                        updateConnectionStatus(false);
                    }
                }

                // 播放/暂停切换
                async function togglePlayPause() {
                    const data = await apiRequest('/play-pause');
                    if (data) {
                        isPlaying = data.isPlaying;
                        elements.playPauseIcon.className = isPlaying ? "fa fa-pause text-2xl md:text-3xl" : "fa fa-play text-2xl md:text-3xl";
                        showMessage(data.message);
                        fetchNowPlaying();
                    }
                }

                // 下一曲
                async function nextTrack() {
                    const data = await apiRequest('/next-track');
                    if (data) {
                        showMessage(data.message);
                        fetchNowPlaying();
                    }
                }

                // 上一曲
                async function previousTrack() {
                    const data = await apiRequest('/previous-track');
                    if (data) {
                        showMessage(data.message);
                        fetchNowPlaying();
                    }
                }

                // 音量增加
                async function volumeUp() {
                    const data = await apiRequest('/volume/up');
                    if (data) {
                        elements.volumeDisplay.textContent = `${Math.round(data.currentVolume * 100)}%`;
                        showMessage(data.message);
                    }
                }

                // 音量减少
                async function volumeDown() {
                    const data = await apiRequest('/volume/down');
                    if (data) {
                        elements.volumeDisplay.textContent = `${Math.round(data.currentVolume * 100)}%`;
                        showMessage(data.message);
                    }
                }

                // 静音切换
                async function toggleMute() {
                    const data = await apiRequest('/mute');
                    if (data) {
                        isMuted = data.isMuted;
                        elements.muteIcon.className = isMuted ? "fa fa-volume-off text-xl" : "fa fa-volume-up text-xl";
                        showMessage(data.message);
                    }
                }

                // 绑定事件监听器
                function bindEvents() {
                    elements.playPauseBtn.addEventListener('click', togglePlayPause);
                    elements.prevBtn.addEventListener('click', previousTrack);
                    elements.nextBtn.addEventListener('click', nextTrack);
                    elements.volumeUpBtn.addEventListener('click', volumeUp);
                    elements.volumeDownBtn.addEventListener('click', volumeDown);
                    elements.muteBtn.addEventListener('click', toggleMute);
                    
                    // 键盘快捷键
                    document.addEventListener('keydown', (e) => {
                        if (!isConnected) return;
                        
                        switch(e.key) {
                            case ' ': // 空格
                                e.preventDefault();
                                togglePlayPause();
                                break;
                            case 'ArrowRight':
                                nextTrack();
                                break;
                            case 'ArrowLeft':
                                previousTrack();
                                break;
                            case 'ArrowUp':
                                volumeUp();
                                break;
                            case 'ArrowDown':
                                volumeDown();
                                break;
                            case 'm':
                                toggleMute();
                                break;
                        }
                    });
                }

                // 初始化
                function init() {
                    // 初始禁用所有按钮
                    document.querySelectorAll('button').forEach(btn => {
                        btn.setAttribute('disabled', 'true');
                    });
                    
                    // 尝试连接
                    fetchNowPlaying();
                    
                    // 每5秒刷新一次播放信息
                    setInterval(fetchNowPlaying, 5000);
                    
                    // 绑定事件
                    bindEvents();
                }

                // 启动应用
                window.addEventListener('DOMContentLoaded', init);
            </script>
        </body>
        </html>
    """.trimIndent()

    init {
        // 初始化SMTC控制器
        SmtcController.init()
    }

    fun start() {
        server = Server(port)
        val context = ServletContextHandler(ServletContextHandler.SESSIONS)
        context.contextPath = "/"
        server.handler = context

        // 注册根路径路由（返回控制界面HTML）
        context.addServlet(HomeServlet::class.java, "/")

        // 注册默认Servlet处理静态资源
        val defaultHolder = ServletHolder("default", DefaultServlet::class.java)
        defaultHolder.setInitParameter("dirAllowed", "false")
        context.addServlet(defaultHolder, "/*")
        
        // 将HttpServer实例存入ServletContext，以便在Servlet中访问
        context.setAttribute("httpServer", this)

        // 注册所有API端点
        context.addServlet(NowPlayingServlet::class.java, "/api/now-playing")
        context.addServlet(PlayPauseServlet::class.java, "/api/play-pause")
        context.addServlet(NextTrackServlet::class.java, "/api/next-track")
        context.addServlet(PreviousTrackServlet::class.java, "/api/previous-track")
        context.addServlet(VolumeUpServlet::class.java, "/api/volume/up")
        context.addServlet(VolumeDownServlet::class.java, "/api/volume/down")
        context.addServlet(MuteServlet::class.java, "/api/mute")

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
     * 根路径路由处理，返回硬编码的HTML内容
     */
    class HomeServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "text/html;charset=UTF-8"
            resp.characterEncoding = "UTF-8"
            
            // 获取HttpServer实例
            val httpServer = req.servletContext.getAttribute("httpServer") as HttpServer
            
            // 直接返回硬编码的HTML内容
            resp.writer.write(httpServer.htmlContent)
            println("成功返回硬编码HTML内容")
        }
    }

    /**
     * 获取当前播放信息API
     * 返回实际的播放状态、歌曲信息和音量
     */
    class NowPlayingServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "application/json;charset=UTF-8"
            
            // 从状态持有者获取实时播放信息
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
     * 通过SMTC控制器执行实际操作并返回更新后的状态
     */
    class PlayPauseServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "application/json;charset=UTF-8"
            
            try {
                // 通过SMTC执行播放/暂停操作
                // 发送系统媒体键事件（模拟物理按键）
                val httpServer = req.servletContext.getAttribute("httpServer") as HttpServer
                httpServer.sendMediaKeyEvent(0xB3) // 0xB3是播放/暂停的虚拟键码
                
                // 短暂延迟确保状态更新
                Thread.sleep(100)
                
                // 返回更新后的状态
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
     * 通过SMTC控制器执行实际操作
     */
    class NextTrackServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "application/json;charset=UTF-8"
            
            try {
                // 通过SMTC执行下一曲操作
                SmtcController.handleNextTrack()
                // 发送系统媒体键事件确保操作生效
                val httpServer = req.servletContext.getAttribute("httpServer") as HttpServer
                httpServer.sendMediaKeyEvent(0xB0) // 0xB0是下一曲的虚拟键码
                
                // 短暂延迟确保状态更新
                Thread.sleep(100)
                
                // 获取更新后的媒体信息
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
     * 通过SMTC控制器执行实际操作
     */
    class PreviousTrackServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "application/json;charset=UTF-8"
            
            try {
                // 通过SMTC执行上一曲操作
                SmtcController.handlePreviousTrack()
                // 发送系统媒体键事件确保操作生效
                val httpServer = req.servletContext.getAttribute("httpServer") as HttpServer
                httpServer.sendMediaKeyEvent(0xB1) // 0xB1是上一曲的虚拟键码
                
                // 短暂延迟确保状态更新
                Thread.sleep(100)
                
                // 获取更新后的媒体信息
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
                val httpServer = req.servletContext.getAttribute("httpServer") as HttpServer
                httpServer.sendMediaKeyEvent(0xAF) // 音量增加虚拟键码
                
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
                val httpServer = req.servletContext.getAttribute("httpServer") as HttpServer
                httpServer.sendMediaKeyEvent(0xAE) // 音量减少虚拟键码
                
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
                val httpServer = req.servletContext.getAttribute("httpServer") as HttpServer
                httpServer.sendMediaKeyEvent(0xAD) // 静音虚拟键码
                
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
     * 发送系统媒体键事件（通过JNA调用Windows API）
     * 确保控制命令能被系统和播放器识别
     */
    private fun sendMediaKeyEvent(virtualKeyCode: Int) {
        try {
            // 使用自定义的JNA接口发送键盘事件
            val user32 = User32Ex.INSTANCE
            
            // 模拟按键按下
            user32.keybd_event(
                virtualKeyCode.toByte(),
                0,
                0, // KEYEVENTF_EXTENDEDKEY = 0
                0
            )
            
            // 短暂延迟模拟实际按键
            Thread.sleep(10)
            
            // 模拟按键释放
            user32.keybd_event(
                virtualKeyCode.toByte(),
                0,
                2, // KEYEVENTF_KEYUP = 2
                0
            )
        } catch (e: Exception) {
            println("发送媒体键事件失败: ${e.message}")
        }
    }

    // 自定义JNA接口，用于发送系统按键事件
    interface User32Ex : com.sun.jna.Library {
        fun keybd_event(bVk: Byte, bScan: Byte, dwFlags: Int, dwExtraInfo: Int)

        companion object {
            val INSTANCE: User32Ex by lazy {
                Native.load("user32", User32Ex::class.java) as User32Ex
            }
        }
    }
}
