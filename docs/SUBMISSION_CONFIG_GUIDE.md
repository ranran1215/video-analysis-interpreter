# 提交版地址与 API 配置说明

本文说明本项目中“哪些地址、API、数据库和本地依赖路径需要根据运行环境修改”。提交时请保留占位说明，不要写入真实 `LLM_API_KEY`、MySQL 密码、cookies、token。

## 1. 默认演示方式

如果只在本机演示，通常不需要改代码里的地址，直接使用默认后端地址：

```text
http://localhost:8080
```

推荐启动：

```powershell
cd G:\视频分析
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
```

启动脚本会提示输入 `LLM_API_KEY`。Key 只进入当前进程环境变量，不会写入文件。

前端入口：

```text
G:\视频分析\test_frontend.html
G:\视频分析\realtime_demo.html
```

## 2. 前端后端地址

### 高精度视频分析页面

文件：

```text
test_frontend.html
```

主要后端地址：

```javascript
const API_BASE = 'http://localhost:8080/api/video';
```

如果后端端口或服务器地址变了，需要改这里。

示例：

```javascript
const API_BASE = 'http://192.168.1.100:8080/api/video';
```

或：

```javascript
const API_BASE = 'http://your-server.com:8080/api/video';
```

注意：`test_frontend.html` 的 debug/health 检查里还有两个写死的本地诊断地址。如果部署到别的机器，也要同步修改：

```text
http://localhost:8080/api/test/ping
http://localhost:8080/api/test/health/full
```

### 准实时同传页面

文件：

```text
realtime_demo.html
```

主要后端地址：

```javascript
const API_BASE = 'http://localhost:8080/api/realtime';
```

如果后端端口或服务器地址变了，需要改这里。

示例：

```javascript
const API_BASE = 'http://192.168.1.100:8080/api/realtime';
```

## 3. 后端端口

文件：

```text
spring_boot/src/main/resources/application.yml
```

默认端口：

```yaml
server:
  port: ${SERVER_PORT:8080}
```

建议不要直接改死配置文件，优先用环境变量：

```powershell
$env:SERVER_PORT="8081"
```

如果后端端口从 `8080` 改成 `8081`，前端也必须同步改：

```javascript
const API_BASE = 'http://localhost:8081/api/video';
const API_BASE = 'http://localhost:8081/api/realtime';
```

## 4. LLM API 配置

后端 AI 分析、字幕翻译、准实时翻译都使用 OpenAI-compatible Chat Completions API。

配置文件：

```text
spring_boot/src/main/resources/application.yml
```

对应配置：

```yaml
llm:
  api-url: ${LLM_API_URL:https://api.openai.com/v1/chat/completions}
  api-key: ${LLM_API_KEY:}
  model: ${LLM_MODEL:gpt-4o-mini}
  timeout-sec: ${LLM_TIMEOUT_SEC:120}
  temperature: ${LLM_TEMPERATURE:0.2}
  max-input-chars: ${LLM_MAX_INPUT_CHARS:20000}
```

本项目推荐通过启动脚本传入模型名：

```powershell
cd G:\视频分析
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
```

如果使用中转站，需要改 API 地址：

```powershell
cd G:\视频分析
.\scripts\start_backend_local.ps1 `
  -LlmApiUrl "https://你的中转站域名/v1/chat/completions" `
  -LlmModel "你的模型名"
```

也可以先设置环境变量：

```powershell
$env:LLM_API_URL="https://你的中转站域名/v1/chat/completions"
$env:LLM_MODEL="你的模型名"
```

重要：不要把真实 `LLM_API_KEY` 写进 `application.yml`、README、提交文档或前端 HTML。启动脚本会安全提示输入。

## 5. 数据库配置

### 默认 H2 模式

默认使用 H2，本机演示和提交 Demo 推荐走这条路线，不需要额外安装数据库。

配置文件：

```text
spring_boot/src/main/resources/application.yml
```

默认数据库：

```yaml
spring:
  datasource:
    url: jdbc:h2:file:./data/videodb
    username: sa
    password:
```

H2 数据会保存在：

```text
spring_boot/data/videodb.mv.db
```

提交代码时一般不要提交 `spring_boot/data/` 里的本机数据库文件。

### 可选 MySQL 模式

MySQL 是可选增强，用于长期保存分析结果，不是默认演示必需。

配置文件：

```text
spring_boot/src/main/resources/application-mysql.yml
```

对应环境变量：

```powershell
$env:MYSQL_URL="jdbc:mysql://localhost:3306/video_analysis?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="你的密码"
```

启动 MySQL profile：

```powershell
cd G:\视频分析
.\scripts\start_backend_mysql.ps1 -LlmModel "gpt-5.5"
```

重要：不要把真实 MySQL 密码写进提交文件。

数据库保存内容：

- 视频元数据
- 任务状态
- 字幕 JSON
- 翻译字幕 JSON
- summary/highlights
- URL 缓存字段
- fileHash 缓存字段

视频文件本体不进数据库，仍然保存在：

```text
spring_boot/uploads/
```

## 6. Python、WhisperX 和本地依赖路径

配置文件：

```text
spring_boot/src/main/resources/application.yml
```

相关配置：

```yaml
video:
  upload-dir: ${VIDEO_UPLOAD_DIR:${user.dir}/uploads}
  python-script-dir: ${VIDEO_PYTHON_SCRIPT_DIR:${user.dir}/../}
  python-command: ${VIDEO_PYTHON_COMMAND:python}
  conda-env: ${VIDEO_CONDA_ENV:}
  whisper-model: ${WHISPER_MODEL:base}
  whisper-device: ${WHISPER_DEVICE:auto}
  whisper-compute-type: ${WHISPER_COMPUTE_TYPE:}
  whisper-batch-size: ${WHISPER_BATCH_SIZE:16}
  whisper-enable-align: ${WHISPER_ENABLE_ALIGN:true}
  whisper-align-fallback: ${WHISPER_ALIGN_FALLBACK:true}
```

