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
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.URL
import java.nio.charset.StandardCharsets

class HttpServer(private val port: Int) {
    private lateinit var server: Server

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
     * 根路径路由处理，返回控制界面HTML
     */
    class HomeServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "text/html;charset=UTF-8"
            resp.characterEncoding = "UTF-8"
            
            // 使用正确的类加载器加载资源
            val htmlContent = loadHtmlResource()
            if (htmlContent != null) {
                resp.writer.write(htmlContent)
            } else {
                resp.status = HttpServletResponse.SC_NOT_FOUND
                resp.writer.write("""
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <title>404 - 控制界面未找到</title>
                        <style>
                            body { 
                                background-color: #1e293b; 
                                color: #f8fafc; 
                                font-family: sans-serif; 
                                display: flex; 
                                justify-content: center; 
                                align-items: center; 
                                height: 100vh; 
                                margin: 0; 
                            }
                            .container { 
                                text-align: center; 
                                padding: 2rem; 
                                border-radius: 1rem; 
                                background-color: #334155; 
                                box-shadow: 0 4px 6px rgba(0,0,0,0.1);
                            }
                            h1 { 
                                color: #ef4444; 
                                margin-bottom: 1rem;
                            }
                            p { 
                                margin-bottom: 0.5rem;
                            }
                            .resource-path {
                                background-color: #475569;
                                padding: 0.5rem;
                                border-radius: 0.25rem;
                                font-family: monospace;
                                margin-top: 1rem;
                            }
                        </style>
                    </head>
                    <body>
                        <div class="container">
                            <h1>404 - 控制界面未找到</h1>
                            <p>无法加载控制界面HTML文件</p>
                            <p>请确保插件已正确打包，且index.html位于类路径根目录</p>
                            <p>当前时间: ${System.currentTimeMillis()}</p>
                            <div class="resource-path">查找路径: ${getResourcePathInfo()}</div>
                        </div>
                    </body>
                    </html>
                """.trimIndent())
                println("无法加载控制界面HTML")
            }
        }

        private fun loadHtmlResource(): String? {
            return try {
                // 使用正确的类加载器访问资源
                val classLoader = this::class.java.classLoader
                val resourceUrl: URL? = classLoader?.getResource("index.html")
                
                if (resourceUrl != null) {
                    println("[资源加载] 找到HTML资源: $resourceUrl")
                    resourceUrl.openStream().use { inputStream ->
                        InputStreamReader(inputStream, StandardCharsets.UTF_8).use { reader ->
                            reader.readText()
                        }
                    }
                } else {
                    println("[资源加载] 未找到HTML资源: index.html")
                    println("[资源加载] 类加载器: ${classLoader?.javaClass?.name}")
                    println("[资源加载] 资源搜索路径: ${getResourcePathInfo()}")
                    null
                }
            } catch (e: Exception) {
                println("[资源加载] 加载HTML资源失败: ${e.message}")
                e.printStackTrace()
                null
            }
        }
        
        private fun getResourcePathInfo(): String {
            return try {
                val classLoader = this::class.java.classLoader
                val urls = (classLoader as? java.net.URLClassLoader)?.urLs
                if (urls != null) {
                    "类路径: ${urls.joinToString("\n") { it.toString() }}"
                } else {
                    "无法获取类路径信息"
                }
            } catch (e: Exception) {
                "获取类路径信息失败: ${e.message}"
            }
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
