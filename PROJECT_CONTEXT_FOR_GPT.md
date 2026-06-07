# 项目上下文给 GPT

## 当前阶段重点

第一阶段已完成 WhisperX 字幕链路修复：字幕脚本现在执行 WhisperX 转录 + forced alignment，并保留 forced alignment 失败回退。

第二阶段已补全后端任务状态字段：前端和 API 可以通过 `status`、`stage`、`progress`、`errorMessage` 明确判断任务卡在哪一步，不再通过 `summary` 文本猜测处理状态。

第三阶段已增加全链路耗时统计：后端保存并返回下载、字幕、forced alignment、AI 分析和总耗时，后端会打印 `[PERF]` 性能日志。普通前端默认不展示工程耗时，避免给用户制造“系统很慢”的感受；只有 `test_frontend.html?debug=1` 调试模式才展示耗时和系统自检。

第四阶段已建立最小端到端验证能力：新增本地环境自检、字幕脚本/AI/下载器分段测试接口和 `scripts/smoke_test.ps1`。前端系统自检区域已改为 debug 模式下显示，避免普通用户看到技术检查项。目标是确认项目不仅能编译，还能定位真实运行链路卡点。

第五阶段目标不是新增业务功能，而是让本机真正跑通一次端到端。当前真实状态：WhisperX 字幕链路、yt-dlp、LLM smoke test、字幕 smoke test 都已跑通过；YouTube 链接下载受 YouTube 风控影响，必要时需要浏览器 cookies。

第六阶段新增本机依赖安装辅助脚本，不改核心业务代码：`scripts/check_env.ps1` 用于检查当前 PowerShell/Python/Torch/CUDA/WhisperX/yt-dlp/ffmpeg/conda/环境变量；`scripts/setup_env.ps1` 用于在用户确认后安装 WhisperX 和 yt-dlp，默认不重装 torch、不覆盖 CUDA 可用 torch、不删除 conda 环境、不写真实 API Key。

第七阶段已移除 Dify 强依赖：字幕提取后默认调用 `LlmAnalysisService`，通过 OpenAI-compatible Chat Completions API 生成 `summary` 和 `highlights`。`DifyService` 和 `/api/test/dify` 仍保留为 legacy 诊断入口，但不再作为主链路，也不再要求 `DIFY_API_KEY`。

第八阶段已新增 URL 级缓存：`/api/video/upload-url` 会先规范化 URL 并计算 `urlHash`，如果同一规范化链接已有 `completed` 记录且本地视频文件仍存在，会直接返回历史结果，不调用 yt-dlp、WhisperX 或 LLM。fileHash 缓存仍保留，用于不同链接下载到同一文件时复用字幕/总结/高光。

第九阶段已新增字幕级翻译轨道与播放同步显示：字幕提取后会先生成 `translatedSubtitleData`，API subtitles 返回 `text/sourceText/translatedText`，前端播放器下方新增“预处理同步翻译模式”区域，按 `currentTime` 同步显示当前原文和中文字幕。该能力面向录播演讲、技术分享、网课，不是麦克风实时同传。

第十阶段已完成真实端到端验收与 Demo 打磨：本机准备了 `video/demo_english_30s.mp4` 作为短英文 Demo 素材，前端同步字幕区域改为“同步双语字幕”，并避免翻译缺失时把同一句英文重复展示两遍。

第十一阶段已新增 MySQL 持久化 profile：默认 H2 本地开发模式保留，新增 `application-mysql.yml`、MySQL Connector/J、`scripts/init_mysql.sql` 和 `scripts/start_backend_mysql.ps1`。MySQL 用于长期保存 `videos` 表中的元数据、字幕、翻译字幕、summary/highlights、URL 缓存和 fileHash 缓存；视频文件本体仍保存在 `uploads/`。

第十二阶段已进入运行验收与交付固化：新增 Demo 指南、故障排查文档、`scripts/demo_check.ps1` 和 `scripts/verify_mysql.ps1`。本阶段不新增核心业务能力，只让用户明确知道怎么启动、怎么检查、怎么演示、遇到 LLM/MySQL/YouTube/WhisperX 常见问题时怎么排障。

第十三阶段新增“准实时同传模式”Demo：在不改动原有高精度视频分析主链路的前提下，新增 `realtime_demo.html`、`/api/realtime/*` 后端接口、内存 session 和 `scripts/realtime_transcribe.py`。该模式通过本地媒体音频分片、轻量 WhisperX chunk 转录、异步 LLM 分片翻译和最近约 15 秒字幕修正，模拟“准实时单向音频流同传 Demo”。它识别的是视频或音频里的语音音轨，不是画面字幕 OCR；它不写数据库、不引入 Redis/WebSocket，不应描述为工业级真正实时同传。

近期真实排障已完成以下关键调整：

