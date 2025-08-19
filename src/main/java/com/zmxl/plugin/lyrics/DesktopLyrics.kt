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
    
    private val timer = Timer(200) { updateLyrics() } // 缩短更新间隔至200ms
    private val gson = Gson()
    
    private var currentSongId = ""
    private var lastLyricUrl = ""
    private var lyricCache = mutableMapOf<String, String>()
    
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
            setAlwaysOnTop(true)
            
            contentPane.apply {
                layout = BorderLayout()
                background = Color(0, 0, 0, 150)
                add(lyricsPanel, BorderLayout.CENTER)
            }
            
            // 添加控制按钮面板
            val controlPanel = JPanel(FlowLayout(FlowLayout.CENTER, 10, 5)).apply {
                background = Color(0, 0, 0, 100)
                isOpaque = false
                
                // 添加上一曲按钮
                val prevButton = JButton("◀").apply {
                    font = Font("微软雅黑", Font.BOLD, 14)
                    foreground = Color.WHITE
                    background = Color(0, 0, 0, 100)
                    border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
                    addActionListener { sendMediaCommand("/api/previous-track") }
                }
                
                // 添加播放/暂停按钮
                val playPauseButton = JButton("▶").apply {
                    font = Font("微软雅黑", Font.BOLD, 14)
                    foreground = Color.WHITE
                    background = Color(0, 0, 0, 100)
                    border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
                    addActionListener { sendMediaCommand("/api/play-pause") }
                }
                
                // 添加下一曲按钮
                val nextButton = JButton("▶").apply {
                    font = Font("微软雅黑", Font.BOLD, 14)
                    foreground = Color.WHITE
                    background = Color(0, 0, 0, 100)
                    border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
                    addActionListener { sendMediaCommand("/api/next-track") }
                }
                
                add(prevButton)
                add(playPauseButton)
                add(nextButton)
            }
            
            add(controlPanel, BorderLayout.SOUTH)
            
            // 设置窗口大小和位置
            setSize(800, 180)
            setLocationRelativeTo(null)
            
            // 添加键盘快捷键
            setupKeyboardShortcuts()
            
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
    
    private fun setupKeyboardShortcuts() {
        val inputMap = frame.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionMap = frame.rootPane.actionMap
        
        // 空格键 - 播放/暂停
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "playPause")
        actionMap.put("playPause", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                sendMediaCommand("/api/play-pause")
            }
        })
        
        // 右箭头 - 下一曲
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "nextTrack")
        actionMap.put("nextTrack", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                sendMediaCommand("/api/next-track")
            }
        })
        
        // 左箭头 - 上一曲
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "previousTrack")
        actionMap.put("previousTrack", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                sendMediaCommand("/api/previous-track")
            }
        })
    }
    
    private fun sendMediaCommand(endpoint: String) {
        Thread {
            try {
                val url = URL("http://localhost:35373$endpoint")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 1000
                conn.responseCode // 触发请求
            } catch (e: Exception) {
                println("发送媒体命令失败: ${e.message}")
            }
        }.start()
    }
    
    private fun setupSystemTray() {
        val tray = SystemTray.getSystemTray()
        val image = createTrayIconImage()
        val trayIcon = TrayIcon(image, "Salt Player 桌面歌词")
        
        val popup = PopupMenu().apply {
            // 显式设置字体防止乱码
            font = Font("微软雅黑", Font.PLAIN, 12)
        }
        
        // 添加设置菜单
        val settingsItem = MenuItem("设置").apply {
            addActionListener { showSettingsDialog() }
        }
        
        // 添加退出菜单
        val exitItem = MenuItem("退出").apply {
            addActionListener { exitApplication() }
        }
        
        popup.add(settingsItem)
        popup.addSeparator()
        popup.add(exitItem)
        
        trayIcon.popupMenu = popup
        trayIcon.addActionListener { frame.isVisible = !frame.isVisible }
        
        try {
            tray.add(trayIcon)
        } catch (e: AWTException) {
            println("无法添加系统托盘图标: ${e.message}")
        }
    }
    
    private fun showSettingsDialog() {
        val dialog = JDialog(frame, "桌面歌词设置", true).apply {
            layout = BorderLayout()
            setSize(400, 400)
            setLocationRelativeTo(frame)
            
            val tabbedPane = JTabbedPane()
            
            // 字体设置面板
            val fontPanel = JPanel(GridLayout(0, 2, 10, 10)).apply {
                border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
                
                // 字体选择
                add(JLabel("字体:"))
                val fontCombo = JComboBox(GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames())
                fontCombo.selectedItem = lyricsPanel.titleFont.family
                add(fontCombo)
                
                // 字体大小
                add(JLabel("字体大小:"))
                val sizeSpinner = JSpinner(SpinnerNumberModel(lyricsPanel.titleFont.size, 8, 48, 1))
                add(sizeSpinner)
                
                // 字体样式
                add(JLabel("字体样式:"))
                val styleCombo = JComboBox(arrayOf("普通", "粗体", "斜体"))
                styleCombo.selectedIndex = when (lyricsPanel.titleFont.style) {
                    Font.BOLD -> 1
                    Font.ITALIC -> 2
                    else -> 0
                }
                add(styleCombo)
                
                // 应用按钮
                val applyButton = JButton("应用字体设置").apply {
                    addActionListener {
                        val fontName = fontCombo.selectedItem as String
                        val fontSize = sizeSpinner.value as Int
                        val fontStyle = when (styleCombo.selectedIndex) {
                            1 -> Font.BOLD
                            2 -> Font.ITALIC
                            else -> Font.PLAIN
                        }
                        
                        lyricsPanel.setFonts(
                            Font(fontName, fontStyle, fontSize),
                            Font(fontName, fontStyle, fontSize - 4),
                            Font(fontName, fontStyle, fontSize + 8),
                            Font(fontName, Font.BOLD, fontSize + 12)
                        )
                    }
                }
                
                add(JLabel())
                add(applyButton)
            }
            
            // 颜色设置面板
            val colorPanel = JPanel(GridLayout(0, 2, 10, 10)).apply {
                border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
                
                // 标题颜色
                add(JLabel("标题颜色:"))
                val titleColorButton = JButton().apply {
                    background = lyricsPanel.titleColor
                    addActionListener { 
                        val color = JColorChooser.showDialog(this@showSettingsDialog, "选择标题颜色", background)
                        if (color != null) {
                            background = color
                            lyricsPanel.titleColor = color
                        }
                    }
                }
                add(titleColorButton)
                
                // 艺术家颜色
                add(JLabel("艺术家颜色:"))
                val artistColorButton = JButton().apply {
                    background = lyricsPanel.artistColor
                    addActionListener { 
                        val color = JColorChooser.showDialog(this@showSettingsDialog, "选择艺术家颜色", background)
                        if (color != null) {
                            background = color
                            lyricsPanel.artistColor = color
                        }
                    }
                }
                add(artistColorButton)
                
                // 歌词颜色
                add(JLabel("歌词颜色:"))
                val lyricColorButton = JButton().apply {
                    background = lyricsPanel.lyricColor
                    addActionListener { 
                        val color = JColorChooser.showDialog(this@showSettingsDialog, "选择歌词颜色", background)
                        if (color != null) {
                            background = color
                            lyricsPanel.lyricColor = color
                        }
                    }
                }
                add(lyricColorButton)
                
                // 高亮歌词颜色
                add(JLabel("高亮歌词颜色:"))
                val highlightColorButton = JButton().apply {
                    background = lyricsPanel.highlightColor
                    addActionListener { 
                        val color = JColorChooser.showDialog(this@showSettingsDialog, "选择高亮歌词颜色", background)
                        if (color != null) {
                            background = color
                            lyricsPanel.highlightColor = color
                        }
                    }
                }
                add(highlightColorButton)
            }
            
            // 其他设置面板
            val otherPanel = JPanel(GridLayout(0, 2, 10, 10)).apply {
                border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
                
                // 透明度设置
                add(JLabel("窗口透明度:"))
                val transparencySlider = JSlider(10, 100, (lyricsPanel.transparency * 100).toInt()).apply {
                    addChangeListener {
                        lyricsPanel.transparency = value / 100f
                        lyricsPanel.background = Color(0, 0, 0, (255 * lyricsPanel.transparency).roundToInt())
                        lyricsPanel.repaint()
                    }
                }
                add(transparencySlider)
                
                // 动画速度设置
                add(JLabel("动画速度:"))
                val animationSlider = JSlider(1, 10, lyricsPanel.animationSpeed).apply {
                    addChangeListener {
                        lyricsPanel.animationSpeed = value
                    }
                }
                add(animationSlider)
            }
            
            tabbedPane.addTab("字体", fontPanel)
            tabbedPane.addTab("颜色", colorPanel)
            tabbedPane.addTab("其他", otherPanel)
            
            add(tabbedPane, BorderLayout.CENTER)
            
            // 添加关闭按钮
            val closeButton = JButton("关闭").apply {
                addActionListener { dispose() }
            }
            
            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(closeButton)
            }
            
            add(buttonPanel, BorderLayout.SOUTH)
        }
        
        dialog.isVisible = true
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
            if (nowPlaying == null) {
                frame.isVisible = false
                return
            }
            
            // 检查歌曲是否变化
            val newSongId = "${nowPlaying.title}-${nowPlaying.artist}-${nowPlaying.album}"
            val songChanged = newSongId != currentSongId
            
            if (songChanged) {
                currentSongId = newSongId
                // 重置歌词状态
                lyricsPanel.resetLyrics()
                lastLyricUrl = ""
            }
            
            // 获取歌词内容（仅在需要时）
            val lyricContent = if (songChanged || lyricsPanel.parsedLyrics.isEmpty()) {
                getLyric()
            } else {
                null
            }
            
            // 更新歌词面板
            lyricsPanel.updateContent(
                title = nowPlaying.title ?: "无歌曲播放",
                artist = nowPlaying.artist ?: "",
                position = nowPlaying.position,
                lyric = lyricContent
            )
            
            frame.isVisible = true
        } catch (e: Exception) {
            // 连接失败时隐藏窗口
            frame.isVisible = false
        }
    }
    
    private fun getNowPlaying(): NowPlaying? {
        try {
            val url = URL("http://localhost:35373/api/now-playing")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 1000
            
            if (conn.responseCode != 200) return null
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val response = reader.readText()
            reader.close()
            
            return gson.fromJson(response, NowPlaying::class.java)
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun getLyric(): String? {
        try {
            // 检查缓存
            if (lastLyricUrl.isNotEmpty() && lyricCache.containsKey(lastLyricUrl)) {
                return lyricCache[lastLyricUrl]
            }
            
            val url = URL("http://localhost:35373/api/lyric")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 1000
            
            if (conn.responseCode != 200) return null
            
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val response = reader.readText()
            reader.close()
            
            val lyricResponse = gson.fromJson(response, LyricResponse::class.java)
            val lyric = lyricResponse.lyric
            
            // 更新缓存
            if (lyric != null) {
                lyricCache[lastLyricUrl] = lyric
            }
            
            return lyric
        } catch (e: Exception) {
            return null
        }
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
    var parsedLyrics = listOf<LyricLine>()
    private var currentLineIndex = -1
    var transparency = 0.8f
    var animationSpeed = 5
    
    // 字体设置
    var titleFont = Font("微软雅黑", Font.BOLD, 16)
    var artistFont = Font("微软雅黑", Font.PLAIN, 14)
    var lyricFont = Font("微软雅黑", Font.BOLD, 24)
    var highlightFont = Font("微软雅黑", Font.BOLD, 28)
    
    // 颜色设置
    var titleColor = Color.WHITE
    var artistColor = Color.WHITE
    var lyricColor = Color.WHITE
    var highlightColor = Color(255, 215, 0) // 金色
    
    // 动画状态
    private var animationProgress = 0f
    private var animationDirection = 1
    private var prevLineIndex = -1
    private var nextLineIndex = -1
    
    init {
        background = Color(0, 0, 0, (255 * transparency).roundToInt())
        isOpaque = false
        border = BorderFactory.createEmptyBorder(10, 20, 10, 20)
        
        // 动画定时器
        Timer(50) {
            animationProgress += 0.1f * animationSpeed * animationDirection
            
            if (animationProgress >= 1f) {
                animationProgress = 1f
                animationDirection = 0
            } else if (animationProgress <= 0f) {
                animationProgress = 0f
                animationDirection = 0
            }
            
            repaint()
        }.start()
    }
    
    fun setFonts(title: Font, artist: Font, lyric: Font, highlight: Font) {
        titleFont = title
        artistFont = artist
        lyricFont = lyric
        highlightFont = highlight
        repaint()
    }
    
    fun resetLyrics() {
        parsedLyrics = emptyList()
        currentLineIndex = -1
        prevLineIndex = -1
        nextLineIndex = -1
        lyric = ""
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
        val newIndex = findCurrentLyricLine()
        
        // 如果行索引变化，启动动画
        if (newIndex != currentLineIndex) {
            prevLineIndex = currentLineIndex
            nextLineIndex = newIndex
            currentLineIndex = newIndex
            animationProgress = 0f
            animationDirection = 1
        }
        
        repaint()
    }
    
    fun toggleTransparency() {
        transparency = if (transparency < 0.5f) 0.8f else 0.3f
        background = Color(0, 0, 0, (255 * transparency).roundToInt())
        repaint()
    }
    
    private fun parseLyrics(lyricText: String?): List<LyricLine> {
        if (lyricText.isNullOrEmpty()) return emptyList()
        
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
        g2d.font = titleFont
        g2d.color = titleColor
        val titleWidth = g2d.fontMetrics.stringWidth(title)
        g2d.drawString(title, (width - titleWidth) / 2, 30)
        
        g2d.font = artistFont
        g2d.color = artistColor
        val artistWidth = g2d.fontMetrics.stringWidth(artist)
        g2d.drawString(artist, (width - artistWidth) / 2, 50)
        
        // 绘制歌词
        val yPos = height - 50
        
        if (parsedLyrics.isNotEmpty()) {
            // 绘制上一行歌词（淡出）
            if (prevLineIndex in parsedLyrics.indices) {
                val alpha = (255 * (1 - animationProgress)).toInt()
                val color = Color(lyricColor.red, lyricColor.green, lyricColor.blue, alpha)
                
                g2d.color = color
                g2d.font = lyricFont
                val prevLine = parsedLyrics[prevLineIndex].text
                val prevWidth = g2d.fontMetrics.stringWidth(prevLine)
                val prevY = yPos - (40 * animationProgress).toInt()
                g2d.drawString(prevLine, (width - prevWidth) / 2, prevY)
            }
            
            // 绘制当前行歌词（淡入）
            if (currentLineIndex in parsedLyrics.indices) {
                val alpha = (255 * animationProgress).toInt()
                val color = Color(highlightColor.red, highlightColor.green, highlightColor.blue, alpha)
                
                g2d.color = color
                g2d.font = highlightFont
                val currentLine = parsedLyrics[currentLineIndex].text
                val currentWidth = g2d.fontMetrics.stringWidth(currentLine)
                val currentY = yPos - (20 * (1 - animationProgress)).toInt()
                g2d.drawString(currentLine, (width - currentWidth) / 2, currentY)
            }
            
            // 绘制下一行歌词
            if (currentLineIndex < parsedLyrics.size - 1) {
                g2d.color = Color(lyricColor.red, lyricColor.green, lyricColor.blue, 150)
                g2d.font = lyricFont
                val nextLine = parsedLyrics[currentLineIndex + 1].text
                val nextWidth = g2d.fontMetrics.stringWidth(nextLine)
                g2d.drawString(nextLine, (width - nextWidth) / 2, yPos + 40)
            }
        } else if (lyric.isNotEmpty()) {
            // 绘制静态歌词
            g2d.color = lyricColor
            g2d.font = lyricFont
            val lyricWidth = g2d.fontMetrics.stringWidth(lyric)
            g2d.drawString(lyric, (width - lyricWidth) / 2, yPos)
        } else {
            // 没有歌词时的提示
            g2d.color = Color.LIGHT_GRAY
            g2d.font = lyricFont
            val message = "歌词加载中..."
            val messageWidth = g2d.fontMetrics.stringWidth(message)
            g2d.drawString(message, (width - messageWidth) / 2, yPos)
        }
    }
    
    data class LyricLine(val time: Long, val text: String)
}
