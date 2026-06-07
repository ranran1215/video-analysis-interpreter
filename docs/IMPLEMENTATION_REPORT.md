# Implementation Report

本文用于说明项目的实现内容、技术选型、原创实现范围、第三方依赖和验证方式，方便评审快速理解当前提交版代码。

## 1. 项目目标

本项目围绕“AI 同声传译助手”方向，提供两种演示模式：

- 高精度视频分析模式：面向录播视频，先完成视频上传或链接下载，再通过 WhisperX 生成字幕、调用 LLM 生成中文字幕、summary 和 highlights，最后在播放器中按时间轴同步展示双语字幕。
- 准实时同传模式：面向 Demo 演示，通过本地媒体音频分片、增量 ASR、异步翻译和最近字幕修正机制，模拟单向音频流的准实时识别与翻译。

准实时模式识别的是视频或音频中的语音音轨，不是画面字幕 OCR；该模式不是工业级实时同传系统。

## 2. 原创实现范围

本项目主要自主实现了以下部分：

- Spring Boot 后端业务链路：视频上传、URL 下载、任务状态流转、缓存命中、字幕提取调度、字幕翻译、LLM 分析和诊断接口。
- 高精度模式前端页面：本地上传、链接提交、任务轮询、视频播放、summary/highlights 展示、双语字幕列表和同步双语字幕显示。
- 准实时模式前端页面：本地媒体播放、音频捕获、WAV 分片、分片队列、字幕滚动显示、翻译轮询刷新和 provisional/final 状态展示。
- 准实时后端接口：内存 session、chunk 接收、ASR 先返回、LLM 异步补齐翻译、最近字幕修正窗口和 finish 接口。
- PowerShell 辅助脚本：本地启动、自检、smoke test、MySQL 验证和 Demo readiness 检查。
- 文档体系：Demo 指南、故障排查、GitHub 上传说明、提交配置说明和项目结构说明。

## 3. 第三方依赖

项目使用了以下第三方框架、库或工具：

- Java 后端：Spring Boot、Spring MVC、Spring Data JPA、H2、MySQL Connector/J、OkHttp、Gson。
- Python/ASR：WhisperX、PyTorch、可选 OpenCC。
- 视频下载与音视频处理：yt-dlp、ffmpeg/ffprobe。
- LLM：OpenAI-compatible Chat Completions API；legacy Dify 诊断接口仍保留，但不是默认主链路。
- 前端：原生 HTML、CSS、JavaScript，未引入前端框架。

以上第三方模型、框架和工具不声明为原创；项目原创部分主要是业务编排、接口设计、前端交互、缓存策略、字幕同步、准实时分片 Demo 和工程化脚本。

## 4. 核心实现思路

### 高精度视频分析模式

链路如下：

```text
本地上传或 URL 下载
-> 文件哈希或 URL 缓存检查
-> WhisperX 字幕识别
-> 字幕级中文翻译
-> LLM 生成 summary/highlights
-> 前端同步双语字幕播放
```

该模式优先保证字幕质量和完整分析结果，适合录播演讲、技术分享和网课等场景。

### 准实时同传模式

链路如下：

```text
本地媒体播放
-> 前端捕获语音音轨并生成短音频分片
-> /api/realtime/chunk
-> WhisperX worker 增量识别
-> 英文原文先返回并显示
-> 后台 LLM 异步补齐中文字幕
-> 前端轮询刷新翻译结果
```

该模式保留最近约 15 秒字幕为 `provisional`，允许被后续分片修正；超过窗口后标记为 `final`。当前实现以 Demo 稳定性为优先，没有引入 WebSocket、Redis 或生产级流式服务。

## 5. 验证方式

推荐验证命令：

```powershell
cd video-analysis-interpreter
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
```

新开 PowerShell：

```powershell
cd video-analysis-interpreter
.\scripts\demo_check.ps1
.\scripts\smoke_test.ps1
.\scripts\smoke_test.ps1 -LlmTest
.\scripts\smoke_test.ps1 -SubtitleTranslateTest
.\scripts\smoke_test.ps1 -VideoPath "C:\path\to\your\demo.mp4"
```

前端验收：

- 打开 `test_frontend.html`，上传本地短视频，等待任务完成后检查 summary、highlights、字幕列表和同步双语字幕。
- 打开 `realtime_demo.html`，选择本地视频或音频，点击开始后检查英文识别是否先出现、中文翻译是否随后补齐、字幕状态是否从“修正中”变为“已确认”。

## 6. 已知限制

- 准实时模式是“准实时单向音频流同传 Demo”，不是工业级低延迟同传系统。
- 当前 realtime ASR 仍复用 WhisperX 能力，后续若继续优化实时性能，建议改为 faster-whisper 常驻模型。
- YouTube 链接下载可能受平台风控影响，比赛或交付演示推荐优先使用本地视频。
- LLM 翻译和 summary/highlights 需要配置有效 `LLM_API_KEY`；未配置时，准实时模式会显示翻译占位，不应导致页面崩溃。
- MySQL 是可选持久化增强，默认 Demo 路线仍使用 H2。

## 7. 提交前检查

提交前应确认：

- README 已列出主要第三方依赖和原创实现范围。
- `.gitignore` 已排除视频、数据库、日志、缓存、密钥和 cookies。
- 没有提交真实 `LLM_API_KEY`、MySQL 密码、cookies 或 token。
- 默认启动方式仍是 H2 本地 Demo。
- `test_frontend.html` 和 `realtime_demo.html` 的默认 API 地址仍指向 `http://localhost:8080`。
