package com.video.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.video.dto.SystemCheckResponse;
import com.video.repository.VideoRepository;
import com.video.service.DifyService;
import com.video.service.DownloadService;
import com.video.service.LlmAnalysisService;
import com.video.service.SubtitleExtractionService;
import com.video.service.SubtitleTranslationService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

@RestController
@RequestMapping("/api/test")
@CrossOrigin(origins = "*")
public class HealthCheckController {

    private static final int COMMAND_TIMEOUT_SEC = 20;
    private static final int MAX_MESSAGE_LENGTH = 1200;

    @Autowired
    private SubtitleExtractionService subtitleExtractionService;

    @Autowired
    private DifyService difyService;

    @Autowired
    private LlmAnalysisService llmAnalysisService;

    @Autowired
    private SubtitleTranslationService subtitleTranslationService;

    @Autowired
    private DownloadService downloadService;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Value("${video.upload-dir}")
    private String uploadDir;

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

    @Value("${video.whisper-enable-align:true}")
    private boolean whisperEnableAlign;

    @Value("${downloader.ytdlp-path:yt-dlp}")
    private String ytdlpPath;

    @Value("${downloader.ffmpeg-path:}")
    private String ffmpegPath;

    @Value("${dify.api-url:}")
    private String difyApiUrl;

    @Value("${dify.api-key:}")
    private String difyApiKey;

    @Value("${llm.api-url:}")
    private String llmApiUrl;

    @Value("${llm.api-key:}")
    private String llmApiKey;

    @Value("${llm.model:}")
    private String llmModel;

    private final Gson gson = new Gson();

    @GetMapping("/health/full")
    public SystemCheckResponse fullHealthCheck() {
        SystemCheckResponse response = new SystemCheckResponse();
        response.addCheck("java-service", true, "Java 服务正常");
        checkUploadDir(response);
        checkPython(response);
        checkWhisperImports(response);
        checkWhisperConfig(response);
        checkExecutable(response, "yt-dlp", buildSimpleCommand(ytdlpPath, "--version"));
        checkExecutable(response, "ffmpeg", buildFfmpegCommand());
        checkLlmConfig(response);
        checkDifyConfig(response);
        checkActiveProfiles(response);
        checkDatabase(response);
        return response;
    }

    @PostMapping("/subtitle-script")
    public SubtitleScriptTestResponse subtitleScript(@RequestBody SubtitleScriptTestRequest request) {
        SubtitleScriptTestResponse response = new SubtitleScriptTestResponse();
        long startMs = System.currentTimeMillis();
        try {
            String videoPath = request != null ? request.getVideoPath() : null;
            String language = request != null && request.getLanguage() != null ? request.getLanguage() : "auto";
            File videoFile = validateLocalVideoPath(videoPath);

            String subtitleJson = subtitleExtractionService.extractSubtitle(videoFile.getAbsolutePath(), language);
            response.setSubtitleDurationMs(elapsedMs(startMs));

            JsonObject subtitle = gson.fromJson(subtitleJson, JsonObject.class);
            response.setOk(true);
            response.setLanguage(readString(subtitle, "language"));
            response.setAligned(readBoolean(subtitle, "aligned"));
            response.setAlignDurationMs(readLong(subtitle, "alignDurationMs"));

            JsonArray segments = subtitle != null && subtitle.has("segments") && subtitle.get("segments").isJsonArray()
                ? subtitle.getAsJsonArray("segments")
                : new JsonArray();
            response.setSegmentCount(segments.size());
            response.setFirstSegments(readFirstSegments(segments));
        } catch (Exception e) {
            response.setOk(false);
            response.setSubtitleDurationMs(elapsedMs(startMs));
            response.setErrorMessage(e.getMessage());
        }
        return response;
    }

