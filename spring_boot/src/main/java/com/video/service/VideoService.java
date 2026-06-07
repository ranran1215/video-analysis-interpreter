package com.video.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.video.dto.VideoAnalysisResponse;
import com.video.entity.Video;
import com.video.repository.VideoRepository;
import com.video.util.UrlNormalizeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class VideoService implements ApplicationRunner {

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_DOWNLOADING = "downloading";
    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_EXTRACTING_SUBTITLE = "extracting_subtitle";
    private static final String STATUS_ANALYZING = "analyzing";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private SubtitleExtractionService subtitleExtractionService;

    @Autowired
    private LlmAnalysisService llmAnalysisService;

    @Autowired
    private SubtitleTranslationService subtitleTranslationService;

    @Autowired
    private DownloadService downloadService;

    @Value("${video.upload-dir}")
    private String uploadDir;

    private final Gson gson = new Gson();

    @Override
    public void run(ApplicationArguments args) {
        markInterruptedTasksAsFailed();
    }

    private void markInterruptedTasksAsFailed() {
        try {
            List<Video> videos = videoRepository.findAll();
            for (Video video : videos) {
                if (isLegacyCompletedVideo(video)) {
                    setTaskState(video, STATUS_COMPLETED, "处理完成", 100, null);
                    videoRepository.save(video);
                    continue;
                }

                if (!isTerminalStatus(video.getStatus())) {
                    video.setStatus(STATUS_FAILED);
                    video.setStage("处理失败");
                    video.setProgress(-1);
                    video.setErrorMessage("服务重启导致任务中断，请重新上传或重新分析");
                    if (video.getAiSummary() == null || video.getAiSummary().trim().isEmpty()
                        || video.getAiSummary().contains("处理中")) {
                        video.setAiSummary("处理失败: 服务重启导致任务中断，请重新上传或重新分析");
                    }
                    videoRepository.save(video);
                }
            }
        } catch (Exception e) {
            System.err.println("启动时标记中断任务失败: " + e.getMessage());
        }
    }

    private String calculateMD5(MultipartFile file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream is = file.getInputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }
        return toHex(md.digest());
    }

    public VideoAnalysisResponse uploadAndAnalyze(MultipartFile file, String language) throws Exception {
        long taskStart = System.currentTimeMillis();
        System.out.println("========================================");
        System.out.println("开始处理新的视频上传");
        System.out.println("========================================");

        String fileHash = calculateMD5(file);
        System.out.println("文件 MD5: " + fileHash);

        Optional<Video> existingVideo = videoRepository.findByFileHash(fileHash);
        if (existingVideo.isPresent()) {
            Video video = existingVideo.get();
            System.out.println("发现缓存视频，ID: " + video.getId() + "，状态: " + video.getStatus());

            if (STATUS_COMPLETED.equals(video.getStatus())) {
                System.out.println("视频已处理完成，直接返回缓存结果（秒开）");
                return buildCachedCompletedResponse(video);
            }
            if (!STATUS_FAILED.equals(video.getStatus())) {
                System.out.println("视频已有任务正在处理中，返回当前状态");
                return buildResponse(video, video.getSubtitleData(), buildStoredAnalysisJson(video));
            }

            System.out.println("视频之前处理失败，重新处理");
            setTaskState(video, STATUS_PROCESSING, "等待处理", 5, null);
            resetDurations(video);
            video.setAiSummary("处理中...");
            video.setHighlights("[]");
            video.setTranslatedSubtitleData(null);
            video.setUploadTime(LocalDateTime.now());
            videoRepository.save(video);

            final Long videoId = video.getId();
            final String videoPath = video.getFilePath();
            CompletableFuture.runAsync(() -> processVideoAsync(videoId, videoPath, language, taskStart));
            return buildResponse(video, video.getSubtitleData(), buildStoredAnalysisJson(video));
        }

        File uploadDirectory = new File(uploadDir);
        if (!uploadDirectory.exists()) {
            uploadDirectory.mkdirs();
        }

        String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        Path filePath = Paths.get(uploadDir, fileName);
        Files.write(filePath, file.getBytes());

        Video video = new Video();
        video.setFileName(file.getOriginalFilename());
        video.setFilePath(filePath.toString());
        video.setVideoUrl("/play/" + fileName);
        video.setFileSize(file.getSize());
        video.setUploadTime(LocalDateTime.now());
        video.setFileHash(fileHash);
        video.setAiSummary("处理中...");
        video.setHighlights("[]");
        setTaskState(video, STATUS_PROCESSING, "等待处理", 5, null);
        resetDurations(video);

        video = videoRepository.save(video);
        System.out.println("视频记录已保存，ID: " + video.getId() + "，状态: processing");

        final Long videoId = video.getId();
        final String videoPath = filePath.toString();
        CompletableFuture.runAsync(() -> processVideoAsync(videoId, videoPath, language, taskStart));

        return buildResponse(video, video.getSubtitleData(), buildStoredAnalysisJson(video));
    }

    private void processVideoAsync(Long videoId, String videoPath, String language) {
        processVideoAsync(videoId, videoPath, language, System.currentTimeMillis());
    }

    private void processVideoAsync(Long videoId, String videoPath, String language, long taskStart) {
        long subtitleStart = 0L;
        long analysisStart = 0L;
        try {
            System.out.println("========================================");
            System.out.println("[异步任务] 开始处理视频 ID: " + videoId);
            System.out.println("========================================");

            updateTaskState(videoId, STATUS_EXTRACTING_SUBTITLE, "正在提取字幕", 30, null);
            subtitleStart = System.currentTimeMillis();
            String subtitleJson = subtitleExtractionService.extractSubtitle(videoPath, language);
            long subtitleDurationMs = elapsedMs(subtitleStart);
            long alignDurationMs = extractAlignDurationMs(subtitleJson);
            System.out.println("[异步任务] 字幕提取完成，长度: " + (subtitleJson != null ? subtitleJson.length() : "null"));

            Video subtitleVideo = updateTaskState(videoId, null, "字幕提取完成，正在生成翻译字幕", 60, null);
            subtitleVideo.setSubtitleData(subtitleJson);
            subtitleVideo.setSubtitleDurationMs(subtitleDurationMs);
            subtitleVideo.setAlignDurationMs(alignDurationMs);
            videoRepository.save(subtitleVideo);

            String translatedSubtitleJson = buildTranslatedSubtitleTrack(subtitleJson);
            Video translatedVideo = updateTaskState(videoId, null, "翻译字幕完成，正在进行 AI 分析", 75, null);
            translatedVideo.setTranslatedSubtitleData(translatedSubtitleJson);
            videoRepository.save(translatedVideo);

            String subtitleText = subtitleExtractionService.convertSubtitleToText(subtitleJson);
            System.out.println("[异步任务] 字幕转换完成，长度: " + (subtitleText != null ? subtitleText.length() : "null"));

            updateTaskState(videoId, STATUS_ANALYZING, "正在调用 AI 分析", 85, null);
            System.out.println("[异步任务] 开始调用 LLM API...");
            analysisStart = System.currentTimeMillis();
            String aiAnalysisJson = llmAnalysisService.analyzeSubtitle(subtitleText, language);
            long analysisDurationMs = elapsedMs(analysisStart);
            System.out.println("[异步任务] LLM API 调用完成");

            JsonObject analysis;
            try {
                analysis = gson.fromJson(aiAnalysisJson, JsonObject.class);
            } catch (Exception e) {
                System.err.println("[异步任务] AI 分析结果解析失败: " + e.getMessage());
                analysis = new JsonObject();
                analysis.addProperty("summary", "AI 分析结果格式异常");
                analysis.add("highlights", new JsonArray());
            }

            Video updatedVideo = videoRepository.findById(videoId).orElseThrow();
            updatedVideo.setSubtitleData(subtitleJson);
            updatedVideo.setTranslatedSubtitleData(translatedSubtitleJson);
            updatedVideo.setAiSummary(analysis.has("summary") ? analysis.get("summary").getAsString() : "暂无总结");
            updatedVideo.setHighlights(readHighlightsArray(analysis).toString());
            updatedVideo.setSubtitleDurationMs(subtitleDurationMs);
            updatedVideo.setAlignDurationMs(alignDurationMs);
            updatedVideo.setAnalysisDurationMs(analysisDurationMs);
            updatedVideo.setTotalDurationMs(elapsedMs(taskStart));
            setTaskState(updatedVideo, STATUS_COMPLETED, "处理完成", 100, null);
            videoRepository.save(updatedVideo);
            printPerfLog(updatedVideo);

            System.out.println("========================================");
            System.out.println("[异步任务] 视频处理完成！ID: " + videoId);
            System.out.println("========================================");
        } catch (Exception e) {
            System.err.println("[异步任务] 处理失败: " + e.getMessage());
            e.printStackTrace();
            updateFailureDurations(videoId, subtitleStart, analysisStart);
            markTaskFailed(videoId, e.getMessage(), elapsedMs(taskStart));
        }
    }

    public VideoAnalysisResponse getVideoAnalysis(Long videoId) {
        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new RuntimeException("视频不存在"));
        return buildResponse(video, video.getSubtitleData(), buildStoredAnalysisJson(video));
    }

    private String buildTranslatedSubtitleTrack(String subtitleJson) {
        try {
            String translatedSubtitleJson = subtitleTranslationService.translateSegments(subtitleJson, "zh");
            System.out.println("[异步任务] 翻译字幕生成完成，长度: "
                + (translatedSubtitleJson != null ? translatedSubtitleJson.length() : "null"));
            return translatedSubtitleJson;
        } catch (Exception e) {
            System.err.println("[WARN] 翻译字幕生成失败，已回退原文字幕: " + e.getMessage());
            return subtitleTranslationService.buildFallbackTranslatedSubtitleJson(subtitleJson, "zh");
        }
    }

    public VideoAnalysisResponse reanalyzeWithLanguage(Long videoId, String language) throws Exception {
        System.out.println("========================================");
        System.out.println("翻译视频分析，ID: " + videoId + ", 目标语言: " + language);
        System.out.println("========================================");

        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new RuntimeException("视频不存在"));

        String currentSummary = video.getAiSummary();
        String currentHighlights = video.getHighlights();

        System.out.println("开始翻译总结和高光片段...");
        String translatedAnalysisJson = llmAnalysisService.translateAnalysis(currentSummary, currentHighlights, language);
        System.out.println("翻译完成");

        JsonObject analysis;
        try {
            analysis = gson.fromJson(translatedAnalysisJson, JsonObject.class);
        } catch (Exception e) {
            System.err.println("翻译结果解析失败: " + e.getMessage());
            analysis = new JsonObject();
            analysis.addProperty("summary", "翻译失败");
            analysis.add("highlights", new JsonArray());
        }

        return buildResponse(video, video.getSubtitleData(), gson.toJson(analysis));
    }

    private VideoAnalysisResponse buildCachedCompletedResponse(Video video) {
        VideoAnalysisResponse response = buildResponse(video, video.getSubtitleData(), buildStoredAnalysisJson(video));
        response.setStatus(STATUS_COMPLETED);
        response.setStage("命中缓存");
        response.setProgress(100);
        response.setErrorMessage(null);
        return response;
    }

    private VideoAnalysisResponse buildUrlCachedCompletedResponse(Video video) {
        VideoAnalysisResponse response = buildResponse(video, video.getSubtitleData(), buildStoredAnalysisJson(video));
        response.setStatus(STATUS_COMPLETED);
        response.setStage("命中链接缓存");
        response.setProgress(100);
        response.setErrorMessage(null);
        return response;
    }

    private VideoAnalysisResponse buildResponse(Video video, String subtitleJson, String aiAnalysisJson) {
        ensureTranslatedSubtitleData(video);
        VideoAnalysisResponse response = new VideoAnalysisResponse();
        response.setVideoId(video.getId());
        response.setVideoUrl(video.getVideoUrl());
        response.setStatus(normalizeStatus(video.getStatus()));
        response.setStage(resolveStage(video));
        response.setProgress(resolveProgress(video));
        response.setErrorMessage(video.getErrorMessage());
        response.setDownloadDurationMs(defaultLong(video.getDownloadDurationMs()));
        response.setSubtitleDurationMs(defaultLong(video.getSubtitleDurationMs()));
        response.setAlignDurationMs(defaultLong(video.getAlignDurationMs()));
        response.setAnalysisDurationMs(defaultLong(video.getAnalysisDurationMs()));
        response.setTotalDurationMs(defaultLong(video.getTotalDurationMs()));

        response.setSubtitles(parseSubtitleSegments(subtitleJson, video.getTranslatedSubtitleData()));

        JsonObject aiAnalysis = parseAnalysisJson(aiAnalysisJson);
        response.setSummary(aiAnalysis.has("summary") ? aiAnalysis.get("summary").getAsString() : defaultSummary(video));
        response.setHighlights(parseHighlights(aiAnalysis));

        return response;
    }

    private void ensureTranslatedSubtitleData(Video video) {
        if (video == null || !STATUS_COMPLETED.equals(video.getStatus())) {
            return;
        }
        if (video.getTranslatedSubtitleData() != null && !video.getTranslatedSubtitleData().trim().isEmpty()) {
            return;
        }
        if (video.getSubtitleData() == null || video.getSubtitleData().trim().isEmpty()) {
            return;
        }

        try {
            System.out.println("[BACKFILL] 旧缓存缺少翻译字幕，开始补生成 videoId=" + video.getId());
            String translatedSubtitleJson = subtitleTranslationService.translateSegmentsStrict(video.getSubtitleData(), "zh");
            video.setTranslatedSubtitleData(translatedSubtitleJson);
            videoRepository.save(video);
            System.out.println("[BACKFILL] 翻译字幕补生成完成 videoId=" + video.getId());
        } catch (Exception e) {
            System.err.println("[WARN] 旧缓存翻译字幕补生成失败，将回退原文字幕 videoId="
                + video.getId() + ": " + e.getMessage());
        }
    }

    private List<VideoAnalysisResponse.SubtitleSegment> parseSubtitleSegments(String subtitleJson, String translatedSubtitleJson) {
        List<VideoAnalysisResponse.SubtitleSegment> translatedSubtitles = parseTranslatedSubtitleSegments(translatedSubtitleJson);
        if (!translatedSubtitles.isEmpty()) {
            return translatedSubtitles;
        }
        return parseSourceSubtitleSegments(subtitleJson);
    }

    private List<VideoAnalysisResponse.SubtitleSegment> parseTranslatedSubtitleSegments(String translatedSubtitleJson) {
        List<VideoAnalysisResponse.SubtitleSegment> subtitleList = new ArrayList<>();
        if (translatedSubtitleJson == null || translatedSubtitleJson.trim().isEmpty()) {
            return subtitleList;
        }

        try {
            JsonObject subtitleData = gson.fromJson(translatedSubtitleJson, JsonObject.class);
            if (subtitleData == null || !subtitleData.has("segments") || !subtitleData.get("segments").isJsonArray()) {
                return subtitleList;
            }

            JsonArray segments = subtitleData.getAsJsonArray("segments");
            for (int i = 0; i < segments.size(); i++) {
                JsonObject seg = segments.get(i).getAsJsonObject();
                String sourceText = readStringField(seg, "sourceText", readStringField(seg, "text", ""));
                String translatedText = readStringField(seg, "translatedText", sourceText);
                VideoAnalysisResponse.SubtitleSegment subtitle = new VideoAnalysisResponse.SubtitleSegment();
                subtitle.setStart(readDoubleField(seg, "start", 0.0));
                subtitle.setEnd(readDoubleField(seg, "end", 0.0));
                subtitle.setText(sourceText);
                subtitle.setSourceText(sourceText);
                subtitle.setTranslatedText(translatedText);
                subtitleList.add(subtitle);
            }
        } catch (Exception e) {
            System.err.println("解析翻译字幕 JSON 失败，回退原字幕: " + e.getMessage());
        }
        return subtitleList;
    }

    private List<VideoAnalysisResponse.SubtitleSegment> parseSourceSubtitleSegments(String subtitleJson) {
        List<VideoAnalysisResponse.SubtitleSegment> subtitleList = new ArrayList<>();
        if (subtitleJson == null || subtitleJson.trim().isEmpty()) {
            return subtitleList;
        }

        try {
            JsonObject subtitleData = gson.fromJson(subtitleJson, JsonObject.class);
            if (subtitleData == null || !subtitleData.has("segments") || !subtitleData.get("segments").isJsonArray()) {
                return subtitleList;
            }

            JsonArray segments = subtitleData.getAsJsonArray("segments");
            for (int i = 0; i < segments.size(); i++) {
                JsonObject seg = segments.get(i).getAsJsonObject();
                VideoAnalysisResponse.SubtitleSegment subtitle = new VideoAnalysisResponse.SubtitleSegment();
                subtitle.setStart(seg.has("start") ? seg.get("start").getAsDouble() : 0.0);
                subtitle.setEnd(seg.has("end") ? seg.get("end").getAsDouble() : 0.0);
                String sourceText = seg.has("text") ? seg.get("text").getAsString() : "";
                subtitle.setText(sourceText);
                subtitle.setSourceText(sourceText);
                subtitle.setTranslatedText(sourceText);
                subtitleList.add(subtitle);
            }
        } catch (Exception e) {
            System.err.println("解析字幕 JSON 失败: " + e.getMessage());
        }
        return subtitleList;
    }

    private String readStringField(JsonObject object, String name, String defaultValue) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return defaultValue;
        }
        try {
            return object.get(name).getAsString();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private double readDoubleField(JsonObject object, String name, double defaultValue) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return defaultValue;
        }
        try {
            return object.get(name).getAsDouble();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private JsonObject parseAnalysisJson(String aiAnalysisJson) {
        if (aiAnalysisJson == null || aiAnalysisJson.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonObject parsed = gson.fromJson(aiAnalysisJson, JsonObject.class);
            return parsed != null ? parsed : new JsonObject();
        } catch (Exception e) {
            System.err.println("构建响应时 AI 分析结果解析失败: " + e.getMessage());
            JsonObject fallback = new JsonObject();
            fallback.addProperty("summary", "AI 分析结果格式异常");
            fallback.add("highlights", new JsonArray());
            return fallback;
        }
    }

    private List<VideoAnalysisResponse.Highlight> parseHighlights(JsonObject aiAnalysis) {
        JsonArray highlightsArray = readHighlightsArray(aiAnalysis);
        List<VideoAnalysisResponse.Highlight> highlightList = new ArrayList<>();
        for (int i = 0; i < highlightsArray.size(); i++) {
            JsonObject hl = highlightsArray.get(i).getAsJsonObject();
            VideoAnalysisResponse.Highlight highlight = new VideoAnalysisResponse.Highlight();
            highlight.setTitle(hl.has("title") ? hl.get("title").getAsString() : "");
            highlight.setStart(readTimeField(hl, "start", "startTime"));
            highlight.setEnd(readTimeField(hl, "end", "endTime"));
            highlight.setDescription(hl.has("description") ? hl.get("description").getAsString() : "");
            highlightList.add(highlight);
        }
        return highlightList;
    }

    private double readTimeField(JsonObject object, String primaryName, String fallbackName) {
        try {
            if (object.has(primaryName) && !object.get(primaryName).isJsonNull()) {
                return object.get(primaryName).getAsDouble();
            }
            if (object.has(fallbackName) && !object.get(fallbackName).isJsonNull()) {
                return object.get(fallbackName).getAsDouble();
            }
        } catch (Exception e) {
            System.err.println("解析高光时间字段失败: " + e.getMessage());
        }
        return 0.0;
    }

    private String buildStoredAnalysisJson(Video video) {
        JsonObject analysis = new JsonObject();
        analysis.addProperty("summary", video.getAiSummary() != null ? video.getAiSummary() : defaultSummary(video));
        analysis.add("highlights", parseHighlightsJson(video.getHighlights()));
        return gson.toJson(analysis);
    }

    private JsonArray parseHighlightsJson(String highlightsJson) {
        if (highlightsJson == null || highlightsJson.trim().isEmpty()) {
            return new JsonArray();
        }
        try {
            JsonElement parsed = gson.fromJson(highlightsJson, JsonElement.class);
            if (parsed != null && parsed.isJsonArray()) {
                return parsed.getAsJsonArray();
            }
        } catch (Exception e) {
            System.err.println("解析历史高光 JSON 失败: " + e.getMessage());
        }
        return new JsonArray();
    }

    private JsonArray readHighlightsArray(JsonObject analysis) {
        if (analysis != null && analysis.has("highlights") && analysis.get("highlights").isJsonArray()) {
            return analysis.getAsJsonArray("highlights");
        }
        return new JsonArray();
    }

    /**
     * 通过 URL 下载并分析视频。
     * 链接任务先创建 downloading 记录并立即返回 videoId，前端随后轮询状态。
     */
    public VideoAnalysisResponse downloadAndAnalyze(String url, String language) {
        long taskStart = System.currentTimeMillis();
        String normalizedUrl = UrlNormalizeUtil.normalize(url);
        String urlHash = UrlNormalizeUtil.hashNormalizedUrl(normalizedUrl);
        System.out.println("========================================");
        System.out.println("开始通过 URL 处理视频");
        System.out.println("[CACHE] normalizedUrl=" + UrlNormalizeUtil.safeForLog(normalizedUrl));
        System.out.println("[CACHE] urlHash=" + urlHash);
        System.out.println("========================================");

        Optional<Video> cachedByUrl = videoRepository.findTopByUrlHashAndStatusOrderByUploadTimeDesc(urlHash, STATUS_COMPLETED);
        if (cachedByUrl.isPresent()) {
            Video cachedVideo = cachedByUrl.get();
            if (hasExistingVideoFile(cachedVideo)) {
                System.out.println("[CACHE] url cache hit urlHash=" + urlHash + " videoId=" + cachedVideo.getId());
                return buildUrlCachedCompletedResponse(cachedVideo);
            }
            System.err.println("[CACHE] url cache stale, local file missing urlHash=" + urlHash
                + " videoId=" + cachedVideo.getId() + "，继续重新下载");
        } else {
            System.out.println("[CACHE] url cache miss urlHash=" + urlHash);
        }

        Video video = new Video();
        video.setFileName("URL 视频下载中");
        video.setSourceUrl(url);
        video.setNormalizedUrl(normalizedUrl);
        video.setUrlHash(urlHash);
        video.setUploadTime(LocalDateTime.now());
        video.setFileSize(0L);
        video.setAiSummary("正在下载视频...");
        video.setHighlights("[]");
        setTaskState(video, STATUS_DOWNLOADING, "正在下载视频", 5, null);
        resetDurations(video);

        video = videoRepository.save(video);

        final Long videoId = video.getId();
        CompletableFuture.runAsync(() -> downloadAndProcessAsync(videoId, normalizedUrl, language, taskStart));

        return buildResponse(video, video.getSubtitleData(), buildStoredAnalysisJson(video));
    }

    private void downloadAndProcessAsync(Long videoId, String url, String language) {
        downloadAndProcessAsync(videoId, url, language, System.currentTimeMillis());
    }

    private void downloadAndProcessAsync(Long videoId, String url, String language, long taskStart) {
        long downloadStart = 0L;
        try {
            updateTaskState(videoId, STATUS_DOWNLOADING, "正在下载视频", 5, null);

            downloadStart = System.currentTimeMillis();
            String downloadedFilePath = downloadService.downloadVideo(url);
            long downloadDurationMs = elapsedMs(downloadStart);
            File downloadedFile = new File(downloadedFilePath);
            String fileHash = calculateMD5FromFile(downloadedFile);
            System.out.println("文件 MD5: " + fileHash);

            Optional<Video> existingVideo = videoRepository.findByFileHash(fileHash)
                .filter(video -> !video.getId().equals(videoId));

            boolean canAttachFileHash = !existingVideo.isPresent();

            if (existingVideo.isPresent() && STATUS_COMPLETED.equals(existingVideo.get().getStatus())) {
                Video cachedVideo = existingVideo.get();
                Video currentVideo = videoRepository.findById(videoId).orElseThrow();
                System.out.println("[CACHE] file hash hit after download, binding url cache urlHash="
                    + safeValue(currentVideo.getUrlHash()) + " cachedVideoId=" + cachedVideo.getId()
                    + " currentVideoId=" + currentVideo.getId());

                if (hasExistingVideoFile(cachedVideo)) {
                    currentVideo.setFileName(cachedVideo.getFileName());
                    currentVideo.setFilePath(cachedVideo.getFilePath());
                    currentVideo.setVideoUrl(cachedVideo.getVideoUrl());
                    currentVideo.setFileSize(cachedVideo.getFileSize());
                } else {
                    System.err.println("[CACHE] file hash cache media missing, using newly downloaded file currentVideoId="
                        + currentVideo.getId());
                    String fileName = downloadedFile.getName();
                    currentVideo.setFileName(fileName);
                    currentVideo.setFilePath(downloadedFilePath);
                    currentVideo.setVideoUrl("/play/" + fileName);
                    currentVideo.setFileSize(downloadedFile.length());
                }
                currentVideo.setSubtitleData(cachedVideo.getSubtitleData());
                currentVideo.setTranslatedSubtitleData(cachedVideo.getTranslatedSubtitleData());
                currentVideo.setAiSummary(cachedVideo.getAiSummary());
                currentVideo.setHighlights(cachedVideo.getHighlights());
                currentVideo.setDownloadDurationMs(downloadDurationMs);
                currentVideo.setSubtitleDurationMs(cachedVideo.getSubtitleDurationMs());
                currentVideo.setAlignDurationMs(cachedVideo.getAlignDurationMs());
                currentVideo.setAnalysisDurationMs(cachedVideo.getAnalysisDurationMs());
                currentVideo.setTotalDurationMs(elapsedMs(taskStart));
                // fileHash 保持为空，避免与原缓存记录的唯一约束冲突。
                setTaskState(currentVideo, STATUS_COMPLETED, "命中文件缓存", 100, null);
                videoRepository.save(currentVideo);
                printPerfLog(currentVideo);
                return;
            }

            Video video = videoRepository.findById(videoId).orElseThrow();
            String fileName = downloadedFile.getName();
            video.setFileName(fileName);
            video.setFilePath(downloadedFilePath);
            video.setVideoUrl("/play/" + fileName);
            video.setFileSize(downloadedFile.length());
            if (canAttachFileHash) {
                video.setFileHash(fileHash);
            }
            video.setDownloadDurationMs(downloadDurationMs);
            setTaskState(video, STATUS_PROCESSING, "视频下载完成，准备提取字幕", 20, null);
            videoRepository.save(video);

            processVideoAsync(videoId, downloadedFilePath, language, taskStart);
        } catch (Exception e) {
            System.err.println("[下载任务] 处理失败: " + e.getMessage());
            e.printStackTrace();
            updateDownloadFailureDuration(videoId, downloadStart);
            markTaskFailed(videoId, e.getMessage(), elapsedMs(taskStart));
        }
    }

    private String calculateMD5FromFile(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream is = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }
        return toHex(md.digest());
    }

    private boolean hasExistingVideoFile(Video video) {
        File file = resolveStoredVideoFile(video);
        return file != null && file.exists() && file.isFile();
    }

    private File resolveStoredVideoFile(Video video) {
        if (video == null) {
            return null;
        }

        String filePath = video.getFilePath();
        if (filePath != null && !filePath.trim().isEmpty()) {
            File file = new File(filePath.trim());
            if (file.exists()) {
                return file;
            }
        }

        String videoUrl = video.getVideoUrl();
        if (videoUrl != null && videoUrl.startsWith("/play/")) {
            String fileName = videoUrl.substring("/play/".length());
            if (!fileName.trim().isEmpty()) {
                return Paths.get(uploadDir, fileName).toFile();
            }
        }

        return null;
    }

    private String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private Video updateTaskState(Long videoId, String status, String stage, Integer progress, String errorMessage) {
        Video video = videoRepository.findById(videoId).orElseThrow();
        setTaskState(video, status, stage, progress, errorMessage);
        return videoRepository.save(video);
    }

    private void setTaskState(Video video, String status, String stage, Integer progress, String errorMessage) {
        if (status != null) {
            video.setStatus(status);
        }
        if (stage != null) {
            video.setStage(stage);
        }
        if (progress != null) {
            video.setProgress(progress);
        }
        video.setErrorMessage(errorMessage);
    }

    private void markTaskFailed(Long videoId, String errorMessage) {
        markTaskFailed(videoId, errorMessage, null);
    }

    private void markTaskFailed(Long videoId, String errorMessage, Long totalDurationMs) {
        try {
            Video failedVideo = videoRepository.findById(videoId).orElseThrow();
            if (totalDurationMs != null) {
                failedVideo.setTotalDurationMs(totalDurationMs);
            }
            setTaskState(failedVideo, STATUS_FAILED, "处理失败", -1, errorMessage);
            failedVideo.setAiSummary("处理失败: " + errorMessage);
            videoRepository.save(failedVideo);
            printPerfLog(failedVideo);
        } catch (Exception ex) {
            System.err.println("[异步任务] 更新失败状态时出错: " + ex.getMessage());
        }
    }

    private void updateFailureDurations(Long videoId, long subtitleStart, long analysisStart) {
        try {
            Video video = videoRepository.findById(videoId).orElseThrow();
            if (subtitleStart > 0 && defaultLong(video.getSubtitleDurationMs()) == 0L) {
                video.setSubtitleDurationMs(elapsedMs(subtitleStart));
            }
            if (analysisStart > 0 && defaultLong(video.getAnalysisDurationMs()) == 0L) {
                video.setAnalysisDurationMs(elapsedMs(analysisStart));
            }
            videoRepository.save(video);
        } catch (Exception ex) {
            System.err.println("[异步任务] 更新失败耗时时出错: " + ex.getMessage());
        }
    }

    private void updateDownloadFailureDuration(Long videoId, long downloadStart) {
        if (downloadStart <= 0) {
            return;
        }
        try {
            Video video = videoRepository.findById(videoId).orElseThrow();
            if (defaultLong(video.getDownloadDurationMs()) == 0L) {
                video.setDownloadDurationMs(elapsedMs(downloadStart));
                videoRepository.save(video);
            }
        } catch (Exception ex) {
            System.err.println("[下载任务] 更新失败下载耗时时出错: " + ex.getMessage());
        }
    }

    private boolean isTerminalStatus(String status) {
        return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status);
    }

    private boolean isLegacyCompletedVideo(Video video) {
        if (video.getStatus() != null && !video.getStatus().trim().isEmpty()) {
            return false;
        }

        String summary = video.getAiSummary();
        boolean hasSubtitle = video.getSubtitleData() != null && !video.getSubtitleData().trim().isEmpty();
        boolean hasHighlights = video.getHighlights() != null && !video.getHighlights().trim().isEmpty()
            && !"[]".equals(video.getHighlights().trim());

        return summary != null && !summary.trim().isEmpty()
            && !summary.contains("处理中")
            && !summary.contains("失败")
            && (hasSubtitle || hasHighlights);
    }

    private String normalizeStatus(String status) {
        return status != null && !status.trim().isEmpty() ? status : STATUS_PENDING;
    }

    private String resolveStage(Video video) {
        if (video.getStage() != null && !video.getStage().trim().isEmpty()) {
            return video.getStage();
        }
        String status = normalizeStatus(video.getStatus());
        if (STATUS_DOWNLOADING.equals(status)) {
            return "正在下载视频";
        }
        if (STATUS_EXTRACTING_SUBTITLE.equals(status)) {
            return "正在提取字幕";
        }
        if (STATUS_ANALYZING.equals(status)) {
            return "正在调用 AI 分析";
        }
        if (STATUS_COMPLETED.equals(status)) {
            return "处理完成";
        }
        if (STATUS_FAILED.equals(status)) {
            return "处理失败";
        }
        if (STATUS_PROCESSING.equals(status)) {
            return "等待处理";
        }
        return "等待处理";
    }

    private Integer resolveProgress(Video video) {
        if (video.getProgress() != null) {
            return video.getProgress();
        }
        String status = normalizeStatus(video.getStatus());
        if (STATUS_DOWNLOADING.equals(status)) {
            return 5;
        }
        if (STATUS_PROCESSING.equals(status)) {
            return 5;
        }
        if (STATUS_EXTRACTING_SUBTITLE.equals(status)) {
            return 30;
        }
        if (STATUS_ANALYZING.equals(status)) {
            return 70;
        }
        if (STATUS_COMPLETED.equals(status)) {
            return 100;
        }
        if (STATUS_FAILED.equals(status)) {
            return -1;
        }
        return 0;
    }

    private String defaultSummary(Video video) {
        if (STATUS_FAILED.equals(video.getStatus())) {
            return video.getErrorMessage() != null ? "处理失败: " + video.getErrorMessage() : "处理失败";
        }
        if (STATUS_COMPLETED.equals(video.getStatus())) {
            return "暂无总结";
        }
        return "视频正在处理中，请稍后刷新页面查看结果...";
    }

    private void resetDurations(Video video) {
        video.setDownloadDurationMs(0L);
        video.setSubtitleDurationMs(0L);
        video.setAlignDurationMs(0L);
        video.setAnalysisDurationMs(0L);
        video.setTotalDurationMs(0L);
    }

    private long elapsedMs(long startMs) {
        return Math.max(0L, System.currentTimeMillis() - startMs);
    }

    private Long extractAlignDurationMs(String subtitleJson) {
        if (subtitleJson == null || subtitleJson.trim().isEmpty()) {
            return 0L;
        }
        try {
            JsonObject subtitleData = gson.fromJson(subtitleJson, JsonObject.class);
            if (subtitleData != null && subtitleData.has("alignDurationMs") && !subtitleData.get("alignDurationMs").isJsonNull()) {
                return subtitleData.get("alignDurationMs").getAsLong();
            }
        } catch (Exception e) {
            System.err.println("解析 alignDurationMs 失败: " + e.getMessage());
        }
        return 0L;
    }

    private Long defaultLong(Long value) {
        return value != null ? value : 0L;
    }

    private String safeValue(String value) {
        return value != null && !value.trim().isEmpty() ? value : "<empty>";
    }

    private void printPerfLog(Video video) {
        System.out.println("[PERF] videoId=" + video.getId());
        System.out.println("[PERF] download=" + defaultLong(video.getDownloadDurationMs()) + " ms");
        System.out.println("[PERF] subtitle=" + defaultLong(video.getSubtitleDurationMs()) + " ms");
        System.out.println("[PERF] align=" + defaultLong(video.getAlignDurationMs()) + " ms");
        System.out.println("[PERF] analysis=" + defaultLong(video.getAnalysisDurationMs()) + " ms");
        System.out.println("[PERF] total=" + defaultLong(video.getTotalDurationMs()) + " ms");
    }
}
