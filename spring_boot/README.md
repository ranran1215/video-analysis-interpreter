# 视频分析系统后端

这是一个 Spring Boot 2.7.18 后端服务，负责视频上传、在线视频下载、WhisperX 字幕提取、OpenAI-compatible LLM 分析、数据库持久化缓存和视频播放。默认使用 H2 本地文件库，也可以通过 `mysql` profile 切换到 MySQL。

Demo 推荐使用项目根目录的 `scripts/start_backend_local.ps1`、`scripts/demo_check.ps1` 和 `video/demo_english_30s.mp4`。完整演示步骤见 `../docs/DEMO_GUIDE.md`，故障排查见 `../docs/TROUBLESHOOTING.md`。

## 真实技术栈

- Java 11+，当前代码可在 Java 21 下编译
- Spring Boot 2.7.18
- Spring MVC + Spring Data JPA
- H2 文件数据库，默认路径 `./data/videodb`
- MySQL 持久化 profile，用于长期保存分析结果和缓存字段
- OkHttp + Gson
- Python + WhisperX 转录 + forced alignment 字幕脚本
- yt-dlp + ffmpeg 在线视频下载

## 配置方式

`src/main/resources/application.yml` 已改为优先读取环境变量，避免把真实密钥写进仓库。

### 数据库模式

默认 H2 模式不需要额外数据库服务，适合快速开发：

```powershell
cd G:\视频分析
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
```

MySQL 模式先创建数据库：

```powershell
cd G:\视频分析
mysql -u root -p < scripts/init_mysql.sql
```

再设置连接信息并启动：

```powershell
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="你的密码"
$env:MYSQL_URL="jdbc:mysql://localhost:3306/video_analysis?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"

.\scripts\start_backend_mysql.ps1 -LlmModel "gpt-5.5"
```

MySQL profile 会设置 `SPRING_PROFILES_ACTIVE=mysql`，并从环境变量读取 `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`。密码不会写入文件。当前阶段使用 Hibernate `ddl-auto=update` 自动创建/更新 `videos` 表；生产环境建议使用 Flyway 或 Liquibase。

数据库保存视频元数据、字幕 JSON、字幕级中文翻译轨道、summary/highlights、URL 缓存和 fileHash 缓存。视频文件仍保存在 `uploads/` 目录，数据库不会存储 mp4 本体。

关键变量：

```powershell
$env:LLM_API_URL="https://fast.smartaipro.cn/v1/chat/completions"
$env:LLM_API_KEY="你的 API Key"
$env:LLM_MODEL="codex5.5"
$env:VIDEO_PYTHON_COMMAND="python"
$env:YTDLP_PATH="yt-dlp"
$env:FFMPEG_PATH="ffmpeg"
```

模型名以 OpenAI 或中转站控制台支持的聊天模型为准；如果 `codex5.5` 不支持 Chat Completions，请换成该平台支持的聊天模型。旧 Dify 配置保留为 legacy 测试，不再是默认主链路。

Legacy Dify 测试变量，仅在需要调用 `/api/test/dify` 时使用：

```powershell
$env:DIFY_API_KEY="你的 Dify API Key"
$env:DIFY_API_URL="https://difyonline.58corp.com/v1/workflows/run"
```

如果 WhisperX 安装在 conda 环境里，可以改成：

```powershell
$env:VIDEO_CONDA_ENV="whisperx_env"
$env:VIDEO_PYTHON_COMMAND="python"
```

服务会使用 `conda run -n whisperx_env python ../extract_subtitle_whisperx.py <video> <language>`。

WhisperX 字幕脚本支持这些环境变量，后端会从 `application.yml` 读取并传给 Python 子进程：

```powershell
$env:WHISPER_MODEL="base"
$env:WHISPER_DEVICE="auto"
$env:WHISPER_COMPUTE_TYPE=""
$env:WHISPER_BATCH_SIZE="16"
$env:WHISPER_ENABLE_ALIGN="true"
$env:WHISPER_ALIGN_FALLBACK="true"
```