    @PostMapping("/dify")
    public DifyTestResponse dify(@RequestBody DifyTestRequest request) {
        DifyTestResponse response = new DifyTestResponse();
        long startMs = System.currentTimeMillis();
        try {
            if (difyApiKey == null || difyApiKey.trim().isEmpty()) {
                throw new IllegalStateException("DIFY_API_KEY 未配置。");
            }
            String text = request != null && request.getText() != null ? request.getText() : "这是一段测试字幕";
            String language = request != null && request.getLanguage() != null ? request.getLanguage() : "zh";
            String rawResult = difyService.analyzeSubtitle(text, language);

            response.setOk(true);
            response.setRawResult(truncate(rawResult));
            try {
                response.setParsedResult(gson.fromJson(rawResult, Object.class));
            } catch (Exception ignored) {
                // rawResult 已返回，解析失败不影响连通性诊断。
            }
        } catch (Exception e) {
            response.setOk(false);
            response.setErrorMessage(e.getMessage());
        } finally {
            response.setDurationMs(elapsedMs(startMs));
        }
        return response;
    }

    @PostMapping("/llm")
    public LlmTestResponse llm(@RequestBody LlmTestRequest request) {
        LlmTestResponse response = new LlmTestResponse();
        long startMs = System.currentTimeMillis();
        try {
            if (llmApiKey == null || llmApiKey.trim().isEmpty()) {
                throw new IllegalStateException("LLM_API_KEY 未配置。");
            }
            String text = request != null && request.getText() != null ? request.getText() : "这是一段测试字幕";
            String language = request != null && request.getLanguage() != null ? request.getLanguage() : "zh";
            String rawResult = llmAnalysisService.analyzeSubtitle(text, language);

            response.setOk(true);
            response.setRawResult(truncate(rawResult));
            try {
                response.setParsedResult(gson.fromJson(rawResult, Object.class));
            } catch (Exception ignored) {
                // rawResult 已返回，解析失败不影响连通性诊断。
            }
        } catch (Exception e) {
            response.setOk(false);
            response.setErrorMessage(e.getMessage());
        } finally {
            response.setDurationMs(elapsedMs(startMs));
        }
        return response;
    }

    @PostMapping("/translate-subtitles")
    public SubtitleTranslateTestResponse translateSubtitles(@RequestBody SubtitleTranslateTestRequest request) {
        SubtitleTranslateTestResponse response = new SubtitleTranslateTestResponse();
        long startMs = System.currentTimeMillis();
        try {
            if (llmApiKey == null || llmApiKey.trim().isEmpty()) {
                throw new IllegalStateException("LLM_API_KEY 未配置。");
            }

            String targetLanguage = request != null && request.getTargetLanguage() != null
                ? request.getTargetLanguage()
                : "zh";
            JsonObject subtitleJson = buildSubtitleTranslateTestJson(request);
            String translatedJson = subtitleTranslationService.translateSegmentsStrict(gson.toJson(subtitleJson), targetLanguage);
            JsonObject translated = gson.fromJson(translatedJson, JsonObject.class);
            JsonArray segments = translated != null && translated.has("segments") && translated.get("segments").isJsonArray()
                ? translated.getAsJsonArray("segments")
                : new JsonArray();

            response.setOk(true);
            response.setSegmentCount(segments.size());
            response.setSegments(readTranslatedSegments(segments));
        } catch (Exception e) {
            response.setOk(false);
            response.setErrorMessage(e.getMessage());
        } finally {
            response.setDurationMs(elapsedMs(startMs));
        }
        return response;
    }

    @PostMapping("/downloader")
    public DownloaderTestResponse downloader(@RequestBody DownloaderTestRequest request) {
        DownloaderTestResponse response = new DownloaderTestResponse();
        long startMs = System.currentTimeMillis();
        try {
            String url = request != null ? request.getUrl() : null;
            if (url == null || url.trim().isEmpty()) {
                throw new IllegalArgumentException("url 不能为空");
            }

            String filePath = downloadService.downloadVideo(url.trim());
            File file = new File(filePath);
            response.setOk(true);
            response.setFilePath(file.getAbsolutePath());
            response.setFileSize(file.exists() ? file.length() : 0L);
        } catch (Exception e) {
            response.setOk(false);
            response.setErrorMessage(e.getMessage());
        } finally {
            response.setDurationMs(elapsedMs(startMs));
        }
        return response;
    }

