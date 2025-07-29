package com.zmxl.plugin

import org.pf4j.Plugin
import org.pf4j.PluginWrapper
import com.zmxl.plugin.server.HttpServer
import com.zmxl.plugin.control.SmtcController  

class SpwControlPlugin(wrapper: PluginWrapper) : Plugin(wrapper) {
    private lateinit var httpServer: HttpServer

    override fun start() {
        super.start()
        httpServer = HttpServer(35373)
        httpServer.start()
        SmtcController.init()  
        println("SPW Control Plugin started with HTTP server on port 35373")
    }

    override fun stop() {
        super.stop()
        httpServer.stop()
        SmtcController.shutdown()  
        println("SPW Control Plugin stopped")
    }
}
