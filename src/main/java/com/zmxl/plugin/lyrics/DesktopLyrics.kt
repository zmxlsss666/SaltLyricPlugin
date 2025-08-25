package com.zmxl.plugin.lyrics
import com.google.gson.Gson
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.math.roundToInt
object DesktopLyrics {
    private val frame = JFrame()
    private val lyricsPanel = LyricsPanel()
    private var isDragging = false
    private var dragStart: Point? = null
    private var resizeStart: Point? = null
    private var isResizing = false
    private val resizeBorder = 8 
    private val timer = Timer(10) { updateLyrics() }
    private val gson = Gson()
    private var currentSongId = ""
    private var lastLyricUrl = ""
    private var lyricCache = mutableMapOf<String, String>()
    private var chineseFont = Font("微软雅黑", Font.BOLD, 24)
    private var japaneseFont = Font("MS Gothic", Font.BOLD, 24)
    private var englishFont = Font("Arial", Font.BOLD, 24)
    private var isLocked = false
    private var titleArtistFormat = 0 
    private lateinit var topPanel: JPanel
    private lateinit var playPauseButton: JButton
    private lateinit var titleArtistLabel: JLabel
    private lateinit var lockButton: JButton
    private lateinit var settingsButton: JButton
    private lateinit var minimizeButton: JButton
    private var scrollOffset = 0
    private var scrollDirection = 1
    private var scrollTimer: Timer? = null
    private var scrollText = ""
    private var maxTextWidth = 0
    private var currentTitle = ""
    private var currentArtist = ""
    private var backgroundAlpha = 0f
    private val backgroundTimer = Timer(16) {
        val targetAlpha = if (frame.mousePosition != null && !isLocked) 0.9f else 0f
        backgroundAlpha += (targetAlpha - backgroundAlpha) * 0.15f 
        if (backgroundAlpha < 0.01f) {
            disableAcrylicEffect()
        } else {
            enableAcrylicEffect((backgroundAlpha * 255).roundToInt())
        }
        frame.repaint()
    }
    private data class AppConfig(
        var windowX: Int = 0,
        var windowY: Int = 0,
        var windowWidth: Int = 560,
        var windowHeight: Int = 180,
        var isLocked: Boolean = false,
        var titleArtistFormat: Int = 0,
        var chineseFontName: String = "微软雅黑",
        var japaneseFontName: String = "MS Gothic",
        var englishFontName: String = "Arial",
        var fontSize: Int = 24,
        var fontStyle: Int = Font.BOLD,
        var lyricColor: Int = Color.WHITE.rgb,
        var highlightColor: Int = Color(255, 215, 0).rgb,
        var backgroundColor: Int = Color(0, 0, 0, 0).rgb,
        var transparency: Float = 0.8f,
        var animationSpeed: Int = 10,
        var alignment: Int = 0, 
        var useShadow: Boolean = true
    )
    private var appConfig = AppConfig()
    private val configDir = File(System.getenv("APPDATA") + File.separator + "Salt Player for Windows" + File.separator + "workshop")
    private val configFile = File(configDir, "desktop_lyrics_config.json")
    interface User32Ex : com.sun.jna.platform.win32.User32 {
        fun SetWindowCompositionAttribute(hWnd: WinDef.HWND, data: WindowCompositionAttributeData): Boolean
        companion object {
            val INSTANCE: User32Ex = Native.load("user32", User32Ex::class.java) as User32Ex
        }
    }
    private val ACCENT_ENABLE_ACRYLICBLURBEHIND = 4
    private val WCA_ACCENT_POLICY = 19
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
    fun start() {
        loadConfig()
        setupUI()
        timer.start()
        backgroundTimer.start()
    }
    fun stop() {
        saveConfig()
        timer.stop()
        backgroundTimer.stop()
        scrollTimer?.stop()
        disableAcrylicEffect()
        frame.dispose()
    }
    private fun loadConfig() {
        try {
            if (configFile.exists()) {
                val json = configFile.readText()
                appConfig = gson.fromJson(json, AppConfig::class.java)
                frame.setSize(appConfig.windowWidth, appConfig.windowHeight)
                frame.setLocation(appConfig.windowX, appConfig.windowY)
                isLocked = appConfig.isLocked
                titleArtistFormat = appConfig.titleArtistFormat
                chineseFont = Font(appConfig.chineseFontName, appConfig.fontStyle, appConfig.fontSize)
                japaneseFont = Font(appConfig.japaneseFontName, appConfig.fontStyle, appConfig.fontSize)
                englishFont = Font(appConfig.englishFontName, appConfig.fontStyle, appConfig.fontSize)
                lyricsPanel.setFonts(chineseFont, japaneseFont, englishFont)
                lyricsPanel.lyricColor = Color(appConfig.lyricColor)
                lyricsPanel.highlightColor = Color(appConfig.highlightColor)
                lyricsPanel.backgroundColor = Color(appConfig.backgroundColor)
                lyricsPanel.transparency = appConfig.transparency
                lyricsPanel.background = Color(
                    lyricsPanel.backgroundColor.red,
                    lyricsPanel.backgroundColor.green,
                    lyricsPanel.backgroundColor.blue,
                    (255 * lyricsPanel.transparency).roundToInt()
                )
                lyricsPanel.animationSpeed = appConfig.animationSpeed
                lyricsPanel.alignment = when (appConfig.alignment) {
                    1 -> LyricsPanel.Alignment.LEFT
                    2 -> LyricsPanel.Alignment.RIGHT
                    else -> LyricsPanel.Alignment.CENTER
                }
                lyricsPanel.useShadow = appConfig.useShadow
            }
        } catch (e: Exception) {
            println("加载配置文件失败: ${e.message}")
        }
    }
    private fun saveConfig() {
        try {
            appConfig.windowX = frame.location.x
            appConfig.windowY = frame.location.y
            appConfig.windowWidth = frame.width
            appConfig.windowHeight = frame.height
            appConfig.isLocked = isLocked
            appConfig.titleArtistFormat = titleArtistFormat
            appConfig.chineseFontName = chineseFont.name
            appConfig.japaneseFontName = japaneseFont.name
            appConfig.englishFontName = englishFont.name
            appConfig.fontSize = chineseFont.size
            appConfig.fontStyle = chineseFont.style
            appConfig.lyricColor = lyricsPanel.lyricColor.rgb
            appConfig.highlightColor = lyricsPanel.highlightColor.rgb
            appConfig.backgroundColor = lyricsPanel.backgroundColor.rgb
            appConfig.transparency = lyricsPanel.transparency
            appConfig.animationSpeed = lyricsPanel.animationSpeed
            appConfig.alignment = when (lyricsPanel.alignment) {
                LyricsPanel.Alignment.LEFT -> 1
                LyricsPanel.Alignment.RIGHT -> 2
                else -> 0
            }
            appConfig.useShadow = lyricsPanel.useShadow
            if (!configDir.exists()) {
                configDir.mkdirs()
            }
            val json = gson.toJson(appConfig)
            configFile.writeText(json)
        } catch (e: Exception) {
            println("保存配置文件失败: ${e.message}")
        }
    }
    private fun enableAcrylicEffect(alpha: Int) {
        try {
            val awtWindow = frame
            val toolkit = Toolkit.getDefaultToolkit()
            val getWindowMethod = toolkit.javaClass.getDeclaredMethod("getWindow", Window::class.java)
            getWindowMethod.isAccessible = true
            val window = getWindowMethod.invoke(toolkit, awtWindow)
            val getWindowHandleMethod = window.javaClass.getDeclaredMethod("getWindowHandle")
            getWindowHandleMethod.isAccessible = true
            val handle = getWindowHandleMethod.invoke(window) as Long
            val hwnd = WinDef.HWND(com.sun.jna.Pointer.createConstant(handle))
            val accent = AccentPolicy()
            accent.AccentState = ACCENT_ENABLE_ACRYLICBLURBEHIND
            accent.AccentFlags = 2 
            accent.GradientColor = (alpha shl 24) or 0x000000 
            val data = WindowCompositionAttributeData()
            data.Attribute = WCA_ACCENT_POLICY
            data.Data = accent.pointer
            data.SizeOfData = accent.size()
            User32Ex.INSTANCE.SetWindowCompositionAttribute(hwnd, data)
        } catch (e: Exception) {
            println("启用毛玻璃效果失败: ${e.message}")
            frame.background = Color(
                0, 0, 0, (alpha / 255f * 180).roundToInt()
            )
        }
    }
    private fun disableAcrylicEffect() {
        try {
            val awtWindow = frame
            val toolkit = Toolkit.getDefaultToolkit()
            val getWindowMethod = toolkit.javaClass.getDeclaredMethod("getWindow", Window::class.java)
            getWindowMethod.isAccessible = true
            val window = getWindowMethod.invoke(toolkit, awtWindow)
            val getWindowHandleMethod = window.javaClass.getDeclaredMethod("getWindowHandle")
            getWindowHandleMethod.isAccessible = true
            val handle = getWindowHandleMethod.invoke(window) as Long
            val hwnd = WinDef.HWND(com.sun.jna.Pointer.createConstant(handle))
            val accent = AccentPolicy()
            accent.AccentState = 0 
            val data = WindowCompositionAttributeData()
            data.Attribute = WCA_ACCENT_POLICY
            data.Data = accent.pointer
            data.SizeOfData = accent.size()
            User32Ex.INSTANCE.SetWindowCompositionAttribute(hwnd, data)
        } catch (e: Exception) {
            println("禁用毛玻璃效果失败: ${e.message}")
            frame.background = Color(0, 0, 0, 0)
        }
    }
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
                add(lyricsPanel, BorderLayout.CENTER)
                topPanel = createTopControlBar()
                add(topPanel, BorderLayout.NORTH)
            }
            setupKeyboardShortcuts()
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (!isLocked) {
                        val cursorType = getCursorType(e.point)
                        if (cursorType != Cursor.DEFAULT_CURSOR) {
                            isResizing = true
                            resizeStart = e.point
                            frame.cursor = Cursor.getPredefinedCursor(cursorType)
                        } else {
                            isDragging = true
                            dragStart = e.point
                            frame.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                        }
                    }
                }
                    override fun mouseReleased(e: MouseEvent) {
                            if (!isLocked) {
                            val wasDraggingOrResizing = isDragging || isResizing
                            isDragging = false
                            isResizing = false
                            frame.cursor = Cursor.getDefaultCursor()
                            if (wasDraggingOrResizing) {
                                saveConfig()
            }
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
                    }
                }
                override fun mouseExited(e: MouseEvent) {
                    if (!isLocked) {
                        try {
                            if (topPanel.isShowing) {
                                val point = MouseInfo.getPointerInfo().location
                                val panelBounds = topPanel.bounds
                                panelBounds.location = topPanel.locationOnScreen
                                if (!panelBounds.contains(point)) {
                                    topPanel.isVisible = false
                                }
                            } else {
                                topPanel.isVisible = false
                            }
                        } catch (ex: Exception) {
                            topPanel.isVisible = false
                        }
                        frame.cursor = Cursor.getDefaultCursor()
                    }
                }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    if (!isLocked) {
                        if (isResizing && resizeStart != null) {
                            val dx = e.x - resizeStart!!.x
                            val dy = e.y - resizeStart!!.y
                            val cursorType = getCursorType(e.point)
                            val newWidth = maxOf(frame.width + (if (cursorType == Cursor.E_RESIZE_CURSOR || cursorType == Cursor.SE_RESIZE_CURSOR || cursorType == Cursor.NE_RESIZE_CURSOR) dx else 0), 300)
                            val newHeight = maxOf(frame.height + (if (cursorType == Cursor.S_RESIZE_CURSOR || cursorType == Cursor.SE_RESIZE_CURSOR || cursorType == Cursor.SW_RESIZE_CURSOR) dy else 0), 100)
                            when (cursorType) {
                                Cursor.N_RESIZE_CURSOR -> {
                                    val newY = frame.y + dy
                                    setBounds(frame.x, newY, frame.width, newHeight)
                                }
                                Cursor.S_RESIZE_CURSOR -> {
                                    setSize(frame.width, newHeight)
                                }
                                Cursor.E_RESIZE_CURSOR -> {
                                    setSize(newWidth, frame.height)
                                }
                                Cursor.W_RESIZE_CURSOR -> {
                                    val newX = frame.x + dx
                                    setBounds(newX, frame.y, newWidth, frame.height)
                                }
                                Cursor.NE_RESIZE_CURSOR -> {
                                    val newY = frame.y + dy
                                    setBounds(frame.x, newY, newWidth, newHeight)
                                }
                                Cursor.NW_RESIZE_CURSOR -> {
                                    val newX = frame.x + dx
                                    val newY = frame.y + dy
                                    setBounds(newX, newY, newWidth, newHeight)
                                }
                                Cursor.SE_RESIZE_CURSOR -> {
                                    setSize(newWidth, newHeight)
                                }
                                Cursor.SW_RESIZE_CURSOR -> {
                                    val newX = frame.x + dx
                                    setBounds(newX, frame.y, newWidth, newHeight)
                                }
                            }
                            resizeStart = e.point
                        } else if (isDragging && dragStart != null) {
                            val currentLocation = location
                            setLocation(
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
            addWindowStateListener { e ->
                if (e.newState == Frame.NORMAL) {
                    updateLyrics()
                    lyricsPanel.repaint()
                }
            }
            if (SystemTray.isSupported()) {
                setupSystemTray()
            }
            topPanel.isVisible = false
            isVisible = true
        }
    }
    private fun getCursorType(point: Point): Int {
        val x = point.x
        val y = point.y
        val width = frame.width
        val height = frame.height
        return when {
            x < resizeBorder && y < resizeBorder -> Cursor.NW_RESIZE_CURSOR
            x < resizeBorder && y > height - resizeBorder -> Cursor.SW_RESIZE_CURSOR
            x > width - resizeBorder && y < resizeBorder -> Cursor.NE_RESIZE_CURSOR
            x > width - resizeBorder && y > height - resizeBorder -> Cursor.SE_RESIZE_CURSOR
            x < resizeBorder -> Cursor.W_RESIZE_CURSOR
            x > width - resizeBorder -> Cursor.E_RESIZE_CURSOR
            y < resizeBorder -> Cursor.N_RESIZE_CURSOR
            y > height - resizeBorder -> Cursor.S_RESIZE_CURSOR
            else -> Cursor.DEFAULT_CURSOR
        }
    }
    private fun updateCursor(point: Point) {
        val cursorType = getCursorType(point)
        frame.cursor = if (cursorType != Cursor.DEFAULT_CURSOR) {
            Cursor.getPredefinedCursor(cursorType)
        } else {
            Cursor.getDefaultCursor()
        }
    }
    private fun createTopControlBar(): JPanel {
        return JPanel(BorderLayout()).apply {
            background = Color(30, 30, 30, 200) 
            isOpaque = true 
            border = BorderFactory.createEmptyBorder(2, 10, 2, 10)
            preferredSize = Dimension(frame.width, 30)
            val infoPanel = JPanel(BorderLayout()).apply {
                background = Color(0, 0, 0, 0) 
                preferredSize = Dimension((frame.width * 0.25).toInt(), 30) 
            }
            titleArtistLabel = object : JLabel() {
                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    g2.font = Font("微软雅黑", Font.PLAIN, 12)
                    g2.color = Color.WHITE
                    val fm = g2.fontMetrics
                    val textWidth = fm.stringWidth(text)
                    if (textWidth > width) {
                        val scrollX = -scrollOffset
                        g2.drawString(text, scrollX, fm.ascent + (height - fm.height) / 2)
                        g2.drawString(text, scrollX + textWidth + 20, fm.ascent + (height - fm.height) / 2)
                    } else {
                        g2.drawString(text, (width - textWidth) / 2, fm.ascent + (height - fm.height) / 2)
                    }
                }
            }.apply {
                foreground = Color.WHITE
                font = Font("微软雅黑", Font.PLAIN, 12)
                horizontalAlignment = SwingConstants.LEFT
            }
            infoPanel.add(titleArtistLabel, BorderLayout.CENTER)
            val controlPanel = JPanel(FlowLayout(FlowLayout.CENTER, 5, 0)).apply {
                background = Color(0, 0, 0, 0)
                isOpaque = false
                val prevButton = createControlButton("◀").apply {
                    addActionListener { sendMediaCommand("/api/previous-track") }
                }
                playPauseButton = createControlButton("▶").apply {
                    addActionListener { sendMediaCommand("/api/play-pause") }
                }
                val nextButton = createControlButton("▶").apply {
                    addActionListener { sendMediaCommand("/api/next-track") }
                }
                add(prevButton)
                add(playPauseButton)
                add(nextButton)
            }
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                background = Color(0, 0, 0, 0)
                isOpaque = false
                lockButton = createControlButton("🔒").apply {
                    addActionListener { toggleLock() }
                }
                settingsButton = createControlButton("⚙").apply {
                    addActionListener { showSettingsDialog() }
                }
                minimizeButton = createControlButton("−").apply {
                    addActionListener { 
                        frame.isVisible = false
                    }
                }
                add(lockButton)
                add(settingsButton)
                add(minimizeButton)
            }
            add(infoPanel, BorderLayout.WEST)
            add(controlPanel, BorderLayout.CENTER)
            add(rightPanel, BorderLayout.EAST)
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    infoPanel.preferredSize = Dimension((width * 0.25).toInt(), 30)
                    revalidate()
                    repaint()
                    updateTitleArtistDisplay(currentTitle, currentArtist)
                }
            })
        }
    }
    private fun createControlButton(text: String): JButton {
        return JButton(text).apply {
            font = Font("Segoe UI Symbol", Font.BOLD, 12)
            foreground = Color.WHITE
            background = Color(60, 60, 60, 200) 
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(150, 150, 150, 150), 1),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)
            )
            isContentAreaFilled = true
            isFocusPainted = false
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    background = Color(80, 80, 80, 220)
                }
                override fun mouseExited(e: MouseEvent) {
                    background = Color(60, 60, 60, 200)
                }
            })
        }
    }
    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            lockButton.text = "🔒"
            topPanel.isVisible = false
            scrollTimer?.stop()
            disableAcrylicEffect()
        } else {
            lockButton.text = "🔓"
            if (frame.mousePosition != null) {
                enableAcrylicEffect(200)
            }
            if (scrollText.isNotEmpty()) {
                startScrollTimer()
            }
        }
    }
    private fun updateTitleArtistDisplay(title: String, artist: String) {
        currentTitle = title
        currentArtist = artist
        val displayText = if (titleArtistFormat == 0) {
            "$title - $artist"
        } else {
            "$artist - $title"
        }
        titleArtistLabel.text = displayText
        val fm = titleArtistLabel.getFontMetrics(titleArtistLabel.font)
        val textWidth = fm.stringWidth(displayText)
        val panelWidth = (topPanel.width * 0.25).toInt()
        scrollTimer?.stop()
        if (textWidth > panelWidth) {
            scrollText = displayText
            scrollOffset = 0
            scrollDirection = 1
            startScrollTimer()
        } else {
            scrollText = ""
        }
    }
    private fun startScrollTimer() {
        scrollTimer?.stop()
        scrollTimer = Timer(20) {
            val fm = titleArtistLabel.getFontMetrics(titleArtistLabel.font)
            val textWidth = fm.stringWidth(scrollText)
            val panelWidth = (topPanel.width * 0.25).toInt()
            if (textWidth > panelWidth) {
                scrollOffset += 1
                if (scrollOffset > textWidth + 20) {
                    scrollOffset = -panelWidth
                }
                titleArtistLabel.repaint()
            } else {
                scrollTimer?.stop()
            }
        }
        scrollTimer?.start()
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
                val url = URL("http:
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 1000
                conn.responseCode 
            } catch (e: Exception) {
                println("发送媒体命令失败: ${e.message}")
            }
        }.start()
    }
    private fun setupSystemTray() {
        val tray = SystemTray.getSystemTray()
        val image = createTrayIconImage()
        val trayIcon = TrayIcon(image, "Salt Player 桌面歌词")
        val popup = PopupMenu()
        val toggleItem = MenuItem("显示/隐藏")
        toggleItem.addActionListener { frame.isVisible = !frame.isVisible }
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
        val font = Font("微软雅黑", Font.PLAIN, 12)
        for (i in 0 until popup.itemCount) {
            val item = popup.getItem(i)
            item.font = font
        }
        trayIcon.popupMenu = popup
        trayIcon.addActionListener { frame.isVisible = !frame.isVisible }
        try {
            tray.add(trayIcon)
        } catch (e: AWTException) {
            println("无法添加系统托盘图标: ${e.message}")
        }
    }
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
        val fontPanel = createFontPanel(dialog)
        val colorPanel = createColorPanel(dialog)
        val otherPanel = createOtherPanel(dialog)
        tabbedPane.addTab("字体", fontPanel)
        tabbedPane.addTab("颜色", colorPanel)
        tabbedPane.addTab("其他", otherPanel)
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
            val chineseFontCombo = JComboBox(GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()).apply {
                selectedItem = chineseFont.family
                font = Font("微软雅黑", Font.PLAIN, 12)
                background = Color.WHITE
                renderer = DefaultListCellRenderer().apply {
                    font = Font("微软雅黑", Font.PLAIN, 12)
                }
            }
            add(chineseFontCombo, gbc)
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("日文字体:").apply { 
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            gbc.gridx = 1
            val japaneseFontCombo = JComboBox(GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()).apply {
                selectedItem = japaneseFont.family
                font = Font("微软雅黑", Font.PLAIN, 12)
                background = Color.WHITE
                renderer = DefaultListCellRenderer().apply {
                    font = Font("微软雅黑", Font.PLAIN, 12)
                }
            }
            add(japaneseFontCombo, gbc)
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("英文字体:").apply { 
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            gbc.gridx = 1
            val englishFontCombo = JComboBox(GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()).apply {
                selectedItem = englishFont.family
                font = Font("微软雅黑", Font.PLAIN, 12)
                background = Color.WHITE
                renderer = DefaultListCellRenderer().apply {
                    font = Font("微软雅黑", Font.PLAIN, 12)
                }
            }
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
            val lyricColorButton = JButton().apply {
                background = lyricsPanel.lyricColor
                preferredSize = Dimension(80, 25)
                addActionListener { 
                    val color = JColorChooser.showDialog(dialog, "选择歌词颜色", background)
                    if (color != null) {
                        background = color
                        lyricsPanel.lyricColor = color
                    }
                }
            }
            add(lyricColorButton, gbc)
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("高亮歌词颜色:").apply { 
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            gbc.gridx = 1
            val highlightColorButton = JButton().apply {
                background = lyricsPanel.highlightColor
                preferredSize = Dimension(80, 25)
                addActionListener { 
                    val color = JColorChooser.showDialog(dialog, "选择高亮歌词颜色", background)
                    if (color != null) {
                        background = color
                        lyricsPanel.highlightColor = color
                    }
                }
            }
            add(highlightColorButton, gbc)
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("背景颜色:").apply { 
                font = Font("微软雅黑", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            gbc.gridx = 1
            val bgColorButton = JButton().apply {
                background = lyricsPanel.backgroundColor
                preferredSize = Dimension(80, 25)
                addActionListener { 
                    val color = JColorChooser.showDialog(dialog, "选择背景颜色", background)
                    if (color != null) {
                        background = color
                        lyricsPanel.backgroundColor = color
                        lyricsPanel.background = Color(
                            color.red, color.green, color.blue, 
                            (255 * lyricsPanel.transparency).roundToInt()
                        )
                    }
                }
            }
            add(bgColorButton, gbc)
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
            val nowPlaying = getNowPlaying()
            if (nowPlaying == null) {
                frame.isVisible = false
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
            val lyricContent = if (songChanged || lyricsPanel.parsedLyrics.isEmpty()) {
                getLyric()
            } else {
                null
            }
            lyricsPanel.updateContent(
                title = nowPlaying.title ?: "无歌曲播放",
                artist = nowPlaying.artist ?: "",
                position = nowPlaying.position,
                lyric = lyricContent
            )
            frame.isVisible = true
        } catch (e: Exception) {
            frame.isVisible = false
        }
    }
    private fun getNowPlaying(): NowPlaying? {
        try {
            val url = URL("http:
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
        if (currentSongId.isNotEmpty() && lyricCache.containsKey(currentSongId)) {
            return lyricCache[currentSongId]
        }
        val endpoints = listOf(
            "/api/lyric",
            "/api/lyric163", 
            "/api/lyrickugou",
            "/api/lyricqq"
        )
        for (endpoint in endpoints) {
            try {
                val url = URL("http:
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 1000
                if (conn.responseCode == 404) {
                    conn.disconnect()
                    continue 
                }
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    continue 
                }
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()
                val lyricResponse = gson.fromJson(response, LyricResponse::class.java)
                val lyric = lyricResponse.lyric
                if (lyric != null && currentSongId.isNotEmpty()) {
                    lyricCache[currentSongId] = lyric
                    return lyric
                }
                conn.disconnect()
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
        var animationSpeed = 10
        var alignment = Alignment.CENTER
        var useShadow = true 
        private var scrollOffset = 0
        private var scrollTimer: Timer? = null
        private var currentLineScrollText = ""
        private var currentLineNeedsScroll = false
        private var hasTranslation = false
        private var chineseFont = Font("微软雅黑", Font.BOLD, 24)
        private var japaneseFont = Font("MS Gothic", Font.BOLD, 24)
        private var englishFont = Font("Arial", Font.BOLD, 24)
        var lyricColor = Color.WHITE
        var highlightColor = Color(255, 215, 0) 
        var backgroundColor = Color(0, 0, 0, 0) 
        private var animationProgress = 0f
        private var animationDirection = 1
        private var nextLineIndex = -1
        private var smoothPosition = 0f
        private var targetPosition = 0f
        private var smoothAlpha = 0f
        private var targetAlpha = 0f
        enum class Alignment {
            LEFT, CENTER, RIGHT
        }
        init {
            background = backgroundColor
            isOpaque = false 
            border = BorderFactory.createEmptyBorder(5, 20, 5, 20) 
            Timer(10) {
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
            nextLineIndex = -1
            lyric = ""
            smoothPosition = 0f
            targetPosition = 0f
            smoothAlpha = 0f
            targetAlpha = 0f
        }
        fun updateContent(title: String, artist: String, position: Long, lyric: String?) {
            this.title = title
            this.artist = artist
            this.position = position
            if (lyric != null && this.lyric != lyric) {
                this.lyric = lyric
                parsedLyrics = parseLyrics(lyric)
                hasTranslation = checkForTranslation(parsedLyrics)
            }
            val newIndex = findCurrentLyricLine()
            if (newIndex != currentLineIndex) {
                nextLineIndex = newIndex
                currentLineIndex = newIndex
                animationProgress = 0f
                animationDirection = 1
                targetPosition = newIndex.toFloat()
                targetAlpha = 1f
                updateScrollTexts()
            }
            repaint()
        }
        private fun checkForTranslation(lyrics: List<LyricLine>): Boolean {
            if (lyrics.size < 2) return false
            for (i in 0 until lyrics.size - 1) {
                if (lyrics[i].time == lyrics[i + 1].time) {
                    return true
                }
            }
            return false
        }
            private fun updateScrollTexts() {
            scrollTimer?.stop()
            if (currentLineIndex in parsedLyrics.indices) {
                currentLineScrollText = parsedLyrics[currentLineIndex].text
                val fm = getFontMetrics(getFontForText(currentLineScrollText))
                val textWidth = fm.stringWidth(currentLineScrollText)
                currentLineNeedsScroll = textWidth > width * 0.85
                if (currentLineNeedsScroll) {
                    startScrollTimer()
                }
            } else {
                currentLineScrollText = ""
                currentLineNeedsScroll = false
            }
        }
        private fun startScrollTimer() {
            scrollTimer?.stop()
            scrollTimer = Timer(20) {
                var needsRepaint = false
                if (currentLineNeedsScroll) {
                    scrollOffset += 1
                    val fm = getFontMetrics(getFontForText(currentLineScrollText))
                    val textWidth = fm.stringWidth(currentLineScrollText)
                    if (scrollOffset > textWidth + 50) {
                        scrollOffset = -width
                    }
                    needsRepaint = true
                }
                if (needsRepaint) {
                    repaint()
                }
            }
            scrollTimer?.start()
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
            val tempLines = mutableListOf<LyricLine>()
            lyricText.split("\n").forEach { line ->
                val match = pattern.find(line) ?: return@forEach
                val (min, sec, millis, text) = match.destructured
                val minutes = min.toLong()
                val seconds = sec.toLong()
                val millisValue = millis.toLongOrNull() ?: 0
                val totalMillis = minutes * 60 * 1000 + seconds * 1000 + millisValue * 10
                if (text.isNotBlank()) {
                    tempLines.add(LyricLine(totalMillis, text.trim()))
                }
            }
            val sortedLines = tempLines.sortedBy { it.time }
            var i = 0
            while (i < sortedLines.size) {
                val currentLine = sortedLines[i]
                if (i + 1 < sortedLines.size && sortedLines[i + 1].time == currentLine.time) {
                    val combinedText = "${currentLine.text}(${sortedLines[i + 1].text})"
                    lines.add(LyricLine(currentLine.time, combinedText))
                    i += 2 
                } else {
                    lines.add(currentLine)
                    i += 1
                }
            }
            return lines
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
            val centerY = height / 2
            if (parsedLyrics.isNotEmpty()) {
                if (currentLineIndex in parsedLyrics.indices) {
                    val alpha = (255 * smoothAlpha).toInt()
                    val color = Color(highlightColor.red, highlightColor.green, highlightColor.blue, alpha)
                    g2d.color = color
                    g2d.font = getFontForText(parsedLyrics[currentLineIndex].text)
                    val currentX = if (currentLineNeedsScroll) {
                        -scrollOffset
                    } else {
                        getTextXPosition(g2d, currentLineScrollText)
                    }
                    val currentY = centerY
                    if (useShadow) {
                        g2d.color = Color(0, 0, 0, alpha / 2)
                        g2d.drawString(currentLineScrollText, currentX + 1, currentY + 1)
                    }
                    g2d.color = color
                    g2d.drawString(currentLineScrollText, currentX, currentY)
                    if (currentLineNeedsScroll) {
                        val textWidth = g2d.fontMetrics.stringWidth(currentLineScrollText)
                        if (useShadow) {
                            g2d.color = Color(0, 0, 0, alpha / 2)
                            g2d.drawString(currentLineScrollText, currentX + textWidth + 50 + 1, currentY + 1)
                        }
                        g2d.color = color
                        g2d.drawString(currentLineScrollText, currentX + textWidth + 50, currentY)
                    }
                }
                if (currentLineIndex < parsedLyrics.size - 1) {
                    g2d.color = Color(lyricColor.red, lyricColor.green, lyricColor.blue, 150)
                    g2d.font = getFontForText(parsedLyrics[currentLineIndex + 1].text)
                    val nextLine = parsedLyrics[currentLineIndex + 1].text
                    val nextX = getTextXPosition(g2d, nextLine)
                    if (useShadow) {
                        g2d.color = Color(0, 0, 0, 75)
                        g2d.drawString(nextLine, nextX + 1, centerY + 40 + 1)
                    }
                    g2d.color = Color(lyricColor.red, lyricColor.green, lyricColor.blue, 150)
                    g2d.drawString(nextLine, nextX, centerY + 40)
                }
            } else if (lyric.isNotEmpty()) {
                g2d.color = lyricColor
                g2d.font = getFontForText(lyric)
                val fm = g2d.fontMetrics
                val textWidth = fm.stringWidth(lyric)
                val needsScroll = textWidth > width * 0.85
                val lyricX = if (needsScroll) {
                    -scrollOffset
                } else {
                    getTextXPosition(g2d, lyric)
                }
                if (useShadow) {
                    g2d.color = Color(0, 0, 0, 150)
                    g2d.drawString(lyric, lyricX + 1, centerY + 1)
                }
                g2d.color = lyricColor
                g2d.drawString(lyric, lyricX, centerY)
                if (needsScroll) {
                    if (useShadow) {
                        g2d.color = Color(0, 0, 0, 150)
                        g2d.drawString(lyric, lyricX + textWidth + 50 + 1, centerY + 1)
                    }
                    g2d.color = lyricColor
                    g2d.drawString(lyric, lyricX + textWidth + 50, centerY)
                    if (scrollTimer == null || !scrollTimer!!.isRunning) {
                        currentLineScrollText = lyric
                        currentLineNeedsScroll = true
                        scrollOffset = 0
                        startScrollTimer()
                    }
                }
            } else {
                g2d.color = Color.LIGHT_GRAY
                g2d.font = chineseFont
                val message = "歌词加载中..."
                val messageX = getTextXPosition(g2d, message)
                if (useShadow) {
                    g2d.color = Color(0, 0, 0, 150)
                    g2d.drawString(message, messageX + 1, centerY + 1)
                }
                g2d.color = Color.LIGHT_GRAY
                g2d.drawString(message, messageX, centerY)
            }
        }
        data class LyricLine(val time: Long, val text: String)
    }