- `WHISPER_ENABLE_ALIGN=true`：转录后调用 `whisperx.load_align_model()` 和 `whisperx.align()` 做 forced alignment。
- `WHISPER_ALIGN_FALLBACK=true`：forced alignment 失败时打印 warning，并回退到 Whisper 原始 segment 时间戳。
- `WHISPER_COMPUTE_TYPE` 留空时由脚本自动判断：CPU 使用 `int8`，CUDA 使用 `float16`。

## Windows 本机环境安装

当前真实状态：WhisperX 字幕链路已跑通，yt-dlp 已在 `whisperx_env` 中可用，health check 除 `LLM_API_KEY` 外基本通过。因此当前还不能声称完整端到端已跑通，完整分析链路需要配置 `LLM_API_KEY`。

### 依赖安装脚本

只检查：

```powershell
cd G:\视频分析
.\scripts\check_env.ps1
```

推荐 conda：

```powershell
conda create -n whisperx_env python=3.10 -y
conda activate whisperx_env
.\scripts\setup_env.ps1 -InstallAll
```

或用脚本创建 conda 环境：

```powershell
.\scripts\setup_env.ps1 -CreateCondaEnv -CondaEnvName whisperx_env
conda activate whisperx_env
.\scripts\setup_env.ps1 -InstallAll
```

跳过确认：

```powershell
.\scripts\setup_env.ps1 -InstallAll -Yes
```

`setup_env.ps1` 支持 `-CreateCondaEnv`、`-CondaEnvName`、`-PythonVersion`、`-InstallWhisperX`、`-InstallYtDlp`、`-InstallAll`、`-UseCurrentPython`、`-ForceTorchInstall`、`-Yes`。默认不重装 torch、不删除环境、不写入真实 API Key。

安装后验证：

```powershell
cd G:\视频分析\spring_boot
mvn spring-boot:run
```

新开 PowerShell：

```powershell
cd G:\视频分析
.\scripts\smoke_test.ps1
.\scripts\smoke_test.ps1 -VideoPath "G:\视频分析\video\demo.mp4"
```

交付前也可以运行：

```powershell
cd G:\视频分析
.\scripts\demo_check.ps1
```

### WhisperX

先确认当前 Python/Torch，不要盲目覆盖当前 CUDA 可用的 torch：

```powershell
python -c "import torch; print(torch.__version__); print(torch.cuda.is_available())"
```

推荐方案 A：新建 conda 环境。

```powershell
conda create -n whisperx_env python=3.10 -y
conda activate whisperx_env
pip install -U whisperx
python -c "import whisperx, torch; print('whisperx ok'); print(torch.cuda.is_available())"
```

如果 conda 环境里需要安装 PyTorch，请按官方页面根据本机 CUDA 选择命令：`https://pytorch.org/get-started/locally/`。

方案 B：直接在当前 Python 环境安装。

```powershell
pip install -U whisperx
python -c "import whisperx, torch; print('whisperx ok'); print(torch.cuda.is_available())"
```

### yt-dlp

```powershell
pip install -U yt-dlp
yt-dlp --version
```

如果命令找不到，可验证：

```powershell
python -m yt_dlp --version
```

当前下载服务按单命令执行 `yt-dlp`，所以优先确保 `yt-dlp --version` 在 PowerShell 中可用；如果只能用 `python -m yt_dlp`，需要后续再适配启动配置或代码。

### LLM

```powershell
$env:LLM_API_URL="https://fast.smartaipro.cn/v1/chat/completions"
$env:LLM_API_KEY="你的 API Key"
$env:LLM_MODEL="codex5.5"
```

如果暂时没有 LLM Key，可以先只跑 `/api/test/subtitle-script` 验证字幕链路。不要把真实 Key 写入代码、文档或日志。

### 推荐环境变量

首次跑通建议用 `tiny` 模型，成功后再切 `base`/`small`。

```powershell
cd G:\视频分析

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
$env:HF_HOME="G:\视频分析\.cache\huggingface"
$env:HUGGINGFACE_HUB_CACHE="G:\视频分析\.cache\huggingface\hub"
$env:TORCH_HOME="G:\视频分析\.cache\torch"
$env:XDG_CACHE_HOME="G:\视频分析\.cache"

$env:LLM_API_URL="https://fast.smartaipro.cn/v1/chat/completions"
$env:LLM_API_KEY="你的新 API Key"
$env:LLM_MODEL="codex5.5"

cd G:\视频分析\spring_boot
mvn spring-boot:run
```

