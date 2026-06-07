# 视频分析系统

这是一个本地视频分析与同传式字幕原型系统：前端使用单文件 HTML，后端使用 Spring Boot。

系统现在提供两种 Demo 模式：

- 高精度视频分析模式：本地上传或粘贴视频链接后，使用 WhisperX 完整字幕识别，可选 forced alignment，生成字幕级翻译、summary 和 highlights，适合录播演讲、技术分享和网课内容。
- 准实时同传模式：通过音频分片、增量识别、增量翻译和最近字幕修正机制，提供“准实时单向音频流同传 Demo”，帮助用户跟上内容节奏。该模式不是工业级实时同传。

## Demo 视频

演示视频文件：`video-analysis-interpreter.mp4`

百度网盘链接：

```text
https://pan.baidu.com/s/1F4iTpD45SocdkDt_hUX6KQ
```

提取码：

```text
fqp8
```

说明：Demo 视频文件较大，已通过 `.gitignore` 排除，不直接提交到 GitHub。评审或客户运行时，可以下载该视频，也可以换成任意本地短视频/音频文件。

## 快速开始

以下命令默认在你克隆后的仓库目录执行。如果你的项目放在其他目录，只需要先进入自己的项目根目录：

```powershell
git clone https://github.com/ranran1215/video-analysis-interpreter.git
cd video-analysis-interpreter
```

## 推荐 Demo 路径

MySQL 是可选持久化增强，不是 Demo 必需。比赛或交付演示建议走 H2 默认模式，并按“双模式”演示：

```powershell
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
.\scripts\demo_check.ps1
```

然后：

- 打开 `test_frontend.html`，上传下载好的 Demo 视频或任意本地短视频，演示高精度视频分析模式。
- 打开 `realtime_demo.html`，选择本地短视频或音频，演示准实时同传模式。

完整步骤见 `docs/DEMO_GUIDE.md`，常见问题见 `docs/TROUBLESHOOTING.md`。

提交和部署相关说明：

- `docs/GITHUB_UPLOAD_GUIDE.md`：GitHub 创建仓库、初始化 git、首次推送步骤。
- `docs/PROJECT_STRUCTURE.md`：项目结构和关键文件说明。
- `docs/SUBMISSION_CONFIG_GUIDE.md`：后端地址、API、数据库、LLM、WhisperX、下载器配置说明。

## 目录结构

```text
video-analysis-interpreter
├── spring_boot/                    # Spring Boot 后端
│   ├── src/main/java/com/video/    # Controller/Service/Entity/Repository/DTO
│   ├── src/main/resources/application.yml
│   ├── src/main/resources/application-mysql.yml
│   ├── data/                       # H2 本地数据库运行产物，不提交 GitHub
│   ├── uploads/                    # 上传、下载、字幕 JSON 运行产物，不提交 GitHub
│   └── pom.xml
├── scripts/init_mysql.sql          # MySQL 数据库初始化脚本
├── scripts/start_backend_local.ps1 # H2 默认本地启动脚本
├── scripts/start_backend_mysql.ps1 # MySQL profile 启动脚本
├── scripts/demo_check.ps1          # Demo readiness 检查脚本
├── scripts/verify_mysql.ps1        # MySQL 登录和初始化验证脚本
├── docs/DEMO_GUIDE.md              # Demo 演示步骤
├── docs/TROUBLESHOOTING.md         # 常见问题排查
├── docs/GITHUB_UPLOAD_GUIDE.md     # GitHub 上传步骤
├── docs/PROJECT_STRUCTURE.md       # 项目结构说明
├── docs/SUBMISSION_CONFIG_GUIDE.md # 提交版配置说明
├── extract_subtitle_whisperx.py    # WhisperX 字幕脚本
├── scripts/realtime_transcribe.py  # 准实时分片转录脚本
├── test_frontend.html              # 前端页面
├── realtime_demo.html              # 准实时同传 Demo 页面
├── DIFY_PROMPT.txt                 # Legacy Dify 工作流提示词示例
├── 链接上传功能指南.md
└── video/                          # 可选本地测试视频素材，不提交 GitHub
```

