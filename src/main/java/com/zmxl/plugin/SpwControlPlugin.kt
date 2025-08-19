package com.zmxl.plugin

import org.pf4j.Plugin
import org.pf4j.PluginWrapper
import com.zmxl.plugin.server.HttpServer
import com.zmxl.plugin.control.SmtcController
import com.zmxl.plugin.lyrics.DesktopLyrics

class SpwControlPlugin(wrapper: PluginWrapper) : Plugin(wrapper) {
    private lateinit var httpServer: HttpServer
    private var lyricsApp: DesktopLyrics? = null

    override fun start() {
        super.start()
        httpServer = HttpServer(35373)
        httpServer.start()
        SmtcController.init()
        
        // 启动桌面歌词应用
        lyricsApp = DesktopLyrics
        lyricsApp?.start()

        println("SPW Control Plugin started with HTTP server on port 35373")
    }

    override fun stop() {
        super.stop()
        httpServer.stop()
        SmtcController.shutdown()
        
        // 停止歌词应用
        lyricsApp?.stop()
        lyricsApp = null

        println("SPW Control Plugin stopped")
    }
}