## 运行

当前项目没有完整 Maven Wrapper 目录，优先使用本机 Maven：

```powershell
cd G:\视频分析\spring_boot
mvn clean package -DskipTests
mvn spring-boot:run
```

或运行打包产物：

```powershell
java -jar target\video-analysis-1.0.0.jar
```

前端页面在同级目录：

```text
G:\视频分析\test_frontend.html
```

默认后端地址是 `http://localhost:8080`。

## API

- `GET /api/test/ping`：后端连通性检测
- `GET /api/test/health/full`：完整环境自检，检查 Python、WhisperX、Torch、下载器、LLM 配置、legacy Dify 配置、active profile 和当前数据库
- `POST /api/test/subtitle-script`：只调用 `SubtitleExtractionService.extractSubtitle()` 验证字幕链路
- `POST /api/test/llm`：只调用 `LlmAnalysisService.analyzeSubtitle()` 验证 LLM 链路
- `POST /api/test/translate-subtitles`：只调用字幕翻译服务，验证字幕级翻译轨道
- `POST /api/test/dify`：legacy，只调用 `DifyService.analyzeSubtitle()` 验证旧 Dify 链路
- `POST /api/test/downloader`：只调用 `DownloadService.downloadVideo()` 验证下载链路
- `POST /api/video/upload`：本地视频上传，表单字段 `file`、`language`
- `POST /api/video/upload-url`：链接下载并分析，请求体包含 `url`、`language`
- `GET /api/video/analysis/{videoId}`：查询分析状态和结果
- `GET /api/video/reanalyze/{videoId}?language=en`：重新翻译总结和高光
- `GET /api/video/play/{fileName}`：播放 `uploads` 内视频
- `GET /api/video/subtitle/{fileName}`：读取对应 `_subtitle.json`

## 任务状态返回

上传、链接分析和查询接口都会返回统一的任务状态字段：

- `status`：机器可读状态，当前使用 `pending`、`downloading`、`processing`、`extracting_subtitle`、`analyzing`、`completed`、`failed`。
- `stage`：前端可直接展示的当前阶段说明，例如 `正在下载视频`、`正在提取字幕`、`正在调用 AI 分析`。
- `progress`：粗略阶段进度，当前不是精准百分比。
- `errorMessage`：失败原因，成功时为空。

典型响应：

```json
{
  "videoId": 1,
  "videoUrl": "/play/demo.mp4",
  "status": "analyzing",
  "stage": "正在调用 AI 分析",
  "progress": 70,
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

本地上传状态流：`processing(5)` → `extracting_subtitle(30)` → `翻译字幕(60/75)` → `analyzing(85)` → `completed(100)` 或 `failed(-1)`。

链接上传状态流：`downloading(5)` → `processing(20)` → `extracting_subtitle(30)` → `翻译字幕(60/75)` → `analyzing(85)` → `completed(100)` 或 `failed(-1)`。

如果本地文件 MD5 命中已完成缓存，接口返回 `status=completed`、`stage=命中缓存`、`progress=100`。如果链接 URL 级缓存命中，接口返回 `status=completed`、`stage=命中链接缓存`、`progress=100`。如果链接下载后 fileHash 命中历史结果，当前任务会返回 `stage=命中文件缓存`。服务启动时会把未完成的历史任务标记为 `failed`，并写入 `errorMessage=服务重启导致任务中断，请重新上传或重新分析`。

## 缓存机制

当前缓存分两层：

- URL 级缓存：`/api/video/upload-url` 会先规范化 URL 并计算 `urlHash`，查询最新 `completed` 记录。命中且本地视频文件仍存在时，直接复用历史字幕、总结和高光，不调用 yt-dlp、WhisperX 或 LLM。
- fileHash 缓存：URL 缓存未命中时才下载视频；下载后计算文件 MD5。如果同一文件内容已有 `completed` 记录，会复制历史字幕、总结和高光到当前 URL 任务，跳过 WhisperX 和 LLM，并让这个 URL 下次可命中 URL 缓存。

URL 规范化规则：去掉 `#fragment`，小写 host，删除 `utm_source`、`utm_medium`、`utm_campaign`、`utm_term`、`utm_content`、`fbclid`、`gclid`，按 query key 排序。YouTube 的 `v`、`list`、`t` 等有意义参数会保留；`youtu.be` 短链本阶段不强行展开。

