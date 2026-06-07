# Troubleshooting

## 8080 是旧后端，接口 404

现象：`/api/test/translate-subtitles` 返回 404，或前端没有字幕翻译字段。

处理：

```powershell
cd G:\视频分析
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
```

启动脚本会停止旧 Spring Boot，避免继续访问老代码。

## H2 file locked

现象：启动时报 H2 文件被占用，或 `videodb.mv.db` locked。

处理：优先使用 `start_backend_local.ps1`，它会停止旧后端。如果仍失败，可以手动确认旧 Java 进程：

```powershell
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like "*spring-boot:run*" -or $_.CommandLine -like "*VideoAnalysisApplication*" } | Select-Object ProcessId,Name,CommandLine
```

确认是本项目后再停止，不要误杀其他 Java 服务。

## LLM_API_KEY 未配置

现象：health check 中 `LLM_API_KEY configured=false`，`/api/test/llm` 或 `/api/test/translate-subtitles` 返回 `LLM_API_KEY 未配置。`

处理：必须在启动后端的同一个进程里配置 Key，推荐直接用启动脚本提示输入：

```powershell
cd G:\视频分析
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
```

不要把真实 Key 写进 README、脚本或日志。

## MySQL Access denied

现象：MySQL profile 启动失败，日志中出现 `Access denied for user 'root'@'localhost'`。

处理：先用 MySQL 客户端确认账号密码可以登录，再启动后端：

```powershell
cd G:\视频分析
.\scripts\verify_mysql.ps1 -MysqlUsername "root"
.\scripts\start_backend_mysql.ps1 -LlmModel "gpt-5.5"
```

如果忘记 MySQL 密码，需要按本机 MySQL 安装方式重置账号密码。重置前请确认不会影响其他本机项目。

## mysql.exe 不在 PATH

现象：`verify_mysql.ps1` 提示找不到 `mysql.exe`。

处理：使用 MySQL 安装目录里的完整路径，或把 MySQL `bin` 目录加入 PATH。常见路径示例：

```powershell
.\scripts\verify_mysql.ps1 -MysqlExe "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -MysqlUsername "root"
```

如果本机只有 `mysqld` 服务，没有 MySQL Client，需要安装 MySQL Client 或 MySQL Shell。

## YouTube 429 或机器人验证

现象：下载时报 `HTTP 429 Too Many Requests`、`Sign in to confirm you're not a bot`。

处理：比赛 Demo 优先改用本地视频 `video/demo_english_30s.mp4`。如果必须用 YouTube，尝试浏览器 cookies：

```powershell
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5" -DownloaderCookiesFromBrowser "edge"
```

或：

```powershell
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5" -DownloaderCookiesFromBrowser "chrome"
```

## WhisperX 慢

现象：字幕阶段耗时很长，尤其是长视频。

处理：默认启动脚本已经设置 `WHISPER_ENABLE_ALIGN=false`。如果手动开启 forced alignment，长视频会显著变慢。普通 summary/highlights 和同步字幕 Demo 使用 segment 级时间戳即可。

## yt-dlp 下载到纯视频无音频

现象：WhisperX 报 `Failed to load audio`，或下载文件没有音轨。

处理：当前 `DownloadService` 已优先选择带音频的单文件 mp4，并在下载后校验音视频流。如果仍出现，检查 yt-dlp 格式选择、ffmpeg 路径和下载日志。

## 前端字幕不显示中文

现象：字幕列表或同步字幕区域只显示英文，中文位置显示原文或“中文字幕待生成”。

处理：

```powershell
cd G:\视频分析
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
.\scripts\smoke_test.ps1 -SubtitleTranslateTest
```

确认新后端已重启，`SubtitleTranslateTest` 通过，并且 API 返回的 `translatedText` 与 `sourceText` 不相同。旧缓存缺少 `translatedSubtitleData` 时，后端会尝试补生成；如果 LLM 不可用，会临时 fallback 原文。