## 真实技术栈

- 后端：Java 11+、Spring Boot 2.7.18、Spring MVC、Spring Data JPA、H2 默认本地库、MySQL 持久化 profile、OkHttp、Gson
- 前端：原生 HTML/CSS/JavaScript，默认请求 `http://localhost:8080/api/video`
- 字幕：Python、WhisperX 转录 + forced alignment、Torch、可选 OpenCC 繁简转换
- 准实时 Demo：本地媒体分片、轻量 WhisperX chunk 转录、LLM 分片翻译、内存 session 字幕修正
- 下载：yt-dlp、ffmpeg
- 分析：OpenAI-compatible Chat Completions API，legacy Dify 测试接口仍保留

## 运行前配置

后端配置在 `spring_boot/src/main/resources/application.yml`，已经改为优先读取环境变量，避免把真实密钥写进仓库。

## 数据库模式

默认模式仍使用 H2 文件库，适合快速开发和 Demo 调试：

```powershell
cd video-analysis-interpreter
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
```

MySQL 模式适合长期保存分析结果。先创建数据库：

```powershell
mysql -u root -p < scripts/init_mysql.sql
```

再设置连接信息并启动 MySQL profile。不要把真实密码写入文件：

```powershell
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="你的密码"
$env:MYSQL_URL="jdbc:mysql://localhost:3306/video_analysis?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"

cd video-analysis-interpreter
.\scripts\start_backend_mysql.ps1 -LlmModel "gpt-5.5"
```

MySQL 保存 `videos` 表中的元数据、字幕 JSON、字幕级翻译轨道、summary/highlights、URL 缓存和 fileHash 缓存字段；`uploads/` 目录仍保存视频文件本体。当前阶段为了本地落地简单，MySQL profile 使用 Hibernate `ddl-auto=update` 自动建表/补列；生产环境建议改为 Flyway 或 Liquibase 管理迁移。

PowerShell 示例：

```powershell
$env:LLM_API_URL="https://fast.smartaipro.cn/v1/chat/completions"
$env:LLM_API_KEY="你的 API Key"
$env:LLM_MODEL="codex5.5"
$env:VIDEO_PYTHON_COMMAND="python"
$env:YTDLP_PATH="yt-dlp"
$env:FFMPEG_PATH="ffmpeg"
```

说明：模型名以 OpenAI 或中转站控制台支持的聊天模型为准；如果 `codex5.5` 不支持 Chat Completions，请换成该平台支持的聊天模型。不要把真实 Key 写入代码、文档或日志。

如果 WhisperX 安装在 conda 环境里：

```powershell
$env:VIDEO_CONDA_ENV="whisperx_env"
$env:VIDEO_PYTHON_COMMAND="python"
```

WhisperX 字幕参数：

```powershell
$env:WHISPER_MODEL="base"
$env:WHISPER_DEVICE="auto"
$env:WHISPER_COMPUTE_TYPE=""
$env:WHISPER_BATCH_SIZE="16"
$env:WHISPER_ENABLE_ALIGN="true"
$env:WHISPER_ALIGN_FALLBACK="true"
```

说明：

- `WHISPER_MODEL` 默认 `base`，CPU 快速测试可改为 `tiny`。
- `WHISPER_DEVICE=auto` 会自动选择 `cuda` 或 `cpu`。
- `WHISPER_COMPUTE_TYPE` 留空时，CPU 默认 `int8`，CUDA 默认 `float16`。
- `WHISPER_ENABLE_ALIGN=true` 会启用 WhisperX forced alignment。
- 如果 forced alignment 加载或执行失败，`WHISPER_ALIGN_FALLBACK=true` 会自动回退到 Whisper 原始 segment 时间戳。

常用可选变量：

