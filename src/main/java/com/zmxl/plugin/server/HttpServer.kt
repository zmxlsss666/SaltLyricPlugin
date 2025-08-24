// 在文件顶部添加导入
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.exceptions.CannotReadException
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagException
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v24Frames
import org.jaudiotagger.tag.id3.framebody.FrameBodyUSLT
import org.jaudiotagger.tag.lyrics3.Lyrics3v2Field
import java.io.File

// 在 HttpServer 类的 start() 方法中添加新的 Servlet 注册
context.addServlet(ServletHolder(LyricFromFileServlet()), "/api/lyric")

// 在 HttpServer 类中添加新的 Servlet 实现
/**
 * 从音频文件元数据中读取歌词API
 */
class LyricFromFileServlet : HttpServlet() {
    private val gson = Gson()
    
    @Throws(IOException::class)
    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        resp.contentType = "application/json;charset=UTF-8"
        
        // 获取文件路径参数
        val filePath = req.getParameter("path")
        
        if (filePath.isNullOrBlank()) {
            resp.status = HttpServletResponse.SC_BAD_REQUEST
            resp.writer.write(gson.toJson(mapOf(
                "status" to "error",
                "message" to "缺少文件路径参数"
            )))
            return
        }
        
        try {
            // 读取音频文件元数据中的歌词
            val lyricContent = extractLyricsFromAudioFile(filePath)
            
            if (lyricContent != null && lyricContent.isNotBlank()) {
                val response = mapOf(
                    "status" to "success",
                    "lyric" to lyricContent,
                    "source" to "file_metadata",
                    "filePath" to filePath
                )
                resp.writer.write(gson.toJson(response))
            } else {
                resp.status = HttpServletResponse.SC_NOT_FOUND
                resp.writer.write(gson.toJson(mapOf(
                    "status" to "error",
                    "message" to "未找到文件中的歌词数据",
                    "filePath" to filePath
                )))
            }
        } catch (e: Exception) {
            resp.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            resp.writer.write(gson.toJson(mapOf(
                "status" to "error",
                "message" to "读取文件歌词失败: ${e.message}",
                "filePath" to filePath
            )))
        }
    }
    
    /**
     * 从音频文件中提取歌词
     */
    private fun extractLyricsFromAudioFile(filePath: String): String? {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            throw IOException("文件不存在或不是有效文件: $filePath")
        }
        
        try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag
            
            if (tag == null) {
                return null
            }
            
            // 尝试从不同格式的标签中提取歌词
            return tryExtractID3v2Lyrics(tag) ?: 
                   tryExtractLyrics3v2(tag) ?: 
                   tryExtractGenericLyricsField(tag)
        } catch (e: CannotReadException) {
            throw IOException("无法读取音频文件: ${e.message}")
        } catch (e: TagException) {
            throw IOException("标签解析错误: ${e.message}")
        } catch (e: ReadOnlyFileException) {
            throw IOException("文件只读: ${e.message}")
        } catch (e: InvalidAudioFrameException) {
            throw IOException("无效的音频帧: ${e.message}")
        } catch (e: Exception) {
            throw IOException("读取文件时发生错误: ${e.message}")
        }
    }
    
    /**
     * 尝试从ID3v2标签中提取歌词
     */
    private fun tryExtractID3v2Lyrics(tag: Any): String? {
        if (tag is AbstractID3v2Tag) {
            // 查找USLT帧（非同步歌词/文本）
            val usltFrame = tag.getFirstField(ID3v24Frames.FRAME_ID_UNSYNC_LYRICS)
            if (usltFrame != null && usltFrame.body is FrameBodyUSLT) {
                val usltBody = usltFrame.body as FrameBodyUSLT
                return usltBody.lyrics
            }
            
            // 查找SYLT帧（同步歌词）
            val syltFrame = tag.getFirstField(ID3v24Frames.FRAME_ID_SYNC_LYRICS)
            if (syltFrame != null) {
                // SYLT帧包含时间同步的歌词，这里简单返回文本内容
                return syltFrame.toString()
            }
        }
        return null
    }
    
    /**
     * 尝试从Lyrics3v2标签中提取歌词
     */
    private fun tryExtractLyrics3v2(tag: Any): String? {
        try {
            // 使用反射检查是否有Lyrics3v2字段
            val tagClass = tag.javaClass
            if (tagClass.name.contains("lyrics3", ignoreCase = true)) {
                val lyricsField = tagClass.getDeclaredField("lyrics")
                lyricsField.isAccessible = true
                val lyricsValue = lyricsField.get(tag)
                
                if (lyricsValue is Lyrics3v2Field) {
                    return lyricsValue.toString()
                }
            }
        } catch (e: Exception) {
            // 忽略反射相关的异常
        }
        return null
    }
    
    /**
     * 尝试从通用歌词字段中提取歌词
     */
    private fun tryExtractGenericLyricsField(tag: Any): String? {
        try {
            // 尝试获取常见的歌词字段
            val methods = tag.javaClass.methods
            for (method in methods) {
                if (method.name.startsWith("getFirst") && 
                    (method.name.contains("Lyric") || method.name.contains("Comment"))) {
                    try {
                        val result = method.invoke(tag) as? String
                        if (!result.isNullOrBlank()) {
                            return result
                        }
                    } catch (e: Exception) {
                        // 忽略调用异常
                    }
                }
            }
            
            // 尝试FieldKey.LYRICS
            try {
                val lyricsMethod = tag.javaClass.getMethod("getFirst", FieldKey::class.java)
                val result = lyricsMethod.invoke(tag, FieldKey.LYRICS) as? String
                if (!result.isNullOrBlank()) {
                    return result
                }
            } catch (e: Exception) {
                // 忽略异常
            }
            
            // 尝试COMMENT字段（有时歌词存储在注释中）
            try {
                val commentMethod = tag.javaClass.getMethod("getFirstComment")
                val result = commentMethod.invoke(tag) as? String
                if (!result.isNullOrBlank() && result.length > 20) { // 假设评论长度大于20可能是歌词
                    return result
                }
            } catch (e: Exception) {
                // 忽略异常
            }
        } catch (e: Exception) {
            // 忽略所有异常
        }
        return null
    }
}