- PowerShell 的 `conda activate whisperx_env` 曾因 conda hook 传入空字符串参数失败，已在用户 PowerShell profile 中修复 wrapper，当前 `conda activate whisperx_env` 可正常使用。
- 本地后端启动推荐使用 `scripts/start_backend_local.ps1`，它会自动停止旧 Spring Boot，避免 H2 文件锁；自动设置 WhisperX Python、yt-dlp、ffmpeg、缓存目录、LLM 配置，并提示安全输入 Key。
- `scripts/start_backend_local.ps1` 默认进入“快模式”：`WHISPER_ENABLE_ALIGN=false`。如需词级或更精细 forced alignment，手动加 `-EnableAlign`。
- 真实测试中，一个 11 分 58 秒中文 YouTube 视频在开启 forced alignment 时字幕阶段约 6 分 15 秒，其中对齐尝试约 5 分 15 秒且最终 `aligned=false`；关闭 forced alignment 后同视频字幕脚本约 69.5 秒完成。结论：普通总结/高光场景默认关闭 forced alignment 更适合，保留 segment 级时间戳即可。
- YouTube 下载曾下载到 DASH 分离格式 `f399.mp4` 纯视频和 `f140.m4a` 纯音频，导致 WhisperX 报 `Failed to load audio`。`DownloadService` 已改为优先下载带音频的单文件 mp4，优先 <=720p，并在下载后强制校验同时包含视频流和音频流。
- YouTube 经常出现 `HTTP 429 Too Many Requests`、`Sign in to confirm you're not a bot`，这是 YouTube 风控，不是 WhisperX/LLM/前端问题。`DownloadService` 已新增 `DOWNLOADER_COOKIES_FROM_BROWSER`、`DOWNLOADER_COOKIES_FILE`、`DOWNLOADER_JS_RUNTIMES`、`DOWNLOADER_SLEEP_REQUESTS_SEC` 配置入口；启动脚本会自动使用 Node 作为 JS runtime（如果本机存在）。
- `FFMPEG_PATH=ffmpeg` 会让 yt-dlp 误报 `ffmpeg-location ffmpeg does not exist`；现在启动脚本优先使用 `whisperx_env` 中的完整 `ffmpeg.exe` 路径，后端也不会再把裸命令 `ffmpeg` 传给 `--ffmpeg-location`。
- 前端已隐藏普通用户不需要的系统自检、工程耗时和固定百分比进度。`progress` 仍由后端返回用于 API/调试，但普通页面只展示阶段文案。
- 前端语言切换会调用 `/api/video/reanalyze/{videoId}?language=...`，后端再调用 `LlmAnalysisService.translateAnalysis()`。前端已加语言结果缓存，同一语言首次切换会调 LLM，之后切回同语言直接走缓存。
- `Video` 实体新增 `sourceUrl`、`normalizedUrl`、`urlHash` 三个字段，H2 通过 `ddl-auto:update` 兼容旧数据；本阶段没有给 `urlHash` 加唯一约束。
- 新增 `UrlNormalizeUtil`：去掉 fragment，小写 host，删除 `utm_*`、`fbclid`、`gclid` 等 tracking 参数，按 query key 排序，保留 YouTube 的 `v/list/t` 等有意义参数，不展开 `youtu.be` 短链。
- URL 相关日志只打印 `urlHash` 或脱敏后的 URL，不打印完整 query value，避免 token 泄露。
- `Video` 实体新增 `translatedSubtitleData` 字段，保存字幕级翻译轨道 JSON；旧缓存没有该字段时，API 会把 `translatedText` fallback 为原文。
- 新增 `SubtitleTranslationService`：每批最多 30 条字幕或约 4000 字符，调用 LLM 只返回 `{index, translatedText}`，后端按 index 合并并保留原始 `start/end`；单批失败只回退该批原文，不让整个视频任务失败。
- 已补旧缓存兼容：`completed` 视频如果有 `subtitleData` 但没有 `translatedSubtitleData`，`GET /analysis/{videoId}` / 缓存响应会尝试严格补生成翻译字幕；只有翻译成功才写入数据库，失败时临时 fallback 原文，不会把英文 fallback 固化。
- 第十阶段 Demo 打磨中已从 `video/33116455914-1-192.mp4` 截取 `video/demo_english_30s.mp4`，用于本地 30 秒英文视频验收。
- 前端同步字幕区域标题已调整为“同步双语字幕”，副标题为“系统已预处理字幕翻译，播放时自动跟随视频进度显示。”；同步匹配增加 0.25 秒容差，避免时间戳小间隙导致字幕闪空。
- 前端字幕列表和同步字幕区域已避免把同一句英文重复显示两遍：只有 `translatedText` 与 `sourceText` 实际不同才显示为中文字幕；否则显示“中文字幕待生成”（或中文源视频显示“原文已是中文”）。真正生成中文翻译仍依赖新后端重启并成功调用 `/api/test/translate-subtitles` / `SubtitleTranslationService`。
- `Video` 长文本字段已适配 MySQL：`subtitleData`、`translatedSubtitleData`、`aiSummary`、`highlights`、`errorMessage` 使用 `@Lob`，URL 字段长度提升到 2048，`fileHash/urlHash` 保持 64 字符，并给 `fileHash/urlHash/status/uploadTime` 加了 JPA 表索引（实际物理列名由 Hibernate 命名策略生成）。
- `/api/test/health/full` 的数据库检查已从固定 `h2-database` 改为通用 `database`，并额外返回 `spring-profiles`；JDBC URL 会脱敏密码参数，不回显数据库密码。
- `scripts/start_backend_mysql.ps1` 会设置 `SPRING_PROFILES_ACTIVE=mysql`，安全读取 `MYSQL_PASSWORD` 和 `LLM_API_KEY`，并复用 WhisperX、yt-dlp、ffmpeg、cache 和 LLM 环境变量设置逻辑。
- `docs/DEMO_GUIDE.md` 记录最终推荐演示路径：H2 默认模式、本地上传 `video/demo_english_30s.mp4`，不推荐现场首次演示 YouTube 下载。
- `docs/TROUBLESHOOTING.md` 汇总旧后端 404、H2 file locked、LLM Key 缺失、MySQL Access denied、mysql.exe 不在 PATH、YouTube 风控、WhisperX 慢和字幕不显示中文等常见问题。
- `scripts/demo_check.ps1` 是交付前只读检查脚本：调用 ping、health、LLM、字幕翻译和 demo 视频字幕脚本，不自动上传视频到主业务表。
- `scripts/verify_mysql.ps1` 用于检查 mysql.exe、验证 MySQL 登录并执行 `scripts/init_mysql.sql`；密码只在当前进程/子进程环境中使用，不写入文件。
- 准实时同传模式新增独立路径：`realtime_demo.html` 调用 `/api/realtime/session/start` 创建内存 session；前端优先用 `captureStream + WebAudio` 捕获播放中的语音音轨，约每 3 秒生成一个独立 16kHz mono WAV 分片发送到 `/api/realtime/chunk`。前端最多缓存 30 个待处理 chunk，队列满时丢弃较旧分片。后端调用 `scripts/realtime_transcribe.py` 常驻 worker 识别 chunk，`/chunk` 只等待 ASR 并先返回英文原文；中文翻译改为后台异步任务复用 `SubtitleTranslationService` 补齐，前端每 1 秒轮询 `/api/realtime/session/{sessionId}/subtitles` 刷新译文。`LLM_API_KEY` 缺失时后台翻译写入占位提示，不让 Demo 因翻译缺失崩溃。不要把前端改回 MediaRecorder webm 分片，实际测试中过 `EBML header parsing failed`。若后续继续优化实时性能，建议 realtime 模式改用 `faster-whisper` 常驻模型。

