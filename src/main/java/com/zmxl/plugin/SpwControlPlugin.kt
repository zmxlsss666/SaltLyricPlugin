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

class SpwControlPlugin(wrapper: PluginWrapper) : Plugin(wrapper) {
    private lateinit var httpServer: HttpServer
    private var lyricsApp: DesktopLyrics? = null

    override fun start() {
        super.start()
        
        println("SPW Control Plugin 开始启动...")
        
        // 初始化ConfigManager
        try {
            val configManager = WorkshopApi.instance.manager.createConfigManager(wrapper.pluginId)
            println("ConfigManager 初始化成功")
            
            // 启动桌面歌词应用并传递ConfigManager
            lyricsApp = DesktopLyrics
            lyricsApp?.setConfigManager(configManager)
            println("DesktopLyrics ConfigManager 设置成功")
        } catch (e: Exception) {
            println("ConfigManager 初始化失败: ${e.message}")
            // 如果无法创建ConfigManager，DesktopLyrics将使用旧式配置文件
            lyricsApp = DesktopLyrics
        }
        
        // 启动HTTP服务器
        httpServer = HttpServer(35373)
        httpServer.start()
        println("HTTP服务器启动成功，端口: 35373")
        
        // 初始化SMTC控制器
        SmtcController.init()
        println("SMTC控制器初始化成功")
        
        // 启动桌面歌词
        lyricsApp?.start()
        println("桌面歌词启动成功")

        println("SPW Control Plugin 启动完成")
    }

    override fun stop() {
        super.stop()
        println("SPW Control Plugin 开始停止...")
        
        httpServer.stop()
        println("HTTP服务器已停止")
        
        SmtcController.shutdown()
        println("SMTC控制器已关闭")
        
        // 停止歌词应用
        lyricsApp?.stop()
        lyricsApp = null
        println("桌面歌词已停止")

        println("SPW Control Plugin 已完全停止")
    }
}