```powershell
$env:SERVER_PORT="8080"
$env:VIDEO_SUBTITLE_TIMEOUT_SEC="600"
$env:DOWNLOADER_PROXY="http://127.0.0.1:7890"
$env:DOWNLOADER_MAX_DURATION_SEC="3600"
$env:DOWNLOADER_MAX_SIZE_MB="500"
$env:DOWNLOADER_ALLOWED_SITES="bilibili.com,douyin.com,youtube.com,youtu.be"
$env:REALTIME_CHUNK_TIMEOUT_SEC="180"
$env:REALTIME_FINALIZE_WINDOW_MS="15000"
```

## 本机环境安装与验证

当前真实状态：WhisperX 字幕链路已跑通，yt-dlp 已在 `whisperx_env` 中可用，health check 除 `LLM_API_KEY` 外基本通过。不要把“编译通过”理解成“端到端已跑通”；完整分析链路需要配置 `LLM_API_KEY`。

## 依赖安装脚本

只检查当前 PowerShell 环境：

```powershell
cd video-analysis-interpreter
.\scripts\check_env.ps1
```

推荐 conda 方式：

```powershell
conda create -n whisperx_env python=3.10 -y
conda activate whisperx_env
.\scripts\setup_env.ps1 -InstallAll
```

也可以用脚本创建 conda 环境。脚本不会永久切换你的 shell 环境，创建后仍需要手动 activate：

```powershell
.\scripts\setup_env.ps1 -CreateCondaEnv -CondaEnvName whisperx_env
conda activate whisperx_env
.\scripts\setup_env.ps1 -InstallAll
```

确认跳过安装前确认：

```powershell
.\scripts\setup_env.ps1 -InstallAll -Yes
```

脚本参数：

- `-CreateCondaEnv`：创建 conda 环境，不删除、不覆盖已有环境。
- `-CondaEnvName`：环境名，默认 `whisperx_env`。
- `-PythonVersion`：conda 环境 Python 版本，默认 `3.10`。
- `-InstallWhisperX`：安装/升级 `whisperx`，默认不重装 torch。
- `-InstallYtDlp`：安装/升级 `yt-dlp` 并验证命令。
- `-InstallAll`：等价于 `-InstallWhisperX -InstallYtDlp`。
- `-UseCurrentPython`：明确使用当前 PATH 下的 `python/pip`。
- `-ForceTorchInstall`：只给出强警告和确认，不硬编码 torch CUDA 安装命令。
- `-Yes`：跳过确认，适合你已经确认命令安全时使用。

安装后可以直接用默认 H2 启动脚本验证：

```powershell
cd video-analysis-interpreter
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
```

新开 PowerShell：

```powershell
cd video-analysis-interpreter
.\scripts\smoke_test.ps1
.\scripts\smoke_test.ps1 -VideoPath "C:\path\to\your\demo.mp4"
```

### WhisperX 安装

安装前先确认当前 Python/Torch 状态，当前机器已经检测到 `torch.cuda.is_available()=true`，不要盲目覆盖现有 torch：

```powershell
python -c "import torch; print(torch.__version__); print(torch.cuda.is_available())"
```

方案 A：conda 环境，推荐。

```powershell
conda create -n whisperx_env python=3.10 -y
conda activate whisperx_env
python -c "import torch; print(torch.__version__); print(torch.cuda.is_available())"
pip install -U whisperx
```

PyTorch 请按本机 CUDA 情况从官方安装页选择命令：`https://pytorch.org/get-started/locally/`。如果当前环境的 torch 已经 CUDA 可用，优先不要覆盖；如果 `pip install -U whisperx` 造成依赖冲突，再使用新的 conda 环境隔离。

方案 B：当前 Python 环境直接安装。

```powershell
pip install -U whisperx
python -c "import whisperx, torch; print('whisperx ok'); print(torch.cuda.is_available())"
```

### yt-dlp 安装

```powershell
pip install -U yt-dlp
yt-dlp --version
```

如果 `yt-dlp` 命令找不到，可以先验证 Python 模块方式：

```powershell
python -m yt_dlp --version
```