    private void checkUploadDir(SystemCheckResponse response) {
        try {
            File dir = new File(uploadDir);
            if (!dir.exists() && !dir.mkdirs()) {
                response.addCheck("uploadDir", false, "目录不存在且创建失败: " + dir.getAbsolutePath());
                return;
            }
            response.addCheck("uploadDir", dir.isDirectory() && dir.canWrite(), "路径: " + dir.getAbsolutePath()
                + ", exists=" + dir.exists() + ", writable=" + dir.canWrite());
        } catch (Exception e) {
            response.addCheck("uploadDir", false, e.getMessage());
        }
    }

    private void checkPython(SystemCheckResponse response) {
        CommandResult result = runCommand(buildPythonCommand("-c", "import sys; print(sys.version)"));
        response.addCheck("python", result.exitCode == 0, result.exitCode == 0
            ? firstLine(result.output)
            : "Python 不可用: " + truncate(result.output));
    }

    private void checkWhisperImports(SystemCheckResponse response) {
        CommandResult whisperxResult = runCommand(buildPythonCommand("-c", "import whisperx; print('whisperx import ok')"));
        response.addCheck("import-whisperx", whisperxResult.exitCode == 0,
            whisperxResult.exitCode == 0 ? "whisperx 可导入" : truncate(whisperxResult.output));

        CommandResult torchResult = runCommand(buildPythonCommand("-c",
            "import torch; print('torch import ok'); print('cuda=' + str(torch.cuda.is_available()))"));
        if (torchResult.exitCode == 0) {
            String output = torchResult.output.trim();
            response.addCheck("import-torch", output.contains("torch import ok"), "torch 可导入");
            response.addCheck("torch.cuda.is_available", true, findCudaMessage(output));
        } else {
            String message = truncate(torchResult.output);
            response.addCheck("import-torch", false, message);
            response.addCheck("torch.cuda.is_available", false, "无法导入 torch，未执行 CUDA 检查");
        }
    }

    private void checkWhisperConfig(SystemCheckResponse response) {
        response.addCheck("WHISPER_MODEL", true, whisperModel);
        response.addCheck("WHISPER_DEVICE", true, whisperDevice);
        response.addCheck("WHISPER_COMPUTE_TYPE", true,
            whisperComputeType == null || whisperComputeType.trim().isEmpty() ? "(auto: cpu=int8, cuda=float16)" : whisperComputeType);
        response.addCheck("WHISPER_BATCH_SIZE", true, String.valueOf(whisperBatchSize));
        response.addCheck("WHISPER_ENABLE_ALIGN", true, String.valueOf(whisperEnableAlign));
    }

    private void checkExecutable(SystemCheckResponse response, String name, List<String> command) {
        CommandResult result = runCommand(command);
        response.addCheck(name, result.exitCode == 0, result.exitCode == 0
            ? truncate(firstLine(result.output))
            : name + " 不可用: " + truncate(result.output));
    }

    private void checkDifyConfig(SystemCheckResponse response) {
        boolean urlConfigured = difyApiUrl != null && !difyApiUrl.trim().isEmpty();
        boolean keyConfigured = difyApiKey != null && !difyApiKey.trim().isEmpty();
        response.addCheck("DIFY_API_URL_LEGACY", true, "legacy configured=" + urlConfigured);
        response.addCheck("DIFY_API_KEY_LEGACY", true, "legacy configured=" + keyConfigured);
    }

    private void checkLlmConfig(SystemCheckResponse response) {
        boolean urlConfigured = llmApiUrl != null && !llmApiUrl.trim().isEmpty();
        boolean keyConfigured = llmApiKey != null && !llmApiKey.trim().isEmpty();
        boolean modelConfigured = llmModel != null && !llmModel.trim().isEmpty();
        response.addCheck("LLM_API_URL", urlConfigured, "configured=" + urlConfigured);
        response.addCheck("LLM_API_KEY", keyConfigured, "configured=" + keyConfigured);
        response.addCheck("LLM_MODEL", modelConfigured, modelConfigured ? llmModel : "not configured");
    }

    private void checkActiveProfiles(SystemCheckResponse response) {
        String[] profiles = environment != null ? environment.getActiveProfiles() : new String[0];
        String message = profiles.length == 0 ? "default (H2)" : String.join(",", profiles);
        response.addCheck("spring-profiles", true, message);
    }

