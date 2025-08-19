package com.zmxl.plugin.lyrics

import com.google.gson.Gson
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.swing.*
import javax.swing.Timer
import kotlin.math.roundToInt

object DesktopLyrics {
    private val frame = JFrame()
    private val lyricsPanel = LyricsPanel()
    private var isDragging = false
    private var dragStart: Point? = null
    
    private val timer = Timer(500) { updateLyrics() }
    private val gson = Gson()
    
    fun start() {
        setupUI()
        timer.start()
    }
    
    fun stop() {
        timer.stop()
        frame.dispose()
    }
    
    private fun setupUI() {
        frame.apply {
            title = "Salt Player 桌面歌词"
            isUndecorated = true
            background = Color(0, 0, 0, 0)
            setAlwaysOnTop(true)  // 修复: 使用正确的方法
            
            contentPane.apply {
                layout = BorderLayout()
                background = Color(0, 0, 0, 150)
                add(lyricsPanel, BorderLayout.CENTER)
            }
            
            // 设置窗口大小和位置
            setSize(800, 150)
            setLocationRelativeTo(null) // 居中显示
            
            // 添加鼠标事件监听器
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    isDragging = true
                    dragStart = e.point
                    frame.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
                
                override fun mouseReleased(e: MouseEvent) {
                    isDragging = false
                    frame.cursor = Cursor.getDefaultCursor()
                }
                
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        // 双击切换透明度
                        lyricsPanel.toggleTransparency()
                    }
                }
            })
            
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    if (isDragging && dragStart != null) {
                        val currentLocation = location
                        setLocation(
                            currentLocation.x + e.x - dragStart!!.x,
                            currentLocation.y + e.y - dragStart!!.y
                        )
                    }
                }
            })
            
            // 添加系统托盘图标
            if (SystemTray.isSupported()) {
                setupSystemTray()
            }
            
            isVisible = true
        }
    }
    
    private fun setupSystemTray() {
        val tray = SystemTray.getSystemTray()
        val image = createTrayIconImage()
        val trayIcon = TrayIcon(image, "Salt Player 桌面歌词")
        
        val popup = PopupMenu()
        val exitItem = MenuItem("退出")
        exitItem.addActionListener { exitApplication() }
        popup.add(exitItem)
        
        trayIcon.popupMenu = popup
        trayIcon.addActionListener { frame.isVisible = !frame.isVisible }
        
        try {
            tray.add(trayIcon)
        } catch (e: AWTException) {
            println("无法添加系统托盘图标: ${e.message}")
        }
    }
    
    private fun createTrayIconImage(): Image {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillOval(0, 0, 16, 16)
        g.color = Color.BLACK
        g.drawString("L", 4, 12)
        g.dispose()
        return image
    }
    
    private fun updateLyrics() {
        try {
            // 获取当前播放信息
            val nowPlaying = getNowPlaying()
            val lyricContent = getLyric()
            
            // 更新歌词面板
            lyricsPanel.updateContent(
                title = nowPlaying?.title ?: "无歌曲播放",
                artist = nowPlaying?.artist ?: "",
                position = nowPlaying?.position ?: 0,
                lyric = lyricContent
            )
            
            frame.isVisible = true
        } catch (e: Exception) {
            // 连接失败时隐藏窗口
            frame.isVisible = false
        }
    }
    
    private fun getNowPlaying(): NowPlaying? {
        val url = URL("http://localhost:35373/api/now-playing")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 1000
        
        if (conn.responseCode != 200) return null
        
        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        val response = reader.readText()
        reader.close()
        
        return gson.fromJson(response, NowPlaying::class.java)
    }
    
    private fun getLyric(): String? {
        val url = URL("http://localhost:35373/api/lyric")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 1000
        
        if (conn.responseCode != 200) return null
        
        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        val response = reader.readText()
        reader.close()
        
        val lyricResponse = gson.fromJson(response, LyricResponse::class.java)
        return lyricResponse.lyric
    }
    
    private fun exitApplication() {
        stop()
    }
    
    data class NowPlaying(
        val status: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val isPlaying: Boolean,
        val position: Long,
        val volume: Int,
        val timestamp: Long
    )
    
    data class LyricResponse(
        val status: String,
        val lyric: String?
    )
}

class LyricsPanel : JPanel() {
    private var title = ""
    private var artist = ""
    private var position = 0L
    private var lyric = ""
    private var parsedLyrics = listOf<LyricLine>()
    private var currentLineIndex = -1
    private var transparency = 0.8f
    
