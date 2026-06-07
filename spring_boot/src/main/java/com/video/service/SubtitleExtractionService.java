package com.video.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;

@Service
public class SubtitleExtractionService {

    @Value("${video.python-script-dir}")
    private String pythonScriptDir;

    @Value("${video.python-command:python}")
    private String pythonCommand;

    @Value("${video.conda-env:}")
    private String condaEnv;

    @Value("${video.subtitle-timeout-sec:600}")
    private int subtitleTimeoutSec;

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

    @Value("${video.whisper-align-fallback:true}")
    private boolean whisperAlignFallback;

    public String extractSubtitle(String videoPath, String language) throws Exception {
        String scriptPath = Paths.get(pythonScriptDir).resolve("extract_subtitle_whisperx.py").normalize().toString();
        String subtitleOutputPath = videoPath.substring(0, videoPath.lastIndexOf('.')) + "_subtitle.json";
        String normalizedLanguage = (language == null || language.trim().isEmpty()) ? "auto" : language.trim();

        System.out.println("开始提取字幕，使用 WhisperX...");
        System.out.println("视频路径: " + videoPath);
        System.out.println("字幕脚本: " + scriptPath);
        System.out.println("预期字幕输出路径: " + subtitleOutputPath);

        ProcessBuilder processBuilder = new ProcessBuilder(buildPythonCommand(scriptPath, videoPath, normalizedLanguage));
        applyWhisperEnvironment(processBuilder);
        processBuilder.redirectErrorStream(false);

        Process process = processBuilder.start();

        // 同时消费 stdout/stderr，避免 Python 输出较大时阻塞进程。
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread stdoutReader = startStreamReader(process.getInputStream(), "[WhisperX-stdout]", stdout);
        Thread stderrReader = startStreamReader(process.getErrorStream(), "[WhisperX]", stderr);

        // 轮询检查字幕文件是否生成，最多等待 10 分钟
        java.io.File subtitleFile = new java.io.File(subtitleOutputPath);
        int maxWaitSeconds = subtitleTimeoutSec;
        int waitedSeconds = 0;
        
        while (!subtitleFile.exists() && waitedSeconds < maxWaitSeconds) {
            if (!process.isAlive()) {
                int exitCode = process.exitValue();
                throw new RuntimeException("字幕脚本提前退出，退出码: " + exitCode 
                    + "，字幕文件未生成: " + subtitleOutputPath 
                    + buildProcessOutputSummary(stdout, stderr));
            }
            try {
                Thread.sleep(1000); // 每秒检查一次
                waitedSeconds++;
                if (waitedSeconds % 10 == 0) {
                    System.out.println("等待字幕文件生成... 已等待 " + waitedSeconds + " 秒");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待字幕文件时被中断");
            }
        }
        
        // 检查文件是否存在
        if (subtitleFile.exists()) {
            System.out.println("成功找到字幕文件，正在读取...");
            String result = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(subtitleOutputPath)), StandardCharsets.UTF_8);
            
            // 强制终止 Python 进程（如果还在运行）
            if (process.isAlive()) {
                System.out.println("字幕文件已生成，强制终止 Python 进程以避免阻塞");
                process.destroyForcibly();
            }
            stdoutReader.join(1000);
            stderrReader.join(1000);
            
            return result;
        } else {
            // 如果文件不存在，强制终止进程并抛出异常
            process.destroyForcibly();
            System.err.println("错误：等待超时，字幕文件未生成！");
            throw new RuntimeException("字幕文件生成失败: " + subtitleOutputPath + buildProcessOutputSummary(stdout, stderr));
        }
    }

    private String buildProcessOutputSummary(StringBuilder stdout, StringBuilder stderr) {
        StringBuilder summary = new StringBuilder();
        if (stderr.length() > 0) {
            summary.append("，stderr: ").append(truncateOutput(stderr.toString()));
        }
        if (stdout.length() > 0) {
            summary.append("，stdout: ").append(truncateOutput(stdout.toString()));
        }
        return summary.toString();
    }

    private String truncateOutput(String output) {
        String normalized = output == null ? "" : output.trim();
        if (normalized.length() <= 1000) {
            return normalized;
        }
        return normalized.substring(0, 1000) + "...";
    }

    private List<String> buildPythonCommand(String scriptPath, String videoPath, String language) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        if (condaEnv != null && !condaEnv.trim().isEmpty()) {
            command.add("conda");
            command.add("run");
            command.add("-n");
            command.add(condaEnv.trim());
        }
        command.add(pythonCommand);
        command.add(scriptPath);
        command.add(videoPath);
        command.add(language);
        System.out.println("字幕命令: " + command);
        return command;
    }

    private void applyWhisperEnvironment(ProcessBuilder processBuilder) {
        java.util.Map<String, String> env = processBuilder.environment();
        putEnvIfPresent(env, "WHISPER_MODEL", whisperModel);
        putEnvIfPresent(env, "WHISPER_DEVICE", whisperDevice);
        putEnvIfPresent(env, "WHISPER_COMPUTE_TYPE", whisperComputeType);
        env.put("WHISPER_BATCH_SIZE", String.valueOf(whisperBatchSize));
        env.put("WHISPER_ENABLE_ALIGN", String.valueOf(whisperEnableAlign));
        env.put("WHISPER_ALIGN_FALLBACK", String.valueOf(whisperAlignFallback));

        System.out.println("Whisper 配置: model=" + env.get("WHISPER_MODEL")
            + ", device=" + env.get("WHISPER_DEVICE")
            + ", computeType=" + env.get("WHISPER_COMPUTE_TYPE")
            + ", batchSize=" + env.get("WHISPER_BATCH_SIZE")
            + ", enableAlign=" + env.get("WHISPER_ENABLE_ALIGN")
            + ", alignFallback=" + env.get("WHISPER_ALIGN_FALLBACK"));
    }

    private void putEnvIfPresent(java.util.Map<String, String> env, String name, String value) {
        if (value != null && !value.trim().isEmpty()) {
            env.put(name, value.trim());
        }
    }

    private Thread startStreamReader(InputStream inputStream, String prefix, StringBuilder capture) {
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(prefix + " " + line);
                    if (capture != null) {
                        capture.append(line).append('\n');
                    }
                }
            } catch (IOException e) {
                // 进程被主动终止时这里可能抛 IO 异常，日志价值不高。
            }
        });
        readerThread.start();
        return readerThread;
    }

    public String convertSubtitleToText(String subtitleJson) {
        if (subtitleJson == null || subtitleJson.isEmpty()) {
            return "";
        }
        try {
            Gson gson = new Gson();
            // Directly parse the JSON to a class that matches its structure.
            SubtitleFileDto subtitleDto = gson.fromJson(subtitleJson, SubtitleFileDto.class);
            if (subtitleDto == null || subtitleDto.segments == null) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            int validSegments = 0;
            for (SubtitleFileDto.Segment segment : subtitleDto.segments) {
                if (segment.text != null && segment.start != null && segment.end != null) {
                    // 生成带时间戳的格式：[开始时间s - 结束时间s] 文本
                    sb.append(String.format("[%.2fs - %.2fs] %s\n", 
                        segment.start, segment.end, segment.text.trim()));
                    validSegments++;
                }
            }
            String result = sb.toString().trim();
            System.out.println("========================================");
            System.out.println("字幕转换完成:");
            System.out.println("有效片段数: " + validSegments);
            System.out.println("转换后文本长度: " + result.length());
            System.out.println("前500字符预览:");
            System.out.println(result.substring(0, Math.min(500, result.length())));
            System.out.println("========================================");
            return result;
        } catch (Exception e) {
            System.err.println("将字幕JSON转换为文本时出错: " + e.getMessage());
            // Fallback to simple text extraction if DTO parsing fails
            try {
                JsonObject subtitleObject = new Gson().fromJson(subtitleJson, JsonObject.class);
                if (subtitleObject.has("text")) {
                    return subtitleObject.get("text").getAsString();
                }
            } catch (Exception ex) {
                System.err.println("Fallback text extraction failed: " + ex.getMessage());
            }
            return ""; // Return empty string on error
        }
    }

    // Helper class for deserializing subtitle JSON
    private static class SubtitleFileDto {
        List<Segment> segments;
        String text;

        static class Segment {
            Double start;
            Double end;
            String text;
        }
    }
}