字幕脚本 `extract_subtitle_whisperx.py` 当前设计：

1. 使用 `whisperx.load_model()` 加载模型。
2. 使用 `model.transcribe()` 转录音频。
3. 使用 `whisperx.load_align_model(language_code=detected_language, device=device)` 加载对齐模型。
4. 使用 `whisperx.align()` 做 forced alignment。
5. 如果 forced alignment 失败，且 `WHISPER_ALIGN_FALLBACK=true`，会打印 stderr warning，并回退到 Whisper 原始 segment 时间戳。
6. 输出 JSON 保持 Java 可解析结构：

```json
{
  "language": "zh",
  "text": "完整字幕文本",
  "segments": [
    {
      "start": 0.0,
      "end": 1.2,
      "text": "字幕文本",
      "words": []
    }
  ],
  "aligned": true,
  "alignDurationMs": 30000
}
```

`words` 字段只在 align 结果包含词级信息时出现；Java 端旧逻辑只依赖 `segments[].start/end/text`。`alignDurationMs` 用于 Java 端保存 forced alignment 真实耗时。

## WhisperX 环境变量

```powershell
$env:WHISPER_MODEL="base"
$env:WHISPER_DEVICE="auto"
$env:WHISPER_COMPUTE_TYPE=""
$env:WHISPER_BATCH_SIZE="16"
$env:WHISPER_ENABLE_ALIGN="true"
$env:WHISPER_ALIGN_FALLBACK="true"
```

默认行为：

- `WHISPER_MODEL=base`
- `WHISPER_DEVICE=auto`，自动选择 `cuda` 或 `cpu`
- `WHISPER_COMPUTE_TYPE` 留空时，CPU 用 `int8`，CUDA 用 `float16`
- `WHISPER_BATCH_SIZE=16`
- `WHISPER_ENABLE_ALIGN=true` 是脚本/配置层默认值，但本机推荐通过 `scripts/start_backend_local.ps1` 启动，脚本默认设为 `false` 以避免长视频 forced alignment 过慢
- `WHISPER_ALIGN_FALLBACK=true`

本机推荐启动方式：

```powershell
cd G:\视频分析
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
```

MySQL 持久化模式：

```powershell
cd G:\视频分析
mysql -u root -p < scripts/init_mysql.sql
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="你的密码"
$env:MYSQL_URL="jdbc:mysql://localhost:3306/video_analysis?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
.\scripts\start_backend_mysql.ps1 -LlmModel "gpt-5.5"
```