    private val titleFont = Font("微软雅黑", Font.BOLD, 16)
    private val artistFont = Font("微软雅黑", Font.PLAIN, 14)
    private val lyricFont = Font("微软雅黑", Font.BOLD, 24)
    private val highlightFont = Font("微软雅黑", Font.BOLD, 28)
    
    init {
        background = Color(0, 0, 0, (255 * transparency).roundToInt())
        isOpaque = false
        border = BorderFactory.createEmptyBorder(10, 20, 10, 20)
    }
    
    fun updateContent(title: String, artist: String, position: Long, lyric: String?) {
        this.title = title
        this.artist = artist
        this.position = position
        
        // 只有在歌词变化时重新解析
        if (lyric != null && this.lyric != lyric) {
            this.lyric = lyric
            parsedLyrics = parseLyrics(lyric)
        }
        
        // 更新当前歌词行
        currentLineIndex = findCurrentLyricLine()
        repaint()
    }
    
    fun toggleTransparency() {
        transparency = if (transparency < 0.5f) 0.8f else 0.3f
        background = Color(0, 0, 0, (255 * transparency).roundToInt())
        repaint()
    }
    
    private fun parseLyrics(lyricText: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        val pattern = Regex("""\[(\d+):(\d+)(?:\.(\d+))?](.*)""")
        
        lyricText.split("\n").forEach { line ->
            val match = pattern.find(line) ?: return@forEach
            val (min, sec, millis, text) = match.destructured
            
            val minutes = min.toLong()
            val seconds = sec.toLong()
            val millisValue = millis.toLongOrNull() ?: 0
            
            // 计算总毫秒数
            val totalMillis = minutes * 60 * 1000 + seconds * 1000 + millisValue * 10
            
            if (text.isNotBlank()) {
                lines.add(LyricLine(totalMillis, text.trim()))
            }
        }
        
        return lines.sortedBy { it.time }
    }
    
    private fun findCurrentLyricLine(): Int {
        if (parsedLyrics.isEmpty()) return -1
        
        // 找到当前时间之前的最后一行歌词
        for (i in parsedLyrics.indices.reversed()) {
            if (position >= parsedLyrics[i].time) {
                return i
            }
        }
        
        return -1
    }
    
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D
        
        // 设置抗锯齿
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        
        // 绘制标题和艺术家
        g2d.color = Color.WHITE
        g2d.font = titleFont
        val titleWidth = g2d.fontMetrics.stringWidth(title)
        g2d.drawString(title, (width - titleWidth) / 2, 30)
        
        g2d.font = artistFont
        val artistWidth = g2d.fontMetrics.stringWidth(artist)
        g2d.drawString(artist, (width - artistWidth) / 2, 50)
        
        // 绘制歌词
        if (parsedLyrics.isNotEmpty()) {
            val yPos = height - 20
            
            // 绘制当前行歌词（高亮）
            if (currentLineIndex in parsedLyrics.indices) {
                g2d.color = Color(255, 215, 0) // 金色高亮
                g2d.font = highlightFont
                val currentLine = parsedLyrics[currentLineIndex].text
                val currentWidth = g2d.fontMetrics.stringWidth(currentLine)
                g2d.drawString(currentLine, (width - currentWidth) / 2, yPos)
            }
            
            // 绘制上一行歌词（如果有）
            if (currentLineIndex > 0) {
                g2d.color = Color.WHITE
                g2d.font = lyricFont
                val prevLine = parsedLyrics[currentLineIndex - 1].text
                val prevWidth = g2d.fontMetrics.stringWidth(prevLine)
                g2d.drawString(prevLine, (width - prevWidth) / 2, yPos - 40)
            }
            
            // 绘制下一行歌词（如果有）
            if (currentLineIndex < parsedLyrics.size - 1) {
                g2d.color = Color.LIGHT_GRAY
                g2d.font = lyricFont
                val nextLine = parsedLyrics[currentLineIndex + 1].text
                val nextWidth = g2d.fontMetrics.stringWidth(nextLine)
                g2d.drawString(nextLine, (width - nextWidth) / 2, yPos + 40)
            }
        } else if (lyric.isNotEmpty()) {
            // 绘制静态歌词
            g2d.color = Color.WHITE
            g2d.font = lyricFont
            val lyricWidth = g2d.fontMetrics.stringWidth(lyric)
            g2d.drawString(lyric, (width - lyricWidth) / 2, height - 30)
        } else {
            // 没有歌词时的提示
            g2d.color = Color.LIGHT_GRAY
            g2d.font = lyricFont
            val message = "歌词加载中..."
            val messageWidth = g2d.fontMetrics.stringWidth(message)
            g2d.drawString(message, (width - messageWidth) / 2, height - 30)
        }
    }
    
    data class LyricLine(val time: Long, val text: String)
}