当前 `DownloadService` 优先按单个可执行命令调用 `yt-dlp`，所以建议优先确保 `yt-dlp --version` 在 PowerShell 里可用；如果只能使用 `python -m yt_dlp`，后续需要调整 `YTDLP_PATH`/启动方式或再做代码适配。

### LLM Key

不要把真实 key 写进仓库。PowerShell 示例：

```powershell
$env:LLM_API_URL="https://fast.smartaipro.cn/v1/chat/completions"
$env:LLM_API_KEY="你的 API Key"
$env:LLM_MODEL="codex5.5"
```

模型名以中转站控制台为准；如果 `codex5.5` 不支持 Chat Completions，请换成该平台支持的聊天模型。如果暂时不想配置 LLM，可以先只跑 `/api/test/subtitle-script` 验证字幕链路。

### 推荐环境变量

首次端到端建议用 `tiny` 模型先确认跑通，跑通后再改 `base`/`small`。有 CUDA 时优先 `cuda + float16`；如果显存不足，再降低 `WHISPER_BATCH_SIZE`。

```powershell
cd video-analysis-interpreter

$env:VIDEO_CONDA_ENV="whisperx_env"
$env:VIDEO_PYTHON_COMMAND="python"
$env:WHISPER_MODEL="tiny"
$env:WHISPER_DEVICE="cuda"
$env:WHISPER_COMPUTE_TYPE="float16"
$env:WHISPER_BATCH_SIZE="16"
$env:WHISPER_ENABLE_ALIGN="true"
$env:WHISPER_ALIGN_FALLBACK="true"
$env:YTDLP_PATH="$env:USERPROFILE\.conda\envs\whisperx_env\Scripts\yt-dlp.exe"
$env:FFMPEG_PATH="ffmpeg"
$env:HF_HOME="$PWD\.cache\huggingface"
$env:HUGGINGFACE_HUB_CACHE="$PWD\.cache\huggingface\hub"
$env:TORCH_HOME="$PWD\.cache\torch"
$env:XDG_CACHE_HOME="$PWD\.cache"

$env:LLM_API_URL="https://fast.smartaipro.cn/v1/chat/completions"
$env:LLM_API_KEY="你的新 API Key"
$env:LLM_MODEL="codex5.5"

cd .\spring_boot
mvn spring-boot:run
```

### 推荐验证顺序

第一步，按上一节环境变量启动后端。

第二步，新开 PowerShell，跑基础自检：

```powershell
cd video-analysis-interpreter
.\scripts\check_env.ps1
.\scripts\smoke_test.ps1
```

第三步，测试字幕：

```powershell
.\scripts\smoke_test.ps1 -VideoPath "C:\path\to\your\demo.mp4"
```

第四步，测试下载：

```powershell
.\scripts\smoke_test.ps1 -VideoUrl "https://..."
```

第五步，测试 URL 缓存：

```powershell
.\scripts\smoke_test.ps1 -VideoUrlCacheTest "https://..."
```

第六步，测试 LLM：

```powershell
.\scripts\smoke_test.ps1 -LlmTest
```

第七步，测试字幕翻译轨道：

```powershell
.\scripts\smoke_test.ps1 -SubtitleTranslateTest
```

如需测试旧 Dify 工作流，可使用 legacy 参数 `.\scripts\smoke_test.ps1 -DifyTest`。最后做完整端到端：打开 `test_frontend.html`，上传一个 30 秒以内小视频，确认状态流转到 `completed`，并看到字幕、总结和高光。工程耗时和系统自检只在 `test_frontend.html?debug=1` 显示。

## 启动后端

当前仓库没有完整 `.mvn/wrapper` 目录，优先使用本机 Maven：

```powershell
cd video-analysis-interpreter
cd .\spring_boot
mvn clean package -DskipTests
mvn spring-boot:run
```

或运行打包产物：

```powershell
java -jar target\video-analysis-1.0.0.jar
```

启动后访问：

```text
http://localhost:8080/api/test/ping
```

## 打开前端

高精度视频分析模式：

```text
<项目根目录>\test_frontend.html
```

准实时同传模式：

```text
<项目根目录>\realtime_demo.html
```