说明：H2 适合快速开发和临时 Demo；MySQL 适合长期保存分析结果、字幕、翻译字幕和缓存字段。MySQL 不保存 mp4 文件本体，`uploads/` 目录仍需要保留和备份。当前阶段用 `ddl-auto=update` 简化建表，生产环境建议改为 Flyway/Liquibase。

如确实需要 forced alignment：

```powershell
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5" -EnableAlign
```

## LLM 环境变量

默认 AI 分析使用 OpenAI-compatible Chat Completions API：

```powershell
$env:LLM_API_URL="https://fast.smartaipro.cn/v1/chat/completions"
$env:LLM_API_KEY="你的 API Key"
$env:LLM_MODEL="gpt-5.5"
$env:LLM_TIMEOUT_SEC="120"
$env:LLM_TEMPERATURE="0.2"
$env:LLM_MAX_INPUT_CHARS="20000"
```

说明：

- `LLM_API_URL` 默认 `https://api.openai.com/v1/chat/completions`。
- `LLM_API_KEY` 必须配置后才能完成 AI 分析；health check 只返回 `configured=true/false`，不能回显真实 Key。
- `LLM_MODEL` 默认 `gpt-4o-mini`；如果使用中转站，模型名以控制台支持的聊天模型为准。
- `LLM_MAX_INPUT_CHARS` 超长字幕会先截断，本阶段不做 map-reduce。
- Dify 配置保留 legacy，但不再是主链路，不再要求 `DIFY_API_KEY`。

## 相关文件

- `extract_subtitle_whisperx.py`：WhisperX 转录和 forced alignment 主逻辑。
- `spring_boot/src/main/java/com/video/service/SubtitleExtractionService.java`：后端调用 Python 脚本，并把 `WHISPER_*` 配置注入 Python 子进程。
- `spring_boot/src/main/resources/application.yml`：后端配置默认值。
- `spring_boot/src/main/resources/application-mysql.yml`：MySQL profile 配置，通过 `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD` 读取连接信息，关闭 H2 console。
- `spring_boot/src/main/java/com/video/entity/Video.java`：视频实体，包含任务状态字段和 `downloadDurationMs`、`subtitleDurationMs`、`alignDurationMs`、`analysisDurationMs`、`totalDurationMs` 耗时字段。
- `spring_boot/src/main/java/com/video/dto/VideoAnalysisResponse.java`：API 响应 DTO，返回任务状态和耗时字段。
- `spring_boot/src/main/java/com/video/service/VideoService.java`：上传、链接下载、URL 缓存、fileHash 缓存、字幕提取、字幕翻译轨道、LLM 分析、耗时统计和 `[PERF]` 日志逻辑。
- `spring_boot/src/main/java/com/video/service/DownloadService.java`：yt-dlp 下载逻辑；当前优先下载带音频的 <=720p 单文件 mp4，下载后校验音视频流，支持 YouTube cookies、JS runtime、代理、大小/时长限制。
- `spring_boot/src/main/java/com/video/service/LlmAnalysisService.java`：OpenAI-compatible Chat Completions 调用、Prompt、结果 JSON 提取和高光字段规范化。
- `spring_boot/src/main/java/com/video/service/SubtitleTranslationService.java`：字幕级翻译轨道生成；分批翻译 segment 文本，保留时间戳，失败批次回退原文。
- `spring_boot/src/main/java/com/video/service/DifyService.java`：legacy Dify 工作流调用，仅用于旧测试接口。
- `spring_boot/src/main/java/com/video/util/UrlNormalizeUtil.java`：URL 规范化、`urlHash` 计算和日志脱敏工具。
- `spring_boot/src/main/java/com/video/controller/HealthCheckController.java`：本地开发诊断接口，提供 `/api/test/health/full`、`/api/test/subtitle-script`、`/api/test/llm`、legacy `/api/test/dify`、`/api/test/downloader`。
- `spring_boot/src/main/java/com/video/dto/SystemCheckResponse.java`：完整环境自检响应 DTO。
- `test_frontend.html`：前端轮询 `/analysis/{videoId}`，根据 `status` 判断继续等待、完成或失败。普通模式隐藏系统自检、工程耗时和假百分比；`?debug=1` 时显示诊断信息。
- `scripts/smoke_test.ps1`：PowerShell smoke test，调用 ping、health、字幕脚本、LLM、legacy Dify 和下载器测试接口。
- `scripts/check_env.ps1`：本机依赖检查脚本。
- `scripts/setup_env.ps1`：本机依赖安装辅助脚本，支持创建 conda 环境、安装 WhisperX、安装 yt-dlp。
- `scripts/start_backend_local.ps1`：推荐本地启动脚本，会停止旧后端、设置 WhisperX/yt-dlp/ffmpeg/cache/LLM 环境变量，默认关闭 forced alignment，支持 `-EnableAlign`、`-DownloaderCookiesFromBrowser`、`-DownloaderCookiesFile`。
- `scripts/init_mysql.sql`：创建 `video_analysis` 数据库，字符集为 `utf8mb4`。
- `scripts/start_backend_mysql.ps1`：MySQL profile 启动脚本，会设置 `SPRING_PROFILES_ACTIVE=mysql` 并安全读取 MySQL 密码。
- `scripts/demo_check.ps1`：Demo readiness 检查脚本，不写主业务表。
- `scripts/verify_mysql.ps1`：MySQL 客户端、登录和初始化 SQL 验证脚本。
- `docs/DEMO_GUIDE.md`：最终 Demo 演示指南。
- `docs/TROUBLESHOOTING.md`：常见故障排查手册。
- `realtime_demo.html`：准实时同传 Demo 页面，本地选择音视频文件，分片发送音频 chunk，滚动显示原文、中文翻译和 `provisional/final` 状态。
- `scripts/realtime_transcribe.py`：轻量实时分片转录脚本，使用 WhisperX 转录音频 chunk，不执行 forced alignment，stdout 输出 JSON。
- `spring_boot/src/main/java/com/video/realtime/controller/RealtimeController.java`：`/api/realtime/*` 接口入口。
- `spring_boot/src/main/java/com/video/realtime/service/RealtimeInterpretationService.java`：准实时内存 session、chunk 保存、Python 转录调用、字幕翻译、最近字幕修正和 final 标记逻辑。
- `README.md` 和 `spring_boot/README.md`：运行说明和环境变量说明。

