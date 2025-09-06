@file:OptIn(UnstableSpwWorkshopApi::class)
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
package com.zmxl.plugin.lyrics
import com.google.gson.Gson
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigManager
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
import javax.swing.event.PopupMenuListener
import javax.swing.event.PopupMenuEvent
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
    // 字体设置
    private var chineseFont = Font("微软雅黑", Font.BOLD, 24)
    private var japaneseFont = Font("MS Gothic", Font.BOLD, 24)
    private var englishFont = Font("Arial", Font.BOLD, 24)
    // 新增设置选项
    private var isLocked = false
    private var titleArtistFormat = 0 // 0: 歌名-歌手, 1: 歌手-歌名
    // 控制按钮面板
    private lateinit var topPanel: JPanel
    private lateinit var playPauseButton: JButton
    private lateinit var titleArtistLabel: JLabel
    private lateinit var lockButton: JButton
    private lateinit var settingsButton: JButton
    private lateinit var minimizeButton: JButton
    // 滚动文本相关
    private var scrollOffset = 0
    private var scrollDirection = 1
    private var scrollTimer: Timer? = null
    private var scrollText = ""
    private var maxTextWidth = 0
    // 存储当前歌曲信息用于滚动显示
    private var currentTitle = ""
    private var currentArtist = ""
    // 毛玻璃效果相关
    private var backgroundAlpha = 0f
    private val backgroundTimer = Timer(16) {
        val targetAlpha = if (frame.mousePosition != null && !isLocked) 0.9f else 0f
        backgroundAlpha += (targetAlpha - backgroundAlpha) * 0.15f // 更平滑的过渡
        if (backgroundAlpha < 0.01f) {
            // 完全透明时禁用毛玻璃效果
            disableAcrylicEffect()
        } else {
            // 启用毛玻璃效果
            enableAcrylicEffect((backgroundAlpha * 255).roundToInt())
        }
        frame.repaint()
    }
    // 配置文件相关
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
        var alignment: Int = 0, // 0: CENTER, 1: LEFT, 2: RIGHT
        var useShadow: Boolean = true
    )
    private var appConfig = AppConfig()
    // ConfigManager 支持
    private lateinit var configManager: ConfigManager
    private lateinit var configHelper: ConfigHelper
    // JNA接口定义
    interface User32Ex : com.sun.jna.platform.win32.User32 {
        fun SetWindowCompositionAttribute(hWnd: WinDef.HWND,  WindowCompositionAttributeData): Boolean
        companion object {
            val INSTANCE: User32Ex = Native.load("user32", User32Ex::class.java) as User32Ex
        }
    }
    // Windows API常量
    private val ACCENT_ENABLE_ACRYLICBLURBEHIND = 4
    private val WCA_ACCENT_POLICY = 19
    // JNA结构体定义
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
    
    fun setConfigManager(manager: ConfigManager) {
        configManager = manager
        configHelper = manager.getConfig("desktop_lyrics_config.json")
        
        // 检查并迁移旧配置
        migrateOldConfig()
    }
    
    private fun migrateOldConfig() {
        try {
            // 旧配置路径 (旧版Salt Player)
            val oldConfigPath1 = File(
                System.getenv("APPDATA") + File.separator + "workshop" + 
                File.separator + "data" + File.separator + "com.zmxl.spw-control-plugin" + 
                File.separator + "desktop_lyrics_config.json"
            )
            
            // 旧配置路径 (新版Salt Player但旧插件ID)
            val oldConfigPath2 = File(
                System.getenv("APPDATA") + File.separator + "Salt Player for Windows" + 
                File.separator + "workshop" + File.separator + "data" + 
                File.separator + "com.zmxl.spw-control-plugin" + File.separator + "desktop_lyrics_config.json"
            )
            
            // 旧配置路径 (使用SaltLyricPlugin作为插件ID)
            val oldConfigPath3 = File(
                System.getenv("APPDATA") + File.separator + "Salt Player for Windows" + 
                File.separator + "workshop" + File.separator + "data" + 
                File.separator + "SaltLyricPlugin" + File.separator + "desktop_lyrics_config.json"
            )
            
            // 检查配置是否已经迁移
            if (!configHelper.getConfigPath().toFile().exists()) {
                // 尝试从第一个旧位置迁移
                if (oldConfigPath1.exists()) {
                    migrateFromPath(oldConfigPath1)
                    return
                }
                
                // 尝试从第二个旧位置迁移
                if (oldConfigPath2.exists()) {
                    migrateFromPath(oldConfigPath2)
                    return
                }
                
                // 尝试从第三个旧位置迁移
                if (oldConfigPath3.exists()) {
                    migrateFromPath(oldConfigPath3)
                    return
                }
            }
        } catch (e: Exception) {
            println("配置迁移失败: ${e.message}")
        }
    }
    
    private fun migrateFromPath(oldConfigPath: File) {
        try {
            println("检测到旧版配置文件，正在迁移...")
            
            // 读取旧配置
            val oldConfigJson = oldConfigPath.readText()
            val oldConfig = gson.fromJson(oldConfigJson, AppConfig::class.java)
            
            // 迁移到新配置
            configHelper.set("windowX", oldConfig.windowX)
            configHelper.set("windowY", oldConfig.windowY)
            configHelper.set("windowWidth", oldConfig.windowWidth)
            configHelper.set("windowHeight", oldConfig.windowHeight)
            configHelper.set("isLocked", oldConfig.isLocked)
            configHelper.set("titleArtistFormat", oldConfig.titleArtistFormat)
            configHelper.set("chineseFontName", oldConfig.chineseFontName)
            configHelper.set("japaneseFontName", oldConfig.japaneseFontName)
            configHelper.set("englishFontName", oldConfig.englishFontName)
            configHelper.set("fontSize", oldConfig.fontSize)
            configHelper.set("fontStyle", oldConfig.fontStyle)
            configHelper.set("lyricColor", oldConfig.lyricColor)
            configHelper.set("highlightColor", oldConfig.highlightColor)
            configHelper.set("backgroundColor", oldConfig.backgroundColor)
            // 修复：直接保存float值，而不是转换为字符串
            configHelper.set("transparency", oldConfig.transparency)
            configHelper.set("animationSpeed", oldConfig.animationSpeed)
            configHelper.set("alignment", oldConfig.alignment)
            configHelper.set("useShadow", oldConfig.useShadow)
            configHelper.save()
            
            println("配置文件已成功迁移到新位置")
        } catch (e: Exception) {
            println("配置迁移失败: ${e.message}")
        }
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
    // 加载配置文件
    private fun loadConfig() {
        try {
            if (::configHelper.isInitialized) {
                // 使用 ConfigManager 加载配置 - 直接获取正确类型的值
                appConfig.windowX = configHelper.get("windowX", appConfig.windowX)
                appConfig.windowY = configHelper.get("windowY", appConfig.windowY)
                appConfig.windowWidth = configHelper.get("windowWidth", appConfig.windowWidth)
                appConfig.windowHeight = configHelper.get("windowHeight", appConfig.windowHeight)
                appConfig.isLocked = configHelper.get("isLocked", appConfig.isLocked)
                appConfig.titleArtistFormat = configHelper.get("titleArtistFormat", appConfig.titleArtistFormat)
                // 字体设置
                appConfig.chineseFontName = configHelper.get("chineseFontName", appConfig.chineseFontName)
                appConfig.japaneseFontName = configHelper.get("japaneseFontName", appConfig.japaneseFontName)
                appConfig.englishFontName = configHelper.get("englishFontName", appConfig.englishFontName)
                appConfig.fontSize = configHelper.get("fontSize", appConfig.fontSize)
                appConfig.fontStyle = configHelper.get("fontStyle", appConfig.fontStyle)
                // 颜色设置 - 直接获取整数值
                appConfig.lyricColor = configHelper.get("lyricColor", appConfig.lyricColor)
                appConfig.highlightColor = configHelper.get("highlightColor", appConfig.highlightColor)
                appConfig.backgroundColor = configHelper.get("backgroundColor", appConfig.backgroundColor)
                // 修复：直接获取float值，而不是获取字符串再转换
                appConfig.transparency = configHelper.get("transparency", appConfig.transparency)
                // 其他设置
                appConfig.animationSpeed = configHelper.get("animationSpeed", appConfig.animationSpeed)
                appConfig.alignment = configHelper.get("alignment", appConfig.alignment)
                appConfig.useShadow = configHelper.get("useShadow", appConfig.useShadow)
            } else {
                // 没有ConfigManager时，尝试从旧路径加载
                val oldConfigDir = File(System.getenv("APPDATA") + File.separator + "workshop" + File.separator + "data" + File.separator + "com.zmxl.spw-control-plugin")
                val oldConfigFile = File(oldConfigDir, "desktop_lyrics_config.json")
                
                if (oldConfigFile.exists()) {
                    val json = oldConfigFile.readText()
                    appConfig = gson.fromJson(json, AppConfig::class.java)
                }
            }
            // 应用配置
            frame.setSize(appConfig.windowWidth, appConfig.windowHeight)
            frame.setLocation(appConfig.windowX, appConfig.windowY)
            isLocked = appConfig.isLocked
            titleArtistFormat = appConfig.titleArtistFormat
            // 字体设置
            chineseFont = Font(appConfig.chineseFontName, appConfig.fontStyle, appConfig.fontSize)
            japaneseFont = Font(appConfig.japaneseFontName, appConfig.fontStyle, appConfig.fontSize)
            englishFont = Font(appConfig.englishFontName, appConfig.fontStyle, appConfig.fontSize)
            lyricsPanel.setFonts(chineseFont, japaneseFont, englishFont)
            // 颜色设置
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
            // 其他设置
            lyricsPanel.animationSpeed = appConfig.animationSpeed
            lyricsPanel.alignment = when (appConfig.alignment) {
                1 -> LyricsPanel.Alignment.LEFT
                2 -> LyricsPanel.Alignment.RIGHT
                else -> LyricsPanel.Alignment.CENTER
            }
            lyricsPanel.useShadow = appConfig.useShadow
        } catch (e: Exception) {
            println("加载配置文件失败: ${e.message}")
        }
    }
    // 保存配置文件
    private fun saveConfig() {
        try {
            // 更新配置
            appConfig.windowX = frame.location.x
            appConfig.windowY = frame.location.y
            appConfig.windowWidth = frame.width
            appConfig.windowHeight = frame.height
            appConfig.isLocked = isLocked
            appConfig.titleArtistFormat = titleArtistFormat
            // 字体设置
            appConfig.chineseFontName = chineseFont.name
            appConfig.japaneseFontName = japaneseFont.name
            appConfig.englishFontName = englishFont.name
            appConfig.fontSize = chineseFont.size
            appConfig.fontStyle = chineseFont.style
            // 颜色设置
            appConfig.lyricColor = lyricsPanel.lyricColor.rgb
            appConfig.highlightColor = lyricsPanel.highlightColor.rgb
            appConfig.backgroundColor = lyricsPanel.backgroundColor.rgb
            appConfig.transparency = lyricsPanel.transparency
            // 其他设置
            appConfig.animationSpeed = lyricsPanel.animationSpeed
            appConfig.alignment = when (lyricsPanel.alignment) {
                LyricsPanel.Alignment.LEFT -> 1
                LyricsPanel.Alignment.RIGHT -> 2
                else -> 0
            }
            appConfig.useShadow = lyricsPanel.useShadow
            if (::configHelper.isInitialized) {
                // 使用 ConfigManager 保存配置 - 直接保存正确类型的值
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
                // 修复：直接保存float值，而不是转换为字符串
                configHelper.set("transparency", appConfig.transparency)
                configHelper.set("animationSpeed", appConfig.animationSpeed)
                configHelper.set("alignment", appConfig.alignment)
                configHelper.set("useShadow", appConfig.useShadow)
                configHelper.save()
            } else {
                // 没有ConfigManager时，保存到旧路径
                val oldConfigDir = File(System.getenv("APPDATA") + File.separator + "workshop" + File.separator + "data" + File.separator + "com.zmxl.spw-control-plugin")
                if (!oldConfigDir.exists()) {
                    oldConfigDir.mkdirs()
                }
                val json = gson.toJson(appConfig)
                File(oldConfigDir, "desktop_lyrics_config.json").writeText(json)
            }
        } catch (e: Exception) {
            println("保存配置文件失败: ${e.message}")
        }
    }
    // 启用Windows毛玻璃效果
    private fun enableAcrylicEffect(alpha: Int) {
        try {
            // 使用反射获取窗口句柄
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
            accent.GradientColor = (alpha shl 24) or 0x000000 // ARGB格式，A=alpha, RGB=黑色
            val data = WindowCompositionAttributeData()
            data.Attribute = WCA_ACCENT_POLICY
            data.Data = accent.pointer
            data.SizeOfData = accent.size()
            User32Ex.INSTANCE.SetWindowCompositionAttribute(hwnd, data)
        } catch (e: Exception) {
            println("启用毛玻璃效果失败: ${e.message}")
            // 备用方案：使用半透明背景
            frame.background = Color(
                0, 0, 0, (alpha / 255f * 180).roundToInt()
            )
        }
    }
    // 禁用毛玻璃效果
    private fun disableAcrylicEffect() {
        try {
            // 使用反射获取窗口句柄
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
            accent.AccentState = 0 // 禁用特效
            val data = WindowCompositionAttributeData()
            data.Attribute = WCA_ACCENT_POLICY
            data.Data = accent.pointer
            data.SizeOfData = accent.size()
            User32Ex.INSTANCE.SetWindowCompositionAttribute(hwnd, data)
        } catch (e: Exception) {
            println("禁用毛玻璃效果失败: ${e.message}")
            // 备用方案：恢复透明背景
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
            // 创建内容面板
            contentPane = JPanel(BorderLayout()).apply {
                background = Color(0, 0, 0, 0)
                isOpaque = false
                // 添加歌词面板
                add(lyricsPanel, BorderLayout.CENTER)
                // 添加顶部控制栏
                topPanel = createTopControlBar()
                add(topPanel, BorderLayout.NORTH)
            }
            // 设置窗口大小和位置（已从配置文件加载）
            // 添加键盘快捷键
            setupKeyboardShortcuts()
            // 添加鼠标事件监听器
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
                        // 只有当鼠标不在控制面板上时才隐藏
                        try {
                            // 修复：检查组件是否已显示在屏幕上
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
                            // 如果获取位置失败，直接隐藏面板
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
                            // 根据不同的调整方向调整窗口位置和大小
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
            // 添加窗口状态监听器
            addWindowStateListener { e: WindowEvent ->
                if (e.newState == Frame.NORMAL) {
                    updateLyrics()
                    lyricsPanel.repaint()
                }
            }
            // 添加系统托盘图标
            if (SystemTray.isSupported()) {
                setupSystemTray()
            }
            // 初始状态隐藏控制面板
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
            // 左侧歌曲信息
            val infoPanel = JPanel(BorderLayout()).apply {
                background = Color(0, 0, 0, 0)
                preferredSize = Dimension((frame.width * 0.25).toInt(), 30) // 固定为控制栏宽度的1/4
            }
            // 自定义标签实现滚动效果
            titleArtistLabel = object : JLabel() {
                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    // 设置字体和颜色
                    g2.font = Font("微软雅黑", Font.PLAIN, 12)
                    g2.color = Color.WHITE
                    // 获取文本宽度
                    val fm = g2.fontMetrics
                    val textWidth = fm.stringWidth(text)
                    // 如果文本宽度超过面板宽度，启用滚动效果
                    if (textWidth > width) {
                        // 计算滚动位置
                        val scrollX = -scrollOffset
                        // 绘制文本
                        g2.drawString(text, scrollX, fm.ascent + (height - fm.height) / 2)
                        // 绘制文本的副本以实现循环滚动
                        g2.drawString(text, scrollX + textWidth + 20, fm.ascent + (height - fm.height) / 2)
                    } else {
                        // 文本宽度足够，居中显示
                        g2.drawString(text, (width - textWidth) / 2, fm.ascent + (height - fm.height) / 2)
                    }
                }
            }.apply {
                foreground = Color.WHITE
                font = Font("微软雅黑", Font.PLAIN, 12)
                horizontalAlignment = SwingConstants.LEFT
            }
            infoPanel.add(titleArtistLabel, BorderLayout.CENTER)
            // 中间控制按钮
            val controlPanel = JPanel(FlowLayout(FlowLayout.CENTER, 5, 0)).apply {
                background = Color(0, 0, 0, 0)
                isOpaque = false
                // 添加上一曲按钮
                val prevButton = createControlButton("◀").apply {
                    addActionListener { sendMediaCommand("/api/previous-track") }
                }
                // 添加播放/暂停按钮
                playPauseButton = createControlButton("▶").apply {
                    addActionListener { sendMediaCommand("/api/play-pause") }
                }
                // 添加下一曲按钮
                val nextButton = createControlButton("▶").apply {
                    addActionListener { sendMediaCommand("/api/next-track") }
                }
                add(prevButton)
                add(playPauseButton)
                add(nextButton)
            }
            // 右侧功能按钮
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                background = Color(0, 0, 0, 0)
                isOpaque = false
                // 锁定按钮
                lockButton = createControlButton("🔒").apply {
                    addActionListener { toggleLock() }
                }
                // 设置按钮 - 保持可见性
                settingsButton = createControlButton("⚙").apply {
                    addActionListener { showSettingsDialog() }
                }
                // 修复最小化按钮 - 使用更直接的方法
                minimizeButton = createControlButton("−").apply {
                    addActionListener {
                        // 直接设置窗口为不可见
                        frame.isVisible = false
                        // 显示托盘消息
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
                        // 确保窗口不会因为其他事件而重新显示
                        // 添加一个临时标志来防止自动显示
                        isManuallyHidden = true
                        // 设置一个定时器，在短暂时间后重置标志
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
            // 解锁后，如果鼠标在窗口内，启用毛玻璃效果
            if (frame.mousePosition != null) {
                enableAcrylicEffect(200)
            }
            // 如果文本需要滚动，重新启动滚动计时器
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
        // 检查文本是否需要滚动
        val fm = titleArtistLabel.getFontMetrics(titleArtistLabel.font)
        val textWidth = fm.stringWidth(displayText)
        val panelWidth = (topPanel.width * 0.25).toInt()
        // 停止之前的滚动计时器
        scrollTimer?.stop()
        if (textWidth > panelWidth) {
            // 文本需要滚动
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
        if (!SystemTray.isSupported()) return
        val tray = SystemTray.getSystemTray()
        val image = createTrayIconImage()
        val trayIcon = TrayIcon(image, "Salt Player 桌面歌词")
        // 创建一个透明的JWindow作为菜单容器
        val menuWindow = JWindow().apply {
            isAlwaysOnTop = true
            background = Color(0, 0, 0, 0)
            focusableWindowState = false
        }
        // 创建菜单面板
        val menuPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Color(60, 60, 60, 230)
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        }
        // 添加显示/隐藏菜单项
        val toggleItem = createMenuItem("显示/隐藏") {
            frame.isVisible = !frame.isVisible
            menuWindow.isVisible = false
            if (frame.isVisible) {
                frame.toFront()
                // 如果解锁状态，显示控制面板
                if (!isLocked && frame.mousePosition != null) {
                    topPanel.isVisible = true
                }
            }
        }
        menuPanel.add(toggleItem)
        // 添加锁定/解锁菜单项
        val lockItem = createMenuItem(if (isLocked) "解锁" else "锁定") {
            toggleLock()
            menuWindow.isVisible = false
        }
        menuPanel.add(lockItem)
        // 添加设置菜单项
        val settingsItem = createMenuItem("设置") {
            showSettingsDialog()
            menuWindow.isVisible = false
        }
        menuPanel.add(settingsItem)
        // 添加分隔线
        menuPanel.add(JSeparator().apply {
            foreground = Color(120, 120, 120)
            maximumSize = Dimension(Int.MAX_VALUE, 1)
        })
        // 添加退出菜单项
        val exitItem = createMenuItem("退出") {
            exitApplication()
            menuWindow.isVisible = false
        }
        menuPanel.add(exitItem)
        // 设置菜单窗口内容
        menuWindow.contentPane = menuPanel
        menuWindow.pack()
        // 添加全局鼠标监听器
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
        // 添加键盘监听器（ESC键关闭菜单）
        val globalKeyListener = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE && menuWindow.isVisible) {
                    menuWindow.isVisible = false
                }
            }
        }
        // 为所有窗口添加监听器
        fun addGlobalListeners() {
            Window.getWindows().forEach { window ->
                if (window.isVisible) {
                    window.addMouseListener(globalMouseListener)
                    window.addKeyListener(globalKeyListener)
                }
            }
        }
        // 移除全局监听器
        fun removeGlobalListeners() {
            Window.getWindows().forEach { window ->
                window.removeMouseListener(globalMouseListener)
                window.removeKeyListener(globalKeyListener)
            }
        }
        // 添加鼠标监听器以显示菜单
        trayIcon.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    // 更新锁定/解锁菜单项文本
                    (lockItem as JButton).text = if (isLocked) "解锁" else "锁定"
                    // 获取鼠标位置
                    val mousePos = MouseInfo.getPointerInfo().location
                    // 设置菜单窗口位置
                    menuWindow.setLocation(
                        mousePos.x - menuWindow.width / 2,
                        mousePos.y - menuWindow.height
                    )
                    // 显示菜单并添加全局监听器
                    menuWindow.isVisible = true
                    addGlobalListeners()
                }
            }
        })
        // 添加菜单窗口监听器
        menuWindow.addWindowListener(object : WindowAdapter() {
            override fun windowDeactivated(e: WindowEvent) {
                // 窗口失去焦点时隐藏
                menuWindow.isVisible = false
                removeGlobalListeners()
            }
            override fun windowClosed(e: WindowEvent) {
                // 确保移除全局监听器
                removeGlobalListeners()
            }
        })
        // 添加左键点击显示/隐藏功能
        trayIcon.addActionListener {
            frame.isVisible = !frame.isVisible
            if (frame.isVisible) {
                frame.toFront()
                // 如果解锁状态，显示控制面板
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
        // 添加应用程序关闭时的清理代码
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                // 确保移除全局监听器
                removeGlobalListeners()
                menuWindow.dispose()
            }
        })
    }
    // 创建菜单项辅助函数
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
            // 添加鼠标悬停效果
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
            // 如果窗口被手动隐藏，则不更新内容
            if (isManuallyHidden) {
                return
            }
            // 获取当前播放信息
            val nowPlaying = getNowPlaying()
            if (nowPlaying == null) {
                frame.isVisible = false
                return
            }
            // 更新播放/暂停按钮图标
            playPauseButton.text = if (nowPlaying.isPlaying) "❚❚" else "▶"
            // 更新标题-艺术家显示
            updateTitleArtistDisplay(nowPlaying.title ?: "", nowPlaying.artist ?: "")
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
            // 只有当有歌曲播放时才显示窗口
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
            if (currentSongId.isNotEmpty() && lyricCache.containsKey(currentSongId)) {
                return lyricCache[currentSongId]
            }
            // 按顺序尝试不同的歌词API
            val endpoints = listOf(
                "/api/lyric",
                "/api/lyric163",
                "/api/lyrickugou",
                "/api/lyricqq"
            )
            for (endpoint in endpoints) {
                try {
                    val url = URL("http://localhost:35373$endpoint")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 1000
                    if (conn.responseCode == 404) {
                        conn.disconnect()
                        continue // 尝试下一个端点
                    }
                    if (conn.responseCode != 200) {
                        conn.disconnect()
                        continue // 尝试下一个端点
                    }
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()
                    val lyricResponse = gson.fromJson(response, LyricResponse::class.java)
                    val lyric = lyricResponse.lyric
                    // 更新缓存
                    if (lyric != null && currentSongId.isNotEmpty()) {
                        lyricCache[currentSongId] = lyric
                        return lyric
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    // 连接失败，继续尝试下一个端点
                    continue
                }
            }
            return null // 所有端点都失败
        } catch (e: Exception) {
            return null
        }
    }
    private fun exitApplication() {
        stop()
    }
    // 将 showSettingsDialog 方法改为 public
    fun showSettingsDialog() {
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
        // 字体设置面板
        val fontPanel = createFontPanel(dialog)
        // 颜色设置面板
        val colorPanel = createColorPanel(dialog)
        // 其他设置面板
        val otherPanel = createOtherPanel(dialog)
        tabbedPane.addTab("字体", fontPanel)
        tabbedPane.addTab("颜色", colorPanel)
        tabbedPane.addTab("其他", otherPanel)
        dialog.add(tabbedPane, BorderLayout.CENTER)
        // 添加关闭按钮
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
            // 标题
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.gridwidth = 2
            add(JLabel("字体设置").apply {
                font = Font("微软雅黑", Font.BOLD, 16)
                foreground = Color(60, 60, 60)
            }, gbc)
            gbc.gridwidth = 1
            gbc.gridy++
            // 中文字体选择
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
            // 日文字体选择
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
            // 英文字体选择
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
            // 字体大小
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
            // 字体样式
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
            // 应用按钮
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
            // 标题
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.gridwidth = 2
            add(JLabel("颜色设置").apply {
                font = Font("微软雅黑", Font.BOLD, 16)
                foreground = Color(60, 60, 60)
            }, gbc)
            gbc.gridwidth = 1
            gbc.gridy++
            // 歌词颜色
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
            // 高亮歌词颜色
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
            // 背景颜色
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
            // 标题
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.gridwidth = 2
            add(JLabel("其他设置").apply {
                font = Font("微软雅黑", Font.BOLD, 16)
                foreground = Color(60, 60, 60)
            }, gbc)
            gbc.gridwidth = 1
            gbc.gridy++
            // 透明度设置
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
            // 动画速度设置
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
            // 歌词对齐方式
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
            // 标题-艺术家显示格式
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
            // 文本阴影效果
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
    var useShadow = true // 是否使用文字阴影
    // 滚动相关变量
    private var scrollOffset = 0
    private var scrollTimer: Timer? = null
    private var currentLineScrollText = ""
    private var currentLineNeedsScroll = false
    private var hasTranslation = false
    // 字体设置
    private var chineseFont = Font("微软雅黑", Font.BOLD, 24)
    private var japaneseFont = Font("MS Gothic", Font.BOLD, 24)
    private var englishFont = Font("Arial", Font.BOLD, 24)
    // 颜色设置
    var lyricColor = Color.WHITE
    var highlightColor = Color(255, 215, 0) // 金色
    var backgroundColor = Color(0, 0, 0, 180) // 背景颜色
    // 动画状态
    private var animationProgress = 0f
    private var animationDirection = 1
    private var nextLineIndex = -1
    // 平滑动画相关
    private var smoothPosition = 0f
    private var targetPosition = 0f
    private var smoothAlpha = 0f
    private var targetAlpha = 0f
    // 逐字高亮相关
    private var currentWordIndex = -1
    private var currentWordProgress = 0f
    private var wordAnimationTimer: Timer? = null
    // 新增：普通歌词高亮进度
    private var normalLyricProgress = 0f
    enum class Alignment {
        LEFT, CENTER, RIGHT
    }
    init {
        background = backgroundColor
        isOpaque = false // 设置为不透明，使背景透明
        border = BorderFactory.createEmptyBorder(5, 20, 5, 20) // 减少上下间距
        // 动画定时器 - 使用更平滑的动画
        Timer(10) {
            // 平滑过渡动画
            smoothPosition += (targetPosition - smoothPosition) * 0.2f
            smoothAlpha += (targetAlpha - smoothAlpha) * 0.2f
            // 歌词行切换动画
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
        // 逐字高亮动画定时器 - 修复 currentTime 作用域问题
        wordAnimationTimer = Timer(50) {
            if (currentLineIndex in parsedLyrics.indices) {
                val line = parsedLyrics[currentLineIndex]
                val currentTime = position // 修复：将 currentTime 定义移到 if-else 结构外部
                if (line.words.isNotEmpty()) {
                    // 对于逐字歌词，按时间戳点亮
                    var foundWord = false
                    // 找到当前应该高亮的字
                    for (i in line.words.indices) {
                        val word = line.words[i]
                        if (currentTime >= word.startTime && currentTime < word.endTime) {
                            currentWordIndex = i
                            // 计算当前字的进度
                            val wordDuration = (word.endTime - word.startTime).toFloat()
                            if (wordDuration > 0) {
                                currentWordProgress = ((currentTime - word.startTime) / wordDuration).coerceIn(0f, 1f)
                            } else {
                                currentWordProgress = 1f
                            }
                            foundWord = true
                            break
                        } else if (currentTime >= word.endTime) {
                            // 这个字已经播放完毕，完全高亮
                            currentWordIndex = i
                            currentWordProgress = 1f
                            foundWord = true
                        }
                    }
                    // 如果当前时间超过最后字的结束时间，保持最后字完全高亮
                    if (!foundWord && line.words.isNotEmpty() && currentTime >= line.words.last().endTime) {
                        currentWordIndex = line.words.size - 1
                        currentWordProgress = 1f
                    }
                    repaint()
                } else {
                    // 对于普通歌词，计算整行进度
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
                // 包含中文或日文字符
                if (text.contains("[\\u4E00-\\u9FFF]".toRegex())) chineseFont else japaneseFont
            }
            else -> englishFont // 英文或其他
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
        currentWordIndex = -1
        currentWordProgress = 0f
        normalLyricProgress = 0f
    }
    fun updateContent(title: String, artist: String, position: Long, lyric: String?) {
        this.title = title
        this.artist = artist
        this.position = position
        // 只有在歌词变化时重新解析
        if (lyric != null && this.lyric != lyric) {
            this.lyric = lyric
            parsedLyrics = parseLyrics(lyric)
            // 检查是否有翻译歌词
            hasTranslation = checkForTranslation(parsedLyrics)
        }
        // 更新当前歌词行
        val newIndex = findCurrentLyricLine()
        // 如果行索引变化，启动动画
        if (newIndex != currentLineIndex) {
            nextLineIndex = newIndex
            currentLineIndex = newIndex
            animationProgress = 0f
            animationDirection = 1
            // 重置逐字高亮
            currentWordIndex = -1
            currentWordProgress = 0f
            normalLyricProgress = 0f
            // 设置平滑动画目标值
            targetPosition = newIndex.toFloat()
            targetAlpha = 1f
            // 更新滚动文本
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
        // 停止之前的滚动计时器
        scrollTimer?.stop()
        if (currentLineIndex in parsedLyrics.indices) {
            currentLineScrollText = parsedLyrics[currentLineIndex].text
            val fm = getFontMetrics(getFontForText(currentLineScrollText))
            val textWidth = fm.stringWidth(currentLineScrollText)
            currentLineNeedsScroll = textWidth > width * 0.85
            // 如果需要滚动，启动计时器
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
                // 修复：滚动到文本末尾后停止，而不是无限循环
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
    // 逐字歌词解析相关数据结构
    data class LyricLine(
        val time: Long, // 行开始时间
        val endTime: Long, // 行结束时间
        val text: String, // 完整行文本
        val words: List<Word> // 逐字时间信息
    )
    data class Word(
        val text: String,
        val startTime: Long,
        val endTime: Long
    )
    private fun parseLyrics(lyricText: String?): List<LyricLine> {
        if (lyricText.isNullOrEmpty()) return emptyList()
        val lines = mutableListOf<LyricLine>()
        // 行时间戳格式的正则表达式 [mm:ss.xxx]
        val linePattern = Regex("\\[(\\d+):(\\d+)(?:\\.(\\d{1,3}))?\\]")
        // 按行分割歌词文本
        lyricText.split("\n").forEach { line ->
            var currentLine = line.trim()
            if (currentLine.isEmpty()) return@forEach
            // 解析行时间戳
            val timeMatches = linePattern.findAll(currentLine).toList()
            if (timeMatches.isEmpty()) {
                return@forEach
            }
            // 处理多个连续时间戳（如 [00:04.797][00:05.016]吉）
            val timeStamps = timeMatches.map { match ->
                val (min, sec, millis) = match.destructured
                val minutes = min.toLong()
                val seconds = sec.toLong()
                val milliseconds = millis.toIntOrNull() ?: 0
                // 修复关键问题：毫秒部分不再乘以10
                minutes * 60 * 1000 + seconds * 1000 + milliseconds
            }
            // 第一个时间戳作为行开始时间
            val startTime = timeStamps.first()
            // 移除所有时间戳前缀
            var textStartIndex = timeMatches.first().range.last + 1
            var text = currentLine.substring(textStartIndex)
            // 检查是否包含行内逐字时间戳（在文本中间的时间戳）
            val hasWordTimestamps = timeMatches.size > 1 || 
                                  (textStartIndex < currentLine.length && 
                                   currentLine.substring(textStartIndex).contains(Regex("<\\d+:\\d+(?:\\.\\d{1,3})?>|\\[\\d+:\\d+(?:\\.\\d{1,3})?\\]")))
            // 处理逐字时间戳（仅当确实存在行内逐字时间戳时）
            val words = mutableListOf<Word>()
            if (hasWordTimestamps) {
                var lastTime = startTime
                var lastIndex = 0
                // 支持 <00:05.016> 和 [00:05.016] 格式的逐字时间戳
                val wordPattern = Regex("""(?:<(\d+):(\d+)(?:\.(\d{1,3}))?>|\[(\d+):(\d+)(?:\.(\d{1,3}))?\])""")
                val wordMatches = wordPattern.findAll(text).toList()
                // 处理逐字时间戳
                for (match in wordMatches) {
                    val groups = match.groups
                    val minStr = groups[1]?.value ?: groups[4]?.value ?: "0"
                    val secStr = groups[2]?.value ?: groups[5]?.value ?: "0"
                    val millisStr = groups[3]?.value ?: groups[6]?.value ?: "0"
                    val minutes = minStr.toLong()
                    val seconds = secStr.toLong()
                    val milliseconds = millisStr.toIntOrNull() ?: 0
                    // 修复关键问题：毫秒部分不再乘以10
                    val wordTime = minutes * 60 * 1000 + seconds * 1000 + milliseconds
                    // 添加上一个时间点到当前时间点之间的文本
                    if (match.range.first > lastIndex) {
                        val wordText = text.substring(lastIndex, match.range.first)
                        if (wordText.isNotEmpty()) {
                            words.add(Word(wordText, lastTime, wordTime))
                        }
                    }
                    lastTime = wordTime
                    lastIndex = match.range.last + 1
                }
                // 添加剩余文本
                if (lastIndex < text.length) {
                    val remainingText = text.substring(lastIndex)
                    if (remainingText.isNotEmpty()) {
                        words.add(Word(remainingText, lastTime, lastTime + 500)) // 假设剩余文本持续500毫秒
                    }
                }
            } else {
                // 对于普通歌词，创建逐字时间信息
                if (text.isNotEmpty()) {
                    // 将文本分割为单个字符
                    val characters = text.toCharArray().map { it.toString() }
                    val charCount = characters.size
                    // 估计每个字符的持续时间（假设整行持续5秒）
                    val estimatedLineDuration = 5000L
                    val charDuration = estimatedLineDuration / charCount
                    // 为每个字符创建时间信息
                    for (i in characters.indices) {
                        val charStartTime = startTime + i * charDuration
                        val charEndTime = startTime + (i + 1) * charDuration
                        words.add(Word(characters[i], charStartTime, charEndTime))
                    }
                }
            }
            // 创建歌词行
            lines.add(LyricLine(
                time = startTime,
                endTime = Long.MAX_VALUE, // 临时值，后面会更新
                text = text,
                words = words
            ))
        }
        // 排序并确定行结束时间
        val sortedLines = lines.sortedBy { it.time }.toMutableList()
        // 设置每行的结束时间为下一行的开始时间
        for (i in 0 until sortedLines.size - 1) {
            sortedLines[i] = sortedLines[i].copy(endTime = sortedLines[i + 1].time)
        }
        // 最后一行的结束时间设为一个很大的值
        if (sortedLines.isNotEmpty()) {
            sortedLines[sortedLines.size - 1] = sortedLines[sortedLines.size - 1].copy(endTime = Long.MAX_VALUE)
        }
        return sortedLines
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
    private fun getTextWidth(g: Graphics2D, text: String): Int {
        return g.fontMetrics.stringWidth(text)
    }
    private fun getTextXPosition(g: Graphics2D, text: String): Int {
        return when (alignment) {
            Alignment.LEFT -> 20
            Alignment.RIGHT -> width - getTextWidth(g, text) - 20
            else -> (width - getTextWidth(g, text)) / 2 // CENTER
        }
    }
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D
        // 设置抗锯齿
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        // 绘制歌词
        val centerY = height / 2
        val currentLineY = centerY
        val nextLineY = centerY + 40
        if (parsedLyrics.isNotEmpty()) {
            // 绘制当前行歌词（双行显示）
            if (currentLineIndex in parsedLyrics.indices) {
                val line = parsedLyrics[currentLineIndex]
                val font = getFontForText(line.text)
                g2d.font = font
                // 计算当前行的起始X位置
                var currentX = when (alignment) {
                    Alignment.LEFT -> 20
                    Alignment.RIGHT -> width - 20
                    else -> width / 2
                }
                // 绘制当前行的每个字
                var totalWidth = 0
                line.words.forEach { word ->
                    totalWidth += getTextWidth(g2d, word.text)
                }
                // 调整起始位置以实现居中
                if (alignment == Alignment.CENTER) {
                    currentX -= totalWidth / 2
                }
                // 绘制当前行歌词
                var x = currentX
                for (i in line.words.indices) {
                    val word = line.words[i]
                    val wordWidth = getTextWidth(g2d, word.text)
                    // 判断是否是当前高亮的字
                    val isCurrentWord = i == currentWordIndex
                    // 计算高亮进度（0.0-1.0）
                    val highlightProgress = if (isCurrentWord) currentWordProgress else {
                        // 对于已经播放过的字，完全高亮
                        if (i < currentWordIndex) 1f else 0f
                    }
                    // 绘制阴影效果
                    if (useShadow) {
                        g2d.color = Color(0, 0, 0, 150)
                        g2d.drawString(word.text, (x + 1).toInt(), currentLineY + 1)
                    }
                    // 绘制普通部分（非高亮）
                    if (highlightProgress <= 0) {
                        g2d.color = lyricColor
                        g2d.drawString(word.text, x.toInt(), currentLineY)
                    } else {
                        // 绘制高亮部分（当前字）
                        // 1. 绘制已高亮部分
                        val highlightedWidth = (wordWidth * highlightProgress).toInt()
                        g2d.color = highlightColor
                        g2d.drawString(word.text, x.toInt(), currentLineY)
                        // 2. 绘制未高亮部分
                        if (highlightProgress < 1f) {
                            g2d.color = lyricColor
                            g2d.clipRect((x + highlightedWidth).toInt(), 0, wordWidth - highlightedWidth, height)
                            g2d.drawString(word.text, x.toInt(), currentLineY)
                            g2d.clip = null
                        }
                    }
                    x += wordWidth
                }
                // 对于普通歌词（没有逐字时间戳），使用平滑高亮效果
                if (line.words.isEmpty()) {
                    val text = line.text
                    val textWidth = getTextWidth(g2d, text)
                    val x = getTextXPosition(g2d, text)
                    // 绘制阴影效果
                    if (useShadow) {
                        g2d.color = Color(0, 0, 0, 150)
                        g2d.drawString(text, x + 1, currentLineY + 1)
                    }
                    // 绘制背景（未高亮部分）
                    g2d.color = lyricColor
                    g2d.drawString(text, x, currentLineY)
                    // 绘制高亮部分
                    val highlightWidth = (textWidth * normalLyricProgress).toInt()
                    if (highlightWidth > 0) {
                        g2d.color = highlightColor
                        // 使用剪裁绘制高亮部分
                        val clip = g2d.clip
                        g2d.clipRect(x, 0, highlightWidth, height)
                        g2d.drawString(text, x, currentLineY)
                        g2d.clip = clip
                    }
                }
                // 绘制下一行歌词
                if (currentLineIndex < parsedLyrics.size - 1) {
                    val nextLine = parsedLyrics[currentLineIndex + 1]
                    g2d.color = Color(lyricColor.red, lyricColor.green, lyricColor.blue, 150)
                    g2d.font = getFontForText(nextLine.text)
                    // 计算下一行的起始X位置
                    var nextX = when (alignment) {
                        Alignment.LEFT -> 20
                        Alignment.RIGHT -> width - 20
                        else -> width / 2
                    }
                    // 计算下一行的总宽度
                    var nextTotalWidth = 0
                    nextLine.words.forEach { word ->
                        nextTotalWidth += getTextWidth(g2d, word.text)
                    }
                    // 调整起始位置以实现居中
                    if (alignment == Alignment.CENTER) {
                        nextX -= nextTotalWidth / 2
                    }
                    // 绘制下一行歌词
                    var nextXPos = nextX
                    nextLine.words.forEach { word ->
                        val wordWidth = getTextWidth(g2d, word.text)
                        // 绘制阴影效果
                        if (useShadow) {
                            g2d.color = Color(0, 0, 0, 75)
                            g2d.drawString(word.text, (nextXPos + 1).toInt(), nextLineY + 1)
                        }
                        // 绘制歌词
                        g2d.color = Color(lyricColor.red, lyricColor.green, lyricColor.blue, 150)
                        g2d.drawString(word.text, nextXPos.toInt(), nextLineY)
                        nextXPos += wordWidth
                    }
                }
            }
        } else if (lyric.isNotEmpty()) {
            // 绘制静态歌词（没有逐字时间戳的情况）
            g2d.color = lyricColor
            g2d.font = getFontForText(lyric)
            // 检查是否需要滚动
            val fm = g2d.fontMetrics
            val textWidth = fm.stringWidth(lyric)
            val needsScroll = textWidth > width * 0.85
            val lyricX = if (needsScroll) {
                -scrollOffset
            } else {
                getTextXPosition(g2d, lyric)
            }
            // 使用阴影效果
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
                // 启动滚动计时器
                if (scrollTimer == null || !scrollTimer!!.isRunning) {
                    currentLineScrollText = lyric
                    currentLineNeedsScroll = true
                    scrollOffset = 0
                    startScrollTimer()
                }
            }
        } else {
            // 没有歌词时的提示
            g2d.color = Color.LIGHT_GRAY
            g2d.font = chineseFont
            val message = "歌词加载中..."
            val messageX = getTextXPosition(g2d, message)
            // 使用阴影效果
            if (useShadow) {
                g2d.color = Color(0, 0, 0, 150)
                g2d.drawString(message, messageX + 1, centerY + 1)
            }
            g2d.color = Color.LIGHT_GRAY
            g2d.drawString(message, messageX, centerY)
        }
    }
}