如果修改了后端端口，需要同步修改 `test_frontend.html` 里的 `API_BASE`。

## 核心 API

- `GET /api/test/ping`：后端连通性检测
- `GET /api/test/health/full`：本地开发环境完整自检
- `POST /api/test/subtitle-script`：只验证 WhisperX 字幕脚本，不调用 LLM
- `POST /api/test/llm`：只验证 OpenAI-compatible LLM 调用，不调用 WhisperX
- `POST /api/test/translate-subtitles`：只验证字幕级翻译轨道，不调用 WhisperX、不写数据库
- `POST /api/test/dify`：legacy，只验证旧 Dify 工作流调用，不参与默认主链路
- `POST /api/test/downloader`：只验证 yt-dlp 下载器，不调用 WhisperX/LLM
- `POST /api/video/upload`：本地上传视频，表单字段为 `file` 和 `language`
- `POST /api/video/upload-url`：粘贴链接下载并分析，请求体包含 `url` 和 `language`
- `GET /api/video/analysis/{videoId}`：查询处理状态和分析结果
- `GET /api/video/reanalyze/{videoId}?language=en`：重新翻译总结和高光
- `GET /api/video/play/{fileName}`：播放 `uploads` 目录内视频
- `GET /api/video/subtitle/{fileName}`：读取同名 `_subtitle.json`
- `POST /api/realtime/session/start`：创建准实时同传 Demo 内存 session
- `POST /api/realtime/chunk`：接收音频分片，返回增量识别与中文翻译字幕
- `GET /api/realtime/session/{sessionId}/subtitles`：读取当前 session 的所有字幕片段
- `POST /api/realtime/session/{sessionId}/finish`：将剩余 provisional 字幕标记为 final

## 任务状态字段

`POST /upload`、`POST /upload-url` 和 `GET /analysis/{videoId}` 会统一返回任务状态字段，前端应优先使用这些字段判断进度，不再通过 `summary` 文本猜测是否完成：

```json
{
  "videoId": 1,
  "videoUrl": "/play/demo.mp4",
  "status": "extracting_subtitle",
  "stage": "正在提取字幕",
  "progress": 30,
  "errorMessage": null,
  "downloadDurationMs": 0,
  "subtitleDurationMs": 120000,
  "alignDurationMs": 30000,
  "analysisDurationMs": 45000,
  "totalDurationMs": 165000,
  "summary": "视频正在处理中，请稍后刷新页面查看结果...",
  "subtitles": [
    {
      "start": 0.0,
      "end": 2.5,
      "text": "hello everyone",
      "sourceText": "hello everyone",
      "translatedText": "大家好"
    }
  ],
  "highlights": []
}
```

状态流转：

- 本地上传新任务：`processing` / `等待处理` / `5`
- 链接任务开始：`downloading` / `正在下载视频` / `5`
- 链接下载完成：`processing` / `视频下载完成，准备提取字幕` / `20`
- 字幕提取开始：`extracting_subtitle` / `正在提取字幕` / `30`
- 字幕提取完成：`字幕提取完成，正在生成翻译字幕` / `60`
- 翻译字幕完成：`翻译字幕完成，正在进行 AI 分析` / `75`
- AI 分析开始：`analyzing` / `正在调用 AI 分析` / `85`
- 全部完成：`completed` / `处理完成` / `100`
- 缓存命中：`completed` / `命中缓存` / `100`
- 链接缓存命中：`completed` / `命中链接缓存` / `100`
- 文件缓存命中：`completed` / `命中文件缓存` / `100`
- 任意失败：`failed` / `处理失败` / `-1`，并返回 `errorMessage`

应用启动时会把数据库中未完成且非失败的历史任务标记为 `failed`，避免服务重启后前端一直轮询旧任务；已存在完整结果但缺少 `status` 的旧缓存会尽量补成 `completed`。

## 缓存机制

当前有两层缓存：

