package com.video.realtime.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.video.realtime.dto.RealtimeChunkResponse;
import com.video.realtime.dto.RealtimeSessionResponse;
import com.video.realtime.dto.RealtimeSubtitleSegment;
import com.video.service.SubtitleTranslationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Service
public class RealtimeInterpretationService {

    private static final String STATUS_PROVISIONAL = "provisional";
    private static final String STATUS_FINAL = "final";
    private static final String TRANSLATION_PENDING = "翻译中...";
    private static final int MAX_TRANSLATION_ATTEMPTS = 3;
    private static final Pattern SECRET_PATTERN = Pattern.compile("sk-[A-Za-z0-9_\\-]+");

    @Value("${video.upload-dir}")
    private String uploadDir;

    @Value("${video.python-script-dir}")
    private String pythonScriptDir;

    @Value("${video.python-command:python}")
    private String pythonCommand;

    @Value("${video.conda-env:}")
    private String condaEnv;

    @Value("${video.whisper-model:base}")
    private String whisperModel;

    @Value("${video.whisper-device:auto}")
    private String whisperDevice;

    @Value("${video.whisper-compute-type:}")
    private String whisperComputeType;

    @Value("${video.whisper-batch-size:16}")
    private int whisperBatchSize;

    @Value("${realtime.chunk-timeout-sec:180}")
    private int chunkTimeoutSec;

    @Value("${realtime.finalize-window-ms:15000}")
    private long finalizeWindowMs;

    @Value("${realtime.max-sessions:20}")
    private int maxSessions;

    @Autowired
    private SubtitleTranslationService subtitleTranslationService;

