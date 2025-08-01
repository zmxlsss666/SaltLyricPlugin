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
        <!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport"content="width=device-width, initial-scale=1.0"><title>SaltPlayer Web控制器</title><link rel="stylesheet"href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css"><style>*{margin:0;padding:0;box-sizing:border-box;font-family:'Segoe UI',Tahoma,Geneva,Verdana,sans-serif}body{background:linear-gradient(135deg,#1a1a2e,#16213e,#0f3460);color:#fff;min-height:100vh;display:flex;justify-content:center;align-items:center;padding:20px}.container{max-width:1000px;width:100%;display:flex;flex-direction:column;gap:20px}.player-card{background:rgba(30,30,46,0.8);border-radius:20px;box-shadow:0 10px 30px rgba(0,0,0,0.3);overflow:hidden;backdrop-filter:blur(10px);border:1px solid rgba(255,255,255,0.1)}.player-header{display:flex;padding:30px;gap:25px;position:relative}.album-art{flex:0 0 200px;height:200px;border-radius:10px;overflow:hidden;box-shadow:0 5px 15px rgba(0,0,0,0.4);background:linear-gradient(45deg,#4e54c8,#8f94fb);display:flex;justify-content:center;align-items:center;position:relative}.album-art img{width:100%;height:100%;object-fit:cover;display:none}.album-art.music-icon{font-size:80px;color:rgba(255,255,255,0.2)}.track-info{flex:1;display:flex;flex-direction:column;justify-content:center}.track-title{font-size:32px;font-weight:700;margin-bottom:10px;letter-spacing:0.5px;text-shadow:0 2px 4px rgba(0,0,0,0.3)}.track-artist{font-size:20px;color:#8a8dcc;margin-bottom:30px;font-weight:500}.progress-container{margin:15px 0}.progress-bar{height:6px;background:rgba(255,255,255,0.1);border-radius:3px;overflow:hidden;position:relative;margin-bottom:5px}.progress{height:100%;background:linear-gradient(to right,#8f94fb,#4e54c8);border-radius:3px;width:30%;position:relative}.progress-time{display:flex;justify-content:space-between;font-size:14px;color:#aaa}.controls{display:flex;align-items:center;gap:20px;margin-top:30px}.control-btn{width:50px;height:50px;border-radius:50%;background:rgba(255,255,255,0.1);border:none;color:#fff;font-size:20px;display:flex;justify-content:center;align-items:center;cursor:pointer;transition:all 0.3s ease;box-shadow:0 4px 10px rgba(0,0,0,0.2)}.control-btn:hover{background:rgba(255,255,255,0.2);transform:scale(1.05)}.control-btn:active{transform:scale(0.95)}.play-btn{width:60px;height:60px;background:linear-gradient(135deg,#8f94fb,#4e54c8);font-size:24px}.volume-container{display:flex;align-items:center;gap:10px;margin-left:auto;background:rgba(255,255,255,0.1);padding:5px 15px;border-radius:30px}.volume-slider{width:100px;-webkit-appearance:none;height:4px;border-radius:2px;background:rgba(255,255,255,0.2);outline:none}.volume-slider::-webkit-slider-thumb{-webkit-appearance:none;width:14px;height:14px;border-radius:50%;background:#fff;cursor:pointer;box-shadow:0 0 5px rgba(0,0,0,0.3)}.volume-btn{background:transparent;font-size:18px}.lyrics-container{padding:30px;background:rgba(20,20,36,0.6);border-top:1px solid rgba(255,255,255,0.05);max-height:300px;overflow-y:auto}.lyrics-title{font-size:18px;color:#8a8dcc;margin-bottom:20px;display:flex;align-items:center;gap:10px}.lyrics-content{line-height:1.8;font-size:18px;text-align:center;color:rgba(255,255,255,0.7);min-height:150px;display:flex;flex-direction:column;justify-content:center;align-items:center}.lyrics-content p{margin:10px 0;transition:all 0.3s ease;padding:5px 15px;border-radius:5px}.lyrics-content.current-lyric{color:#fff;font-size:22px;font-weight:bold;background:rgba(143,148,251,0.15);transform:scale(1.05)}.status-bar{display:flex;justify-content:space-between;align-items:center;padding:15px 30px;background:rgba(15,15,30,0.7);border-top:1px solid rgba(255,255,255,0.05);font-size:14px;color:#aaa}.status-item{display:flex;align-items:center;gap:8px}.loading{display:none;text-align:center;padding:20px;color:#8a8dcc}.error{display:none;color:#ff6b6b;background:rgba(255,107,107,0.1);padding:15px;border-radius:8px;margin:20px;text-align:center}@media(max-width:768px){.player-header{flex-direction:column;padding:20px}.album-art{width:100%;max-width:200px;margin:0 auto}.track-info{text-align:center}.controls{justify-content:center}.volume-container{margin-left:0}}</style></head><body><div class="container"><div class="player-card"><div class="player-header"><div class="album-art"><i class="fas fa-music music-icon"></i><img id="album-cover"src=""alt="Album Cover"></div><div class="track-info"><h1 class="track-title"id="track-title">歌曲标题</h1><p class="track-artist"id="track-artist">艺术家</p><div class="progress-container"><div class="progress-bar"><div class="progress"id="progress-bar"></div></div><div class="progress-time"><span id="current-time">00:00</span><span id="total-time">00:00</span></div></div><div class="controls"><button class="control-btn"id="prev-btn"title="上一曲"><i class="fas fa-step-backward"></i></button><button class="control-btn play-btn"id="play-btn"title="播放/暂停"><i class="fas fa-play"></i></button><button class="control-btn"id="next-btn"title="下一曲"><i class="fas fa-step-forward"></i></button><div class="volume-container"><button class="control-btn volume-btn"id="mute-btn"title="静音"><i class="fas fa-volume-up"></i></button><input type="range"class="volume-slider"id="volume-slider"min="0"max="100"value="80"></div></div></div></div><div class="lyrics-container"><div class="lyrics-title"><i class="fas fa-music"></i>歌词</div><div class="lyrics-content"id="lyrics-content"><p>正在加载歌词...</p></div></div><div class="status-bar"><div class="status-item"><i class="fas fa-plug"></i><span>SaltPlayer API已连接</span></div><div class="status-item"><i class="fas fa-clock"></i><span id="update-time">最后更新:--:--:--</span></div></div></div><div class="loading"id="loading"><i class="fas fa-spinner fa-spin"></i>正在加载数据...</div><div class="error"id="error-message"></div></div><script>let currentLyrics=[];let currentPosition=0;let updateInterval;let lyricsUpdateInterval;const trackTitle=document.getElementById('track-title');const trackArtist=document.getElementById('track-artist');const progressBar=document.getElementById('progress-bar');const currentTime=document.getElementById('current-time');const totalTime=document.getElementById('total-time');const playBtn=document.getElementById('play-btn');const playBtnIcon=playBtn.querySelector('i');const prevBtn=document.getElementById('prev-btn');const nextBtn=document.getElementById('next-btn');const muteBtn=document.getElementById('mute-btn');const muteBtnIcon=muteBtn.querySelector('i');const volumeSlider=document.getElementById('volume-slider');const lyricsContent=document.getElementById('lyrics-content');const albumCover=document.getElementById('album-cover');const updateTime=document.getElementById('update-time');const loading=document.getElementById('loading');const errorMessage=document.getElementById('error-message');function formatTime(ms){if(isNaN(ms))return"00:00";const seconds=Math.floor(ms/1000);const minutes=Math.floor(seconds/60);const remainingSeconds=seconds%60;return`${minutes.toString().padStart(2,'0')}:${remainingSeconds.toString().padStart(2,'0')}`}function formatTimeDetailed(ms){if(isNaN(ms))return"00:00.000";const totalSeconds=ms/1000;const minutes=Math.floor(totalSeconds/60);const seconds=Math.floor(totalSeconds%60);const milliseconds=Math.floor((ms%1000));return`${minutes.toString().padStart(2,'0')}:${seconds.toString().padStart(2,'0')}.${milliseconds.toString().padStart(3,'0')}`}async function getNowPlaying(){try{const response=await fetch('http://localhost:35373/api/now-playing');if(!response.ok)throw new Error('网络响应不正常');return await response.json()}catch(error){showError(`无法获取当前播放信息:${error.message}`);return null}}async function getLyrics(){try{const response=await fetch('http://localhost:35373/api/lyric');if(!response.ok)throw new Error('歌词获取失败');const data=await response.json();if(data.status==='success'&&data.lyric){return data.lyric}return null}catch(error){console.error('获取歌词失败:',error);return null}}function parseLyrics(lyricsText){if(!lyricsText)return[];const lines=lyricsText.split('\n');const lyrics=[];lines.forEach(line=>{const timeTags=line.match(/\[(\d+):(\d+)\.(\d+)\]/g);if(!timeTags)return;const text=line.replace(timeTags.join(''),'').trim();if(!text)return;timeTags.forEach(tag=>{const match=tag.match(/\[(\d+):(\d+)\.(\d+)\]/);if(match){const min=parseInt(match[1]);const sec=parseInt(match[2]);const ms=parseInt(match[3]);const time=min*60000+sec*1000+ms;lyrics.push({time,text})}})});lyrics.sort((a,b)=>a.time-b.time);return lyrics}function updateLyricsDisplay(position){if(!currentLyrics.length){lyricsContent.innerHTML='<p>暂无歌词</p>';return}let currentIndex=-1;for(let i=0;i<currentLyrics.length;i++){if(currentLyrics[i].time>position){currentIndex=i-1;break}}if(currentIndex===-1){currentIndex=currentLyrics.length-1}let lyricsHTML='';for(let i=Math.max(0,currentIndex-3);i<Math.min(currentLyrics.length,currentIndex+4);i++){const className=i===currentIndex?'current-lyric':'';lyricsHTML+=`<p class="${className}">${currentLyrics[i].text}</p>`}lyricsContent.innerHTML=lyricsHTML;const currentElement=document.querySelector('.current-lyric');if(currentElement){currentElement.scrollIntoView({behavior:'smooth',block:'center'})}}async function updatePlayer(){try{loading.style.display='block';errorMessage.style.display='none';const data=await getNowPlaying();if(!data)return;trackTitle.textContent=data.title||'未知标题';trackArtist.textContent=data.artist||'未知艺术家';if(data.isPlaying){playBtnIcon.className='fas fa-pause'}else{playBtnIcon.className='fas fa-play'}currentPosition=data.position||0;const positionPercent=Math.min(100,(currentPosition/300000)*100);progressBar.style.width=`${positionPercent}%`;currentTime.textContent=formatTime(currentPosition);totalTime.textContent=formatTime(300000);await updateCover();if(!currentLyrics.length){const lyricsText=await getLyrics();currentLyrics=parseLyrics(lyricsText)}const now=new Date();updateTime.textContent=`最后更新:${now.toLocaleTimeString()}`;loading.style.display='none'}catch(error){showError(`更新播放器失败:${error.message}`);loading.style.display='none'}}async function updateCover(){try{const coverUrl=`https:albumCover.src=coverUrl;albumCover.style.display='block';albumCover.previousElementSibling.style.display='none'}catch(error){console.error('更新封面失败:',error);albumCover.style.display='none';albumCover.previousElementSibling.style.display='block'}}function showError(message){errorMessage.textContent=message;errorMessage.style.display='block';setTimeout(()=>{errorMessage.style.display='none'},5000)}async function togglePlayPause(){try{const response=await fetch('http://localhost:35373/api/play-pause');if(!response.ok)throw new Error('操作失败');const data=await response.json();if(data.isPlaying){playBtnIcon.className='fas fa-pause'}else{playBtnIcon.className='fas fa-play'}}catch(error){showError(`播放/暂停操作失败:${error.message}`)}}async function prevTrack(){try{await fetch('http://localhost:35373/api/previous-track');currentLyrics=[];await updatePlayer()}catch(error){showError(`上一曲操作失败:${error.message}`)}}async function nextTrack(){try{await fetch('http://localhost:35373/api/next-track');currentLyrics=[];await updatePlayer()}catch(error){showError(`下一曲操作失败:${error.message}`)}}function toggleMute(){const isMuted=volumeSlider.value==='0';if(isMuted){volumeSlider.value='80';muteBtnIcon.className='fas fa-volume-up'}else{volumeSlider.value='0';muteBtnIcon.className='fas fa-volume-mute'}}async function volumeUp(){try{await fetch('http://localhost:35373/api/volume/up');const currentVolume=parseInt(volumeSlider.value);const newVolume=Math.min(100,currentVolume+10);volumeSlider.value=newVolume;if(newVolume===0){muteBtnIcon.className='fas fa-volume-mute'}else{muteBtnIcon.className='fas fa-volume-up'}}catch(error){showError(`音量增加失败:${error.message}`)}}async function volumeDown(){try{await fetch('http://localhost:35373/api/volume/down');const currentVolume=parseInt(volumeSlider.value);const newVolume=Math.max(0,currentVolume-10);volumeSlider.value=newVolume;if(newVolume===0){muteBtnIcon.className='fas fa-volume-mute'}else{muteBtnIcon.className='fas fa-volume-up'}}catch(error){showError(`音量减少失败:${error.message}`)}}playBtn.addEventListener('click',togglePlayPause);prevBtn.addEventListener('click',prevTrack);nextBtn.addEventListener('click',nextTrack);muteBtn.addEventListener('click',toggleMute);volumeSlider.addEventListener('input',()=>{if(volumeSlider.value==='0'){muteBtnIcon.className='fas fa-volume-mute'}else{muteBtnIcon.className='fas fa-volume-up'}});document.addEventListener('keydown',(e)=>{if(e.key==='ArrowUp'){volumeUp()}else if(e.key==='ArrowDown'){volumeDown()}});async function initPlayer(){await updatePlayer();updateInterval=setInterval(updatePlayer,100);lyricsUpdateInterval=setInterval(()=>{currentPosition+=1000;updateLyricsDisplay(currentPosition)},1000)}initPlayer();</script></body></html>
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