- URL 级缓存：`/api/video/upload-url` 收到链接后先做 URL 规范化并计算 `urlHash`，如果同一个规范化链接已有 `completed` 记录，且本地视频文件仍存在，会直接返回历史字幕、总结和高光，不调用 yt-dlp、WhisperX 或 LLM。
- fileHash 缓存：如果 URL 缓存未命中，系统会正常下载视频；下载后计算文件 MD5，如果同一文件内容已有 `completed` 记录，会复用字幕、总结和高光，跳过 WhisperX 和 LLM，并把当前 URL 绑定到新的 completed 记录，方便后续同链接秒开。

URL 规范化会去掉 `#fragment`，小写 host，删除 `utm_source`、`utm_medium`、`utm_campaign`、`utm_term`、`utm_content`、`fbclid`、`gclid` 等 tracking 参数，并按 query key 排序。YouTube 的 `v`、`list`、`t` 等有意义参数会保留；`youtu.be` 短链本阶段不会强行展开。

URL 缓存命中前提是：之前该规范化 URL 已成功分析到 `completed`，本地视频文件仍存在，并且规范化后的 `urlHash` 一致。YouTube 的 429、登录验证或 cookies 问题仍可能影响第一次下载，但不会影响已经命中的 URL 缓存。

## 预处理同步翻译模式

系统会在字幕提取后生成字幕级中文翻译轨道，并保存到 `translatedSubtitleData`。API 返回的 `subtitles` 同时包含 `text/sourceText/translatedText`，前端播放视频时会根据 `currentTime` 同步显示当前原文字幕和中文字幕。

这个模式面向录播演讲、技术分享、网课等视频：系统先完成字幕识别和翻译，用户播放时获得接近同传式的观看体验。它不是麦克风实时同传，也不做真正实时流式翻译。

字幕翻译采用批处理：每批最多 30 条 segment，或约 4000 字符。模型只返回 `{index, translatedText}`，后端保留原始 `start/end`，避免模型改时间戳。某批翻译失败时，该批 `translatedText` 会回退为原文，不会让整个视频任务失败。

## 准实时同传模式

`realtime_demo.html` 提供准实时单向音频流同传 Demo。前端选择本地视频或音频后，会优先通过 `captureStream + WebAudio` 捕获播放中的语音音轨，约每 3 秒生成一个独立 16kHz mono WAV 分片，并缓存最多 30 个待处理 chunk，避免 worker 冷启动或后端慢时大量丢片。如果浏览器无法捕获播放音轨，会降级为整文件预解码后切 WAV 分片；不要改回 MediaRecorder webm 分片，实际测试中过 `EBML header parsing failed`。后端通过 `/api/realtime/chunk` 接收分片，调用 `scripts/realtime_transcribe.py` 常驻 worker 做轻量 WhisperX 转录，并先返回英文 ASR 结果；中文翻译由后台异步任务复用 `SubtitleTranslationService` 补齐，前端每 1 秒轮询 session 字幕刷新译文。

实时 session 只保存在内存中，不写数据库，不引入 Redis 或 WebSocket。当前 chunk 返回的字幕默认为 `provisional`；最近约 15 秒字幕允许被新分片覆盖修正，超过窗口后自动标记为 `final`。如果 `LLM_API_KEY` 未配置，接口不会因为翻译缺失而让 Demo 崩溃，后台翻译会把 `translatedText` 更新为占位提示。

准实时模式识别的是视频或音频文件中的语音音轨，不是画面字幕 OCR。当前仍保留 WhisperX 实现；如果后续继续优化实时性能，建议把 realtime ASR 替换为 `faster-whisper` 常驻模型，并保持高精度视频分析主链路继续使用 WhisperX。

该模式用于贴合同声传译题目的演示表达，不代表系统已经实现工业级实时同传。

## 比赛 Demo 验收步骤

