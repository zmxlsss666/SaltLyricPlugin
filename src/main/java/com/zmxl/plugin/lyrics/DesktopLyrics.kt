package com.zmxl.plugin.lyrics

import com.google.gson.Gson
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.math.roundToInt

// ==================== 数据类定义 ====================
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
    val lyric: String?,
    val simplified: Boolean? = false
)

data class LyricLine(val time: Long, val text: String)

// ==================== Windows API 相关 ====================
interface User32Ex : User32 {
    fun SetWindowCompositionAttribute(hWnd: WinDef.HWND, data: WindowCompositionAttributeData): Boolean
    
    companion object {
        val INSTANCE: User32Ex = Native.load("user32", User32Ex::class.java) as User32Ex
    }
}

@Structure.FieldOrder("AccentState", "AccentFlags", "GradientColor", "AnimationId")
class AccentPolicy : Structure() {
    @JvmField var AccentState: Int = 0
    @JvmField var AccentFlags: Int = 0
    @JvmField var GradientColor: Int = 0
    @JvmField var AnimationId: Int = 0
}

@Structure.FieldOrder("Attribute", "Data", "SizeOfData")
class WindowCompositionAttributeData : Structure() {
    @JvmField var Attribute: Int = 0
    @JvmField var Data: com.sun.jna.Pointer? = null
    @JvmField var SizeOfData: Int = 0
}

// ==================== 歌词面板类 ====================
class LyricsPanel : JPanel() {
    enum class Alignment { LEFT, CENTER, RIGHT }
    
    private var title = ""
    private var artist = ""
    private var position = 0L
    private var lyric = ""
    var parsedLyrics = listOf<LyricLine>()
    private var currentLineIndex = -1
    var transparency = 0.8f
    var animationSpeed = 10
    var alignment = Alignment.CENTER
    var useShadow = true
    var isSimplified = false
    
    // 字体设置
    private var chineseFont = Font("微软雅黑", Font.BOLD, 24)
    private var japaneseFont = Font("MS Gothic", Font.BOLD, 24)
    private var englishFont = Font("Arial", Font.BOLD, 24)
    
    // 颜色设置
    var lyricColor = Color.WHITE
    var highlightColor = Color(255, 215, 0)
    var backgroundColor = Color(0, 0, 0, 0)
    
    // 动画状态
    private var animationProgress = 0f
    private var animationDirection = 1
    private var prevLineIndex = -1
    private var nextLineIndex = -1
    
    // 平滑动画相关
    private var smoothPosition = 0f
    private var targetPosition = 0f
    private var smoothAlpha = 0f
    private var targetAlpha = 0f
    
