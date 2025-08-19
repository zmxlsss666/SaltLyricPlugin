package com.zmxl.plugin.control

import com.sun.jna.*
import com.sun.jna.platform.win32.*
import com.sun.jna.win32.W32APIOptions
import com.zmxl.plugin.playback.PlaybackStateHolder
import com.zmxl.plugin.playback.SpwPlaybackExtension
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

object SmtcController {
    private val playbackExtension = SpwPlaybackExtension()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var hwnd: WinDef.HWND? = null
    private var windowClass: String = "SPW_MEDIA_CONTROL_CLASS"
    private var messageLoopThread: Thread? = null
    private var isRunning = false

    // Windows API常量定义
    private val WM_APPCOMMAND = 0x0319
    private val APPCOMMAND_MEDIA_PLAY_PAUSE = 14
    private val APPCOMMAND_MEDIA_NEXTTRACK = 11
    private val APPCOMMAND_MEDIA_PREVIOUSTRACK = 12
    private val APPCOMMAND_VOLUME_UP = 10
    private val APPCOMMAND_VOLUME_DOWN = 9
    private val APPCOMMAND_VOLUME_MUTE = 8
    private val CW_USEDEFAULT = 0x80000000.toInt()

    fun init() {
        if (isRunning) return
        
        isRunning = true
        registerWindowClass()
        createWindow()
        startMessageLoop()
        registerMediaKeys()
        
        println("SMTC控制器初始化完成")
    }

    private fun registerWindowClass() {
        val hInstance = Kernel32.INSTANCE.GetModuleHandle(null)
        
        val wc = WinUser.WNDCLASSEX()
        wc.cbSize = wc.size()
        wc.lpfnWndProc = object : WinUser.WindowProc {
            override fun callback(hwnd: WinDef.HWND?, uMsg: Int, wParam: WinDef.WPARAM?, lParam: WinDef.LPARAM?): WinDef.LRESULT {
                return when (uMsg) {
                    WM_APPCOMMAND -> handleAppCommand(hwnd, wParam, lParam)
                    WinUser.WM_DESTROY -> {
                        User32.INSTANCE.PostQuitMessage(0)
                        WinDef.LRESULT(0)
                    }
                    else -> User32.INSTANCE.DefWindowProc(hwnd, uMsg, wParam, lParam)
                }
            }
        }
        wc.hInstance = hInstance
        wc.lpszClassName = windowClass
        
        val atom = User32.INSTANCE.RegisterClassEx(wc)
        if (atom.toInt() == 0) {
            throw RuntimeException("注册窗口类失败，错误码: ${Kernel32.INSTANCE.GetLastError()}")
        }
    }

    private fun createWindow() {
        val hInstance = Kernel32.INSTANCE.GetModuleHandle(null)
        hwnd = User32.INSTANCE.CreateWindowEx(
            0,
            windowClass,
            "SPW Media Control Window",
            0,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            100,
            100,
            null,
            null,
            hInstance,
            null as WinDef.LPVOID?
        )
        
        if (hwnd == null || hwnd!!.pointer == Pointer.NULL) {
            throw RuntimeException("创建窗口失败，错误码: ${Kernel32.INSTANCE.GetLastError()}")
        }
    }