注意：URL 缓存不能解决第一次下载时的 YouTube 429、登录验证或 cookies 问题；它只保证已经成功分析过且本地视频文件还在的同一规范化链接可以秒开。

## 预处理同步翻译模式

字幕提取完成后，后端会生成字幕级中文翻译轨道并保存到 `Video.translatedSubtitleData`。`subtitleData` 仍保存 WhisperX 原始字幕 JSON，`translatedSubtitleData` 保存 `{language, segments}`，每条 segment 包含 `start/end/sourceText/translatedText`。

字幕翻译采用批处理：每批最多 30 条 segment，或约 4000 字符。Prompt 要求模型只翻译 `text`，返回 `{index, translatedText}` 数组；后端按 index 合并并保留原始 `start/end`，不允许模型改时间戳。单批失败时只回退该批 `translatedText=sourceText`，不会让整个视频任务失败。

前端 `test_frontend.html` 会在播放器下方显示“预处理同步翻译模式”字幕区域，根据视频 `currentTime` 同步显示当前原文和中文字幕。这个模式适合录播演讲、技术分享、网课，不等同于麦克风实时同传。

## 性能分析

后端会把各阶段耗时写入 `videos` 表，并通过 `VideoAnalysisResponse` 返回给前端：

- `downloadDurationMs`：链接下载耗时，本地上传为 `0`。
- `subtitleDurationMs`：字幕脚本总耗时，包含 Whisper 转录和 forced alignment。
- `alignDurationMs`：WhisperX forced alignment 耗时，由 `extract_subtitle_whisperx.py` 输出到字幕 JSON。
- `analysisDurationMs`：OpenAI-compatible LLM 分析耗时。
- `totalDurationMs`：任务总耗时，链接任务包含下载、字幕和 AI 分析。

任务完成或失败时会打印 `[PERF]` 日志，便于快速判断瓶颈：

```text
[PERF] videoId=1
[PERF] download=1234 ms
[PERF] subtitle=120000 ms
[PERF] align=30000 ms
[PERF] analysis=45000 ms
[PERF] total=166234 ms
```

`subtitleDurationMs` 已包含 `alignDurationMs`，不要把二者简单相加当作字幕总耗时。

## 最小运行验证

这些测试接口只用于本地开发诊断，不要暴露到公网；LLM/Dify 自检只返回 `configured=true/false`，不会回显真实 API Key。

编译验证：

```powershell
cd G:\视频分析
python -m py_compile .\extract_subtitle_whisperx.py
cd .\spring_boot
mvn clean compile
```

启动服务：

```powershell
cd G:\视频分析\spring_boot
mvn spring-boot:run
```

环境自检：

```powershell
Invoke-RestMethod http://localhost:8080/api/test/health/full
```

字幕链路验证：

