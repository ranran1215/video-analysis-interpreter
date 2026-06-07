# Demo Guide

## 项目定位

本项目提供两种同传式字幕 Demo 能力：

- 高精度视频分析模式：面向录播演讲、技术分享、网课等视频，系统先离线完成视频下载或上传、WhisperX 字幕识别、字幕级中文翻译、summary/highlights 分析；用户播放视频时，前端再根据视频进度同步显示原文和中文字幕。
- 准实时同传模式：面向比赛题目“AI 同声传译助手”的演示表达，系统通过本地媒体音频分片、增量识别、增量翻译和最近字幕修正，模拟单向音频流准实时同传。

准实时模式不是工业级实时同传，也不承诺现场复杂流式架构；Demo 时建议表述为“准实时单向音频流同传 Demo”。

## 推荐演示方式

推荐使用本地短视频演示。可以下载 README 中的网盘 Demo 视频，也可以准备任意 30 秒以内英文视频/音频文件：

```text
video-analysis-interpreter.mp4
或 C:\path\to\your\demo.mp4
```

不推荐比赛现场首次演示 YouTube 链接下载。YouTube 可能出现 `HTTP 429 Too Many Requests`、`Sign in to confirm you're not a bot` 或 cookies 风控，容易把演示风险转移到平台反爬，而不是产品能力本身。

## 启动后端

H2 默认模式适合 Demo，不需要额外数据库服务：

```powershell
cd video-analysis-interpreter
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
```

启动脚本会停止旧 Spring Boot，设置 WhisperX/yt-dlp/ffmpeg/cache/LLM 环境变量，并安全读取 `LLM_API_KEY`。不要把真实 Key 写进文件。

## 运行检查

新开 PowerShell：

```powershell
cd video-analysis-interpreter
.\scripts\smoke_test.ps1
.\scripts\smoke_test.ps1 -LlmTest
.\scripts\smoke_test.ps1 -SubtitleTranslateTest
.\scripts\smoke_test.ps1 -VideoPath "C:\path\to\your\demo.mp4"
```

也可以用交付检查脚本一次性看 Demo readiness：

```powershell
cd video-analysis-interpreter
.\scripts\demo_check.ps1
```

如果 `LLM_API_KEY` 未配置，LLM 和字幕翻译检查会失败，但应该给出清晰错误：`LLM_API_KEY 未配置。`

## 双模式演示路线

### 1. 高精度视频分析模式

打开：

```text
<项目根目录>\test_frontend.html
```

操作步骤：

1. 选择“本地上传”。
2. 上传下载好的 Demo 视频或任意本地短视频。
3. 等待任务状态到 `completed`。
4. 播放视频，观察播放器下方“同步双语字幕”。

### 2. 准实时同传模式

打开：

```text
<项目根目录>\realtime_demo.html
```

操作步骤：

1. 选择本地短视频或音频文件，推荐使用下载好的 Demo 视频或其他 30 秒以内英文素材。
2. 点击“开始准实时同传”。
3. 前端会优先捕获播放中的语音音轨，通过 WebAudio 约每 3 秒生成一个独立 16kHz mono WAV 分片并发送给后端；后端慢时前端最多缓存 30 个分片，队列满时才丢弃较旧分片。
4. 观察右侧滚动字幕流：英文识别结果应先出现，中文翻译可稍后异步补齐；每条包含时间范围、英文原文、中文字幕和“修正中/已确认”状态。
5. 点击“结束并确认全部字幕”，剩余 provisional 字幕会标记为 final。

如果浏览器不能直接捕获播放音轨，页面会降级为整文件预解码后切 WAV 分片；这是 Demo 兜底路径，不影响高精度模式。不要改回 MediaRecorder webm 分片，实际测试中过 `EBML header parsing failed`。

## 验收点

- 状态最终为 `completed`。
- 页面展示视频播放器。
- 页面展示 `summary`。
- 页面展示 `highlights`。
- 字幕列表每条包含原文 `sourceText` 和中文 `translatedText`。
- 播放器下方同步双语字幕会随 `currentTime` 变化。
- 点击字幕列表项可以跳转到对应视频时间。
- 普通模式不展示 health check、工程耗时和固定百分比。
- `test_frontend.html?debug=1` 可以显示 currentTime/start/end 等调试信息。
- `realtime_demo.html` 可以创建 realtime session，并滚动显示分片识别结果。
- 准实时模式识别的是视频或音频的语音音轨，不是画面字幕 OCR。
- 英文 ASR 结果应先返回，中文翻译可以稍后补齐。
- 准实时字幕最近约 15 秒显示为“修正中”，超过窗口后变为“已确认”。
- 未配置 `LLM_API_KEY` 时，准实时模式应显示翻译占位提示，不应让页面崩溃。

## MySQL 说明

MySQL 是可选持久化增强，不是 Demo 必需。H2 已足够完成比赛演示。需要长期保存分析结果时，再执行：

```powershell
mysql -u root -p < scripts/init_mysql.sql
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="你的密码"
$env:MYSQL_URL="jdbc:mysql://localhost:3306/video_analysis?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
.\scripts\start_backend_mysql.ps1 -LlmModel "gpt-5.5"
```

MySQL 只保存元数据、字幕、翻译字幕、summary/highlights 和缓存字段；视频文件仍保存在 `uploads/`。