推荐不要手动改 `application.yml`，而是使用启动脚本：

```powershell
cd G:\视频分析
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
```

启动脚本会自动设置：

- `VIDEO_PYTHON_COMMAND`
- `YTDLP_PATH`
- `FFMPEG_PATH`
- `WHISPER_MODEL`
- `WHISPER_DEVICE`
- `WHISPER_COMPUTE_TYPE`
- `WHISPER_ENABLE_ALIGN`

如果换机器后 WhisperX 环境不同，需要重点检查：

```powershell
cd G:\视频分析
.\scripts\check_env.ps1
```

## 7. 下载器配置

在线视频链接下载使用 `yt-dlp` 和 `ffmpeg`。

配置文件：

```text
spring_boot/src/main/resources/application.yml
```

相关配置：

```yaml
downloader:
  ytdlp-path: ${YTDLP_PATH:yt-dlp}
  ffmpeg-path: ${FFMPEG_PATH:}
  cookies-from-browser: ${DOWNLOADER_COOKIES_FROM_BROWSER:}
  cookies-file: ${DOWNLOADER_COOKIES_FILE:}
  js-runtimes: ${DOWNLOADER_JS_RUNTIMES:}
  proxy: ${DOWNLOADER_PROXY:}
  max-duration-sec: ${DOWNLOADER_MAX_DURATION_SEC:3600}
  max-size-mb: ${DOWNLOADER_MAX_SIZE_MB:500}
  allowed-sites: ${DOWNLOADER_ALLOWED_SITES:bilibili.com,douyin.com,youtube.com,youtu.be}
```

如果 YouTube 出现登录验证或 429，可以运行：

```powershell
cd G:\视频分析
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5" -DownloaderCookiesFromBrowser "edge"
```

或：

```powershell
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5" -DownloaderCookiesFromBrowser "chrome"
```

重要：cookies 文件不要提交。

## 8. API 接口清单

### 高精度视频分析接口

基础前缀：

```text
/api/video
```

接口：

```text
POST /api/video/upload
POST /api/video/upload-url
GET  /api/video/analysis/{videoId}
GET  /api/video/play/{fileName}
GET  /api/video/subtitle/{fileName}
GET  /api/video/reanalyze/{videoId}?language=zh
```

用途：

- `/upload`：本地上传视频并分析。
- `/upload-url`：粘贴链接下载并分析。
- `/analysis/{videoId}`：轮询任务状态和结果。
- `/play/{fileName}`：前端播放本地保存的视频。
- `/subtitle/{fileName}`：读取字幕 JSON。
- `/reanalyze/{videoId}`：切换语言时重新翻译 summary/highlights。

### 准实时同传接口

基础前缀：

```text
/api/realtime
```

接口：

```text
POST /api/realtime/session/start
POST /api/realtime/chunk
GET  /api/realtime/session/{sessionId}/subtitles
POST /api/realtime/session/{sessionId}/finish
```

用途：

- `/session/start`：创建准实时同传 session。
- `/chunk`：上传音频片段，先返回识别结果。
- `/session/{sessionId}/subtitles`：获取当前 session 的字幕列表和翻译结果。
- `/session/{sessionId}/finish`：结束 session，并确认剩余字幕。

### 本地诊断接口

基础前缀：

```text
/api/test
```

常用接口：

```text
GET  /api/test/ping
GET  /api/test/health/full
POST /api/test/subtitle-script
POST /api/test/llm
POST /api/test/translate-subtitles
POST /api/test/downloader
```

这些接口只建议本地开发和演示前自检使用，不建议暴露到公网。

## 9. 提交前检查清单

提交前确认：

- `test_frontend.html` 的 `API_BASE` 地址正确。
- `realtime_demo.html` 的 `API_BASE` 地址正确。
- 如果改了端口，前端里的 `localhost:8080` 已同步修改。
- 没有把真实 `LLM_API_KEY` 写入任何文件。
- 没有把 MySQL 密码写入任何文件。
- 没有提交 cookies 文件。
- 没有提交 `spring_boot/uploads/` 里的运行视频。
- 没有提交 `spring_boot/data/` 里的 H2 数据库文件，除非明确需要演示数据。
- 没有提交 `.cache/` 模型缓存。
- 默认演示路线仍是 H2，本地短视频优先。

推荐验证：

```powershell
cd G:\视频分析
.\scripts\demo_check.ps1
.\scripts\smoke_test.ps1

cd G:\视频分析\spring_boot
mvn -q -DskipTests compile
```

## 10. 最小改动总结

如果只是在本机录制或提交演示，通常只需要：

```powershell
cd G:\视频分析
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
```

如果换服务器或换端口，主要改：

```text
test_frontend.html       -> API_BASE
realtime_demo.html       -> API_BASE
application.yml          -> SERVER_PORT 或环境变量 SERVER_PORT
启动脚本参数             -> LlmApiUrl / LlmModel
```

如果换数据库，改：

```text
MYSQL_URL
MYSQL_USERNAME
MYSQL_PASSWORD
```

如果换 Python/WhisperX 环境，检查：

```text
VIDEO_PYTHON_COMMAND
WHISPER_MODEL
WHISPER_DEVICE
WHISPER_COMPUTE_TYPE
YTDLP_PATH
FFMPEG_PATH
```
