# 项目结构说明

本文用于让接手者或评审快速理解项目目录，不改变现有代码结构。

## 顶层目录

```text
G:\视频分析
├── spring_boot/                    # Spring Boot 后端
├── scripts/                        # PowerShell 和 Python 辅助脚本
├── docs/                           # 演示、故障排查、提交配置说明
├── test_frontend.html              # 高精度视频分析页面
├── realtime_demo.html              # 准实时同传展示页面
├── extract_subtitle_whisperx.py    # 高精度模式 WhisperX 字幕脚本
├── README.md                       # 项目主说明
├── PROJECT_CONTEXT_FOR_GPT.md      # 项目阶段上下文
├── DIFY_PROMPT.txt                 # Legacy Dify prompt
└── 链接上传功能指南.md             # 链接上传历史说明
```

## 后端目录

```text
spring_boot/
├── pom.xml
├── src/main/java/com/video/
│   ├── VideoAnalysisApplication.java
│   ├── controller/                 # 视频分析与本地诊断接口
│   ├── service/                    # 下载、字幕、翻译、分析主逻辑
│   ├── realtime/                   # 准实时同传接口、DTO 和服务
│   ├── entity/                     # JPA 实体
│   ├── repository/                 # 数据库访问
│   ├── dto/                        # API 响应和请求 DTO
│   ├── util/                       # URL 规范化工具
│   └── config/                     # CORS 配置
└── src/main/resources/
    ├── application.yml             # 默认 H2 配置
    └── application-mysql.yml       # 可选 MySQL profile
```

## 两种核心模式

### 高精度视频分析模式

入口：

```text
test_frontend.html
```

核心链路：

```text
本地上传或 URL 下载
-> WhisperX 完整字幕识别
-> 字幕级中文翻译
-> summary/highlights
-> 同步双语字幕播放
```

关键文件：

- `spring_boot/src/main/java/com/video/service/VideoService.java`
- `spring_boot/src/main/java/com/video/service/DownloadService.java`
- `spring_boot/src/main/java/com/video/service/SubtitleExtractionService.java`
- `spring_boot/src/main/java/com/video/service/SubtitleTranslationService.java`
- `spring_boot/src/main/java/com/video/service/LlmAnalysisService.java`
- `extract_subtitle_whisperx.py`

### 准实时同传模式

入口：

```text
realtime_demo.html
```

核心链路：

```text
本地媒体播放
-> 前端捕获音频并生成小段音频
-> /api/realtime/chunk
-> WhisperX worker ASR
-> 英文先显示
-> LLM 后台异步补中文
```

关键文件：

- `spring_boot/src/main/java/com/video/realtime/controller/RealtimeController.java`
- `spring_boot/src/main/java/com/video/realtime/service/RealtimeInterpretationService.java`
- `spring_boot/src/main/java/com/video/realtime/dto/RealtimeChunkResponse.java`
- `spring_boot/src/main/java/com/video/realtime/dto/RealtimeSessionResponse.java`
- `spring_boot/src/main/java/com/video/realtime/dto/RealtimeSubtitleSegment.java`
- `scripts/realtime_transcribe.py`

## 脚本目录

```text
scripts/
├── start_backend_local.ps1         # 推荐本地 H2 启动脚本
├── start_backend_mysql.ps1         # MySQL profile 启动脚本
├── smoke_test.ps1                  # 分段 smoke test
├── demo_check.ps1                  # Demo readiness 检查
├── check_env.ps1                   # 本机环境检查
├── setup_env.ps1                   # 依赖安装辅助
├── verify_mysql.ps1                # MySQL 登录和初始化检查
├── init_mysql.sql                  # MySQL 建库 SQL
└── realtime_transcribe.py          # 准实时模式 ASR worker
```

## 文档目录

```text
docs/
├── DEMO_GUIDE.md                   # 演示路线
├── TROUBLESHOOTING.md              # 常见问题排查
├── SUBMISSION_CONFIG_GUIDE.md      # 地址/API/数据库/环境变量配置说明
├── GITHUB_UPLOAD_GUIDE.md          # GitHub 创建和上传步骤
└── PROJECT_STRUCTURE.md            # 当前文件
```

## 不应上传 GitHub 的运行产物

这些目录或文件是本地运行产物，已经通过 `.gitignore` 排除：

- `video/`
- `uploads/`
- `spring_boot/uploads/`
- `spring_boot/data/`
- `spring_boot/logs/`
- `.cache/`
- `__pycache__/`
- `*.mp4`
- `*.wav`
- `*.log`
- `gpt_project_package/`
- `gpt_project_package.zip`

## 推荐启动命令

H2 默认模式：

```powershell
cd G:\视频分析
.\scripts\start_backend_local.ps1 -LlmModel "gpt-5.5"
```

MySQL 可选模式：

```powershell
cd G:\视频分析
.\scripts\start_backend_mysql.ps1 -LlmModel "gpt-5.5"
```

演示页面：

```text
G:\视频分析\test_frontend.html
G:\视频分析\realtime_demo.html
```
