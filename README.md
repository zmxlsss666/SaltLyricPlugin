# SaltLyricPlugin
一个适用于 Salt Player For Windows 的桌面歌词插件，提供桌面歌词显示及 HTTP API 控制功能。
## 功能特点
*   桌面歌词显示：支持中文、日文、英文等多语言字体自动适配，支持逐字歌词
*   歌词翻译支持：自动识别并显示带翻译的歌词（原文 + 翻译合并显示）
*   在线歌词获取：对于未内嵌歌词的歌曲，通过网易云、QQ、酷狗等在线音乐平台获取歌词
*   HTTP API 接口：提供音乐控制及歌词获取接口，方便第三方集成[PS:真的会有人集成吗……]
## 安装方法
1.  下载插件
2.  进入SPW→设置→创意工坊→模组管理→导入→SaltLyricPlugin→启用，[初次使用必须启用后重启，否则会导致功能异常]
## 编译
```
gradlew plugin
```
## 软件截图
### 桌面歌词
#### 未锁定：
![未锁定](https://raw.githubusercontent.com/zmxlsss666/SaltLyricPlugin/refs/heads/main/images/1.png)
#### 锁定
锁定后可在托盘菜单中解锁
![锁定](https://raw.githubusercontent.com/zmxlsss666/SaltLyricPlugin/refs/heads/main/images/2.png)
#### 设置
![设置1](https://raw.githubusercontent.com/zmxlsss666/SaltLyricPlugin/refs/heads/main/images/settings1.png)
![设置2](https://raw.githubusercontent.com/zmxlsss666/SaltLyricPlugin/refs/heads/main/images/settings2.png)
![设置3](https://raw.githubusercontent.com/zmxlsss666/SaltLyricPlugin/refs/heads/main/images/settings3.png)
### Web 控制界面
浏览器访问 `http://localhost:35373` 即可进入控制界面，（目前歌曲封面未使用歌曲内嵌封面，有概率无法获取）
![Web控制界面](https://raw.githubusercontent.com/zmxlsss666/SaltLyricPlugin/refs/heads/main/images/web.png)
## API 文档
插件提供以下 HTTP API 接口，方便第三方应用集成：
### 1. 获取当前播放信息
*   **端点**：`/api/now-playing`
*   **方法**：`GET`
*   **响应示例**：
```
{
     "status": "success",
     "title": "歌曲标题",
     "artist": "艺术家",
     "album": "专辑",
     "isPlaying": true,
     "position": 123456,
     "volume": 80,
     "timestamp": 1620000000000
}
```
### 2. 播放 / 暂停切换
*   **端点**：`/api/play-pause`
*   **方法**：`GET`
*   **响应示例**：
```
{
     "status": "success",
     "action": "play\_pause\_toggled",
     "isPlaying": true,
     "message": "已开始播放"
}
```
### 3. 下一曲
*   **端点**：`/api/next-track`
*   **方法**：`GET`
*   **响应示例**：
```
{
     "status": "success",
     "action": "next\_track",
     "newTrack": "下一首歌曲",
     "message": "已切换到下一曲"
}
```
### 4. 上一曲
*   **端点**：`/api/previous-track`
*   **方法**：`GET`
*   **响应示例**：
```
{
     "status": "success",
     "action": "previous\_track",
     "newTrack": "上一首歌曲",
     "message": "已切换到上一曲"
}
```
### 5. 音量调节
*   **音量增加**：`/api/volume/up`（GET）
*   **音量减少**：`/api/volume/down`（GET）
*   **静音切换**：`/api/mute`（GET）
### 6. 获取歌词（使用歌曲文件内嵌歌词）
*   **端点**：`/api/lyric`
*   **方法**：`GET`
*   **响应示例**：
```
{
     "status": "success",
     "lyric": "\[00:00.00]歌词内容..."
}
```
*   其他来源歌词：
    *   网易云歌词：`/api/lyric163`
    *   QQ 音乐歌词：`/api/lyricqq`
    *   酷狗音乐歌词：`/api/lyrickugou`
    *   SPW内置歌词（仅当前播放行）：`/api/lyricspw`
### 7. 获取当前播放位置
*   **端点**：`/api/current-position`
*   **方法**：`GET`
*   **响应示例**：
```
{
     "status": "success",
     "position": 123456
}
```

# 致谢
本项目的开发离不开以下开源项目的支持与贡献，在此表示诚挚的感谢：
*   **Kotlin**
    [https://kotlinlang.org/](https://kotlinlang.org/)
    
    项目主要开发语言，提供了简洁、安全的编程体验，其 JVM 生态为项目开发提供了坚实基础。
    
    许可证：[Apache License 2.0](https://github.com/JetBrains/kotlin/blob/master/LICENSE.txt)
*   **spw-workshop-api**
    [https://github.com/Moriafly/spw-workshop-api](https://github.com/Moriafly/spw-workshop-api)
    
    提供 Salt Player For Windows 插件开发的核心接口，是本插件与播放器交互的基础。
    
    许可证：[Apache License 2.0](https://github.com/Moriafly/spw-workshop-api/blob/main/LICENSE)
*   **Spark Java**
    [https://github.com/perwendel/spark](https://github.com/perwendel/spark)
    
    轻量级 Java Web 框架，用于实现本项目的 HTTP 服务及 API 接口，简化了 Web 服务开发流程。
    
    许可证：[Apache License 2.0](https://github.com/perwendel/spark/blob/master/LICENSE)
*   **JNA (Java Native Access)**
    [https://github.com/java-native-access/jna](https://github.com/java-native-access/jna)
    
    提供 Java 与原生代码的交互能力，为本项目实现 Windows 系统媒体控制（如媒体键响应）提供了关键支持。
    
    许可证：[Apache License 2.0](https://github.com/java-native-access/jna/blob/master/LICENSE)
*   **Gson**
    [https://github.com/google/gson](https://github.com/google/gson)
    
    Google 开发的 JSON 处理库，用于 API 接口的请求与响应数据序列化 / 反序列化。
    
    许可证：[Apache License 2.0](https://github.com/google/gson/blob/master/LICENSE)
*   **SLF4J**
    [https://www.slf4j.org/](https://www.slf4j.org/)
    
    简单日志门面，为项目提供统一的日志接口，简化了日志系统的集成与管理。
    
    许可证：[MIT License](https://www.slf4j.org/license.html)
*   **Eclipse Jetty**
    [https://www.eclipse.org/jetty/](https://www.eclipse.org/jetty/)
    
    轻量级 Java Web 服务器，用于支撑本项目的 HTTP 服务运行。
    
    许可证：[Apache License 2.0](https://www.eclipse.org/jetty/licenses.html)
*   **Jaudiotagger**
    [https://bitbucket.org/ijabz/jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger)
    
    用于实现歌词内容的解析与处理。
    
    许可证：[GNU Lesser General Public License v2.1](https://bitbucket.org/ijabz/jaudiotagger/src/master/license.txt)
*   **Font Awesome**
    [https://fontawesome.com/](https://fontawesome.com/)
    
    提供丰富的图标资源，美化了 Web 控制界面的视觉呈现。
    
    许可证：[CC BY 4.0 License](https://fontawesome.com/license)

如果没有这些优秀的开源项目，本插件无法成功开发。再次感谢以上所有开源项目的开发者及贡献者们的辛勤付出！
## 许可证
本项目采用 [Apache License 2.0](LICENSE) 开源协议。
详细条款请参见根目录下的 `LICENSE` 文件。使用本项目前，请确保遵守许可条款。