    init {
        background = backgroundColor
        isOpaque = false
        border = BorderFactory.createEmptyBorder(5, 20, 5, 20)
        
        // 动画定时器
        Timer(50) {
            smoothPosition += (targetPosition - smoothPosition) * 0.2f
            smoothAlpha += (targetAlpha - smoothAlpha) * 0.2f
            
            animationProgress += 0.02f * animationSpeed * animationDirection
            
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
    
    fun setFonts(chinese: Font, japanese: Font, english: Font) {
        chineseFont = chinese
        japaneseFont = japanese
        englishFont = english
        repaint()
    }
    
    private fun getFontForText(text: String): Font {
        return when {
            text.contains("[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF]".toRegex()) -> {
                if (text.contains("[\\u4E00-\\u9FFF]".toRegex())) chineseFont else japaneseFont
            }
            else -> englishFont
        }
    }
    
    fun resetLyrics() {
        parsedLyrics = emptyList()
        currentLineIndex = -1
        prevLineIndex = -1
        nextLineIndex = -1
        lyric = ""
        isSimplified = false
        smoothPosition = 0f
        targetPosition = 0f
        smoothAlpha = 0f
        targetAlpha = 0f
    }
    
    fun updateContent(title: String, artist: String, position: Long, lyric: String?, isSimplified: Boolean = false) {
        this.title = title
        this.artist = artist
        this.position = position
        this.isSimplified = isSimplified
        
        if (lyric != null && this.lyric != lyric) {
            this.lyric = lyric
            parsedLyrics = parseLyrics(lyric)
        }
        
        val newIndex = if (isSimplified) 0 else findCurrentLyricLine()
        
        if (newIndex != currentLineIndex) {
            prevLineIndex = currentLineIndex
            nextLineIndex = newIndex
            currentLineIndex = newIndex
            animationProgress = 0f
            animationDirection = 1
            
            targetPosition = newIndex.toFloat()
            targetAlpha = 1f
        }
        
        repaint()
    }
    
    fun toggleTransparency() {
        transparency = if (transparency < 0.5f) 0.8f else 0.3f
        val bg = backgroundColor
        background = Color(bg.red, bg.green, bg.blue, (255 * transparency).roundToInt())
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
            
            val totalMillis = minutes * 60 * 1000 + seconds * 1000 + millisValue * 10
            
            if (text.isNotBlank()) {
                lines.add(LyricLine(totalMillis, text.trim()))
            }
        }
        
        return lines.sortedBy { it.time }
    }
    
    private fun findCurrentLyricLine(): Int {
        if (parsedLyrics.isEmpty()) return -1
        
        for (i in parsedLyrics.indices.reversed()) {
            if (position >= parsedLyrics[i].time) {
                return i
            }
        }
        
        return -1
    }
    
    private fun getTextWidth(g: Graphics2D, text: String): Int {
        return g.fontMetrics.stringWidth(text)
    }
    
    private fun getTextXPosition(g: Graphics2D, text: String): Int {
        return when (alignment) {
            Alignment.LEFT -> 20
            Alignment.RIGHT -> width - getTextWidth(g, text) - 20
            else -> (width - getTextWidth(g, text)) / 2
        }
    }
    
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D
        
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        
        val yPos = height / 2 + 20
        
        if (parsedLyrics.isNotEmpty()) {
            if (isSimplified) {
                var currentY = yPos - 40
                for (i in parsedLyrics.indices) {
                    val line = parsedLyrics[i]
                    val isCurrentLine = i == 0
                    
                    val alpha = if (isCurrentLine) 255 else 150
                    val color = if (isCurrentLine) highlightColor else Color(
                        lyricColor.red, lyricColor.green, lyricColor.blue, alpha
                    )
                    
                    g2d.color = color
                    g2d.font = getFontForText(line.text)
                    val x = getTextXPosition(g2d, line.text)
                    
                    if (useShadow) {
                        g2d.color = Color(0, 0, 0, alpha / 2)
                        g2d.drawString(line.text, x + 1, currentY + 1)
                    }
                    
                    g2d.color = color
                    g2d.drawString(line.text, x, currentY)
                    
                    currentY += 40
                }
            } else {
                if (prevLineIndex in parsedLyrics.indices) {
                    val alpha = (255 * (1 - smoothAlpha)).toInt()
                    val color = Color(lyricColor.red, lyricColor.green, lyricColor.blue, alpha)
                    
                    g2d.color = color
                    g2d.font = getFontForText(parsedLyrics[prevLineIndex].text)
                    val prevLine = parsedLyrics[prevLineIndex].text
                    val prevX = getTextXPosition(g2d, prevLine)
                    val prevY = yPos - (40 * smoothAlpha).toInt()
                    
                    if (useShadow) {
                        g2d.color = Color(0, 0, 0, alpha / 2)
                        g2d.drawString(prevLine, prevX + 1, prevY + 1)
                    }
                    
                    g2d.color = color
                    g2d.drawString(prevLine, prevX, prevY)
                }
                
                if (currentLineIndex in parsedLyrics.indices) {
                    val alpha = (255 * smoothAlpha).toInt()
                    val color = Color(highlightColor.red, highlightColor.green, highlightColor.blue, alpha)
                    
                    g2d.color = color
                    g2d.font = getFontForText(parsedLyrics[currentLineIndex].text)
                    val currentLine = parsedLyrics[currentLineIndex].text
                    val currentX = getTextXPosition(g2d, currentLine)
                    val currentY = yPos - (20 * (1 - smoothAlpha).toInt())
                    
                    if (useShadow) {
                        g2d.color = Color(0, 0, 0, alpha / 2)
                        g2d.drawString(currentLine, currentX + 1, currentY + 1)
                    }
                    
                    g2d.color = color
                    g2d.drawString(currentLine, currentX, currentY)
                }
                
                if (currentLineIndex < parsedLyrics.size - 1) {
                    g2d.color = Color(lyricColor.red, lyricColor.green, lyricColor.blue, 150)
                    g2d.font = getFontForText(parsedLyrics[currentLineIndex + 1].text)
                    val nextLine = parsedLyrics[currentLineIndex + 1].text
                    val nextX = getTextXPosition(g2d, nextLine)
                    
                    if (useShadow) {
                        g2d.color = Color(0, 0, 0, 75)
                        g2d.drawString(nextLine, nextX + 1, yPos + 40 + 1)
                    }
                    
                    g2d.color = Color(lyricColor.red, lyricColor.green, lyricColor.blue, 150)
                    g2d.drawString(nextLine, nextX, yPos + 40)
                }
            }
        } else if (lyric.isNotEmpty()) {
            g2d.color = lyricColor
            g2d.font = getFontForText(lyric)
            val lyricX = getTextXPosition(g2d, lyric)
            
            if (useShadow) {
                g2d.color = Color(0, 0, 0, 150)
                g2d.drawString(lyric, lyricX + 1, yPos + 1)
            }
            
            g2d.color = lyricColor
            g2d.drawString(lyric, lyricX, yPos)
        } else {
            g2d.color = Color.LIGHT_GRAY
            g2d.font = chineseFont
            val message = "歌词加载中..."
            val messageX = getTextXPosition(g2d, message)
            
            if (useShadow) {
                g2d.color = Color(0, 0, 0, 150)
                g2d.drawString(message, messageX + 1, yPos + 1)
            }
            
            g2d.color = Color.LIGHT_GRAY
            g2d.drawString(message, messageX, yPos)
        }
    }
}

