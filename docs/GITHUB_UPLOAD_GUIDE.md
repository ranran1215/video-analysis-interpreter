# GitHub 上传指南

本文用于把本项目上传或更新到 GitHub。当前推荐仓库地址：

```text
https://github.com/ranran1215/video-analysis-interpreter
```

如果仓库已经存在，后续只需要在项目根目录执行 `git add`、`git commit`、`git push`。如果要从零创建新仓库，可以按下面步骤操作。

## 1. 在 GitHub 网页创建新仓库

在你截图的 `github.com/new` 页面填写：

- 仓库名称：建议使用 `video-analysis-interpreter` 或 `ai-interpreter-video-analysis`
- 描述：`AI video analysis and real-time interpretation demo with WhisperX and Spring Boot`
- 可见性：如果是比赛/提交展示，建议先选 `Private`；如果确定可以公开，再选 `Public`
- `Add README file`：不要勾选，本地项目已经有 `README.md`
- `.gitignore`：选择 `None`，本地已经有 `.gitignore`
- `License`：可以先选 `No license`

创建后，GitHub 会显示仓库地址，例如：

```text
https://github.com/ranran1215/video-analysis-interpreter.git
```

后面命令里的 `<你的仓库地址>` 就替换成这个地址。

## 2. 本地初始化 git

打开 PowerShell：

```powershell
cd video-analysis-interpreter
git init
git branch -M main
```

检查将要提交的文件：

```powershell
git status --short
```

如果看到 `video/`、`spring_boot/uploads/`、`spring_boot/data/`、`.cache/`、`*.mp4`、`*.log`、`gpt_project_package/` 出现在待提交列表里，说明 `.gitignore` 没生效，需要先处理，不能直接提交。

## 3. 首次提交

```powershell
git add .
git status --short
git commit -m "Initial project submission"
```

提交前重点确认不要包含：

- 真实 `LLM_API_KEY`
- MySQL 密码
- cookies 文件
- H2 数据库文件
- 上传/下载的视频运行产物
- 模型缓存
- 录屏视频
- `gpt_project_package` 交接包

## 4. 绑定远程仓库并推送

把 `<你的仓库地址>` 换成 GitHub 页面给你的地址：

```powershell
git remote add origin <你的仓库地址>
git push -u origin main
```

示例：

```powershell
git remote add origin https://github.com/ranran1215/video-analysis-interpreter.git
git push -u origin main
```

如果 GitHub 要求登录，按浏览器或终端提示完成授权即可。

## 5. 后续更新代码

以后每次改完代码：

```powershell
cd video-analysis-interpreter
git status --short
git add .
git commit -m "Update realtime interpretation demo"
git push
```

## 6. 推荐提交内容

建议提交：

- `README.md`
- `PROJECT_CONTEXT_FOR_GPT.md`
- `docs/`
- `scripts/`
- `spring_boot/src/`
- `spring_boot/pom.xml`
- `spring_boot/mvnw.cmd`
- `test_frontend.html`
- `realtime_demo.html`
- `extract_subtitle_whisperx.py`
- `DIFY_PROMPT.txt`
- `链接上传功能指南.md`

不建议提交：

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

## 7. 提交后 README 中的演示说明

GitHub 仓库不包含本地视频素材和录屏视频。当前本地 Demo 录屏是：

```text
video-analysis-interpreter.mp4
```

这个文件约 1GB，不能直接提交到 GitHub。当前已上传到百度网盘，链接已放到 `README.md` 的 `Demo 视频` 小节。

演示时需要自己准备视频，或在文档里说明本地 Demo 推荐素材路径：

```text
video/demo_english_30s.mp4
```

如果评审从 GitHub 拉代码运行，需要另外准备：

- Python/WhisperX 环境
- ffmpeg
- yt-dlp
- LLM API Key
- 可选 MySQL

具体配置见：

```text
docs/SUBMISSION_CONFIG_GUIDE.md
docs/DEMO_GUIDE.md
docs/TROUBLESHOOTING.md
```