1. 启动后端：进入克隆后的项目根目录，运行 `.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"`。
2. 新开 PowerShell 跑 smoke：`.\scripts\smoke_test.ps1`、`.\scripts\smoke_test.ps1 -LlmTest`、`.\scripts\smoke_test.ps1 -SubtitleTranslateTest`。
3. 高精度模式：打开 `<项目根目录>\test_frontend.html`，上传下载好的 Demo 视频或任意本地短视频，等待状态到 `completed`。
4. 高精度验收：确认页面展示视频播放器、summary、highlights、字幕列表，以及播放器下方“同步双语字幕”。
5. 准实时模式：打开 `<项目根目录>\realtime_demo.html`，选择本地短视频或音频，点击“开始准实时同传”。
6. 准实时验收：确认页面按分片滚动显示原文、中文字幕和“修正中/已确认”状态；未配置 `LLM_API_KEY` 时应显示翻译占位，不应让页面崩溃。
7. 打开 `<项目根目录>\test_frontend.html?debug=1`，确认高精度模式 debug 信息仍可用。
8. 如果 YouTube 链接下载失败，优先用本地视频演示；YouTube 失败通常是 429、登录验证或 cookies 风控，不是字幕翻译链路问题。

## 性能分析

每条视频记录会保存全链路粗粒度耗时，接口也会返回同名字段。普通前端默认不展示这些工程耗时，`test_frontend.html?debug=1` 调试模式会显示：

- `downloadDurationMs`：链接下载耗时；本地上传任务为 `0`。
- `subtitleDurationMs`：Python 字幕脚本整体耗时，包含 Whisper 转录和 forced alignment。
- `alignDurationMs`：WhisperX forced alignment 耗时，来自字幕 JSON 的 `alignDurationMs`。
- `analysisDurationMs`：调用 OpenAI-compatible LLM 分析耗时。
- `totalDurationMs`：后端任务总耗时；链接任务包含下载、字幕和 AI 分析。

任务完成或失败时后端会打印性能日志：

```text
[PERF] videoId=1
[PERF] download=1234 ms
[PERF] subtitle=120000 ms
[PERF] align=30000 ms
[PERF] analysis=45000 ms
[PERF] total=166234 ms
```

注意：`subtitleDurationMs` 包含 `alignDurationMs`，两者不要相加后再理解为总字幕耗时。

## 真正验证项目是否跑通

测试接口只用于本地开发诊断，不要暴露到公网。接口不会返回真实 `LLM_API_KEY`、`DIFY_API_KEY` 或其他密钥，只返回是否已配置。

编译验证只说明代码能编译：

```powershell
cd video-analysis-interpreter
python -m py_compile extract_subtitle_whisperx.py
cd .\spring_boot
mvn clean compile
```

启动后端后做环境自检：

```powershell
cd video-analysis-interpreter
cd .\spring_boot
mvn spring-boot:run
```

```powershell
Invoke-RestMethod http://localhost:8080/api/test/health/full
```

也可以直接使用 smoke 脚本：

```powershell
cd video-analysis-interpreter
.\scripts\smoke_test.ps1
.\scripts\smoke_test.ps1 -VideoPath "C:\path\to\your\demo.mp4"
.\scripts\smoke_test.ps1 -LlmTest
.\scripts\smoke_test.ps1 -SubtitleTranslateTest
.\scripts\smoke_test.ps1 -VideoUrl "https://..."
.\scripts\smoke_test.ps1 -VideoUrlCacheTest "https://..."
```

分段验证接口：