// ==================== 主桌面歌词类 ====================
object DesktopLyrics {
    private const val ACCENT_ENABLE_ACRYLICBLURBEHIND = 4
    private const val WCA_ACCENT_POLICY = 19
    private const val WS_EX_TOOLWINDOW = 0x00000080L
    private const val RESIZE_AREA = 8
    
    private val frame = JFrame()
    private val lyricsPanel = LyricsPanel()
    private val gson = Gson()
    
    private var isDragging = false
    private var dragStart: Point? = null
    private var resizeStart: Point? = null
    private var isResizing = false
    private var currentSongId = ""
    private var lastLyricUrl = ""
    private var lyricCache = mutableMapOf<String, String>()
    private var isLocked = false
    private var titleArtistFormat = 0
    private var isWindowVisible = true
    private var backgroundAlpha = 0f
    
    // 字体设置
    private var chineseFont = Font("微软雅黑", Font.BOLD, 24)
    private var japaneseFont = Font("MS Gothic", Font.BOLD, 24)
    private var englishFont = Font("Arial", Font.BOLD, 24)
    
    // 控制按钮面板
    private lateinit var topPanel: JPanel
    private lateinit var playPauseButton: JButton
    private lateinit var titleArtistLabel: JLabel
    private lateinit var lockButton: JButton
    private lateinit var settingsButton: JButton
    private lateinit var minimizeButton: JButton
    
    // 定时器
    private val timer = Timer(100) { updateLyrics() }
    private val backgroundTimer = Timer(50) {
        val targetAlpha = if (frame.mousePosition != null && !isLocked) 0.9f else 0f
        backgroundAlpha += (targetAlpha - backgroundAlpha) * 0.15f
        
        if (backgroundAlpha < 0.01f) {
            disableAcrylicEffect()
        } else {
            enableAcrylicEffect((backgroundAlpha * 255).roundToInt())
        }
        
        frame.repaint()
    }
    
    fun start() {
        setupUI()
        timer.start()
        backgroundTimer.start()
    }
    
    fun stop() {
        timer.stop()
        backgroundTimer.stop()
        disableAcrylicEffect()
        frame.dispose()
    }
    
    // ==================== Windows 特效管理 ====================
    private fun enableAcrylicEffect(alpha: Int) {
        try {
            val hwnd = getWindowHandle()
            val accent = AccentPolicy().apply {
                AccentState = ACCENT_ENABLE_ACRYLICBLURBEHIND
                AccentFlags = 2
                GradientColor = (alpha shl 24) or 0x000000
            }
            
            val data = WindowCompositionAttributeData().apply {
                Attribute = WCA_ACCENT_POLICY
                Data = accent.pointer
                SizeOfData = accent.size()
            }
            
            User32Ex.INSTANCE.SetWindowCompositionAttribute(hwnd, data)
        } catch (e: Exception) {
            println("启用毛玻璃效果失败: ${e.message}")
            frame.background = Color(0, 0, 0, (alpha / 255f * 180).roundToInt())
        }
    }
    
    private fun disableAcrylicEffect() {
        try {
            val hwnd = getWindowHandle()
            val accent = AccentPolicy().apply {
                AccentState = 0
            }
            
            val data = WindowCompositionAttributeData().apply {
                Attribute = WCA_ACCENT_POLICY
                Data = accent.pointer
                SizeOfData = accent.size()
            }
            
            User32Ex.INSTANCE.SetWindowCompositionAttribute(hwnd, data)
        } catch (e: Exception) {
            println("禁用毛玻璃效果失败: ${e.message}")
            frame.background = Color(0, 0, 0, 0)
        }
    }
    
