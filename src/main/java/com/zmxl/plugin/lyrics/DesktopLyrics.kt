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
import javax.swing.border.EmptyBorder
import kotlin.math.roundToInt

object DesktopLyrics {
    private val frame = JFrame()
    private val lyricsPanel = LyricsPanel()
    private var isDragging = false
    private var dragStart: Point? = null
    
    private val timer = Timer(10) { updateLyrics() } // 缩短更新间隔至10ms
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
    private lateinit var controlPanel: JPanel
    private lateinit var playPauseButton: JButton
    private lateinit var titleArtistLabel: JLabel
    private lateinit var lockButton: JButton
    private lateinit var settingsButton: JButton
    private lateinit var minimizeButton: JButton
    
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
            
            // 创建内容面板
            contentPane = JPanel(BorderLayout()).apply {
                background = Color(0, 0, 0, 0)
                isOpaque = false
                
                // 添加歌词面板
                add(lyricsPanel, BorderLayout.CENTER)
                
                // 添加顶部控制栏
                add(createTopControlBar(), BorderLayout.NORTH)
            }
            
            // 设置窗口大小和位置
            setSize(800, 180)
            setLocationRelativeTo(null)
            
            // 添加键盘快捷键
            setupKeyboardShortcuts()
            
            // 添加鼠标事件监听器
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (!isLocked) {
                        isDragging = true
                        dragStart = e.point
                        frame.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    }
                }
                
                override fun mouseReleased(e: MouseEvent) {
                    if (!isLocked) {
                        isDragging = false
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
                        controlPanel.isVisible = true
                        titleArtistLabel.isVisible = true
                    }
                }
                
                override fun mouseExited(e: MouseEvent) {
                    if (!isLocked) {
                        // 只有当鼠标不在控制面板上时才隐藏
                        val point = MouseInfo.getPointerInfo().location
                        val panelBounds = controlPanel.bounds
                        panelBounds.location = controlPanel.locationOnScreen
                        
                        if (!panelBounds.contains(point)) {
                            controlPanel.isVisible = false
                            titleArtistLabel.isVisible = false
                        }
                    }
                }
            })
            
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    if (!isLocked && isDragging && dragStart != null) {
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
            
            // 初始状态隐藏控制面板
            controlPanel.isVisible = false
            titleArtistLabel.isVisible = false
            
            isVisible = true
        }
    }
    
    private fun createTopControlBar(): JPanel {
        return JPanel(BorderLayout()).apply {
            background = Color(0, 0, 0, 180)
            isOpaque = true
            border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
            preferredSize = Dimension(frame.width, 40)
            
            // 左侧歌曲信息
            titleArtistLabel = JLabel("", SwingConstants.LEFT).apply {
                foreground = Color.WHITE
                font = Font("微软雅黑", Font.PLAIN, 14)
            }
            
            // 中间控制按钮
            controlPanel = JPanel(FlowLayout(FlowLayout.CENTER, 10, 0)).apply {
                background = Color(0, 0, 0, 0)
                isOpaque = false
                
                // 添加上一曲按钮
                val prevButton = JButton("◀").apply {
                    font = Font("Segoe UI Symbol", Font.BOLD, 14)
                    foreground = Color.WHITE
                    background = Color(0, 0, 0, 100)
                    border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
                    addActionListener { sendMediaCommand("/api/previous-track") }
                }
                
                // 添加播放/暂停按钮
                playPauseButton = JButton("▶").apply {
                    font = Font("Segoe UI Symbol", Font.BOLD, 14)
                    foreground = Color.WHITE
                    background = Color(0, 0, 0, 100)
                    border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
                    addActionListener { sendMediaCommand("/api/play-pause") }
                }
                
                // 添加下一曲按钮
                val nextButton = JButton("▶").apply {
                    font = Font("Segoe UI Symbol", Font.BOLD, 14)
                    foreground = Color.WHITE
                    background = Color(0, 0, 0, 100)
                    border = BorderFactory.createEmptyBorder(5, 10, 5, 10)
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
                lockButton = JButton("🔒").apply {
                    font = Font("Segoe UI Symbol", Font.PLAIN, 14)
                    foreground = Color.WHITE
                    background = Color(0, 0, 0, 100)
                    border = BorderFactory.createEmptyBorder(5, 8, 5, 8)
                    addActionListener { toggleLock() }
                }
                
                // 设置按钮
                settingsButton = JButton("⚙").apply {
                    font = Font("Segoe UI Symbol", Font.PLAIN, 14)
                    foreground = Color.WHITE
                    background = Color(0, 0, 0, 100)
                    border = BorderFactory.createEmptyBorder(5, 8, 5, 8)
                    addActionListener { showSettingsDialog() }
                }
                
                // 最小化按钮
                minimizeButton = JButton("−").apply {
                    font = Font("Segoe UI Symbol", Font.BOLD, 16)
                    foreground = Color.WHITE
                    background = Color(0, 0, 0, 100)
                    border = BorderFactory.createEmptyBorder(5, 8, 5, 8)
                    addActionListener { frame.isVisible = false }
                }
                
                add(lockButton)
                add(settingsButton)
                add(minimizeButton)
            }
            
            add(titleArtistLabel, BorderLayout.WEST)
            add(controlPanel, BorderLayout.CENTER)
            add(rightPanel, BorderLayout.EAST)
        }
    }
    
    private fun toggleLock() {
        isLocked = !isLocked
        
        if (isLocked) {
            lockButton.text = "🔒"
            controlPanel.isVisible = false
            titleArtistLabel.isVisible = false
        } else {
            lockButton.text = "🔓"
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
        
        // 使用英文菜单项避免乱码
        val popup = PopupMenu()
        
        // 添加显示/隐藏菜单
        val toggleItem = MenuItem("Show/Hide")
        toggleItem.addActionListener { frame.isVisible = !frame.isVisible }
        
        // 添加锁定/解锁菜单
        val lockItem = MenuItem(if (isLocked) "Unlock" else "Lock")
        lockItem.addActionListener { toggleLock() }
        
        // 添加设置菜单
        val settingsItem = MenuItem("Settings")
        settingsItem.addActionListener { showSettingsDialog() }
        
        // 添加退出菜单
        val exitItem = MenuItem("Exit")
        exitItem.addActionListener { exitApplication() }
        
        popup.add(toggleItem)
        popup.add(lockItem)
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
        val dialog = JDialog(frame, "Desktop Lyrics Settings", true)
        dialog.layout = BorderLayout()
        dialog.setSize(500, 500)
        dialog.setLocationRelativeTo(frame)
        
        // 使用现代化外观
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        val tabbedPane = JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT).apply {
            border = EmptyBorder(10, 10, 10, 10)
            background = Color(240, 240, 240)
        }
        
        // 字体设置面板
        val fontPanel = createFontPanel(dialog)
        
        // 颜色设置面板
        val colorPanel = createColorPanel(dialog)
        
        // 其他设置面板
        val otherPanel = createOtherPanel(dialog)
        
        tabbedPane.addTab("Font", fontPanel)
        tabbedPane.addTab("Color", colorPanel)
        tabbedPane.addTab("Other", otherPanel)
        
        dialog.add(tabbedPane, BorderLayout.CENTER)
        
        // 添加关闭按钮
        val closeButton = JButton("Close").apply {
            font = Font("Segoe UI", Font.BOLD, 12)
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
            add(JLabel("Font Settings").apply { 
                font = Font("Segoe UI", Font.BOLD, 16)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridwidth = 1
            gbc.gridy++
            
            // 中文字体选择
            gbc.gridx = 0
            add(JLabel("Chinese Font:").apply { 
                font = Font("Segoe UI", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val chineseFontCombo = JComboBox(GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()).apply {
                selectedItem = chineseFont.family
                font = Font("Segoe UI", Font.PLAIN, 12)
                background = Color.WHITE
                renderer = DefaultListCellRenderer().apply {
                    font = Font("Segoe UI", Font.PLAIN, 12)
                }
            }
            add(chineseFontCombo, gbc)
            
            // 日文字体选择
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("Japanese Font:").apply { 
                font = Font("Segoe UI", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val japaneseFontCombo = JComboBox(GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()).apply {
                selectedItem = japaneseFont.family
                font = Font("Segoe UI", Font.PLAIN, 12)
                background = Color.WHITE
                renderer = DefaultListCellRenderer().apply {
                    font = Font("Segoe UI", Font.PLAIN, 12)
                }
            }
            add(japaneseFontCombo, gbc)
            
            // 英文字体选择
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("English Font:").apply { 
                font = Font("Segoe UI", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val englishFontCombo = JComboBox(GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()).apply {
                selectedItem = englishFont.family
                font = Font("Segoe UI", Font.PLAIN, 12)
                background = Color.WHITE
                renderer = DefaultListCellRenderer().apply {
                    font = Font("Segoe UI", Font.PLAIN, 12)
                }
            }
            add(englishFontCombo, gbc)
            
            // 字体大小
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("Font Size:").apply { 
                font = Font("Segoe UI", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val sizeSpinner = JSpinner(SpinnerNumberModel(chineseFont.size, 8, 48, 1)).apply {
                font = Font("Segoe UI", Font.PLAIN, 12)
            }
            add(sizeSpinner, gbc)
            
            // 字体样式
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("Font Style:").apply { 
                font = Font("Segoe UI", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val styleCombo = JComboBox(arrayOf("Normal", "Bold", "Italic")).apply {
                selectedIndex = when (chineseFont.style) {
                    Font.BOLD -> 1
                    Font.ITALIC -> 2
                    else -> 0
                }
                font = Font("Segoe UI", Font.PLAIN, 12)
                background = Color.WHITE
                renderer = DefaultListCellRenderer().apply {
                    font = Font("Segoe UI", Font.PLAIN, 12)
                }
            }
            add(styleCombo, gbc)
            
            // 应用按钮
            gbc.gridx = 0
            gbc.gridy++
            gbc.gridwidth = 2
            gbc.anchor = GridBagConstraints.CENTER
            val applyButton = JButton("Apply Font Settings").apply {
                font = Font("Segoe UI", Font.BOLD, 12)
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
            add(JLabel("Color Settings").apply { 
                font = Font("Segoe UI", Font.BOLD, 16)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridwidth = 1
            gbc.gridy++
            
            // 标题颜色
            gbc.gridx = 0
            add(JLabel("Title Color:").apply { 
                font = Font("Segoe UI", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val titleColorButton = JButton().apply {
                background = lyricsPanel.titleColor
                preferredSize = Dimension(80, 25)
                addActionListener { 
                    val color = JColorChooser.showDialog(dialog, "Choose Title Color", background)
                    if (color != null) {
                        background = color
                        lyricsPanel.titleColor = color
                    }
                }
            }
            add(titleColorButton, gbc)
            
            // 艺术家颜色
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("Artist Color:").apply { 
                font = Font("Segoe UI", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val artistColorButton = JButton().apply {
                background = lyricsPanel.artistColor
                preferredSize = Dimension(80, 25)
                addActionListener { 
                    val color = JColorChooser.showDialog(dialog, "Choose Artist Color", background)
                    if (color != null) {
                        background = color
                        lyricsPanel.artistColor = color
                    }
                }
            }
            add(artistColorButton, gbc)
            
            // 歌词颜色
            gbc.gridx = 0
            gbc.gridy++
            add(JLabel("Lyric Color:").apply { 
                font = Font("Segoe UI", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val lyricColorButton = JButton().apply {
                background = lyricsPanel.lyricColor
                preferredSize = Dimension(80, 25)
                addActionListener { 
                    val color = JColorChooser.showDialog(dialog, "Choose Lyric Color", background)
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
            add(JLabel("Highlight Color:").apply { 
                font = Font("Segoe UI", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val highlightColorButton = JButton().apply {
                background = lyricsPanel.highlightColor
                preferredSize = Dimension(80, 25)
                addActionListener { 
                    val color = JColorChooser.showDialog(dialog, "Choose Highlight Color", background)
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
            add(JLabel("Background Color:").apply { 
                font = Font("Segoe UI", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val bgColorButton = JButton().apply {
                background = lyricsPanel.backgroundColor
                preferredSize = Dimension(80, 25)
                addActionListener { 
                    val color = JColorChooser.showDialog(dialog, "Choose Background Color", background)
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
            add(JLabel("Other Settings").apply { 
                font = Font("Segoe UI", Font.BOLD, 16)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridwidth = 1
            gbc.gridy++
            
            // 透明度设置
            gbc.gridx = 0
            add(JLabel("Window Transparency:").apply { 
                font = Font("Segoe UI", Font.PLAIN, 12)
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
            add(JLabel("Animation Speed:").apply { 
                font = Font("Segoe UI", Font.PLAIN, 12)
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
            add(JLabel("Lyric Alignment:").apply { 
                font = Font("Segoe UI", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val alignmentCombo = JComboBox(arrayOf("Center", "Left", "Right")).apply {
                selectedIndex = when (lyricsPanel.alignment) {
                    LyricsPanel.Alignment.LEFT -> 1
                    LyricsPanel.Alignment.RIGHT -> 2
                    else -> 0
                }
                font = Font("Segoe UI", Font.PLAIN, 12)
                background = Color.WHITE
                renderer = DefaultListCellRenderer().apply {
                    font = Font("Segoe UI", Font.PLAIN, 12)
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
            add(JLabel("Title-Artist Format:").apply { 
                font = Font("Segoe UI", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val formatCombo = JComboBox(arrayOf("Title - Artist", "Artist - Title")).apply {
                selectedIndex = titleArtistFormat
                font = Font("Segoe UI", Font.PLAIN, 12)
                background = Color.WHITE
                renderer = DefaultListCellRenderer().apply {
                    font = Font("Segoe UI", Font.PLAIN, 12)
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
            add(JLabel("Text Shadow Effect:").apply { 
                font = Font("Segoe UI", Font.PLAIN, 12)
                foreground = Color(60, 60, 60)
            }, gbc)
            
            gbc.gridx = 1
            val shadowCheckBox = JCheckBox("Enable", lyricsPanel.useShadow).apply {
                font = Font("Segoe UI", Font.PLAIN, 12)
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
                title = nowPlaying.title ?: "No song playing",
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
    var animationSpeed = 10
    var alignment = Alignment.CENTER
    var useShadow = true // 是否使用文字阴影
    
    // 字体设置
    private var chineseFont = Font("微软雅黑", Font.BOLD, 24)
    private var japaneseFont = Font("MS Gothic", Font.BOLD, 24)
    private var englishFont = Font("Arial", Font.BOLD, 24)
    
    // 颜色设置
    var titleColor = Color.WHITE
    var artistColor = Color.WHITE
    var lyricColor = Color.WHITE
    var highlightColor = Color(255, 215, 0) // 金色
    var backgroundColor = Color(0, 0, 0) // 背景颜色
    
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
    
    enum class Alignment {
        LEFT, CENTER, RIGHT
    }
    
    init {
        background = Color(0, 0, 0, (255 * transparency).roundToInt())
        isOpaque = false
        border = BorderFactory.createEmptyBorder(10, 20, 10, 20)
        
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
        prevLineIndex = -1
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
            
            // 设置平滑动画目标值
            targetPosition = newIndex.toFloat()
            targetAlpha = 1f
        }
        
        repaint()
    }
    
    fun toggleTransparency() {
        transparency = if (transparency < 0.5f) 0.8f else 0.3f
        background = Color(backgroundColor.red, backgroundColor.green, backgroundColor.blue, (255 * transparency).roundToInt())
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
        
        // 绘制标题和艺术家
        g2d.font = getFontForText(title)
        
        // 使用阴影效果提高白色背景下的可见性
        if (useShadow) {
            g2d.color = Color(0, 0, 0, 150)
            val titleX = getTextXPosition(g2d, title)
            g2d.drawString(title, titleX + 1, 30 + 1)
        }
        
        g2d.color = titleColor
        val titleX = getTextXPosition(g2d, title)
        g2d.drawString(title, titleX, 30)
        
        g2d.font = getFontForText(artist)
        
        // 使用阴影效果提高白色背景下的可见性
        if (useShadow) {
            g2d.color = Color(0, 0, 0, 150)
            val artistX = getTextXPosition(g2d, artist)
            g2d.drawString(artist, artistX + 1, 50 + 1)
        }
        
        g2d.color = artistColor
        val artistX = getTextXPosition(g2d, artist)
        g2d.drawString(artist, artistX, 50)
        
        // 绘制歌词
        val yPos = height - 50
        
        if (parsedLyrics.isNotEmpty()) {
            // 绘制上一行歌词（淡出）
            if (prevLineIndex in parsedLyrics.indices) {
                val alpha = (255 * (1 - smoothAlpha)).toInt()
                val color = Color(lyricColor.red, lyricColor.green, lyricColor.blue, alpha)
                
                g2d.color = color
                g2d.font = getFontForText(parsedLyrics[prevLineIndex].text)
                val prevLine = parsedLyrics[prevLineIndex].text
                val prevX = getTextXPosition(g2d, prevLine)
                val prevY = yPos - (40 * smoothAlpha).toInt()
                
                // 使用阴影效果
                if (useShadow) {
                    g2d.color = Color(0, 0, 0, alpha / 2)
                    g2d.drawString(prevLine, prevX + 1, prevY + 1)
                }
                
                g2d.color = color
                g2d.drawString(prevLine, prevX, prevY)
            }
            
            // 绘制当前行歌词（淡入）
            if (currentLineIndex in parsedLyrics.indices) {
                val alpha = (255 * smoothAlpha).toInt()
                val color = Color(highlightColor.red, highlightColor.green, highlightColor.blue, alpha)
                
                g2d.color = color
                g2d.font = getFontForText(parsedLyrics[currentLineIndex].text)
                val currentLine = parsedLyrics[currentLineIndex].text
                val currentX = getTextXPosition(g2d, currentLine)
                val currentY = yPos - (20 * (1 - smoothAlpha)).toInt()
                
                // 使用阴影效果
                if (useShadow) {
                    g2d.color = Color(0, 0, 0, alpha / 2)
                    g2d.drawString(currentLine, currentX + 1, currentY + 1)
                }
                
                g2d.color = color
                g2d.drawString(currentLine, currentX, currentY)
            }
            
            // 绘制下一行歌词
            if (currentLineIndex < parsedLyrics.size - 1) {
                g2d.color = Color(lyricColor.red, lyricColor.green, lyricColor.blue, 150)
                g2d.font = getFontForText(parsedLyrics[currentLineIndex + 1].text)
                val nextLine = parsedLyrics[currentLineIndex + 1].text
                val nextX = getTextXPosition(g2d, nextLine)
                
                // 使用阴影效果
                if (useShadow) {
                    g2d.color = Color(0, 0, 0, 75)
                    g2d.drawString(nextLine, nextX + 1, yPos + 40 + 1)
                }
                
                g2d.color = Color(lyricColor.red, lyricColor.green, lyricColor.blue, 150)
                g2d.drawString(nextLine, nextX, yPos + 40)
            }
        } else if (lyric.isNotEmpty()) {
            // 绘制静态歌词
            g2d.color = lyricColor
            g2d.font = getFontForText(lyric)
            val lyricX = getTextXPosition(g2d, lyric)
            
            // 使用阴影效果
            if (useShadow) {
                g2d.color = Color(0, 0, 0, 150)
                g2d.drawString(lyric, lyricX + 1, yPos + 1)
            }
            
            g2d.color = lyricColor
            g2d.drawString(lyric, lyricX, yPos)
        } else {
            // 没有歌词时的提示
            g2d.color = Color.LIGHT_GRAY
            g2d.font = chineseFont
            val message = "歌词加载中..."
            val messageX = getTextXPosition(g2d, message)
            
            // 使用阴影效果
            if (useShadow) {
                g2d.color = Color(0, 0, 0, 150)
                g2d.drawString(message, messageX + 1, yPos + 1)
            }
            
            g2d.color = Color.LIGHT_GRAY
            g2d.drawString(message, messageX, yPos)
        }
    }
    
    data class LyricLine(val time: Long, val text: String)
}
