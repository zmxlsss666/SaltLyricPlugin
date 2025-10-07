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
import java.util.Base64
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File
import java.nio.charset.Charset
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
        context.setAttribute("httpServer", this)
        context.addServlet(ServletHolder(NowPlayingServlet()), "/api/now-playing")
        context.addServlet(ServletHolder(PlayPauseServlet()), "/api/play-pause")
        context.addServlet(ServletHolder(NextTrackServlet()), "/api/next-track")
        context.addServlet(ServletHolder(PreviousTrackServlet()), "/api/previous-track")
        context.addServlet(ServletHolder(VolumeUpServlet()), "/api/volume/up")
        context.addServlet(ServletHolder(VolumeDownServlet()), "/api/volume/down")
        context.addServlet(ServletHolder(MuteServlet()), "/api/mute")
        context.addServlet(ServletHolder(Lyric163Servlet()), "/api/lyric163")
        context.addServlet(ServletHolder(LyricQQServlet()), "/api/lyricqq")
        context.addServlet(ServletHolder(LyricKugouServlet()), "/api/lyrickugou")
        context.addServlet(ServletHolder(LyricFileServlet()), "/api/lyric")
		context.addServlet(ServletHolder(LyricFileLrcServlet()), "/api/lyricfile")
        context.addServlet(ServletHolder(PicServlet()), "/api/pic")
        context.addServlet(ServletHolder(CurrentPositionServlet()), "/api/current-position")
        context.addServlet(ServletHolder(object : HttpServlet() {
            @Throws(IOException::class)
            override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
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
 * 歌词API - JAudioTagger (专门优化USLT帧提取)
 */
class LyricFileServlet : HttpServlet() {
    private val gson = Gson()
    @Throws(IOException::class)
    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        resp.contentType = "application/json;charset=UTF-8"
        val currentMedia = PlaybackStateHolder.currentMedia
        if (currentMedia == null || currentMedia.path.isNullOrBlank()) {
            resp.status = HttpServletResponse.SC_BAD_REQUEST
            resp.writer.write(gson.toJson(mapOf(
                "status" to "error",
                "message" to "没有当前播放媒体或媒体路径为空"
            )))
            return
        }
        val filePath = currentMedia.path
        println("使用当前播放媒体路径: $filePath")
        try {
            val file = File(filePath)
            if (!file.exists() || !file.isFile) {
                resp.status = HttpServletResponse.SC_NOT_FOUND
                resp.writer.write(gson.toJson(mapOf(
                    "status" to "error",
                    "message" to "文件不存在: $filePath"
                )))
                return
            }
            val extension = file.extension.lowercase()
            val supportedFormats = listOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "wma", "opus")
            if (!supportedFormats.contains(extension)) {
                resp.status = HttpServletResponse.SC_BAD_REQUEST
                resp.writer.write(gson.toJson(mapOf(
                    "status" to "error",
                    "message" to "不支持的音频文件格式: $extension. 支持格式: ${supportedFormats.joinToString()}"
                )))
                return
            }
            val lyrics = extractLyricsFromFile(file)
            if (lyrics.isNotBlank()) {
                val response = mapOf(
                    "status" to "success",
                    "lyric" to lyrics,
                    "source" to "file_metadata",
                    "file" to filePath,
                    "format" to extension
                )
                resp.writer.write(gson.toJson(response))
            } else {
                resp.status = HttpServletResponse.SC_NOT_FOUND
                resp.writer.write(gson.toJson(mapOf(
                    "status" to "error",
                    "message" to "文件中未找到歌词元数据",
                    "file" to filePath,
                    "format" to extension
                )))
            }
        } catch (e: Exception) {
            resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            resp.writer.write(gson.toJson(mapOf(
                "status" to "error",
                "message" to "提取文件歌词失败: ${e.message}"
            )))
            e.printStackTrace()
        }
    }
    /**
     * 使用JAudioTagger从音频文件中提取歌词
     */
    private fun extractLyricsFromFile(file: File): String {
        try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag
            if (tag == null) {
                println("文件没有标签信息: ${file.name}")
                return ""
            }
            val usltLyrics = extractUSLTFrameDirectly(tag)
            if (usltLyrics.isNotBlank()) {
                println("成功从USLT帧提取歌词")
                return usltLyrics
            }
            return findLyricsInStandardFields(tag, file.extension)
        } catch (e: Exception) {
            println("使用JAudioTagger读取文件失败: ${e.message}")
            return ""
        }
    }
    /**
     * 直接提取USLT帧内容
     */
    private fun extractUSLTFrameDirectly(tag: Tag): String {
        try {
            if (tag.toString().contains("ID3v2")) {
                return extractFromID3v2Tag(tag)
            }
            return extractUSLTByFieldIteration(tag)
        } catch (e: Exception) {
            println("直接提取USLT帧失败: ${e.message}")
            return ""
        }
    }
    /**
     * 从ID3v2标签提取USLT
     */
    private fun extractFromID3v2Tag(tag: Tag): String {
        try {
            val tagClass = tag.javaClass
            val getFirstFieldMethod = tagClass.methods.find {
                it.name == "getFirstField" && it.parameterCount == 1
            }
            if (getFirstFieldMethod != null) {
                val usltIdentifiers = listOf("USLT", "UNSYNCED LYRICS", "UNSYNCED_LYRICS", "ULT")
                for (identifier in usltIdentifiers) {
                    try {
                        val field = getFirstFieldMethod.invoke(tag, identifier)
                        if (field != null) {
                            val fieldString = field.toString()
                            println("找到USLT字段 [$identifier]: $fieldString")
                            val lyricContent = extractLyricContentFromField(field)
                            if (lyricContent.isNotBlank()) {
                                return lyricContent
                            }
                            return fieldString
                        }
                    } catch (e: Exception) {
                        println("尝试标识符 '$identifier' 失败: ${e.message}")
                    }
                }
            }
            val getFieldsMethod = tagClass.methods.find {
                it.name == "getFields" && it.parameterCount == 1
            }
            if (getFieldsMethod != null) {
                val fields = getFieldsMethod.invoke(tag, "USLT") as? List<*>
                if (fields != null && fields.isNotEmpty()) {
                    val lyricsBuilder = StringBuilder()
                    for (field in fields) {
                        if (field != null) {
                            val content = extractLyricContentFromField(field)
                            if (content.isNotBlank()) {
                                lyricsBuilder.append(content).append("\n")
                            } else {
                                lyricsBuilder.append(field.toString()).append("\n")
                            }
                        }
                    }
                    val result = lyricsBuilder.toString().trim()
                    if (result.isNotBlank()) {
                        return result
                    }
                }
            }
        } catch (e: Exception) {
            println("从ID3v2标签提取USLT失败: ${e.message}")
        }
        return ""
    }
    /**
     * 从字段对象中提取歌词内容
     */
    private fun extractLyricContentFromField(field: Any): String {
        try {
            val getContentMethod = field.javaClass.methods.find { it.name == "getContent" }
            if (getContentMethod != null) {
                val content = getContentMethod.invoke(field) as? String
                if (!content.isNullOrBlank()) {
                    return content
                }
            }
            val fieldString = field.toString()
            return cleanUSLTContent(fieldString)
        } catch (e: Exception) {
            println("提取字段内容失败: ${e.message}")
            return ""
        }
    }
    /**
     * 清理USLT内容，移除框架标识符
     */
    private fun cleanUSLTContent(rawContent: String): String {
        var content = rawContent
        val prefixes = listOf(
            "USLT:",
            "USLT=",
            "Unsynchronised lyric:",
            "Unsynchronized lyric:",
            "Unsynchronized lyric/text transcription:"
        )
        prefixes.forEach { prefix ->
            if (content.startsWith(prefix, ignoreCase = true)) {
                content = content.substring(prefix.length).trim()
            }
        }
        if (content.length >= 5 && content.substring(3, 5) == "\u0000") {
            content = content.substring(5)
        }
        return content.trim()
    }
    /**
     * 通过字段迭代查找USLT
     */
    private fun extractUSLTByFieldIteration(tag: Tag): String {
        val lyricsBuilder = StringBuilder()
        try {
            val fieldsMethod = tag.javaClass.methods.find { it.name == "getFields" }
            if (fieldsMethod != null) {
                val fields = fieldsMethod.invoke(tag) as? List<*>
                fields?.forEach { field ->
                    if (field != null) {
                        val fieldString = field.toString()
                        if (fieldString.contains("USLT", ignoreCase = true) ||
                            fieldString.contains("UNSYNCED", ignoreCase = true)) {
                            println("找到可能的歌词字段: $fieldString")
                            val content = extractLyricContentFromField(field)
                            if (content.isNotBlank()) {
                                lyricsBuilder.append(content).append("\n")
                            } else {
                                lyricsBuilder.append(fieldString).append("\n")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("字段迭代查找USLT失败: ${e.message}")
        }
        return lyricsBuilder.toString().trim()
    }
    /**
     * 在标准字段中查找歌词
     */
    private fun findLyricsInStandardFields(tag: Tag, fileExtension: String): String {
        val lyricFields = listOf(
            FieldKey.LYRICS,
            FieldKey.LYRICIST,
            FieldKey.COMMENT
        )
        for (field in lyricFields) {
            try {
                if (tag.hasField(field)) {
                    val value = tag.getFirst(field)
                    if (value.isNotBlank()) {
                        println("找到标准歌词字段: $field = $value")
                        return value
                    }
                }
            } catch (e: Exception) {
                println("读取字段 $field 失败: ${e.message}")
            }
        }
        return ""
    }
}
/**
 * 本地LRC歌词文件API
 * 检查当前播放媒体所在目录是否存在同名的.lrc文件，并返回其内容
 */
class LyricFileLrcServlet : HttpServlet() {
    private val gson = Gson()
    @Throws(IOException::class)
    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        resp.contentType = "application/json;charset=UTF-8"
        val currentMedia = PlaybackStateHolder.currentMedia
        if (currentMedia == null || currentMedia.path.isNullOrBlank()) {
            resp.status = HttpServletResponse.SC_BAD_REQUEST
            resp.writer.write(gson.toJson(mapOf(
                "status" to "error",
                "message" to "没有当前播放媒体或媒体路径为空"
            )))
            return
        }
        val mediaPath = currentMedia.path
        println("当前播放媒体路径: $mediaPath")
        try {
            val lrcFile = findLrcFile(mediaPath)
            if (lrcFile != null && lrcFile.exists() && lrcFile.isFile) {
                println("找到LRC歌词文件: ${lrcFile.absolutePath}")
                val lyricContent = readLrcFile(lrcFile)
                if (lyricContent.isNotBlank()) {
                    val response = mapOf(
                        "status" to "success",
                        "lyric" to lyricContent,
                        "source" to "local_lrc_file",
                        "file" to lrcFile.absolutePath,
                        "fileSize" to lrcFile.length(),
                        "message" to "成功从本地LRC文件加载歌词"
                    )
                    resp.writer.write(gson.toJson(response))
                } else {
                    resp.status = HttpServletResponse.SC_NOT_FOUND
                    resp.writer.write(gson.toJson(mapOf(
                        "status" to "error",
                        "message" to "LRC文件为空或无法读取",
                        "file" to lrcFile.absolutePath
                    )))
                }
            } else {
                val searchedPath = generateLrcFilePath(mediaPath)
                resp.status = HttpServletResponse.SC_NOT_FOUND
                resp.writer.write(gson.toJson(mapOf(
                    "status" to "error",
                    "message" to "未找到同名的LRC歌词文件",
                    "searchedPath" to searchedPath,
                    "suggestion" to "请确保LRC文件与音频文件在同一目录且文件名相同"
                )))
            }
        } catch (e: Exception) {
            resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            resp.writer.write(gson.toJson(mapOf(
                "status" to "error",
                "message" to "查找或读取LRC文件失败: ${e.message}"
            )))
            e.printStackTrace()
        }
    }
    /**
     * 根据媒体文件路径查找对应的LRC文件
     */
    private fun findLrcFile(mediaPath: String): File? {
        val mediaFile = File(mediaPath)
        if (!mediaFile.exists()) {
            return null
        }
        val lrcFilePath = generateLrcFilePath(mediaPath)
        val lrcFile = File(lrcFilePath)
        if (lrcFile.exists() && lrcFile.isFile) {
            return lrcFile
        }
        return findAlternativeLrcFiles(mediaFile)
    }
    /**
     * 生成LRC文件路径（将原文件扩展名替换为.lrc）
     */
    private fun generateLrcFilePath(mediaPath: String): String {
        val mediaFile = File(mediaPath)
        val parentDir = mediaFile.parent
        val fileNameWithoutExt = mediaFile.nameWithoutExtension
        return "$parentDir${File.separator}$fileNameWithoutExt.lrc"
    }
    /**
     * 查找其他可能的LRC文件变体
     */
    private fun findAlternativeLrcFiles(mediaFile: File): File? {
        val parentDir = mediaFile.parent
        val baseName = mediaFile.nameWithoutExtension
        val possibleNames = listOf(
            "$baseName.lrc",
            "${baseName.replace(" - ", ".")}.lrc",
            "${baseName.replace(" ", "_")}.lrc",
            "${baseName.replace(" ", "")}.lrc",
            "$baseName.lyric",
            "$baseName.txt"
        )
        for (fileName in possibleNames) {
            val lrcFile = File(parentDir, fileName)
            if (lrcFile.exists() && lrcFile.isFile) {
                println("找到备选LRC文件: ${lrcFile.absolutePath}")
                return lrcFile
            }
        }
        return null
    }
    /**
     * 读取LRC文件内容
     */
    private fun readLrcFile(lrcFile: File): String {
        return try {
            lrcFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            println("UTF-8读取失败，尝试其他编码: ${e.message}")
            val encodings = listOf("GBK", "GB2312", "ISO-8859-1", "Windows-1252")
            for (encoding in encodings) {
                try {
                    return lrcFile.readText(Charset.forName(encoding))
                } catch (e: Exception) {
                    println("编码 $encoding 读取失败: ${e.message}")
                }
            }
            ""
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
        private fun tryGetLyricFromMultipleSources(title: String?, artist: String?): String? {
            if (title.isNullOrBlank()) return null
            val lyric1 = getLyricFromNeteaseOfficial(title, artist)
            if (lyric1 != null) return lyric1
            return null
        }
        private fun getLyricFromNeteaseOfficial(title: String?, artist: String?): String? {
            try {
                val searchQuery = if (!artist.isNullOrBlank()) "$title $artist" else title
                val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
                val searchUrl = "https://music.163.com/api/search/get?type=1&offset=0&limit=1&s=$encodedQuery"
                val searchResult = getUrlContentWithHeaders(searchUrl, mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                    "Referer" to "https://music.163.com/"
                ))
                val searchJson = JSONObject(searchResult)
                if (!searchJson.has("result") || searchJson.isNull("result")) {
                    return null
                }
                val result = searchJson.getJSONObject("result")
                if (!result.has("songs") || result.isNull("songs")) {
                    return null
                }
                val songs = result.getJSONArray("songs")
                if (songs.length() > 0) {
                    val songId = songs.getJSONObject(0).getInt("id")
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
        private fun getUrlContentWithHeaders(urlString: String, headers: Map<String, String>): String {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            headers.forEach { (key, value) ->
                conn.setRequestProperty(key, value)
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        }
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
        private fun getLyricFromQQMusic(title: String?, artist: String?): String? {
            if (title.isNullOrBlank()) return null
            try {
                val searchQuery = if (!artist.isNullOrBlank()) "$title $artist" else title
                val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
                val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/music_search_new_platform?format=json&p=1&n=1&w=$encodedQuery"
                val searchResult = getUrlContentWithHeaders(searchUrl, mapOf(
                    "User-AAgent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                    "Referer" to "https://y.qq.com/"
                ))
                val searchJson = JSONObject(searchResult)
                if (!searchJson.has("data") || searchJson.isNull("data") ||
                    !searchJson.getJSONObject("data").has("song") ||
                    searchJson.getJSONObject("data").isNull("song") ||
                    searchJson.getJSONObject("data").getJSONObject("song").getJSONArray("list").length() == 0) {
                    return null
                }
                val songList = searchJson.getJSONObject("data").getJSONObject("song").getJSONArray("list")
                if (songList.length() > 0) {
                    val fField = songList.getJSONObject(0).getString("f")
                    val fParts = fField.split("|")
                    if (fParts.size > 0) {
                        val songId = fParts[0]
                        val mid = getSongMidFromId(songId)
                        if (mid != null) {
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
        private fun getSongMidFromId(songId: String): String? {
            try {
                val songDetailUrl = "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg?tpl=yqq_song_detail&format=jsonp&callback=getOneSongInfoCallback&songid=$songId"
                val songDetailResult = getUrlContentWithHeaders(songDetailUrl, mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                    "Referer" to "https://y.qq.com/"
                ))
                val jsonStart = songDetailResult.indexOf('{')
                val jsonEnd = songDetailResult.lastIndexOf('}') + 1
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    val jsonStr = songDetailResult.substring(jsonStart, jsonEnd)
                    val songDetailJson = JSONObject(jsonStr)
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
        private fun getUrlContentWithHeaders(urlString: String, headers: Map<String, String>): String {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
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
        private fun getLyricFromKugou(title: String?, artist: String?): String? {
            if (title.isNullOrBlank()) return null
            try {
                val searchQuery = if (!artist.isNullOrBlank()) "$title $artist" else title
                val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
                val searchUrl = "http://ioscdn.kugou.com/api/v3/search/song?page=1&pagesize=1&version=7910&keyword=$encodedQuery"
                val searchResult = getUrlContentWithHeaders(searchUrl, mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                ))
                val searchJson = JSONObject(searchResult)
                if (!searchJson.has("data") || searchJson.isNull("data") ||
                    !searchJson.getJSONObject("data").has("info") ||
                    searchJson.getJSONObject("data").isNull("info") ||
                    searchJson.getJSONObject("data").getJSONArray("info").length() == 0) {
                    return null
                }
                val songList = searchJson.getJSONObject("data").getJSONArray("info")
                if (songList.length() > 0) {
                    val songInfo = songList.getJSONObject(0)
                    if (songInfo.has("hash") && !songInfo.isNull("hash")) {
                        val hash = songInfo.getString("hash")
                        val lyricInfo = getLyricInfoFromHash(hash)
                        if (lyricInfo != null) {
                            val id = lyricInfo.first
                            val accesskey = lyricInfo.second
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
        private fun getLyricInfoFromHash(hash: String): Pair<String, String>? {
            try {
                val lyricInfoUrl = "http://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=%20-%20&duration=139039&hash=$hash"
                val lyricInfoResult = getUrlContentWithHeaders(lyricInfoUrl, mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                ))
                val lyricInfoJson = JSONObject(lyricInfoResult)
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
        private fun getLyricFromKugouWithIdAndKey(id: String, accesskey: String): String? {
            try {
                val lyricUrl = "http://lyrics.kugou.com/download?ver=1&client=pc&fmt=lrc&charset=utf8&id=$id&accesskey=$accesskey"
                val lyricResult = getUrlContentWithHeaders(lyricUrl, mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                ))
                val lyricJson = JSONObject(lyricResult)
                if (lyricJson.has("content") && !lyricJson.isNull("content")) {
                    val base64Content = lyricJson.getString("content")
                    return String(Base64.getDecoder().decode(base64Content), Charsets.UTF_8)
                }
            } catch (e: Exception) {
                println("获取歌词内容失败: ${e.message}")
                e.printStackTrace()
            }
            return null
        }
        private fun getUrlContentWithHeaders(urlString: String, headers: Map<String, String>): String {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            headers.forEach { (key, value) ->
                conn.setRequestProperty(key, value)
            }
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
                val url = URL(coverUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val contentType = conn.contentType ?: "image/jpeg"
                resp.contentType = contentType
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
    interface User32Ex : com.sun.jna.Library {
        fun keybd_event(bVk: Byte, bScan: Byte, dwFlags: Int, dwExtraInfo: Int)
        companion object {
            val INSTANCE: User32Ex by lazy {
                Native.load("user32", User32Ex::class.java) as User32Ex
            }
        }
    }
}