    private fun setWindowClickThrough(clickThrough: Boolean) {
        try {
            val hwnd = getWindowHandle()
            val currentStyle = User32.INSTANCE.GetWindowLong(hwnd, User32.GWL_EXSTYLE)
            
            val newStyle = if (clickThrough) {
                currentStyle or User32.WS_EX_TRANSPARENT or User32.WS_EX_LAYERED
            } else {
                currentStyle and User32.WS_EX_TRANSPARENT.inv() and User32.WS_EX_LAYERED.inv()
            }
            
            User32.INSTANCE.SetWindowLong(hwnd, User32.GWL_EXSTYLE, newStyle)
        } catch (e: Exception) {
            println("设置窗口点击穿透失败: ${e.message}")
        }
    }
    
    private fun getWindowHandle(): WinDef.HWND {
        val awtWindow = frame
        val toolkit = Toolkit.getDefaultToolkit()
        val getWindowMethod = toolkit.javaClass.getDeclaredMethod("getWindow", Window::class.java)
        getWindowMethod.isAccessible = true
        val window = getWindowMethod.invoke(toolkit, awtWindow)
        val getWindowHandleMethod = window.javaClass.getDeclaredMethod("getWindowHandle")
        getWindowHandleMethod.isAccessible = true
        val handle = getWindowHandleMethod.invoke(window) as Long
        
        return WinDef.HWND(com.sun.jna.Pointer.createConstant(handle))
    }
    
    // ==================== UI 设置 ====================
    private fun setupUI() {
        frame.apply {
            title = "Salt Player 桌面歌词"
            isUndecorated = true
            background = Color(0, 0, 0, 0)
            setAlwaysOnTop(true)
            isFocusable = false
            focusableWindowState = false
            
            contentPane = JPanel(BorderLayout()).apply {
                background = Color(0, 0, 0, 0)
                isOpaque = false
                isFocusable = false
                
                add(lyricsPanel, BorderLayout.CENTER)
                
                topPanel = createTopControlBar()
                add(topPanel, BorderLayout.NORTH)
            }
            
            setSize(560, 180)
            setLocationRelativeTo(null)
            
            setupKeyboardShortcuts()
            setupMouseListeners()
            setupWindowListeners()
            setupWindowStyle()
            
            if (SystemTray.isSupported()) {
                setupSystemTray()
            }
            
            topPanel.isVisible = false
            setWindowClickThrough(true)
            isVisible = true
        }
    }
    