## 本地验证接口

这些接口只用于本地开发诊断，不要暴露到公网。任何接口都不能返回真实 `LLM_API_KEY`、`DIFY_API_KEY` 或其他密钥；配置检查只返回 `configured=true/false`。

- `GET /api/test/ping`：基础连通性，返回 `pong`。
- `GET /api/test/health/full`：完整环境自检，检查 Java 服务、上传目录、Python、WhisperX、Torch、CUDA、Whisper 配置、yt-dlp、ffmpeg、LLM 配置、legacy Dify 配置、active profile 和当前数据库。
- `POST /api/test/subtitle-script`：请求 `{ "videoPath": "本地视频绝对路径", "language": "auto" }`，只调用 `SubtitleExtractionService.extractSubtitle()`，不调用 LLM、不写业务表；返回 `ok/language/aligned/segmentCount/firstSegments/alignDurationMs/subtitleDurationMs/errorMessage`。
- `POST /api/test/llm`：请求 `{ "text": "这是一段测试字幕", "language": "zh" }`，只调用 `LlmAnalysisService.analyzeSubtitle()`；如果 `LLM_API_KEY` 为空，返回 `LLM_API_KEY 未配置。`。
- `POST /api/test/translate-subtitles`：请求 `{ "targetLanguage": "zh", "segments": [{"start":0.0,"end":2.5,"text":"hello everyone"}] }`，只调用 `SubtitleTranslationService`；如果 `LLM_API_KEY` 为空，返回 `LLM_API_KEY 未配置。`。
- `POST /api/test/dify`：legacy，请求 `{ "text": "这是一段测试字幕", "language": "zh" }`，只调用旧 `DifyService.analyzeSubtitle()`。
- `POST /api/test/downloader`：请求 `{ "url": "视频链接" }`，只调用 `DownloadService.downloadVideo()`，不调用 WhisperX/LLM；返回 `ok/filePath/fileSize/durationMs/errorMessage`。
- `POST /api/realtime/session/start`：创建准实时同传内存 session，返回 `sessionId`。
- `POST /api/realtime/chunk`：multipart 接收 `sessionId/chunkStartMs/chunkEndMs/audioChunk`，返回当前 session 字幕片段；每条包含 `startMs/endMs/originalText/translatedText/status/updatedAt`，翻译失败或 Key 缺失时返回 `warning`。
- `GET /api/realtime/session/{sessionId}/subtitles`：返回当前 session 的所有实时字幕片段。
- `POST /api/realtime/session/{sessionId}/finish`：将剩余 `provisional` 字幕标记为 `final`。

`/api/test/subtitle-script` 会校验 `videoPath` 必须存在、必须是本机普通文件，并限制为常见视频扩展名，避免测试接口读取敏感文件内容。

Smoke 脚本：

```powershell
cd G:\视频分析
.\scripts\smoke_test.ps1
.\scripts\smoke_test.ps1 -VideoPath "G:\视频分析\video\demo.mp4"
.\scripts\smoke_test.ps1 -LlmTest
.\scripts\smoke_test.ps1 -SubtitleTranslateTest
.\scripts\smoke_test.ps1 -VideoUrl "https://..."
.\scripts\smoke_test.ps1 -VideoUrlCacheTest "https://..."
.\scripts\demo_check.ps1
.\scripts\verify_mysql.ps1 -MysqlUsername "root"
```

## Windows 本机安装步骤

WhisperX 安装前先检查当前 Torch。当前机器已检测到 CUDA 可用，不要盲目覆盖现有 torch：

```powershell
python -c "import torch; print(torch.__version__); print(torch.cuda.is_available())"
```

