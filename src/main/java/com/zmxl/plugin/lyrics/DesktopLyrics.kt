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
@file:OptIn(UnstableSpwWorkshopApi::class)
package com.zmxl.plugin.lyrics
import com.google.gson.Gson
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.function.Consumer
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.math.roundToInt
object DesktopLyrics {
    private var isManuallyHidden = false
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
        var backgroundColor: Int = Color(0, 0, 0, 180).rgb,
        var transparency: Float = 0.8f,
        var animationSpeed: Int = 10,
        var alignment: Int = 0,
        var useShadow: Boolean = true
    )
    private var appConfig = AppConfig()
    private val configDir = File(System.getenv("APPDATA") + File.separator + "workshop" + File.separator + "data" + File.separator + "com.zmxl.spw-control-plugin")
    private val configFile = File(configDir, "desktop_lyrics_config.json")
    private lateinit var configManager: ConfigManager
    private lateinit var configHelper: ConfigHelper
    private var configChangeListener: Consumer<ConfigHelper>? = null
    private var isInitialized = false
    private var settingsDialog: JDialog? = null
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
    @JvmStatic
    fun openSettingsDialog() {
            DesktopLyrics.showSettingsDialog()
        }
    fun setConfigManager(manager: ConfigManager) {
        configManager = manager
        configHelper = manager.getConfig("desktop_lyrics_config.json")
        configChangeListener = Consumer { helper ->
            if (isInitialized) {
                println("检测到配置更改，重新加载配置...")
                loadConfig()
                applyConfig()
                println("配置已更新并应用")
            }
        }
        try {
            configManager.addConfigChangeListener("desktop_lyrics_config.json", configChangeListener!!)
            println("配置更改监听器已注册")
        } catch (e: Exception) {
            println("注册配置更改监听器失败: ${e.message}")
        }
    }
    fun start() {
        if (!::configManager.isInitialized) {
            loadConfigLegacy()
        } else {
            loadConfig()
        }
        setupUI()
        timer.start()
        backgroundTimer.start()
        isInitialized = true
        println("桌面歌词已启动")
    }
    fun stop() {
        isInitialized = false
        configChangeListener?.let {
            try {
                println("尝试移除配置监听器...")
            } catch (e: Exception) {
                println("移除配置监听器失败: ${e.message}")
            }
        }
        saveConfig()
        timer.stop()
        backgroundTimer.stop()
        scrollTimer?.stop()
        disableAcrylicEffect()
        frame.dispose()
        println("桌面歌词已停止")
    }
    private fun loadConfig() {
        try {
            if (::configHelper.isInitialized) {
                appConfig.windowX = configHelper.get("windowX", appConfig.windowX)
                appConfig.windowY = configHelper.get("windowY", appConfig.windowY)
                appConfig.windowWidth = configHelper.get("windowWidth", appConfig.windowWidth)
                appConfig.windowHeight = configHelper.get("windowHeight", appConfig.windowHeight)
                appConfig.isLocked = configHelper.get("isLocked", appConfig.isLocked)
                appConfig.titleArtistFormat = configHelper.get("titleArtistFormat", appConfig.titleArtistFormat)
                appConfig.chineseFontName = configHelper.get("chineseFontName", appConfig.chineseFontName)
                appConfig.japaneseFontName = configHelper.get("japaneseFontName", appConfig.japaneseFontName)
                appConfig.englishFontName = configHelper.get("englishFontName", appConfig.englishFontName)
                appConfig.fontSize = configHelper.get("fontSize", appConfig.fontSize)
                appConfig.fontStyle = configHelper.get("fontStyle", appConfig.fontStyle)
                appConfig.lyricColor = configHelper.get("lyricColor", appConfig.lyricColor)
                appConfig.highlightColor = configHelper.get("highlightColor", appConfig.highlightColor)
                appConfig.backgroundColor = configHelper.get("backgroundColor", appConfig.backgroundColor)
                appConfig.transparency = configHelper.get("transparency", appConfig.transparency.toString()).toFloat()
                appConfig.animationSpeed = configHelper.get("animationSpeed", appConfig.animationSpeed)
                appConfig.alignment = configHelper.get("alignment", appConfig.alignment)
                appConfig.useShadow = configHelper.get("useShadow", appConfig.useShadow)
                println("从ConfigManager加载配置成功")
            } else if (configFile.exists()) {
                val json = configFile.readText()
                appConfig = gson.fromJson(json, AppConfig::class.java)
                println("从文件加载配置成功")
            }
            applyConfig()
        } catch (e: Exception) {
            println("加载配置文件失败: ${e.message}")
        }
    }
    private fun applyConfig() {
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
        if (::lockButton.isInitialized) {
            lockButton.text = if (isLocked) "🔒" else "🔓"
        }
        println("配置已应用到UI")
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
            if (::configHelper.isInitialized) {
                configHelper.set("windowX", appConfig.windowX)
                configHelper.set("windowY", appConfig.windowY)
                configHelper.set("windowWidth", appConfig.windowWidth)
                configHelper.set("windowHeight", appConfig.windowHeight)
                configHelper.set("isLocked", appConfig.isLocked)
                configHelper.set("titleArtistFormat", appConfig.titleArtistFormat)
                configHelper.set("chineseFontName", appConfig.chineseFontName)
                configHelper.set("japaneseFontName", appConfig.japaneseFontName)
                configHelper.set("englishFontName", appConfig.englishFontName)
                configHelper.set("fontSize", appConfig.fontSize)
                configHelper.set("fontStyle", appConfig.fontStyle)
                configHelper.set("lyricColor", appConfig.lyricColor)
                configHelper.set("highlightColor", appConfig.highlightColor)
                configHelper.set("backgroundColor", appConfig.backgroundColor)
                configHelper.set("transparency", appConfig.transparency.toString())
                configHelper.set("animationSpeed", appConfig.animationSpeed)
                configHelper.set("alignment", appConfig.alignment)
                configHelper.set("useShadow", appConfig.useShadow)
                if (configHelper.save()) {
                    println("配置已保存到ConfigManager")
                } else {
                    println("保存配置到ConfigManager失败")
                    saveConfigLegacy()
                }
            } else {
                saveConfigLegacy()
            }
        } catch (e: Exception) {
            println("保存配置文件失败: ${e.message}")
            saveConfigLegacy()
        }
    }
    private fun loadConfigLegacy() {
        try {
            if (configFile.exists()) {
                val json = configFile.readText()
                appConfig = gson.fromJson(json, AppConfig::class.java)
                applyConfig()
                println("从旧式配置文件加载配置成功")
            }
        } catch (e: Exception) {
            println("加载旧式配置文件失败: ${e.message}")
        }
    }
    private fun saveConfigLegacy() {
        try {
            if (!configDir.exists()) {
                configDir.mkdirs()
            }
            val json = gson.toJson(appConfig)
            configFile.writeText(json)
            println("配置已保存到文件")
        } catch (e: Exception) {
            println("保存旧式配置文件失败: ${e.message}")
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
                        try {
                            if (SystemTray.isSupported()) {
                                val tray = SystemTray.getSystemTray()
                                val trayIcons = tray.trayIcons
                                if (trayIcons.isNotEmpty()) {
                                    trayIcons[0].displayMessage(
                                        "Salt Player 桌面歌词",
                                        "歌词窗口已隐藏，点击托盘图标可重新显示",
                                        TrayIcon.MessageType.INFO
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            println("显示托盘消息失败: ${e.message}")
                        }
                        isManuallyHidden = true
                        Timer(1000) { isManuallyHidden = false }.start()
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
        saveConfig()
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
    private fun setupSystemTray() {
        if (!SystemTray.isSupported()) return
        val tray = SystemTray.getSystemTray()
        val image = createTrayIconImage()
        val trayIcon = TrayIcon(image, "Salt Player 桌面歌词")
        val menuWindow = JWindow().apply {
            isAlwaysOnTop = true
            background = Color(0, 0, 0, 0)
            focusableWindowState = false
        }
        val menuPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color(60, 60, 60, 230)
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        }
        val toggleItem = createMenuItem("显示/隐藏") {
            frame.isVisible = !frame.isVisible
            menuWindow.isVisible = false
            if (frame.isVisible) {
                frame.toFront()
                if (!isLocked && frame.mousePosition != null) {
                    topPanel.isVisible = true
                }
            }
        }
        menuPanel.add(toggleItem)
        val lockItem = createMenuItem(if (isLocked) "解锁" else "锁定") {
            toggleLock()
            menuWindow.isVisible = false
        }
        menuPanel.add(lockItem)
        val settingsItem = createMenuItem("设置") {
            showSettingsDialog()
            menuWindow.isVisible = false
        }
        menuPanel.add(settingsItem)
        menuPanel.add(JSeparator().apply {
            foreground = Color(120, 120, 120)
            maximumSize = Dimension(Int.MAX_VALUE, 1)
        })
        val exitItem = createMenuItem("退出") {
            exitApplication()
            menuWindow.isVisible = false
        }
        menuPanel.add(exitItem)
        menuWindow.contentPane = menuPanel
        menuWindow.pack()
        val globalMouseListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (menuWindow.isVisible) {
                    val mousePoint = e.locationOnScreen
                    val menuBounds = Rectangle(menuWindow.location, menuWindow.size)
                    if (!menuBounds.contains(mousePoint)) {
                        menuWindow.isVisible = false
                    }
                }
            }
        }
        val globalKeyListener = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE && menuWindow.isVisible) {
                    menuWindow.isVisible = false
                }
            }
        }
        fun addGlobalListeners() {
            Window.getWindows().forEach { window ->
                if (window.isVisible) {
                    window.addMouseListener(globalMouseListener)
                    window.addKeyListener(globalKeyListener)
                }
            }
        }
        fun removeGlobalListeners() {
            Window.getWindows().forEach { window ->
                window.removeMouseListener(globalMouseListener)
                window.removeKeyListener(globalKeyListener)
            }
        }
        trayIcon.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    (lockItem as JButton).text = if (isLocked) "解锁" else "锁定"
                    val mousePos = MouseInfo.getPointerInfo().location
                    menuWindow.setLocation(
                        mousePos.x - menuWindow.width / 2,
                        mousePos.y - menuWindow.height
                    )
                    menuWindow.isVisible = true
                    addGlobalListeners()
                }
            }
        })
        menuWindow.addWindowListener(object : WindowAdapter() {
            override fun windowDeactivated(e: WindowEvent) {
                menuWindow.isVisible = false
                removeGlobalListeners()
            }
            override fun windowClosed(e: WindowEvent) {
                removeGlobalListeners()
            }
        })
        trayIcon.addActionListener {
            frame.isVisible = !frame.isVisible
            if (frame.isVisible) {
                frame.toFront()
                if (!isLocked && frame.mousePosition != null) {
                    topPanel.isVisible = true
                }
            }
        }
        try {
            tray.add(trayIcon)
        } catch (e: AWTException) {
            println("无法添加系统托盘图标: ${e.message}")
        }
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                removeGlobalListeners()
                menuWindow.dispose()
            }
        })
    }
    private fun createMenuItem(text: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            font = Font("微软雅黑", Font.PLAIN, 12)
            foreground = Color.WHITE
            background = Color(0, 0, 0, 0)
            border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
            horizontalAlignment = SwingConstants.LEFT
            isContentAreaFilled = false
            isFocusPainted = false
            addActionListener { action() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    background = Color(80, 80, 80, 200)
                    isContentAreaFilled = true
                }
                override fun mouseExited(e: MouseEvent) {
                    background = Color(0, 0, 0, 0)
                    isContentAreaFilled = false
                }
            })
        }
    }
    private fun updateLyrics() {
        try {
            if (isManuallyHidden) {
                return
            }
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
            
            // 处理无歌词的情况
            if (lyricContent == "NO_LYRIC") {
                lyricsPanel.updateContent(
                    title = nowPlaying.title ?: "无歌曲播放", 
                    artist = nowPlaying.artist ?: "",
                    position = nowPlaying.position,
                    lyric = "" // 传递空字符串表示无歌词
                )
                lyricsPanel.setNoLyrics(true) // 标记为无歌词状态
            } else {
                lyricsPanel.updateContent(
                    title = nowPlaying.title ?: "无歌曲播放",
                    artist = nowPlaying.artist ?: "",
                    position = nowPlaying.position,
                    lyric = lyricContent
                )
                lyricsPanel.setNoLyrics(false) // 标记为有歌词状态
            }
            
            frame.isVisible = true
        } catch (e: Exception) {
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
            if (currentSongId.isNotEmpty() && lyricCache.containsKey(currentSongId)) {
                val cachedLyric = lyricCache[currentSongId]
                // 如果是空歌词标记，返回特定值表示无歌词
                if (cachedLyric == "NO_LYRIC") {
                    return "NO_LYRIC"
                }
                return cachedLyric
            }
            
            val endpoints = listOf(
                "/api/lyric",
                "/api/lyricfile", 
                "/api/lyric163",
                "/api/lyrickugou",
                "/api/lyricqq"
            )
            
            var successCount = 0
            var lastException: Exception? = null
            
            for (endpoint in endpoints) {
                try {
                    val url = URL("http://localhost:35373$endpoint")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 1000
                    conn.readTimeout = 2000 // 添加读取超时
                    
                    if (conn.responseCode == 404) {
                        conn.disconnect()
                        successCount++
                        continue // 404不算错误，只是没有歌词
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
                    
                    if (lyric != null && lyric.isNotEmpty() && currentSongId.isNotEmpty()) {
                        lyricCache[currentSongId] = lyric
                        return lyric
                    }
                    
                    conn.disconnect()
                    successCount++ // 成功访问但无歌词
                    
                } catch (e: Exception) {
                    lastException = e
                    // 记录错误但不中断循环，继续尝试其他接口
                    println("歌词接口 $endpoint 请求失败: ${e.message}")
                }
            }
            
            // 所有接口都尝试过了
            if (successCount == endpoints.size) {
                // 所有接口都成功访问但没有歌词，缓存空结果
                if (currentSongId.isNotEmpty()) {
                    lyricCache[currentSongId] = "NO_LYRIC"
                }
                return "NO_LYRIC"
            }
            
            // 如果有网络错误，返回null（不缓存，下次再尝试）
            return null
            
        } catch (e: Exception) {
            println("获取歌词过程发生异常: ${e.message}")
            return null
        }
    }
    private fun exitApplication() {
        stop()
    }
    fun showSettingsDialog() {
        if (!isInitialized) {
            println("桌面歌词未初始化，无法打开设置")
            return
        }
        settingsDialog?.dispose()
        settingsDialog = JDialog(frame, "桌面歌词设置", true)
        settingsDialog!!.layout = BorderLayout()
        settingsDialog!!.setSize(500, 500)
        settingsDialog!!.setLocationRelativeTo(frame)
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
        val fontPanel = createFontPanel(settingsDialog!!)
        val colorPanel = createColorPanel(settingsDialog!!)
        val otherPanel = createOtherPanel(settingsDialog!!)
        tabbedPane.addTab("字体", fontPanel)
        tabbedPane.addTab("颜色", colorPanel)
        tabbedPane.addTab("其他", otherPanel)
        settingsDialog!!.add(tabbedPane, BorderLayout.CENTER)
        val closeButton = JButton("关闭").apply {
            font = Font("微软雅黑", Font.BOLD, 12)
            background = Color(192, 57, 43)
            foreground = Color.WHITE
            border = EmptyBorder(8, 20, 8, 20)
            addActionListener { settingsDialog!!.dispose() }
        }
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            background = Color(240, 240, 240)
            border = EmptyBorder(10, 10, 10, 10)
            add(closeButton)
        }
        settingsDialog!!.add(buttonPanel, BorderLayout.SOUTH)
        settingsDialog!!.isVisible = true
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
                    if (::configHelper.isInitialized) {
                        configHelper.set("chineseFontName", chineseFontName)
                        configHelper.set("japaneseFontName", japaneseFontName)
                        configHelper.set("englishFontName", englishFontName)
                        configHelper.set("fontSize", fontSize)
                        configHelper.set("fontStyle", fontStyle)
                        configHelper.save()
                    }
                    JOptionPane.showMessageDialog(dialog, "字体设置已应用", "提示", JOptionPane.INFORMATION_MESSAGE)
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
                        if (::configHelper.isInitialized) {
                            configHelper.set("lyricColor", color.rgb)
                            configHelper.save()
                        }
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
                        if (::configHelper.isInitialized) {
                            configHelper.set("highlightColor", color.rgb)
                            configHelper.save()
                        }
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
                        if (::configHelper.isInitialized) {
                            configHelper.set("backgroundColor", color.rgb)
                            configHelper.save()
                        }
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
                    if (::configHelper.isInitialized) {
                        configHelper.set("transparency", lyricsPanel.transparency.toString())
                        configHelper.save()
                    }
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
                    if (::configHelper.isInitialized) {
                        configHelper.set("animationSpeed", value)
                        configHelper.save()
                    }
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
                    if (::configHelper.isInitialized) {
                        configHelper.set("alignment", selectedIndex)
                        configHelper.save()
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
                    if (::configHelper.isInitialized) {
                        configHelper.set("titleArtistFormat", selectedIndex)
                        configHelper.save()
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
                    if (::configHelper.isInitialized) {
                        configHelper.set("useShadow", isSelected)
                        configHelper.save()
                    }
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
    var backgroundColor = Color(0, 0, 0, 180)
    private var animationProgress = 0f
    private var animationDirection = 1
    private var nextLineIndex = -1
    private var smoothPosition = 0f
    private var targetPosition = 0f
    private var smoothAlpha = 0f
    private var targetAlpha = 0f
    private var currentWordIndex = -1
    private var currentWordProgress = 0f
    private var wordAnimationTimer: Timer? = null
    private var normalLyricProgress = 0f
    // 添加无歌词状态标志
    private var noLyrics = false
    
    enum class Alignment {
        LEFT, CENTER, RIGHT
    }
    
    // 添加设置无歌词状态的方法
    fun setNoLyrics(noLyrics: Boolean) {
        this.noLyrics = noLyrics
        repaint()
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
        wordAnimationTimer = Timer(50) {
            if (currentLineIndex in parsedLyrics.indices) {
                val line = parsedLyrics[currentLineIndex]
                val currentTime = position
                if (line.words.isNotEmpty()) {
                    var foundWord = false
                    for (i in line.words.indices) {
                        val word = line.words[i]
                        if (currentTime >= word.startTime && currentTime < word.endTime) {
                            currentWordIndex = i
                            val wordDuration = (word.endTime - word.startTime).toFloat()
                            if (wordDuration > 0) {
                                currentWordProgress = ((currentTime - word.startTime) / wordDuration).coerceIn(0f, 1f)
                            } else {
                                currentWordProgress = 1f
                            }
                            foundWord = true
                            break
                        } else if (currentTime >= word.endTime) {
                            currentWordIndex = i
                            currentWordProgress = 1f
                            foundWord = true
                        }
                    }
                    if (!foundWord && line.words.isNotEmpty() && currentTime >= line.words.last().endTime) {
                        currentWordIndex = line.words.size - 1
                        currentWordProgress = 1f
                    }
                    repaint()
                } else {
                    if (currentTime >= line.time && currentTime < line.endTime) {
                        val lineDuration = (line.endTime - line.time).toFloat()
                        if (lineDuration > 0) {
                            normalLyricProgress = ((currentTime - line.time) / lineDuration).coerceIn(0f, 1f)
                        } else {
                            normalLyricProgress = 1f
                        }
                    } else if (currentTime >= line.endTime) {
                        normalLyricProgress = 1f
                    }
                    repaint()
                }
            }
        }
        wordAnimationTimer?.start()
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
        noLyrics = false // 重置无歌词状态
        smoothPosition = 0f
        targetPosition = 0f
        smoothAlpha = 0f
        targetAlpha = 0f
        currentWordIndex = -1
        currentWordProgress = 0f
        normalLyricProgress = 0f
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
            currentWordIndex = -1
            currentWordProgress = 0f
            normalLyricProgress = 0f
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
                    scrollTimer?.stop()
                    scrollOffset = 0
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
    data class LyricLine(
        val time: Long,
        val endTime: Long,
        val text: String,
        val words: List<Word>
    )
    data class Word(
        val text: String,
        val startTime: Long,
        val endTime: Long
    )
    private fun parseLyrics(lyricText: String?): List<LyricLine> {
        if (lyricText.isNullOrEmpty()) return emptyList()
        val lines = mutableListOf<LyricLine>()
        val linePattern = Regex("\\[(\\d+):(\\d+)(?:\\.(\\d{1,3}))?\\]")
        lyricText.split("\n").forEach { line ->
            var currentLine = line.trim()
            if (currentLine.isEmpty()) return@forEach
            val timeMatches = linePattern.findAll(currentLine).toList()
            if (timeMatches.isEmpty()) {
                return@forEach
            }
            val timeStamps = timeMatches.map { match ->
                val (min, sec, millis) = match.destructured
                val minutes = min.toLong()
                val seconds = sec.toLong()
                val milliseconds = millis.toIntOrNull() ?: 0
                minutes * 60 * 1000 + seconds * 1000 + milliseconds
            }
            val startTime = timeStamps.first()
            var textStartIndex = timeMatches.first().range.last + 1
            var text = currentLine.substring(textStartIndex)
            val hasWordTimestamps = timeMatches.size > 1 ||
                                  (textStartIndex < currentLine.length &&
                                   currentLine.substring(textStartIndex).contains(Regex("<\\d+:\\d+(?:\\.\\d{1,3})?>|\\[\\d+:\\d+(?:\\.\\d{1,3})?\\]")))
            val words = mutableListOf<Word>()
            if (hasWordTimestamps) {
                var lastTime = startTime
                var lastIndex = 0
                val wordPattern = Regex("""(?:<(\d+):(\d+)(?:\.(\d{1,3}))?>|\[(\d+):(\d+)(?:\.(\d{1,3}))?\])""")
                val wordMatches = wordPattern.findAll(text).toList()
                for (match in wordMatches) {
                    val groups = match.groups
                    val minStr = groups[1]?.value ?: groups[4]?.value ?: "0"
                    val secStr = groups[2]?.value ?: groups[5]?.value ?: "0"
                    val millisStr = groups[3]?.value ?: groups[6]?.value ?: "0"
                    val minutes = minStr.toLong()
                    val seconds = secStr.toLong()
                    val milliseconds = millisStr.toIntOrNull() ?: 0
                    val wordTime = minutes * 60 * 1000 + seconds * 1000 + milliseconds
                    if (match.range.first > lastIndex) {
                        val wordText = text.substring(lastIndex, match.range.first)
                        if (wordText.isNotEmpty()) {
                            words.add(Word(wordText, lastTime, wordTime))
                        }
                    }
                    lastTime = wordTime
                    lastIndex = match.range.last + 1
                }
                if (lastIndex < text.length) {
                    val remainingText = text.substring(lastIndex)
                    if (remainingText.isNotEmpty()) {
                        words.add(Word(remainingText, lastTime, lastTime + 500))
                    }
                }
            } else {
                if (text.isNotEmpty()) {
                    val characters = text.toCharArray().map { it.toString() }
                    val charCount = characters.size
                    val estimatedLineDuration = 5000L
                    val charDuration = estimatedLineDuration / charCount
                    for (i in characters.indices) {
                        val charStartTime = startTime + i * charDuration
                        val charEndTime = startTime + (i + 1) * charDuration
                        words.add(Word(characters[i], charStartTime, charEndTime))
                    }
                }
            }
            lines.add(LyricLine(
                time = startTime,
                endTime = Long.MAX_VALUE,
                text = text,
                words = words
            ))
        }
        val sortedLines = lines.sortedBy { it.time }.toMutableList()
        for (i in 0 until sortedLines.size - 1) {
            sortedLines[i] = sortedLines[i].copy(endTime = sortedLines[i + 1].time)
        }
        if (sortedLines.isNotEmpty()) {
            sortedLines[sortedLines.size - 1] = sortedLines[sortedLines.size - 1].copy(endTime = Long.MAX_VALUE)
        }
        return sortedLines
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
        
        // 处理无歌词情况
        if (noLyrics) {
            g2d.color = Color.LIGHT_GRAY
            g2d.font = chineseFont
            val message = "暂无歌词"
            val messageX = getTextXPosition(g2d, message)
            if (useShadow) {
                g2d.color = Color(0, 0, 0, 150)
                g2d.drawString(message, messageX + 1, centerY + 1)
            }
            g2d.color = Color.LIGHT_GRAY
            g2d.drawString(message, messageX, centerY)
            return
        }
        
        // 原有的绘制逻辑...
        val currentLineY = centerY
        val nextLineY = centerY + 40
        
        if (parsedLyrics.isNotEmpty()) {
            if (currentLineIndex in parsedLyrics.indices) {
                val line = parsedLyrics[currentLineIndex]
                val font = getFontForText(line.text)
                g2d.font = font
                var currentX = when (alignment) {
                    Alignment.LEFT -> 20
                    Alignment.RIGHT -> width - 20
                    else -> width / 2
                }
                var totalWidth = 0
                line.words.forEach { word ->
                    totalWidth += getTextWidth(g2d, word.text)
                }
                if (alignment == Alignment.CENTER) {
                    currentX -= totalWidth / 2
                }
                var x = currentX
                for (i in line.words.indices) {
                    val word = line.words[i]
                    val wordWidth = getTextWidth(g2d, word.text)
                    val isCurrentWord = i == currentWordIndex
                    val highlightProgress = if (isCurrentWord) currentWordProgress else {
                        if (i < currentWordIndex) 1f else 0f
                    }
                    if (useShadow) {
                        g2d.color = Color(0, 0, 0, 150)
                        g2d.drawString(word.text, (x + 1).toInt(), currentLineY + 1)
                    }
                    if (highlightProgress <= 0) {
                        g2d.color = lyricColor
                        g2d.drawString(word.text, x.toInt(), currentLineY)
                    } else {
                        val highlightedWidth = (wordWidth * highlightProgress).toInt()
                        g2d.color = highlightColor
                        g2d.drawString(word.text, x.toInt(), currentLineY)
                        if (highlightProgress < 1f) {
                            g2d.color = lyricColor
                            g2d.clipRect((x + highlightedWidth).toInt(), 0, wordWidth - highlightedWidth, height)
                            g2d.drawString(word.text, x.toInt(), currentLineY)
                            g2d.clip = null
                        }
                    }
                    x += wordWidth
                }
                if (line.words.isEmpty()) {
                    val text = line.text
                    val textWidth = getTextWidth(g2d, text)
                    val x = getTextXPosition(g2d, text)
                    if (useShadow) {
                        g2d.color = Color(0, 0, 0, 150)
                        g2d.drawString(text, x + 1, currentLineY + 1)
                    }
                    g2d.color = lyricColor
                    g2d.drawString(text, x, currentLineY)
                    val highlightWidth = (textWidth * normalLyricProgress).toInt()
                    if (highlightWidth > 0) {
                        g2d.color = highlightColor
                        val clip = g2d.clip
                        g2d.clipRect(x, 0, highlightWidth, height)
                        g2d.drawString(text, x, currentLineY)
                        g2d.clip = clip
                    }
                }
                if (currentLineIndex < parsedLyrics.size - 1) {
                    val nextLine = parsedLyrics[currentLineIndex + 1]
                    g2d.color = Color(lyricColor.red, lyricColor.green, lyricColor.blue, 150)
                    g2d.font = getFontForText(nextLine.text)
                    var nextX = when (alignment) {
                        Alignment.LEFT -> 20
                        Alignment.RIGHT -> width - 20
                        else -> width / 2
                    }
                    var nextTotalWidth = 0
                    nextLine.words.forEach { word ->
                        nextTotalWidth += getTextWidth(g2d, word.text)
                    }
                    if (alignment == Alignment.CENTER) {
                        nextX -= nextTotalWidth / 2
                    }
                    var nextXPos = nextX
                    nextLine.words.forEach { word ->
                        val wordWidth = getTextWidth(g2d, word.text)
                        if (useShadow) {
                            g2d.color = Color(0, 0, 0, 75)
                            g2d.drawString(word.text, (nextXPos + 1).toInt(), nextLineY + 1)
                        }
                        g2d.color = Color(lyricColor.red, lyricColor.green, lyricColor.blue, 150)
                        g2d.drawString(word.text, nextXPos.toInt(), nextLineY)
                        nextXPos += wordWidth
                    }
                }
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
            // 只有当不是无歌词状态时才显示"歌词加载中..."
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
}