    private fun startMessageLoop() {
        messageLoopThread = Thread { 
            val msg = WinUser.MSG()
            while (User32.INSTANCE.GetMessage(msg, null, 0, 0) != 0) {
                User32.INSTANCE.TranslateMessage(msg)
                User32.INSTANCE.DispatchMessage(msg)
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun registerMediaKeys() {
        executor.scheduleAtFixedRate({
            updateSystemMediaStatus()
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun handleAppCommand(hwnd: WinDef.HWND?, wParam: WinDef.WPARAM?, lParam: WinDef.LPARAM?): WinDef.LRESULT {
        val command = (lParam!!.toInt() shr 16) and 0xFF
        
        when (command) {
            // 处理播放/暂停命令
            APPCOMMAND_MEDIA_PLAY_PAUSE -> {
                playbackExtension.togglePlayback()
                // 同步更新播放状态到状态持有者
                PlaybackStateHolder.isPlaying = !PlaybackStateHolder.isPlaying
            }
            // 处理下一曲命令
            APPCOMMAND_MEDIA_NEXTTRACK -> handleNextTrack()
            // 处理上一曲命令
            APPCOMMAND_MEDIA_PREVIOUSTRACK -> handlePreviousTrack()
            APPCOMMAND_VOLUME_UP -> handleVolumeUp()
            APPCOMMAND_VOLUME_DOWN -> handleVolumeDown()
            APPCOMMAND_VOLUME_MUTE -> handleMute()
        }
        
        return WinDef.LRESULT(0)
    }

    private fun updateSystemMediaStatus() {
        val currentMedia = PlaybackStateHolder.currentMedia
        if (currentMedia != null) {
            // 实时更新系统媒体状态（包括播放/暂停状态）
            println("${if (PlaybackStateHolder.isPlaying) "正在播放" else "已暂停"}: ${currentMedia.title} - ${currentMedia.artist}")
        }
    }

    // 实现实际下一曲逻辑
    fun handleNextTrack() {
        try {
            println("执行下一曲操作")
            // 调用播放扩展的下一曲方法
            playbackExtension.next()
            // 更新当前媒体信息（假设切换后会自动更新，这里可根据实际情况调整）
            val newMedia = PlaybackStateHolder.currentMedia
            if (newMedia != null) {
                println("切换到下一曲: ${newMedia.title}")
            }
        } catch (e: Exception) {
            println("下一曲操作失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // 实现实际上一曲逻辑
    fun handlePreviousTrack() {
        try {
            println("执行上一曲操作")
            // 调用播放扩展的上一曲方法
            playbackExtension.previous()
            // 更新当前媒体信息
            val newMedia = PlaybackStateHolder.currentMedia
            if (newMedia != null) {
                println("切换到上一曲: ${newMedia.title}")
            }
        } catch (e: Exception) {
            println("上一曲操作失败: ${e.message}")
            e.printStackTrace()
        }
    }

    // 改为public以便HttpServer访问
    fun handleVolumeUp() {
        val newVolume = minOf(PlaybackStateHolder.volume + 5, 100)
        playbackExtension.setVolume(newVolume)
        PlaybackStateHolder.volume = newVolume // 同步音量状态
    }

    // 改为public以便HttpServer访问
    fun handleVolumeDown() {
        val newVolume = maxOf(PlaybackStateHolder.volume - 5, 0)
        playbackExtension.setVolume(newVolume)
        PlaybackStateHolder.volume = newVolume // 同步音量状态
    }

    fun handleMute() {
        PlaybackStateHolder.toggleMute()
        playbackExtension.setVolume(PlaybackStateHolder.volume)
    }

    fun shutdown() {
        if (!isRunning) return
        
        isRunning = false
        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.SECONDS)
        
        if (hwnd != null && hwnd!!.pointer != Pointer.NULL) {
            User32.INSTANCE.DestroyWindow(hwnd)
            hwnd = null
        }
        
        val hInstance = Kernel32.INSTANCE.GetModuleHandle(null)
        User32.INSTANCE.UnregisterClass(windowClass, hInstance)
        
        messageLoopThread?.join(1000)
    }

    interface User32 : com.sun.jna.platform.win32.User32 {
        override fun RegisterClassEx(lpwcx: WinUser.WNDCLASSEX): WinDef.ATOM
        
        override fun CreateWindowEx(
            dwExStyle: Int,
            lpClassName: String,
            lpWindowName: String,
            dwStyle: Int,
            x: Int,
            y: Int,
            nWidth: Int,
            nHeight: Int,
            hWndParent: WinDef.HWND?,
            hMenu: WinDef.HMENU?,
            hInstance: WinDef.HINSTANCE?,
            lpParam: WinDef.LPVOID?
        ): WinDef.HWND

        companion object {
            val INSTANCE: User32 = Native.load("user32", User32::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}