```powershell
Invoke-RestMethod http://localhost:8080/api/test/subtitle-script `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"videoPath":"G:\\视频分析\\video\\demo.mp4","language":"auto"}'
```

LLM 链路验证：

```powershell
Invoke-RestMethod http://localhost:8080/api/test/llm `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"text":"这是一段测试字幕","language":"zh"}'
```

Legacy Dify 链路验证：

```powershell
Invoke-RestMethod http://localhost:8080/api/test/dify `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"text":"这是一段测试字幕","language":"zh"}'
```

下载链路验证：

```powershell
Invoke-RestMethod http://localhost:8080/api/test/downloader `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"url":"https://..."}'
```

字幕翻译轨道验证：

```powershell
Invoke-RestMethod http://localhost:8080/api/test/translate-subtitles `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"targetLanguage":"zh","segments":[{"start":0.0,"end":2.5,"text":"hello everyone"},{"start":2.5,"end":5.0,"text":"today we talk about AI"}]}'
```

Smoke 脚本：

```powershell
cd G:\视频分析
.\scripts\smoke_test.ps1
.\scripts\smoke_test.ps1 -VideoPath "G:\视频分析\video\demo.mp4"
.\scripts\smoke_test.ps1 -LlmTest
.\scripts\smoke_test.ps1 -SubtitleTranslateTest
.\scripts\smoke_test.ps1 -VideoUrl "https://..."
.\scripts\smoke_test.ps1 -VideoUrlCacheTest "https://..."
```

完整端到端验证：打开 `G:\视频分析\test_frontend.html`，上传一个 30 秒以内小视频，观察状态从 `processing` / `extracting_subtitle` / `analyzing` / `completed` 变化，并确认播放器下方同步显示原文和中文字幕。

验证结果要分开判断：

- 编译通过：只代表 Java/Python 语法没问题。
- Health check 通过：代表基础环境依赖可用。
- 字幕测试通过：代表 WhisperX 字幕链路可用。
- 下载测试通过：代表 yt-dlp 下载链路可用。
- LLM 测试通过：代表 `LLM_API_KEY`、模型和 Chat Completions 接口可用。
- 字幕翻译测试通过：代表字幕级翻译轨道可用。
- Legacy Dify 测试通过：只代表旧 Dify Key 和工作流可用。
- 前端上传完成：才代表完整端到端跑通。

## 核心调用链

1. `VideoController` 接收上传或链接请求。
2. `VideoService` 计算 MD5，命中完成缓存就直接返回 `completed`。
3. 本地上传新视频入库为 `processing`；链接任务先入库为 `downloading`，下载完成后进入 `processing`。
4. 后台任务进入 `extracting_subtitle` 后，`SubtitleExtractionService` 调 Python 脚本，脚本先转录，再做 forced alignment，失败时可回退原始 segment 时间戳，最后生成 `_subtitle.json`。
5. 字幕完成后，`SubtitleTranslationService` 分批生成字幕级中文翻译轨道，写入 `translatedSubtitleData`。
6. 翻译字幕完成后任务进入 `analyzing`，`LlmAnalysisService` 把字幕文本提交给 OpenAI-compatible Chat Completions API。
7. 分析结果写回当前数据库的 `videos` 表，状态改为 `completed`；异常时写入 `failed` 和 `errorMessage`。
8. 前端轮询 `/analysis/{videoId}`，用 `status` 判断是否继续轮询，并在播放时按 `currentTime` 同步显示双语字幕。

## 注意事项

- 必须配置 `LLM_API_KEY`，否则字幕提取后会在 AI 分析阶段失败；不再要求 `DIFY_API_KEY`。
- `/api/test/*` 诊断接口只建议本地开发使用，不建议公网暴露。
- 字幕 JSON 会包含 `language` 和 `segments`；每个 segment 至少有 `start`、`end`、`text`，align 成功时可能额外保留 `words`。
- API subtitles 会返回 `text/sourceText/translatedText`；旧缓存没有 `translatedSubtitleData` 时会回退为原文字幕。
- `downloader.max-size-mb` 现在会同时传给 `yt-dlp --max-filesize`，下载后也会二次校验。
- 播放和字幕接口只允许读取 `uploads` 目录下的文件。
- H2 是嵌入式文件库，同一时间不要用多个进程独占打开 `data/videodb.mv.db`。
- MySQL profile 不依赖 H2 文件，适合长期保存分析记录；但仍要同时备份 `uploads/`，否则数据库里的 `videoUrl/filePath` 会指向不存在的视频文件。
- URL 缓存和 fileHash 缓存都依赖当前数据库中的 `videos` 记录；切换 H2/MySQL 后，缓存命中范围也会随数据库切换。
- 新增任务状态字段、耗时字段和 MySQL 表结构由 Hibernate `ddl-auto:update` 自动补列；生产环境建议改用 Flyway/Liquibase。
- `uploads` 和 `data` 是本地运行产物，不建议提交到版本库。