    private fun setupMouseListeners() {
        frame.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!isLocked) {
                    if (isInResizeArea(e.point)) {
                        isResizing = true
                        resizeStart = e.point
                        frame.cursor = Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
                    } else {
                        isDragging = true
                        dragStart = e.point
                        frame.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    }
                }
            }
            
            override fun mouseReleased(e: MouseEvent) {
                if (!isLocked) {
                    isDragging = false
                    isResizing = false
                    frame.cursor = Cursor.getDefaultCursor()
                }
            }
            
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && !isLocked) {
                    lyricsPanel.toggleTransparency()
                }
            }
            
            override fun mouseEntered(e: MouseEvent) {
                if (!isLocked) {
                    topPanel.isVisible = true
                    updateCursor(e.point)
                    setWindowClickThrough(false)
                }
            }
            
            override fun mouseExited(e: MouseEvent) {
                if (!isLocked) {
                    try {
                        val point = MouseInfo.getPointerInfo().location
                        val panelBounds = topPanel.bounds
                        if (topPanel.isShowing) {
                            val panelLocation = topPanel.locationOnScreen
                            panelBounds.location = panelLocation
                            
                            if (!panelBounds.contains(point)) {
                                topPanel.isVisible = false
                                setWindowClickThrough(true)
                            }
                        } else {
                            topPanel.isVisible = false
                            setWindowClickThrough(true)
                        }
                    } catch (ex: Exception) {
                        topPanel.isVisible = false
                        setWindowClickThrough(true)
                    }
                    frame.cursor = Cursor.getDefaultCursor()
                }
            }
        })
        
        frame.addMouseMotionListener(object : MouseMotionAdapter() {
override fun mouseDragged(e: MouseEvent) {
    if (!isLocked) {
        if (isResizing && resizeStart != null) {
            val dx = e.x - resizeStart!!.x
            val dy = e.y - resizeStart!!.y
            val newWidth = maxOf(frame.width + dx, 300)
            val newHeight = maxOf(frame.height + dy, 100)
            frame.setSize(newWidth, newHeight)
            resizeStart = e.point
        } else if (isDragging && dragStart != null) {
            // 修复：显式使用 frame 引用
            val currentLocation = frame.location
            frame.setLocation(
                currentLocation.x + e.x - dragStart!!.x,
                currentLocation.y + e.y - dragStart!!.y
            )
        }
    }
}
            override fun mouseMoved(e: MouseEvent) {
                if (!isLocked) {
                    updateCursor(e.point)
                }
            }
        })
    }
    
    private fun setupWindowListeners() {
        frame.addWindowStateListener { e ->
            if (e.newState == Frame.NORMAL) {
                isWindowVisible = true
                updateLyrics()
                lyricsPanel.repaint()
            } else if (e.newState == Frame.ICONIFIED) {
                isWindowVisible = false
            }
        }
        
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                isWindowVisible = false
            }
            
            override fun windowClosed(e: WindowEvent?) {
                isWindowVisible = false
            }
            
            override fun windowOpened(e: WindowEvent?) {
                isWindowVisible = true
            }
        })
    }
    
    private fun setupWindowStyle() {
        try {
            val hwnd = getWindowHandle()
            val currentStyle = User32.INSTANCE.GetWindowLong(hwnd, User32.GWL_EXSTYLE)
            val newStyle = currentStyle or WS_EX_TOOLWINDOW.toInt()
            User32.INSTANCE.SetWindowLong(hwnd, User32.GWL_EXSTYLE, newStyle)
        } catch (e: Exception) {
            println("设置窗口工具样式失败: ${e.message}")
        }
    }
    
    private fun isInResizeArea(point: Point): Boolean {
        return point.x >= frame.width - RESIZE_AREA && point.y >= frame.height - RESIZE_AREA
    }
    
    private fun updateCursor(point: Point) {
        frame.cursor = if (isInResizeArea(point)) {
            Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
        } else {
            Cursor.getDefaultCursor()
        }
    }
    
    // ==================== 控制栏创建 ====================
    private fun createTopControlBar(): JPanel {
        return JPanel(BorderLayout()).apply {
            background = Color(0, 0, 0, 0)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(2, 10, 2, 10)
            preferredSize = Dimension(frame.width, 30)
            
            titleArtistLabel = JLabel("", SwingConstants.LEFT).apply {
                foreground = Color.WHITE
                font = Font("微软雅黑", Font.PLAIN, 12)
            }
            
            val controlPanel = createControlPanel()
            val rightPanel = createRightPanel()
            
            add(titleArtistLabel, BorderLayout.WEST)
            add(controlPanel, BorderLayout.CENTER)
            add(rightPanel, BorderLayout.EAST)
        }
    }
    
    private fun createControlPanel(): JPanel {
        return JPanel(FlowLayout(FlowLayout.CENTER, 5, 0)).apply {
            background = Color(0, 0, 0, 0)
            isOpaque = false
            
            val prevButton = createMediaButton("◀", "/api/previous-track")
            playPauseButton = createMediaButton("▶", "/api/play-pause")
            val nextButton = createMediaButton("▶", "/api/next-track")
            
            add(prevButton)
            add(playPauseButton)
            add(nextButton)
        }
    }
    
    private fun createMediaButton(text: String, endpoint: String): JButton {
        return JButton(text).apply {
            font = Font("Segoe UI Symbol", Font.BOLD, 12)
            foreground = Color.WHITE
            background = Color(0, 0, 0, 100)
            border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
            addActionListener { sendMediaCommand(endpoint) }
        }
    }
    
    private fun createRightPanel(): JPanel {
        return JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
            background = Color(0, 0, 0, 0)
            isOpaque = false
            
            lockButton = createFunctionButton("🔒") { toggleLock() }
            settingsButton = createFunctionButton("⚙") { showSettingsDialog() }
            minimizeButton = createFunctionButton("−") {
                frame.isVisible = false
                isWindowVisible = false
            }
            
            add(lockButton)
            add(settingsButton)
            add(minimizeButton)
        }
    }
    
    private fun createFunctionButton(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            font = when (text) {
                "−" -> Font("Segoe UI Symbol", Font.BOLD, 14)
                else -> Font("Segoe UI Symbol", Font.PLAIN, 12)
            }
            foreground = Color.WHITE
            background = Color(0, 0, 0, 100)
            border = BorderFactory.createEmptyBorder(3, 6, 3, 6)
            addActionListener { action() }
        }
    }
    
    // ==================== 功能方法 ====================
    private fun toggleLock() {
        isLocked = !isLocked
        
        if (isLocked) {
            lockButton.text = "🔒"
            topPanel.isVisible = false
            disableAcrylicEffect()
            setWindowClickThrough(true)
        } else {
            lockButton.text = "🔓"
            if (frame.mousePosition != null) {
                enableAcrylicEffect(200)
                setWindowClickThrough(false)
            } else {
                setWindowClickThrough(true)
            }
        }
    }
    
    private fun updateTitleArtistDisplay(title: String, artist: String) {
        val displayText = if (titleArtistFormat == 0) {
            "$title - $artist"
        } else {
            "$artist - $title"
        }
        
        titleArtistLabel.text = displayText
    }
    
    private fun setupKeyboardShortcuts() {
        val inputMap = frame.rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionMap = frame.rootPane.actionMap
        
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "playPause")
        actionMap.put("playPause", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                sendMediaCommand("/api/play-pause")
            }
        })
        
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "nextTrack")
        actionMap.put("nextTrack", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                sendMediaCommand("/api/next-track")
            }
        })
        
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
                conn.responseCode
            } catch (e: Exception) {
                println("发送媒体命令失败: ${e.message}")
            }
        }.start()
    }
    
    // ==================== 系统托盘设置 ====================
    private fun setupSystemTray() {
        if (!SystemTray.isSupported()) {
            println("系统托盘不支持")
            return
        }
        
        val tray = SystemTray.getSystemTray()
        val image = createTrayIconImage()
        val trayIcon = TrayIcon(image, "Salt Player 桌面歌词")
        
        val popup = PopupMenu()
        
        val toggleItem = MenuItem("显示/隐藏")
        toggleItem.addActionListener {
            frame.isVisible = !frame.isVisible
            isWindowVisible = frame.isVisible
            if (frame.isVisible) {
                updateLyrics()
            }
        }
        
        val lockItem = MenuItem(if (isLocked) "解锁" else "锁定")
        lockItem.addActionListener { toggleLock() }
        
        val settingsItem = MenuItem("设置")
        settingsItem.addActionListener { showSettingsDialog() }
        
        val exitItem = MenuItem("退出")
        exitItem.addActionListener { exitApplication() }
        
        popup.add(toggleItem)
        popup.add(lockItem)
        popup.add(settingsItem)
        popup.addSeparator()
        popup.add(exitItem)
        
        try {
            val font = Font(Font.DIALOG, Font.PLAIN, 12)
            for (i in 0 until popup.itemCount) {
                popup.getItem(i).font = font
            }
        } catch (e: Exception) {
            println("设置菜单字体失败: ${e.message}")
        }
        
        trayIcon.popupMenu = popup
        trayIcon.addActionListener {
            frame.isVisible = !frame.isVisible
            isWindowVisible = frame.isVisible
            if (frame.isVisible) {
                updateLyrics()
            }
        }
        
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
    
    // ==================== 设置对话框 ====================
    private fun showSettingsDialog() {
        val dialog = JDialog(frame, "桌面歌词设置", true)
        dialog.layout = BorderLayout()
        dialog.setSize(500, 500)
        dialog.setLocationRelativeTo(frame)
        
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        val tabbedPane = JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT).apply {
            border = EmptyBorder(10, 10, 10, 10)
            background = Color(240, 240, 240)
            font = Font("微软雅黑", Font.PLAIN, 12)
        }
        
        tabbedPane.addTab("字体", createFontPanel(dialog))
        tabbedPane.addTab("颜色", createColorPanel(dialog))
        tabbedPane.addTab("其他", createOtherPanel(dialog))
        
        dialog.add(tabbedPane, BorderLayout.CENTER)
        
        val closeButton = JButton("关闭").apply {
            font = Font("微软雅黑", Font.BOLD, 12)
            background = Color(192, 57, 43)
            foreground = Color.WHITE
            border = EmptyBorder(8, 20, 8, 20)
            addActionListener { dialog.dispose() }
        }
        
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            background = Color(240, 240, 240)
            border = EmptyBorder(10, 10, 10, 10)
            add(closeButton)
        }
        
        dialog.add(buttonPanel, BorderLayout.SOUTH)
        dialog.isVisible = true
    }
    
    private fun createFontPanel(dialog: JDialog): JPanel {
        return JPanel(GridBagLayout()).apply {
            border = EmptyBorder(15, 15, 15, 15)
            background = Color.WHITE
            
            val gbc = GridBagConstraints().apply {
                insets = Insets(8, 8, 8, 8)
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
            }
            
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.gridwidth = 2
            add(JLabel("字体设置").apply {
                font = Font("微软雅黑", Font.BOLD, 16)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridwidth = 1
            gbc.gridy++
            
            gbc.gridx = 0
            add(JLabel("中文字体:").apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val chineseFontCombo = createFontComboBox(chineseFont.family)
            add(chineseFontCombo, gbc)
            
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("日文字体:").apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val japaneseFontCombo = createFontComboBox(japaneseFont.family)
            add(japaneseFontCombo, gbc)
            
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("英文字体:").apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val englishFontCombo = createFontComboBox(englishFont.family)
            add(englishFontCombo, gbc)
            
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("字体大小:").apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val sizeSpinner = JSpinner(SpinnerNumberModel(chineseFont.size, 8, 48, 1)).apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
            }
            add(sizeSpinner, gbc)
            
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("字体样式:").apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val styleCombo = JComboBox(arrayOf("普通", "粗体", "斜体")).apply {
                selectedIndex = when (chineseFont.style) {
                    Font.BOLD -> 1
                    Font.ITALIC -> 2
                    else -> 0
                }
                font = Font("微软雅黑", Font.PLAIN, 12)
                background = Color.WHITE
                renderer = DefaultListCellRenderer().apply {
                    font = Font("微软雅黑", Font.PLAIN, 12)
                }
            }
            add(styleCombo, gbc)
            
            gbc.gridx = 0
            gbc.gridy++
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.CENTER
            val applyButton = JButton("应用字体设置").apply {
                font = Font("微软雅黑", Font.BOLD, 12)
                background = Color(70, 130, 180)
                foreground = Color.WHITE
                border = EmptyBorder(8, 20, 8, 20)
                addActionListener {
                    val chineseFontName = chineseFontCombo.selectedItem as String
                    val japaneseFontName = japaneseFontCombo.selectedItem as String
                    val englishFontName = englishFontCombo.selectedItem as String
                    val fontSize = sizeSpinner.value as Int
                    val fontStyle = when (styleCombo.selectedIndex) {
                        1 -> Font.BOLD
                        2 -> Font.ITALIC
                        else -> Font.PLAIN
                    }
                    
                    chineseFont = Font(chineseFontName, fontStyle, fontSize)
                    japaneseFont = Font(japaneseFontName, fontStyle, fontSize)
                    englishFont = Font(englishFontName, fontStyle, fontSize)
                    
                    lyricsPanel.setFonts(chineseFont, japaneseFont, englishFont)
                }
            }
            add(applyButton, gbc)
        }
    }
    
    private fun createFontComboBox(selectedFont: String): JComboBox<String> {
        return JComboBox(GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getAvailableFontFamilyNames()).apply {
            selectedItem = selectedFont
            font = Font("微软雅黑", Font.PLAIN, 12)
            background = Color.WHITE
            renderer = DefaultListCellRenderer().apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
            }
        }
    }
    
    private fun createColorPanel(dialog: JDialog): JPanel {
        return JPanel(GridBagLayout()).apply {
            border = EmptyBorder(15, 15, 15, 15)
            background = Color.WHITE
            
            val gbc = GridBagConstraints().apply {
                insets = Insets(8, 8, 8, 8)
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
            }
            
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.gridwidth = 2
            add(JLabel("颜色设置").apply {
                font = Font("微软雅黑", Font.BOLD, 16)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridwidth = 1
            gbc.gridy++
            
            gbc.gridx = 0
            add(JLabel("歌词颜色:").apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val lyricColorButton = createColorButton(lyricsPanel.lyricColor) { color ->
                lyricsPanel.lyricColor = color
            }
            add(lyricColorButton, gbc)
            
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("高亮歌词颜色:").apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val highlightColorButton = createColorButton(lyricsPanel.highlightColor) { color ->
                lyricsPanel.highlightColor = color
            }
            add(highlightColorButton, gbc)
            
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("背景颜色:").apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val bgColorButton = createColorButton(lyricsPanel.backgroundColor) { color ->
                lyricsPanel.backgroundColor = color
                lyricsPanel.background = Color(
                    color.red, color.green, color.blue,
                    (255 * lyricsPanel.transparency).roundToInt()
                )
            }
            add(bgColorButton, gbc)
        }
    }
    
    private fun createColorButton(color: Color, onColorSelected: (Color) -> Unit): JButton {
        return JButton().apply {
            background = color
            preferredSize = Dimension(80, 25)
            addActionListener {
                val newColor = JColorChooser.showDialog(frame, "选择颜色", background)
                if (newColor != null) {
                    background = newColor
                    onColorSelected(newColor)
                }
            }
        }
    }
    
    private fun createOtherPanel(dialog: JDialog): JPanel {
        return JPanel(GridBagLayout()).apply {
            border = EmptyBorder(15, 15, 15, 15)
            background = Color.WHITE
            
            val gbc = GridBagConstraints().apply {
                insets = Insets(8, 8, 8, 8)
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
            }
            
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.gridwidth = 2
            add(JLabel("其他设置").apply {
                font = Font("微软雅黑", Font.BOLD, 16)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridwidth = 1
            gbc.gridy++
            
            gbc.gridx = 0
            add(JLabel("窗口透明度:").apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val transparencySlider = JSlider(10, 100, (lyricsPanel.transparency * 100).toInt()).apply {
                addChangeListener {
                    lyricsPanel.transparency = value / 100f
                    val bg = lyricsPanel.backgroundColor
                    lyricsPanel.background = Color(bg.red, bg.green, bg.blue, (255 * lyricsPanel.transparency).roundToInt())
                    lyricsPanel.repaint()
                }
            }
            add(transparencySlider, gbc)
            
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("动画速度:").apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val animationSlider = JSlider(1, 20, lyricsPanel.animationSpeed).apply {
                addChangeListener {
                    lyricsPanel.animationSpeed = value
                }
            }
            add(animationSlider, gbc)
            
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("歌词对齐:").apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val alignmentCombo = JComboBox(arrayOf("居中", "左对齐", "右对齐")).apply {
                selectedIndex = when (lyricsPanel.alignment) {
                    LyricsPanel.Alignment.LEFT -> 1
                    LyricsPanel.Alignment.RIGHT -> 2
                    else -> 0
                }
                font = Font("微软雅黑", Font.PLAIN, 12)
                background = Color.WHITE
                renderer = DefaultListCellRenderer().apply {
                    font = Font("微软雅黑", Font.PLAIN, 12)
                }
                addActionListener {
                    lyricsPanel.alignment = when (selectedIndex) {
                        1 -> LyricsPanel.Alignment.LEFT
                        2 -> LyricsPanel.Alignment.RIGHT
                        else -> LyricsPanel.Alignment.CENTER
                    }
                }
            }
            add(alignmentCombo, gbc)
            
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("标题-艺术家格式:").apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val formatCombo = JComboBox(arrayOf("歌名 - 歌手", "歌手 - 歌名")).apply {
                selectedIndex = titleArtistFormat
                font = Font("微软雅黑", Font.PLAIN, 12)
                background = Color.WHITE
                renderer = DefaultListCellRenderer().apply {
                    font = Font("微软雅黑", Font.PLAIN, 12)
                }
                addActionListener {
                    titleArtistFormat = selectedIndex
                    val nowPlaying = getNowPlaying()
                    if (nowPlaying != null) {
                        updateTitleArtistDisplay(nowPlaying.title ?: "", nowPlaying.artist ?: "")
                    }
                }
            }
            add(formatCombo, gbc)
            
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("文字阴影效果:").apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val shadowCheckBox = JCheckBox("启用", lyricsPanel.useShadow).apply {
                font = Font("微软雅黑", Font.PLAIN, 12)
                addActionListener {
                    lyricsPanel.useShadow = isSelected
                    lyricsPanel.repaint()
                }
            }
            add(shadowCheckBox, gbc)
        }
    }
    
    // ==================== 歌词更新逻辑 ====================
    private fun updateLyrics() {
        if (!isWindowVisible) return
        
        try {
            val nowPlaying = getNowPlaying()
            if (nowPlaying == null) {
                frame.isVisible = false
                isWindowVisible = false
                return
            }
            
            playPauseButton.text = if (nowPlaying.isPlaying) "❚❚" else "▶"
            updateTitleArtistDisplay(nowPlaying.title ?: "", nowPlaying.artist ?: "")
            
            val newSongId = "${nowPlaying.title}-${nowPlaying.artist}-${nowPlaying.album}"
            val songChanged = newSongId != currentSongId
            
            if (songChanged) {
                currentSongId = newSongId
                lyricsPanel.resetLyrics()
                lastLyricUrl = ""
            }
            
            val lyricResponse = getLyric()
            val lyricContent = lyricResponse?.lyric
            
            lyricsPanel.updateContent(
                title = nowPlaying.title ?: "无歌曲播放",
                artist = nowPlaying.artist ?: "",
                position = nowPlaying.position,
                lyric = lyricContent,
                isSimplified = lyricResponse?.simplified ?: false
            )
            
            frame.isVisible = true
            isWindowVisible = true
        } catch (e: Exception) {
            frame.isVisible = false
            isWindowVisible = false
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
    
    private fun getLyric(): LyricResponse? {
        try {
            val endpoints = listOf(
                "/api/lyric163",
                "/api/lyricqq",
                "/api/lyrickugou",
                "/api/lyricspw"
            )
            
            for (endpoint in endpoints) {
                try {
                    val url = URL("http://localhost:35373$endpoint")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 1000
                    
                    if (conn.responseCode == 200) {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream))
                        val response = reader.readText()
                        reader.close()
                        
                        val lyricResponse = gson.fromJson(response, LyricResponse::class.java)
                        
                        if (lyricResponse.lyric != null && lyricResponse.lyric.isNotBlank()) {
                            return lyricResponse
                        }
                    } else if (conn.responseCode == 404) {
                        continue
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            
            return null
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun exitApplication() {
        stop()
    }
}