```powershell
Invoke-RestMethod http://localhost:8080/api/test/subtitle-script `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"videoPath":"C:\\path\\to\\demo.mp4","language":"auto"}'
```

```powershell
Invoke-RestMethod http://localhost:8080/api/test/dify `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"text":"这是一段测试字幕","language":"zh"}'
```

```powershell
Invoke-RestMethod http://localhost:8080/api/test/llm `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"text":"这是一段测试字幕","language":"zh"}'
```

```powershell
Invoke-RestMethod http://localhost:8080/api/test/translate-subtitles `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"targetLanguage":"zh","segments":[{"start":0.0,"end":2.5,"text":"hello everyone"},{"start":2.5,"end":5.0,"text":"today we talk about AI"}]}'
```

```powershell
Invoke-RestMethod http://localhost:8080/api/test/downloader `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"url":"https://..."}'
```

完整端到端验证：打开 `test_frontend.html`，上传一个 30 秒以内小视频，观察状态从 `processing` / `extracting_subtitle` / `analyzing` / `completed` 变化，并确认展示字幕、同步双语字幕、总结和高光。如需系统自检和处理耗时，打开 `test_frontend.html?debug=1`。

## 核心流程

1. 前端提交本地文件或视频链接。
2. `VideoController` 接收请求并交给 `VideoService`。
3. 本地文件先算 MD5，完成缓存命中时直接返回历史结果；链接任务先规范化 URL 并查 `urlHash`，命中时直接返回 `completed`、`命中链接缓存`、`100`。
4. URL 缓存未命中时，新链接任务写入当前数据库，状态为 `downloading`，后台异步下载和处理。
5. 下载完成后再算文件 MD5；如果 fileHash 命中 completed，复制历史字幕、总结和高光到当前 URL 任务，状态为 `命中文件缓存`。
6. 未命中缓存时，`SubtitleExtractionService` 调用 `extract_subtitle_whisperx.py`，脚本先转录，再按配置决定是否执行 WhisperX forced alignment，失败时可回退原始 segment 时间戳。
7. `SubtitleTranslationService` 分批调用 LLM 生成字幕级中文翻译轨道，保存到 `translatedSubtitleData`；单批失败会回退原文并继续。
8. `LlmAnalysisService` 调用 OpenAI-compatible Chat Completions API，要求返回 `summary` 和 `highlights` JSON。
9. 分析结果写回 `videos` 表，状态改为 `completed`。
10. 前端每 3 秒轮询 `/analysis/{videoId}`，根据 `status` 判断继续等待、展示失败原因或渲染完整结果，并在播放时同步显示双语字幕。

准实时模式不进入上述数据库流程：`realtime_demo.html` 创建 `/api/realtime/session/start`，随后把本地媒体音频分片发到 `/api/realtime/chunk`；后端识别和翻译分片后只写内存 session，页面滚动展示当前 session 字幕。

## 重要注意

- 必须配置 `LLM_API_KEY`，否则字幕提取完成后会在 AI 分析阶段失败；不再要求 `DIFY_API_KEY`。
- `/api/test/*` 诊断接口只用于本地开发，不建议部署到公网。
- `uploads/`、`data/`、`target/` 都是运行或构建产物，不建议提交版本库。
- H2 是嵌入式文件数据库，同一时间不要用多个进程独占打开 `spring_boot/data/videodb.mv.db`。
- MySQL profile 不依赖 H2 文件库，适合长期保存 `videos` 表；但视频文件仍在 `uploads/`，不要只备份数据库而忽略视频目录。
- URL 缓存和 fileHash 缓存都依赖数据库持久化；切换数据库后，缓存命中范围也会随当前数据库记录变化。
- `downloader.max-size-mb` 已经真正传给 `yt-dlp --max-filesize`，下载完成后也会二次校验。
- 字幕 JSON 保持 `language`、`text`、`segments` 结构；每个 segment 至少包含 `start`、`end`、`text`，align 成功时可能额外包含 `words`。
- 翻译字幕轨道保存在 `translatedSubtitleData`，API subtitles 会返回 `text/sourceText/translatedText`；旧缓存没有翻译轨道时会回退为原文字幕。
- 前端轮询以 `status` 为准：`completed` 停止并展示结果，`failed` 停止并展示 `errorMessage`，其他状态继续展示 `stage/progress`。
- 当前最小验证能力包括 `scripts/smoke_test.ps1`、`/api/test/health/full`、字幕/LLM/下载器分段测试，以及前端端到端手动上传。
- `scripts/smoke_test.ps1 -VideoUrlCacheTest "https://..."` 可验证同一 URL 第二次提交是否命中 `命中链接缓存`。
- `scripts/smoke_test.ps1 -SubtitleTranslateTest` 可验证字幕级翻译轨道；未配置 `LLM_API_KEY` 时会返回清晰错误。