方案 A：conda 环境，推荐。

```powershell
conda create -n whisperx_env python=3.10 -y
conda activate whisperx_env
pip install -U whisperx
python -c "import whisperx, torch; print('whisperx ok'); print(torch.cuda.is_available())"
```

如果需要安装 PyTorch，请根据本机 CUDA 从官方页面选择命令：`https://pytorch.org/get-started/locally/`。

方案 B：当前 Python 环境直接安装。

```powershell
pip install -U whisperx
python -c "import whisperx, torch; print('whisperx ok'); print(torch.cuda.is_available())"
```

yt-dlp：

```powershell
pip install -U yt-dlp
yt-dlp --version
```

如果 `yt-dlp` 命令找不到，可先验证：

```powershell
python -m yt_dlp --version
```

当前 `DownloadService` 按单命令执行 `yt-dlp`，所以优先确保 `yt-dlp --version` 可用；如果只能使用 `python -m yt_dlp`，后续需要适配配置或代码。

LLM：

```powershell
$env:LLM_API_URL="https://fast.smartaipro.cn/v1/chat/completions"
$env:LLM_API_KEY="你的 API Key"
$env:LLM_MODEL="gpt-5.5"
```

如果暂时没有 LLM Key，可以先只跑 `/api/test/subtitle-script` 验证字幕链路。模型名以中转站控制台支持的聊天模型为准；不要写入真实 Key。

推荐首次端到端启动方式：

```powershell
cd G:\视频分析
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
```

如果 YouTube 出现 `Sign in to confirm you're not a bot` 或 `HTTP 429`，使用浏览器 cookies 重新启动：

```powershell
cd G:\视频分析
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5" -DownloaderCookiesFromBrowser "edge"
```

如果平时用 Chrome 登录 YouTube：

```powershell
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5" -DownloaderCookiesFromBrowser "chrome"
```

`start_backend_local.ps1` 会设置：

- `VIDEO_PYTHON_COMMAND=$env:USERPROFILE\.conda\envs\whisperx_env\python.exe`
- `YTDLP_PATH=$env:USERPROFILE\.conda\envs\whisperx_env\Scripts\yt-dlp.exe`
- `FFMPEG_PATH=$env:USERPROFILE\.conda\envs\whisperx_env\Library\bin\ffmpeg.exe`（如果存在）
- `WHISPER_MODEL=tiny`
- `WHISPER_DEVICE=cuda`
- `WHISPER_COMPUTE_TYPE=float16`
- `WHISPER_ENABLE_ALIGN=false`（默认快模式）
- `DOWNLOADER_JS_RUNTIMES=node`（如果本机有 Node）
- HuggingFace/Torch 缓存目录到 `G:\视频分析\.cache`

首次测试建议 `WHISPER_MODEL=tiny`，确认跑通后再改 `base`/`small`。如果显存不足，先降低 `WHISPER_BATCH_SIZE`。如需要精细 forced alignment，启动脚本加 `-EnableAlign`，但长视频可能显著变慢。

推荐验证顺序：

1. `cd G:\视频分析 && .\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"`
2. 新开 PowerShell：`cd G:\视频分析 && .\scripts\smoke_test.ps1`
3. 字幕：`.\scripts\smoke_test.ps1 -VideoPath "G:\视频分析\video\demo.mp4"`
4. 下载：`.\scripts\smoke_test.ps1 -VideoUrl "https://..."`
5. LLM：`.\scripts\smoke_test.ps1 -LlmTest`
6. 字幕翻译：`.\scripts\smoke_test.ps1 -SubtitleTranslateTest`
7. URL 缓存：`.\scripts\smoke_test.ps1 -VideoUrlCacheTest "https://..."`
8. 打开 `test_frontend.html` 上传 30 秒以内小视频，确认状态流转到 `completed`，并确认播放器下方同步双语字幕会随播放时间变化

MySQL 验证顺序是在上述流程前先执行 `scripts/init_mysql.sql` 并用 `scripts/start_backend_mysql.ps1` 启动；`/api/test/health/full` 应看到 `spring-profiles=mysql` 和 `database` 检查通过。

验证结果必须分开记录：编译通过、health check 通过、字幕测试通过、下载测试通过、LLM 测试通过、字幕翻译测试通过、URL 缓存测试通过、完整前端端到端通过是不同层级，不能互相替代。`-DifyTest` 只用于 legacy Dify 工作流。

## 依赖脚本说明

检查当前环境：

```powershell
cd G:\视频分析
.\scripts\check_env.ps1
```

推荐 conda 安装：

```powershell
conda create -n whisperx_env python=3.10 -y
conda activate whisperx_env
.\scripts\setup_env.ps1 -InstallAll
```

或由脚本创建 conda 环境：

```powershell
.\scripts\setup_env.ps1 -CreateCondaEnv -CondaEnvName whisperx_env
conda activate whisperx_env
.\scripts\setup_env.ps1 -InstallAll
```

跳过确认：

```powershell
.\scripts\setup_env.ps1 -InstallAll -Yes
```