    private void checkDatabase(SystemCheckResponse response) {
        try {
            long count = videoRepository.count();
            String jdbcUrl = "";
            String product = "";
            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metaData = connection.getMetaData();
                jdbcUrl = sanitizeJdbcUrl(metaData.getURL());
                product = metaData.getDatabaseProductName();
            }
            response.addCheck("database", true, product + " 可访问，videos=" + count + ", url=" + jdbcUrl);
        } catch (Exception e) {
            response.addCheck("database", false, e.getMessage());
        }
    }

    private String sanitizeJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "";
        }
        return jdbcUrl
            .replaceAll("(?i)(password=)[^&;]+", "$1***")
            .replaceAll("(?i)(pwd=)[^&;]+", "$1***");
    }

    private List<String> buildPythonCommand(String... args) {
        List<String> command = new ArrayList<>();
        if (condaEnv != null && !condaEnv.trim().isEmpty()) {
            command.add("conda");
            command.add("run");
            command.add("-n");
            command.add(condaEnv.trim());
        }
        command.add(pythonCommand);
        command.addAll(Arrays.asList(args));
        return command;
    }

    private List<String> buildSimpleCommand(String executable, String arg) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add(arg);
        return command;
    }

    private List<String> buildFfmpegCommand() {
        String executable = ffmpegPath != null && !ffmpegPath.trim().isEmpty() ? ffmpegPath.trim() : "ffmpeg";
        return buildSimpleCommand(executable, "-version");
    }

    private CommandResult runCommand(List<String> command) {
        CommandResult result = new CommandResult();
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            Thread reader = new Thread(() -> {
                try (BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = bufferedReader.readLine()) != null) {
                        if (output.length() < MAX_MESSAGE_LENGTH * 2) {
                            output.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                    // 命令进程退出时可能关闭流，这里只做健康检查。
                }
            });
            reader.start();

            boolean finished = process.waitFor(COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.exitCode = -1;
                result.output = "命令超时: " + String.join(" ", command);
                return result;
            }

            reader.join(1000);
            result.exitCode = process.exitValue();
            result.output = output.toString();
        } catch (Exception e) {
            result.exitCode = -1;
            result.output = e.getMessage();
        }
        return result;
    }

    private File validateLocalVideoPath(String videoPath) {
        if (videoPath == null || videoPath.trim().isEmpty()) {
            throw new IllegalArgumentException("videoPath 不能为空");
        }

        Path path = Paths.get(videoPath.trim()).toAbsolutePath().normalize();
        File file = path.toFile();
        if (!file.exists()) {
            throw new IllegalArgumentException("视频文件不存在: " + path);
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("videoPath 必须是本机普通文件: " + path);
        }

        String lower = file.getName().toLowerCase();
        if (!(lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv")
            || lower.endsWith(".webm") || lower.endsWith(".avi") || lower.endsWith(".m4v"))) {
            throw new IllegalArgumentException("仅允许常见视频文件扩展名，避免测试接口读取敏感文件: " + file.getName());
        }
        return file;
    }

    private List<SubtitleSegmentPreview> readFirstSegments(JsonArray segments) {
        List<SubtitleSegmentPreview> result = new ArrayList<>();
        int limit = Math.min(3, segments.size());
        for (int i = 0; i < limit; i++) {
            JsonElement element = segments.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject segment = element.getAsJsonObject();
            SubtitleSegmentPreview preview = new SubtitleSegmentPreview();
            preview.setStart(readDouble(segment, "start"));
            preview.setEnd(readDouble(segment, "end"));
            preview.setText(readString(segment, "text"));
            result.add(preview);
        }
        return result;
    }

    private JsonObject buildSubtitleTranslateTestJson(SubtitleTranslateTestRequest request) {
        JsonObject subtitle = new JsonObject();
        subtitle.addProperty("language", "en");
        JsonArray segments = new JsonArray();

        List<SubtitleTranslateSegmentRequest> requestSegments = request != null ? request.getSegments() : null;
        if (requestSegments != null) {
            for (SubtitleTranslateSegmentRequest segment : requestSegments) {
                JsonObject object = new JsonObject();
                object.addProperty("start", segment.getStart() != null ? segment.getStart() : 0.0);
                object.addProperty("end", segment.getEnd() != null ? segment.getEnd() : 0.0);
                object.addProperty("text", segment.getText() != null ? segment.getText() : "");
                segments.add(object);
            }
        }

        subtitle.add("segments", segments);
        return subtitle;
    }

    private List<TranslatedSubtitleSegmentPreview> readTranslatedSegments(JsonArray segments) {
        List<TranslatedSubtitleSegmentPreview> result = new ArrayList<>();
        for (JsonElement element : segments) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject segment = element.getAsJsonObject();
            TranslatedSubtitleSegmentPreview preview = new TranslatedSubtitleSegmentPreview();
            preview.setStart(readDouble(segment, "start"));
            preview.setEnd(readDouble(segment, "end"));
            preview.setSourceText(readString(segment, "sourceText"));
            preview.setTranslatedText(readString(segment, "translatedText"));
            result.add(preview);
        }
        return result;
    }

    private String readString(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        return object.get(name).getAsString();
    }

    private Boolean readBoolean(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        return object.get(name).getAsBoolean();
    }

    private Long readLong(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return 0L;
        }
        return object.get(name).getAsLong();
    }

    private Double readDouble(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return 0.0;
        }
        return object.get(name).getAsDouble();
    }

    private String findCudaMessage(String output) {
        for (String line : output.split("\\R")) {
            if (line.startsWith("cuda=")) {
                return line;
            }
        }
        return "cuda=unknown";
    }

    private String firstLine(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        return value.trim().split("\\R", 2)[0];
    }

    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= MAX_MESSAGE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }

    private long elapsedMs(long startMs) {
        return Math.max(0L, System.currentTimeMillis() - startMs);
    }

    private static class CommandResult {
        private int exitCode;
        private String output;
    }

    @Data
    public static class SubtitleScriptTestRequest {
        private String videoPath;
        private String language = "auto";
    }

    @Data
    public static class SubtitleScriptTestResponse {
        private boolean ok;
        private String language;
        private Boolean aligned;
        private int segmentCount;
        private List<SubtitleSegmentPreview> firstSegments = new ArrayList<>();
        private Long alignDurationMs;
        private Long subtitleDurationMs;
        private String errorMessage;
    }

    @Data
    public static class SubtitleSegmentPreview {
        private Double start;
        private Double end;
        private String text;
    }

    @Data
    public static class DifyTestRequest {
        private String text;
        private String language = "zh";
    }

    @Data
    public static class DifyTestResponse {
        private boolean ok;
        private String rawResult;
        private Object parsedResult;
        private Long durationMs;
        private String errorMessage;
    }

    @Data
    public static class LlmTestRequest {
        private String text;
        private String language = "zh";
    }

    @Data
    public static class LlmTestResponse {
        private boolean ok;
        private String rawResult;
        private Object parsedResult;
        private Long durationMs;
        private String errorMessage;
    }

    @Data
    public static class SubtitleTranslateTestRequest {
        private List<SubtitleTranslateSegmentRequest> segments = new ArrayList<>();
        private String targetLanguage = "zh";
    }

    @Data
    public static class SubtitleTranslateSegmentRequest {
        private Double start;
        private Double end;
        private String text;
    }

    @Data
    public static class SubtitleTranslateTestResponse {
        private boolean ok;
        private int segmentCount;
        private List<TranslatedSubtitleSegmentPreview> segments = new ArrayList<>();
        private Long durationMs;
        private String errorMessage;
    }

    @Data
    public static class TranslatedSubtitleSegmentPreview {
        private Double start;
        private Double end;
        private String sourceText;
        private String translatedText;
    }

    @Data
    public static class DownloaderTestRequest {
        private String url;
    }

    @Data
    public static class DownloaderTestResponse {
        private boolean ok;
        private String filePath;
        private Long fileSize;
        private Long durationMs;
        private String errorMessage;
    }
}
