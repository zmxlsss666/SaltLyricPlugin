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
package com.zmxl.plugin

import org.pf4j.Plugin
import org.pf4j.PluginWrapper
import com.zmxl.plugin.server.HttpServer
import com.zmxl.plugin.control.SmtcController
import com.zmxl.plugin.lyrics.DesktopLyrics
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi

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
        
        // 初始化ConfigManager并传递给DesktopLyrics
        try {
            val configManager = WorkshopApi.instance.manager.createConfigManager(wrapper.pluginId)
            lyricsApp?.setConfigManager(configManager)
        } catch (e: Exception) {
            println("Failed to create ConfigManager: ${e.message}")
            // 如果无法创建ConfigManager，DesktopLyrics将使用旧式配置文件
        }
        
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