    private final Map<String, RealtimeSession> sessions = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();
    private final Object workerLock = new Object();
    private Process workerProcess;
    private BufferedReader workerReader;
    private BufferedWriter workerWriter;
    private long workerRequestSeq = 0L;
    private final AtomicBoolean workerPrewarmInFlight = new AtomicBoolean(false);
    private final AtomicInteger workerWarmupThreadSeq = new AtomicInteger(0);
    private final AtomicInteger translationThreadSeq = new AtomicInteger(0);
    private final ExecutorService workerWarmupExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName("realtime-worker-warmup-" + workerWarmupThreadSeq.incrementAndGet());
        return thread;
    });
    private final ExecutorService translationExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName("realtime-translation-" + translationThreadSeq.incrementAndGet());
        return thread;
    });

    public RealtimeSessionResponse startSession() {
        pruneOldSessionsIfNeeded();

        String sessionId = UUID.randomUUID().toString();
        String startedAt = Instant.now().toString();
        sessions.put(sessionId, new RealtimeSession(sessionId, startedAt));
        triggerWorkerPrewarm();

        return new RealtimeSessionResponse(
            sessionId,
            "started",
            startedAt,
            "准实时同传 session 已创建，Whisper worker 正在后台预热。首个分片到来时如果模型还未就绪，会短暂等待。"
        );
    }

    public RealtimeChunkResponse processChunk(String sessionId, long chunkStartMs, long chunkEndMs,
                                              MultipartFile audioChunk, String language) {
        RealtimeSession session = findSession(sessionId);
        if (session == null) {
            return buildMissingSessionResponse(sessionId);
        }
        if (audioChunk == null || audioChunk.isEmpty()) {
            return buildResponse(session, "audioChunk 为空，本次未处理。");
        }

        long normalizedStartMs = Math.max(0L, chunkStartMs);
        long normalizedEndMs = Math.max(normalizedStartMs + 1L, chunkEndMs);
        String warning = null;

        try {
            Path chunkPath = saveAudioChunk(sessionId, normalizedStartMs, normalizedEndMs, audioChunk);
            TranscriptionResult transcription = transcribeChunk(chunkPath, normalizeLanguage(language));
            List<RealtimeSubtitleSegment> newSegments = buildGlobalSegments(
                sessionId,
                normalizedStartMs,
                normalizedEndMs,
                transcription
            );

            synchronized (session) {
                session.lastLanguage = normalizeLanguage(transcription.language);
            }
            updateSessionSegments(session, normalizedStartMs, normalizedEndMs, newSegments);
            if (newSegments.isEmpty()) {
                warning = "该分片未识别到可用语音。";
            } else {
                scheduleTranslation(session, transcription.language, newSegments);
            }
        } catch (Exception e) {
            warning = "实时识别失败，本次分片已跳过: " + sanitizeWarning(e.getMessage());
            logRealtimeWarning("chunk 处理失败", e);
            synchronized (session) {
                markFinalByWindow(session.segments, normalizedEndMs);
            }
        }

        return buildResponse(session, warning);
    }

    public RealtimeChunkResponse getSubtitles(String sessionId) {
        RealtimeSession session = findSession(sessionId);
        if (session == null) {
            return buildMissingSessionResponse(sessionId);
        }
        scheduleMissingTranslations(session);
        return buildResponse(session, null);
    }

    @PreDestroy
    public void shutdown() {
        stopWorker();
        workerWarmupExecutor.shutdownNow();
        translationExecutor.shutdownNow();
    }

    public RealtimeChunkResponse finishSession(String sessionId) {
        RealtimeSession session = findSession(sessionId);
        if (session == null) {
            return buildMissingSessionResponse(sessionId);
        }
        synchronized (session) {
            String now = Instant.now().toString();
            for (RealtimeSubtitleSegment segment : session.segments) {
                segment.setStatus(STATUS_FINAL);
                segment.setUpdatedAt(now);
            }
            session.finished = true;
        }
        scheduleMissingTranslations(session);
        return buildResponse(session, null);
    }

    private Path saveAudioChunk(String sessionId, long chunkStartMs, long chunkEndMs, MultipartFile audioChunk)
        throws IOException {
        Path realtimeRoot = Paths.get(uploadDir).toAbsolutePath().normalize().resolve("realtime").normalize();
        Path sessionRoot = realtimeRoot.resolve(sessionId).normalize();
        if (!sessionRoot.startsWith(realtimeRoot)) {
            throw new SecurityException("非法 realtime session 路径");
        }
        Files.createDirectories(sessionRoot);

        String extension = resolveExtension(audioChunk);
        String fileName = "chunk_" + chunkStartMs + "_" + chunkEndMs + extension;
        Path chunkPath = sessionRoot.resolve(fileName).normalize();
        if (!chunkPath.startsWith(sessionRoot)) {
            throw new SecurityException("非法 realtime chunk 路径");
        }
        audioChunk.transferTo(chunkPath.toFile());
        return chunkPath;
    }

    private String resolveExtension(MultipartFile audioChunk) {
        String originalName = audioChunk.getOriginalFilename();
        if (originalName != null) {
            int dotIndex = originalName.lastIndexOf('.');
            if (dotIndex >= 0 && dotIndex < originalName.length() - 1) {
                String extension = originalName.substring(dotIndex).toLowerCase();
                if (extension.matches("\\.[a-z0-9]{1,8}")) {
                    return extension;
                }
            }
        }

        String contentType = audioChunk.getContentType();
        if (contentType != null) {
            String type = contentType.toLowerCase();
            if (type.contains("wav")) {
                return ".wav";
            }
            if (type.contains("mpeg") || type.contains("mp3")) {
                return ".mp3";
            }
            if (type.contains("mp4")) {
                return ".m4a";
            }
            if (type.contains("ogg")) {
                return ".ogg";
            }
        }
        return ".webm";
    }

    private TranscriptionResult transcribeChunk(Path chunkPath, String language) throws Exception {
        Path scriptPath = Paths.get(pythonScriptDir).resolve("scripts").resolve("realtime_transcribe.py").normalize();
        File scriptFile = scriptPath.toFile();
        if (!scriptFile.exists()) {
            throw new IOException("实时转录脚本不存在: " + scriptPath);
        }

        try {
            return transcribeChunkWithWorker(scriptPath, chunkPath, language);
        } catch (Exception e) {
            stopWorker();
            throw e;
        }
    }

    private void triggerWorkerPrewarm() {
        synchronized (workerLock) {
            if (workerProcess != null && workerProcess.isAlive() && workerReader != null && workerWriter != null) {
                return;
            }
        }
        if (!workerPrewarmInFlight.compareAndSet(false, true)) {
            return;
        }

        Path scriptPath = Paths.get(pythonScriptDir).resolve("scripts").resolve("realtime_transcribe.py").normalize();
        workerWarmupExecutor.submit(() -> {
            try {
                synchronized (workerLock) {
                    ensureWorkerStarted(scriptPath);
                }
                System.out.println("[REALTIME] worker 后台预热完成");
            } catch (Exception e) {
                System.err.println("[REALTIME] worker 后台预热失败，将在 chunk 到来时重试: "
                    + sanitizeWarning(e.getMessage()));
                stopWorker();
            } finally {
                workerPrewarmInFlight.set(false);
            }
        });
    }

    private TranscriptionResult transcribeChunkWithWorker(Path scriptPath, Path chunkPath, String language) throws Exception {
        synchronized (workerLock) {
            ensureWorkerStarted(scriptPath);

            String requestId = "chunk-" + (++workerRequestSeq);
            JsonObject request = new JsonObject();
            request.addProperty("type", "transcribe");
            request.addProperty("requestId", requestId);
            request.addProperty("audioPath", chunkPath.toString());
            request.addProperty("language", normalizeLanguage(language));

            workerWriter.write(gson.toJson(request));
            workerWriter.newLine();
            workerWriter.flush();

            JsonObject object = readWorkerJsonLine(requestId, Math.max(10, chunkTimeoutSec));
            if (object.has("error") && !object.get("error").isJsonNull()) {
                throw new IOException(object.get("error").getAsString());
            }
            return readTranscriptionResult(object);
        }
    }

    private void ensureWorkerStarted(Path scriptPath) throws Exception {
        if (workerProcess != null && workerProcess.isAlive() && workerReader != null && workerWriter != null) {
            return;
        }

        stopWorker();

        List<String> command = buildPythonWorkerCommand(scriptPath.toString());
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        applyWhisperEnvironment(processBuilder);
        processBuilder.redirectErrorStream(false);

        System.out.println("[REALTIME] starting transcribe worker: " + command);
        workerProcess = processBuilder.start();
        workerReader = new BufferedReader(new InputStreamReader(workerProcess.getInputStream(), StandardCharsets.UTF_8));
        workerWriter = new BufferedWriter(new OutputStreamWriter(workerProcess.getOutputStream(), StandardCharsets.UTF_8));
        startStreamReader(workerProcess.getErrorStream(), "[RealtimeWhisper]", new StringBuilder());

        JsonObject ready = readWorkerJsonLine(null, Math.max(30, chunkTimeoutSec));
        if (ready.has("error") && !ready.get("error").isJsonNull()) {
            throw new IOException(ready.get("error").getAsString());
        }
        System.out.println("[REALTIME] transcribe worker ready");
    }

    private JsonObject readWorkerJsonLine(String expectedRequestId, int timeoutSec) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        StringBuilder skipped = new StringBuilder();

        while (System.currentTimeMillis() < deadline) {
            if (workerProcess == null || !workerProcess.isAlive()) {
                throw new IOException("实时转录 worker 已退出" + buildProcessOutputSummary("", skipped.toString()));
            }

            if (workerReader.ready()) {
                String line = workerReader.readLine();
                if (line == null) {
                    throw new IOException("实时转录 worker stdout 已关闭");
                }
                JsonObject object;
                try {
                    object = parseJsonObject(line, "实时转录 worker 输出不是有效 JSON");
                } catch (IOException e) {
                    skipped.append(line).append('\n');
                    continue;
                }

                String type = readString(object, "type", "");
                String requestId = readString(object, "requestId", "");
                if (expectedRequestId == null && ("ready".equals(type) || object.has("error"))) {
                    return object;
                }
                if (expectedRequestId != null && expectedRequestId.equals(requestId)) {
                    return object;
                }
                skipped.append(line).append('\n');
                continue;
            }

            Thread.sleep(50);
        }

        throw new IOException("实时转录 worker 响应超时: " + timeoutSec + " 秒"
            + buildProcessOutputSummary("", skipped.toString()));
    }

    private List<String> buildPythonWorkerCommand(String scriptPath) {
        List<String> command = new ArrayList<>();
        if (condaEnv != null && !condaEnv.trim().isEmpty()) {
            command.add("conda");
            command.add("run");
            command.add("-n");
            command.add(condaEnv.trim());
        }
        command.add(pythonCommand);
        command.add(scriptPath);
        command.add("--worker");
        return command;
    }

    private void stopWorker() {
        synchronized (workerLock) {
            closeQuietly(workerWriter);
            closeQuietly(workerReader);
            workerWriter = null;
            workerReader = null;
            if (workerProcess != null && workerProcess.isAlive()) {
                workerProcess.destroyForcibly();
            }
            workerProcess = null;
        }
    }

    private void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            // Ignore cleanup failures.
        }
    }

    private void applyWhisperEnvironment(ProcessBuilder processBuilder) {
        Map<String, String> env = processBuilder.environment();
        putEnvIfPresent(env, "WHISPER_MODEL", whisperModel);
        putEnvIfPresent(env, "WHISPER_DEVICE", whisperDevice);
        putEnvIfPresent(env, "WHISPER_COMPUTE_TYPE", whisperComputeType);
        env.put("WHISPER_BATCH_SIZE", String.valueOf(whisperBatchSize));
    }

    private void putEnvIfPresent(Map<String, String> env, String name, String value) {
        if (value != null && !value.trim().isEmpty()) {
            env.put(name, value.trim());
        }
    }

    private TranscriptionResult readTranscriptionResult(JsonObject object) {
        TranscriptionResult result = new TranscriptionResult();
        result.language = readString(object, "language", "unknown");
        result.segments = new ArrayList<>();

        if (!object.has("segments") || !object.get("segments").isJsonArray()) {
            return result;
        }

        JsonArray segments = object.getAsJsonArray("segments");
        for (JsonElement element : segments) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject segmentObject = element.getAsJsonObject();
            String text = readString(segmentObject, "text", "").trim();
            if (text.isEmpty()) {
                continue;
            }
            LocalSegment segment = new LocalSegment();
            segment.startSec = readDouble(segmentObject, "start", 0.0);
            segment.endSec = readDouble(segmentObject, "end", segment.startSec);
            segment.text = text;
            result.segments.add(segment);
        }
        return result;
    }

    private List<RealtimeSubtitleSegment> buildGlobalSegments(String sessionId, long chunkStartMs, long chunkEndMs,
                                                              TranscriptionResult transcription) {
        List<RealtimeSubtitleSegment> segments = new ArrayList<>();
        String now = Instant.now().toString();
        for (LocalSegment local : transcription.segments) {
            long maxStartMs = Math.max(chunkStartMs, chunkEndMs - 1L);
            long startMs = clamp(chunkStartMs + Math.round(local.startSec * 1000.0), chunkStartMs, maxStartMs);
            long endMs = clamp(chunkStartMs + Math.round(local.endSec * 1000.0), startMs + 1L, chunkEndMs);
            RealtimeSubtitleSegment segment = new RealtimeSubtitleSegment(
                sessionId,
                startMs,
                endMs,
                local.text,
                TRANSLATION_PENDING,
                STATUS_PROVISIONAL,
                now
            );
            segments.add(segment);
        }
        return segments;
    }

    private void scheduleTranslation(RealtimeSession session, String language, List<RealtimeSubtitleSegment> segments) {
        if (segments.isEmpty()) {
            return;
        }

        List<RealtimeSubtitleSegment> scheduled = new ArrayList<>();
        synchronized (session) {
            session.lastLanguage = normalizeLanguage(language);
            for (RealtimeSubtitleSegment segment : segments) {
                if (!needsTranslation(segment)) {
                    continue;
                }
                String key = translationKey(segment);
                int attempts = session.translationAttemptCounts.getOrDefault(key, 0);
                if (attempts >= MAX_TRANSLATION_ATTEMPTS || session.translationInFlightKeys.contains(key)) {
                    continue;
                }
                session.translationInFlightKeys.add(key);
                session.translationAttemptCounts.put(key, attempts + 1);
                scheduled.add(segment);
            }
        }

        if (scheduled.isEmpty()) {
            return;
        }

        List<RealtimeSubtitleSegment> snapshot = copySegments(scheduled);
        translationExecutor.submit(() -> translateAndApply(session, language, snapshot));
    }

    private void scheduleMissingTranslations(RealtimeSession session) {
        List<RealtimeSubtitleSegment> missing = new ArrayList<>();
        String language;
        synchronized (session) {
            language = session.lastLanguage;
            for (RealtimeSubtitleSegment segment : session.segments) {
                if (needsTranslation(segment)) {
                    missing.add(segment);
                }
            }
        }
        scheduleTranslation(session, language, missing);
    }

    private void translateAndApply(RealtimeSession session, String language, List<RealtimeSubtitleSegment> snapshot) {
        try {
            List<String> translatedTexts = translateSegmentTexts(language, snapshot);
            applyTranslatedTexts(session, snapshot, translatedTexts);
        } catch (Exception e) {
            if (hasRemainingTranslationAttempts(session, snapshot)) {
                System.err.println("[REALTIME] 字幕后台翻译失败，将保持翻译中并等待重试: " + sanitizeWarning(e.getMessage()));
            } else {
                String placeholder = buildTranslationPlaceholder(e.getMessage());
                applyTranslatedTexts(session, snapshot, repeatText(placeholder, snapshot.size()));
                System.err.println("[REALTIME] 字幕后台翻译多次失败，已写入占位: " + sanitizeWarning(e.getMessage()));
            }
        } finally {
            releaseTranslationKeys(session, snapshot);
        }
    }

    private List<String> translateSegmentTexts(String language, List<RealtimeSubtitleSegment> segments) throws IOException {
        if (segments.isEmpty()) {
            return new ArrayList<>();
        }

        String subtitleJson = buildSubtitleJson(language, segments);
        String translatedJson = subtitleTranslationService.translateSegmentsStrict(subtitleJson, "zh");
        JsonObject translatedObject = parseJsonObject(translatedJson, "实时字幕翻译输出不是有效 JSON");
        JsonArray translatedSegments = translatedObject.has("segments") && translatedObject.get("segments").isJsonArray()
            ? translatedObject.getAsJsonArray("segments")
            : new JsonArray();

        List<String> result = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            String translatedText = segments.get(i).getOriginalText();
            if (i < translatedSegments.size() && translatedSegments.get(i).isJsonObject()) {
                translatedText = readString(translatedSegments.get(i).getAsJsonObject(), "translatedText", translatedText);
            }
            result.add(defaultString(translatedText));
        }
        return result;
    }

    private void applyTranslatedTexts(RealtimeSession session, List<RealtimeSubtitleSegment> snapshot,
                                      List<String> translatedTexts) {
        synchronized (session) {
            String now = Instant.now().toString();
            int count = Math.min(snapshot.size(), translatedTexts.size());
            for (int i = 0; i < count; i++) {
                RealtimeSubtitleSegment source = snapshot.get(i);
                RealtimeSubtitleSegment target = findMatchingSegment(session, source);
                String translatedText = defaultString(translatedTexts.get(i));
                if (target != null && shouldApplyTranslatedText(target, translatedText)) {
                    target.setTranslatedText(translatedText);
                    target.setUpdatedAt(now);
                }
            }
        }
    }

    private RealtimeSubtitleSegment findMatchingSegment(RealtimeSession session, RealtimeSubtitleSegment source) {
        RealtimeSubtitleSegment fallback = null;
        long bestOverlap = 0L;
        for (RealtimeSubtitleSegment segment : session.segments) {
            if (segment.getStartMs() == source.getStartMs()
                && segment.getEndMs() == source.getEndMs()
                && defaultString(segment.getOriginalText()).equals(defaultString(source.getOriginalText()))) {
                return segment;
            }
            if (!needsTranslation(segment)) {
                continue;
            }
            if (!defaultString(segment.getOriginalText()).equals(defaultString(source.getOriginalText()))) {
                continue;
            }
            long overlap = overlapMs(segment, source);
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                fallback = segment;
            }
        }
        return bestOverlap > 0L ? fallback : null;
    }

    private long overlapMs(RealtimeSubtitleSegment left, RealtimeSubtitleSegment right) {
        long start = Math.max(left.getStartMs(), right.getStartMs());
        long end = Math.min(left.getEndMs(), right.getEndMs());
        return Math.max(0L, end - start);
    }

    private boolean shouldApplyTranslatedText(RealtimeSubtitleSegment target, String translatedText) {
        if (translatedText == null || translatedText.trim().isEmpty()) {
            return false;
        }
        return needsTranslation(target) || !isTranslationPlaceholder(translatedText);
    }

    private boolean needsTranslation(RealtimeSubtitleSegment segment) {
        if (segment == null || defaultString(segment.getOriginalText()).trim().isEmpty()) {
            return false;
        }
        String translatedText = defaultString(segment.getTranslatedText()).trim();
        return translatedText.isEmpty() || TRANSLATION_PENDING.equals(translatedText) || isTranslationPlaceholder(translatedText);
    }

    private boolean isTranslationPlaceholder(String value) {
        String text = defaultString(value).trim();
        return text.startsWith("[") && text.endsWith("]") && text.contains("翻译");
    }

    private String translationKey(RealtimeSubtitleSegment segment) {
        return defaultString(segment.getSessionId()) + "|"
            + segment.getStartMs() + "|"
            + segment.getEndMs() + "|"
            + defaultString(segment.getOriginalText());
    }

    private boolean hasRemainingTranslationAttempts(RealtimeSession session, List<RealtimeSubtitleSegment> snapshot) {
        synchronized (session) {
            for (RealtimeSubtitleSegment segment : snapshot) {
                String key = translationKey(segment);
                if (session.translationAttemptCounts.getOrDefault(key, 0) < MAX_TRANSLATION_ATTEMPTS) {
                    return true;
                }
            }
        }
        return false;
    }

    private void releaseTranslationKeys(RealtimeSession session, List<RealtimeSubtitleSegment> snapshot) {
        synchronized (session) {
            for (RealtimeSubtitleSegment segment : snapshot) {
                session.translationInFlightKeys.remove(translationKey(segment));
            }
        }
    }

    private List<String> repeatText(String value, int count) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(value);
        }
        return result;
    }

    private String buildSubtitleJson(String language, List<RealtimeSubtitleSegment> segments) {
        JsonObject object = new JsonObject();
        object.addProperty("language", normalizeLanguage(language));
        JsonArray array = new JsonArray();
        StringBuilder fullText = new StringBuilder();
        for (RealtimeSubtitleSegment segment : segments) {
            JsonObject item = new JsonObject();
            item.addProperty("start", segment.getStartMs() / 1000.0);
            item.addProperty("end", segment.getEndMs() / 1000.0);
            item.addProperty("text", defaultString(segment.getOriginalText()));
            array.add(item);
            if (fullText.length() > 0) {
                fullText.append(' ');
            }
            fullText.append(defaultString(segment.getOriginalText()));
        }
        object.addProperty("text", fullText.toString());
        object.add("segments", array);
        return gson.toJson(object);
    }

    private void updateSessionSegments(RealtimeSession session, long chunkStartMs, long chunkEndMs,
                                       List<RealtimeSubtitleSegment> newSegments) {
        synchronized (session) {
            if (!newSegments.isEmpty()) {
                List<RealtimeSubtitleSegment> merged = new ArrayList<>();
                String now = Instant.now().toString();
                for (RealtimeSubtitleSegment segment : session.segments) {
                    if (STATUS_FINAL.equals(segment.getStatus())
                        || segment.getEndMs() <= chunkStartMs
                        || segment.getStartMs() >= chunkEndMs) {
                        merged.add(segment);
                        continue;
                    }

                    if (segment.getStartMs() < chunkStartMs) {
                        segment.setEndMs(chunkStartMs);
                        segment.setUpdatedAt(now);
                        if (segment.getEndMs() > segment.getStartMs()) {
                            merged.add(segment);
                        }
                    }
                }
                session.segments.clear();
                session.segments.addAll(merged);
                session.segments.addAll(newSegments);
            }
            session.segments.sort(Comparator
                .comparingLong(RealtimeSubtitleSegment::getStartMs)
                .thenComparingLong(RealtimeSubtitleSegment::getEndMs));
            markFinalByWindow(session.segments, chunkEndMs);
        }
    }

    private void markFinalByWindow(List<RealtimeSubtitleSegment> segments, long latestChunkEndMs) {
        long finalBeforeMs = Math.max(0L, latestChunkEndMs - finalizeWindowMs);
        String now = Instant.now().toString();
        for (RealtimeSubtitleSegment segment : segments) {
            if (segment.getEndMs() <= finalBeforeMs) {
                segment.setStatus(STATUS_FINAL);
                segment.setUpdatedAt(now);
            } else if (!STATUS_FINAL.equals(segment.getStatus())) {
                segment.setStatus(STATUS_PROVISIONAL);
                segment.setUpdatedAt(now);
            }
        }
    }

    private RealtimeChunkResponse buildResponse(RealtimeSession session, String warning) {
        synchronized (session) {
            return new RealtimeChunkResponse(session.sessionId, copySegments(session.segments), warning);
        }
    }

    private RealtimeChunkResponse buildMissingSessionResponse(String sessionId) {
        String safeSessionId = sessionId == null ? "" : sessionId.trim();
        String warning = "realtime session 不存在或已过期，请重新点击“开始准实时同传”创建新 session。";
        return new RealtimeChunkResponse(safeSessionId, new ArrayList<>(), warning);
    }

    private List<RealtimeSubtitleSegment> copySegments(List<RealtimeSubtitleSegment> source) {
        List<RealtimeSubtitleSegment> copy = new ArrayList<>();
        for (RealtimeSubtitleSegment segment : source) {
            copy.add(new RealtimeSubtitleSegment(
                segment.getSessionId(),
                segment.getStartMs(),
                segment.getEndMs(),
                segment.getOriginalText(),
                segment.getTranslatedText(),
                segment.getStatus(),
                segment.getUpdatedAt()
            ));
        }
        return copy;
    }

    private RealtimeSession findSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        return sessions.get(sessionId.trim());
    }

    private void pruneOldSessionsIfNeeded() {
        if (maxSessions <= 0 || sessions.size() < maxSessions) {
            return;
        }
        String oldestSessionId = null;
        String oldestStartedAt = null;
        for (RealtimeSession session : sessions.values()) {
            if (oldestStartedAt == null || session.startedAt.compareTo(oldestStartedAt) < 0) {
                oldestStartedAt = session.startedAt;
                oldestSessionId = session.sessionId;
            }
        }
        if (oldestSessionId != null) {
            sessions.remove(oldestSessionId);
        }
    }

    private Thread startStreamReader(InputStream inputStream, String prefix, StringBuilder capture) {
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(prefix + " " + line);
                    capture.append(line).append('\n');
                }
            } catch (IOException e) {
                // Process shutdown can close streams early; no action needed for this demo path.
            }
        });
        readerThread.start();
        return readerThread;
    }

    private JsonObject parseJsonObject(String value, String errorMessage) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(extractFirstJsonObject(value));
            if (parsed != null && parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (Exception e) {
            throw new IOException(errorMessage + ": " + e.getMessage(), e);
        }
        throw new IOException(errorMessage);
    }

    private String extractFirstJsonObject(String value) throws IOException {
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("JSON 内容为空");
        }

        int start = value.indexOf('{');
        if (start < 0) {
            throw new IOException("未找到 JSON 对象起始符");
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return value.substring(start, i + 1);
                }
            }
        }
        throw new IOException("JSON 对象不完整");
    }

    private String buildProcessOutputSummary(String stdout, String stderr) {
        StringBuilder summary = new StringBuilder();
        if (stderr != null && !stderr.trim().isEmpty()) {
            summary.append("，stderr: ").append(truncate(stderr.trim()));
        }
        if (stdout != null && !stdout.trim().isEmpty()) {
            summary.append("，stdout: ").append(truncate(stdout.trim()));
        }
        return summary.toString();
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return defaultString(value);
        }
        return value.substring(0, 1000) + "...";
    }

    private String buildTranslationPlaceholder(String message) {
        String safeMessage = sanitizeWarning(message);
        if (safeMessage != null && safeMessage.contains("LLM_API_KEY")) {
            return "[未配置 LLM_API_KEY，暂未翻译]";
        }
        return "[字幕翻译暂不可用]";
    }

    private String sanitizeWarning(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        return SECRET_PATTERN.matcher(value).replaceAll("sk-***REDACTED***");
    }

    private void logRealtimeWarning(String prefix, Exception e) {
        String message = e == null ? "" : sanitizeWarning(e.getMessage());
        String type = e == null ? "unknown" : e.getClass().getName();
        System.err.println("[REALTIME] " + prefix + ": " + type + ": " + message);
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.trim().isEmpty()) {
            return "auto";
        }
        return language.trim();
    }

    private long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private String readString(JsonObject object, String name, String defaultValue) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return defaultValue;
        }
        try {
            return object.get(name).getAsString();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private double readDouble(JsonObject object, String name, double defaultValue) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return defaultValue;
        }
        try {
            return object.get(name).getAsDouble();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private static class RealtimeSession {
        private final String sessionId;
        private final String startedAt;
        private final List<RealtimeSubtitleSegment> segments = new ArrayList<>();
        private final Set<String> translationInFlightKeys = ConcurrentHashMap.newKeySet();
        private final Map<String, Integer> translationAttemptCounts = new ConcurrentHashMap<>();
        private String lastLanguage = "auto";
        private boolean finished;

        private RealtimeSession(String sessionId, String startedAt) {
            this.sessionId = sessionId;
            this.startedAt = startedAt;
        }
    }

    private static class TranscriptionResult {
        private String language;
        private List<LocalSegment> segments = new ArrayList<>();
    }

    private static class LocalSegment {
        private double startSec;
        private double endSec;
        private String text;
    }
}
