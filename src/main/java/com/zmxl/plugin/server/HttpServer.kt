    /**
     * 实际控制页面
     */
    class ControlServlet : HttpServlet() {
        @Throws(IOException::class)
        override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
            resp.contentType = "text/html;charset=UTF-8"
            resp.characterEncoding = "UTF-8"
            
            // 返回控制界面HTML内容
            resp.writer.write("""
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Salt Player 控制器</title>
                <style>
                    /* 完整样式实现 */
                    :root {
                        --primary: #3B82F6;
                        --secondary: #10B981;
                        --dark: #1E293B;
                        --light: #F8FAFC;
                        --accent: #8B5CF6;
                    }
                    
                    body {
                        background: linear-gradient(to bottom right, var(--dark), #0f172a);
                        color: var(--light);
                        font-family: 'Segoe UI', system-ui, sans-serif;
                        min-height: 100vh;
                        margin: 0;
                        padding: 20px;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                    }
                    
                    .control-container {
                        background: rgba(255, 255, 255, 0.1);
                        backdrop-filter: blur(10px);
                        border-radius: 16px;
                        box-shadow: 0 4px 30px rgba(0, 0, 0, 0.1);
                        border: 1px solid rgba(255, 255, 255, 0.2);
                        width: 100%;
                        max-width: 500px;
                        padding: 30px;
                        box-sizing: border-box;
                    }
                    
                    .status-container {
                        display: flex;
                        align-items: center;
                        margin-bottom: 20px;
                    }
                    
                    .status-indicator {
                        width: 12px;
                        height: 12px;
                        border-radius: 50%;
                        margin-right: 10px;
                    }
                    
                    .status-connected {
                        background-color: #10B981;
                    }
                    
                    .status-disconnected {
                        background-color: #EF4444;
                        animation: pulse 1.5s infinite;
                    }
                    
                    @keyframes pulse {
                        0% { opacity: 1; }
                        50% { opacity: 0.5; }
                        100% { opacity: 1; }
                    }
                    
                    .track-info {
                        text-align: center;
                        margin-bottom: 30px;
                    }
                    
                    .track-title {
                        font-size: 24px;
                        font-weight: bold;
                        margin-bottom: 5px;
                        overflow: hidden;
                        text-overflow: ellipsis;
                        white-space: nowrap;
                    }
                    
                    .track-artist {
                        font-size: 18px;
                        color: #94a3b8;
                    }
                    
                    .controls {
                        display: flex;
                        justify-content: space-between;
                        margin-bottom: 30px;
                    }
                    
                    .control-btn {
                        background: none;
                        border: none;
                        color: var(--light);
                        font-size: 24px;
                        cursor: pointer;
                        width: 60px;
                        height: 60px;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        border-radius: 50%;
                        transition: all 0.2s;
                    }
                    
                    .control-btn:hover {
                        background: rgba(255, 255, 255, 0.1);
                        transform: scale(1.1);
                    }
                    
                    .control-btn:active {
                        transform: scale(0.95);
                    }
                    
                    .play-pause-btn {
                        background-color: var(--primary);
                        width: 80px;
                        height: 80px;
                        box-shadow: 0 4px 20px rgba(59, 130, 246, 0.3);
                    }
                    
                    .volume-controls {
                        display: flex;
                        justify-content: center;
                        gap: 20px;
                    }
                    
                    .status-message {
                        text-align: center;
                        margin-top: 20px;
                        min-height: 24px;
                        transition: opacity 0.3s;
                    }
                    
                    .message-success {
                        color: #10B981;
                    }
                    
                    .message-error {
                        color: #EF4444;
                    }
                </style>
            </head>
            <body>
                <div class="control-container">
                    <div class="status-container">
                        <div id="status-indicator" class="status-indicator status-disconnected"></div>
                        <span id="status-text">未连接到API</span>
                    </div>
                    
                    <div class="track-info">
                        <div id="track-title" class="track-title">等待连接...</div>
                        <div id="track-artist" class="track-artist">未知艺术家</div>
                    </div>
                    
                    <div class="controls">
                        <button id="prev-btn" class="control-btn">
                            <svg width="24" height="24" fill="currentColor" viewBox="0 0 16 16">
                                <path d="M8 0a8 8 0 1 0 0 16A8 8 0 0 0 8 0zm3.5 7.5a.5.5 0 0 1 0 1H11v2.5a.5.5 0 0 1-1 0V8.5H8v3a.5.5 0 0 1-1 0v-7a.5.5 0 0 1 1 0v3h2V4.5a.5.5 0 0 1 1 0V7h.5z"/>
                            </svg>
                        </button>
                        
                        <button id="play-pause-btn" class="control-btn play-pause-btn">
                            <svg id="play-icon" width="32" height="32" fill="currentColor" viewBox="0 0 16 16">
                                <path d="M8 0a8 8 0 1 0 0 16A8 8 0 0 0 8 0zM7 11.5V4.5a.5.5 0 0 1 .757-.429l5 3.5a.5.5 0 0 1 0 .858l-5 3.5A.5.5 0 0 1 7 11.5z"/>
                            </svg>
                            <svg id="pause-icon" width="32" height="32" fill="currentColor" viewBox="0 0 16 16" style="display:none;">
                                <path d="M8 0a8 8 0 1 0 0 16A8 8 0 0 0 8 0zM6 11.5a.5.5 0 0 1-.5.5h-1a.5.5 0 0 1-.5-.5v-7a.5.5 0 0 1 .5-.5h1a.5.5 0 0 1 .5.5v7zm4 0a.5.5 0 0 1-.5.5h-1a.5.5 0 0 1-.5-.5v-7a.5.5 0 0 1 .5-.5h1a.5.5 0 0 1 .5.5v7z"/>
                            </svg>
                        </button>
                        
                        <button id="next-btn" class="control-btn">
                            <svg width="24" height="24" fill="currentColor" viewBox="0 0 16 16">
                                <path d="M8 0a8 8 0 1 0 0 16A8 8 0 0 0 8 0zM4.5 7.5a.5.5 0 0 1 0-1H5v-2a.5.5 0 0 1 1 0v2h.5a.5.5 0 0 1 0 1H6v3.5a.5.5 0 0 1-1 0V7.5h-.5zm7 0a.5.5 0 0 1 0 1H11v3.5a.5.5 0 0 1-1 0V8.5H8v3a.5.5 0 0 1-1 0v-7a.5.5 0 0 1 1 0v3h2V7.5h.5z"/>
                            </svg>
                        </button>
                    </div>
                    
                    <div class="volume-controls">
                        <button id="volume-down-btn" class="control-btn">
                            <svg width="20" height="20" fill="currentColor" viewBox="0 0 16 16">
                                <path d="M8 0a8 8 0 1 0 0 16A8 8 0 0 0 8 0zM5 8a1 1 0 1 1 2 0 1 1 0 0 1-2 0zm6-1a1 1 0 1 1 0 2 1 1 0 0 1 0-2zM4.5 6h7a.5.5 0 0 1 0 1h-7a.5.5 0 0 1 0-1zm0 3h7a.5.5 0 0 1 0 1h-7a.5.5 0 0 1 0-1z"/>
                            </svg>
                        </button>
                        
                        <button id="mute-btn" class="control-btn">
                            <svg id="mute-icon" width="20" height="20" fill="currentColor" viewBox="0 极