`setup_env.ps1` 参数：

- `-CreateCondaEnv`：执行 `conda create -n <CondaEnvName> python=<PythonVersion> -y`。
- `-CondaEnvName`：默认 `whisperx_env`。
- `-PythonVersion`：默认 `3.10`。
- `-InstallWhisperX`：检查 torch/CUDA 后执行 `pip install -U whisperx`；默认不自动安装 torch。
- `-InstallYtDlp`：执行 `pip install -U yt-dlp` 并验证 `yt-dlp --version`，失败时尝试 `python -m yt_dlp --version`。
- `-InstallAll`：等价于 `-InstallWhisperX -InstallYtDlp`。
- `-UseCurrentPython`：明确使用当前 PATH 下的 python/pip。
- `-ForceTorchInstall`：只提示用户去 PyTorch 官方页面选择命令，不硬编码 CUDA 版本。
- `-Yes`：跳过确认。

`smoke_test.ps1` 如果发现 health check 里 `import-whisperx` 或 `yt-dlp` 失败，会提示先运行 `check_env.ps1` 或 `setup_env.ps1 -InstallAll`，但不会自动安装依赖。

## 下载链路真实行为

`DownloadService` 当前使用 yt-dlp 下载在线视频。已做过的关键修复：

- 格式选择从“优先高画质 DASH 分离流”调整为“优先带音频的单文件 mp4，优先 <=720p”。这是为了字幕分析速度和稳定性，不追求最高画质。
- 下载后使用 ffprobe/ffmpeg 校验文件必须同时包含视频流和音频流，避免把 YouTube 的 `.f399.mp4` 纯视频文件交给 WhisperX。
- 如果 yt-dlp 捕获到 `.f399.mp4`、`.f140.m4a` 这类中间格式文件，后端不会把它们当最终视频。
- `FFMPEG_PATH=ffmpeg` 这种裸命令不再传给 `--ffmpeg-location`；如果有完整 ffmpeg.exe 路径才传给 yt-dlp。
- `DOWNLOADER_COOKIES_FROM_BROWSER` 和 `DOWNLOADER_COOKIES_FILE` 用于处理 YouTube 登录/反机器人限制。
- `DOWNLOADER_JS_RUNTIMES` 用于给 yt-dlp 配置 Node/Deno，减少 YouTube 新版解析警告。

YouTube 的真实限制：

- `HTTP Error 429: Too Many Requests`、`Sign in to confirm you're not a bot` 是 YouTube 风控，不能靠 WhisperX 或 LLM 解决。
- 解决方向是稍后重试、换网络/代理，或在本机浏览器登录 YouTube 后用 cookies：

```powershell
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5" -DownloaderCookiesFromBrowser "edge"
```

或：

```powershell
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5" -DownloaderCookiesFromBrowser "chrome"
```

如果浏览器 cookies 方式仍失败，可以导出 Netscape cookies 文件后设置：

```powershell
$env:DOWNLOADER_COOKIES_FILE="G:\视频分析\cookies.txt"
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
```

不要把 cookies 文件提交或发送给别人。

## 任务状态字段

`POST /api/video/upload`、`POST /api/video/upload-url` 和 `GET /api/video/analysis/{videoId}` 返回统一结构：

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

字段含义：

- `status`：机器可读状态，当前包括 `pending`、`downloading`、`processing`、`extracting_subtitle`、`analyzing`、`completed`、`failed`。
- `stage`：当前阶段展示文案。
- `progress`：粗略阶段式进度，不是真实百分比。
- `errorMessage`：失败原因，成功时为空。

状态流转：

- 本地上传新任务：`processing` / `等待处理` / `5`
- 链接下载开始：`downloading` / `正在下载视频` / `5`
- 链接下载完成：`processing` / `视频下载完成，准备提取字幕` / `20`
- 字幕提取开始：`extracting_subtitle` / `正在提取字幕` / `30`
- 字幕提取完成：`stage=字幕提取完成，正在生成翻译字幕` / `progress=60`
- 翻译字幕完成：`stage=翻译字幕完成，正在进行 AI 分析` / `progress=75`
- AI 分析开始：`analyzing` / `正在调用 AI 分析` / `85`
- 全部完成：`completed` / `处理完成` / `100`
- 完成缓存命中：`completed` / `命中缓存` / `100`
- URL 缓存命中：`completed` / `命中链接缓存` / `100`
- 下载后 fileHash 缓存命中：`completed` / `命中文件缓存` / `100`
- 任意失败：`failed` / `处理失败` / `-1`，并写入 `errorMessage`

服务启动时会把数据库中非 `completed` 且非 `failed` 的历史任务标记为失败，避免前端永久轮询；如果旧数据没有 `status` 但已有完整分析结果，会尽量补成 `completed`。

## 缓存机制真实行为

当前缓存分为 **URL 级缓存** 和 **文件哈希缓存** 两层。

本地上传：

