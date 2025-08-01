# SaltApiPlugin

一个适用于Salt Player For Windows的插件，通过http进行简易的音乐控制，默认端口为35373

### 1. 获取当前播放信息

- **端点**：`/api/now-playing`

- **方法**：`GET`

- **用途**：获取当前播放的媒体信息、播放状态、进度和音量（音量显示无效,SPW未提供内部音量控制）

- **请求参数**：无

- **成功响应示例**：

```
{

 "status": "success",

 "title": "Hello World",

 "artist": "Example Artist",

 "album": "Sample Album",

 "isPlaying": true,

 "position": 15000,  // 播放进度（毫秒）

 "volume": 0.8,       // 音量

 "timestamp": 1690845678901

}
```

- **使用示例**：

```
curl http://localhost:35373/api/now-playing
```

### 2. 播放 / 暂停切换

- **端点**：`/api/play-pause`

- **方法**：`GET`

- **用途**：切换当前播放状态（播放→暂停 或 暂停→播放）

- **请求参数**：无

- **成功响应示例**：

```
{

 "status": "success",

 "action": "play_pause_toggled",

 "isPlaying": false,

 "message": "已暂停"

}
```

- **错误响应示例**：

```
{

 "status": "error",

 "message": "播放/暂停操作失败: 播放器未初始化"

}
```

- **使用示例**：

```
curl http://localhost:35373/api/play-pause
```

### 3. 下一曲

- **端点**：`/api/next-track`

- **方法**：`GET`

- **用途**：切换到下一首曲目

- **请求参数**：无

- **成功响应示例**：

```
{

 "status": "success",

 "action": "next_track",

 "newTrack": "Next Song Title",

 "message": "已切换到下一曲"

}
```

- **错误响应示例**：

```
{

 "status": "error",

 "message": "下一曲操作失败: 已到达播放列表末尾"

}
```

- **使用示例**：

```
curl http://localhost:35373/api/next-track
```

### 4. 上一曲

- **端点**：`/api/previous-track`

- **方法**：`GET`

- **用途**：切换到上一首曲目

- **请求参数**：无

- **成功响应示例**：

```
{

 "status": "success",

 "action": "previous_track",

 "newTrack": "Previous Song Title",

 "message": "已切换到上一曲"

}
```

- **错误响应示例**：

```
{

 "status": "error",

 "message": "上一曲操作失败: 已到达播放列表开头"

}
```

- **使用示例**：

```
curl http://localhost:35373/api/previous-track
```

### 5. 音量增加

- **端点**：`/api/volume/up`

- **方法**：`GET`

- **用途**：增加系统音量（音量范围有问题））

- **请求参数**：无

- **成功响应示例**：

```
{

 "status": "success",

 "action": "volume_up",

 "currentVolume": 0.9,

 "message": "音量已增加"

}
```

- **错误响应示例**：

```
{

 "status": "error",

 "message": "音量增加操作失败: 已达到最大音量"

}
```

- **使用示例**：

```
curl http://localhost:35373/api/volume/up
```

### 6. 音量减少

- **端点**：`/api/volume/down`

- **方法**：`GET`

- **用途**：减少系统音量（音量范围有问题）

- **请求参数**：无

- **成功响应示例**：

```
{

 "status": "success",

 "action": "volume_down",

 "currentVolume": 0.7,

 "message": "音量已减少"

}
```

- **错误响应示例**：

```
{

 "status": "error",

 "message": "音量减少操作失败: 已达到最小音量"

}
```

- **使用示例**：

```
curl http://localhost:35373/api/volume/down
```

### 7. 静音切换

- **端点**：`/api/mute`

- **方法**：`GET`

- **用途**：切换静音状态（静音→取消静音 或 取消静音→静音）

- **请求参数**：无

- **成功响应示例**：

```
{

 "status": "success",

 "action": "mute_toggle",

 "isMuted": true,

 "message": "已静音"

}
```

- **错误响应示例**：

```
{

 "status": "error",

 "message": "静音操作失败: 音量控制服务未响应"

}
```

- **使用示例**：

```
curl http://localhost:35373/api/mute
```
### 8. 歌词

- **端点**：`/api/lyric`

- **方法**：`GET`

- **用途**：输出歌词，使用api.injahow.cn的api

- **请求参数**：无

- **成功响应示例**：

```
{
    "status": "success",
    "lyric": "[歌词]"
}
```

- **使用示例**：

```
curl http://localhost:35373/api/lyric
```
### 9. 封面

- **端点**：`/api/pic`

- **方法**：`GET`

- **用途**：显示封面，使用api.injahow.cn的api

- **请求参数**：无

- **成功响应示例**：

```
纯图片
```

- **使用示例**：

```
curl http://localhost:35373/api/pic
```
### 10. 播放进度

- **端点**：`/api/current-position`

- **方法**：`GET`

- **用途**：格式化毫秒的播放进度

- **请求参数**：无

- **成功响应示例**：

```
{
  "status": "success",
  "position": 105201,
  "formatted": "01:45:201"
}
```

- **使用示例**：

```
curl http://localhost:35373/api/current-position
```