- `VideoService.uploadAndAnalyze()` 会先计算上传文件 MD5。
- 如果 `findByFileHash()` 命中 `completed` 记录，直接返回缓存结果，`status=completed`、`stage=命中缓存`、`progress=100`，不再跑字幕和 LLM。

链接上传：

- `VideoService.downloadAndAnalyze()` 现在先执行 `UrlNormalizeUtil.normalize(url)`，再计算 `urlHash`。
- 规范化规则：trim，去 fragment，小写 host，删除 `utm_source`、`utm_medium`、`utm_campaign`、`utm_term`、`utm_content`、`fbclid`、`gclid`，按 query key 排序；保留 YouTube `v/list/t` 等有意义参数；不展开 `youtu.be` 短链。
- 如果 `findTopByUrlHashAndStatusOrderByUploadTimeDesc(urlHash, completed)` 命中，且缓存记录的本地视频文件仍存在，直接返回该 completed 结果：`status=completed`、`stage=命中链接缓存`、`progress=100`。此时不会调用 yt-dlp、WhisperX 或 LLM。
- 如果 URL 缓存命中但本地视频文件已丢失，会打印 `[CACHE] url cache stale`，然后继续走正常下载流程，不会假装成功。
- URL 缓存未命中时才创建 `downloading` 任务并调用 `DownloadService.downloadVideo(url)`。
- 下载完成后计算本地文件 MD5，再查 `findByFileHash()`；如果 fileHash 命中 completed，会复制历史字幕、翻译字幕轨道、总结和高光到当前 URL 任务，状态为 `completed` / `命中文件缓存` / `100`，并保留当前 `sourceUrl/normalizedUrl/urlHash`。这样下一次同 URL 可命中 URL 缓存。
- 旧 completed 记录如果没有 `translatedSubtitleData`，API 响应会 fallback：`sourceText=text`、`translatedText=text`，不破坏缓存命中。
- 因此“已经成功分析过的同一个规范化链接”后续再次复制，通常会直接读数据库秒开；前提是本地视频文件还在。第一次下载仍可能受 YouTube 风控、cookies、网络和 yt-dlp 行为影响。

## 性能字段和日志

`Video` 和 `VideoAnalysisResponse` 包含这些耗时字段：

- `downloadDurationMs`：链接下载耗时；本地上传任务为 `0`。
- `subtitleDurationMs`：字幕脚本整体耗时，包含 Whisper 转录和 forced alignment。
- `alignDurationMs`：WhisperX forced alignment 耗时，来自字幕 JSON 的 `alignDurationMs`。
- `analysisDurationMs`：调用 OpenAI-compatible LLM 分析耗时。
- `totalDurationMs`：后端任务总耗时；链接任务包含下载、字幕和 AI 分析。

任务完成或失败时 `VideoService` 打印：

```text
[PERF] videoId=1
[PERF] download=1234 ms
[PERF] subtitle=120000 ms
[PERF] align=30000 ms
[PERF] analysis=45000 ms
[PERF] total=166234 ms
```

注意：`subtitleDurationMs` 已包含 `alignDurationMs`，不要把二者简单相加当作字幕总耗时。

普通前端默认不展示这些耗时字段，因为真实用户容易把工程耗时理解为“产品很慢”。这些字段主要用于开发诊断、性能分析和 `[PERF]` 日志。访问 `test_frontend.html?debug=1` 时可显示系统自检、处理耗时和更多调试信息。

## 前端轮询逻辑

`test_frontend.html` 当前轮询规则：

- `status=completed`：停止轮询，调用原有 `displayResults(data)` 展示视频、总结、字幕和高光。
- `status=failed`：停止轮询，展示 `errorMessage`。
- 其他状态：继续每 3 秒轮询，并展示 `stage` 阶段文案。普通模式不展示固定百分比，因为当前 `progress` 是阶段式粗略值，不是真实百分比。
- 链接任务初始可能没有 `videoUrl`，下载完成后轮询拿到 `videoUrl` 时才显示播放器。
- 普通模式隐藏“处理耗时”和“系统自检”；debug 模式 `test_frontend.html?debug=1` 才显示。
- 播放器下方新增“预处理同步翻译模式”区域，监听 video `timeupdate/seeked`，根据 `start <= currentTime <= end` 找当前字幕并显示原文和中文字幕；debug 模式额外显示 currentTime/start/end。
- 字幕列表保留点击跳转，同时增强为时间戳、原文、中文字幕三行展示。
- 语言切换按钮会调用 `/api/video/reanalyze/{videoId}?language=...`，后端调用 LLM 翻译总结和高光；前端已做同语言缓存，避免重复切换时反复请求 LLM。
- 下载或分析失败时，前端会把 YouTube 风控、无音轨、JS runtime 等常见技术错误简化为用户可理解文案，避免直接展示整段 yt-dlp 日志。

## 不在本阶段修改的范围

- 生产级数据库迁移体系（Flyway/Liquibase）和 H2 历史数据自动搬迁到 MySQL。
- 大规模前端 UI 重构。
- 视频下载器大架构。
- 把本地诊断接口当作公网运维接口使用。
