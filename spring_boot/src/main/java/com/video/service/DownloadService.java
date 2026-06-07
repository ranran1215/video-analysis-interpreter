package com.video.service;

import com.video.util.UrlNormalizeUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频下载服务
 * 使用 yt-dlp 下载在线视频（支持 Bilibili、抖音、YouTube 等）
 */
@Service
public class DownloadService {

    @Value("${video.upload-dir}")
    private String uploadDir;

    @Value("${downloader.ytdlp-path:yt-dlp}")
    private String ytdlpPath;

    @Value("${downloader.ffmpeg-path:}")
    private String ffmpegPath;

    @Value("${downloader.cookies-from-browser:}")
    private String cookiesFromBrowser;

    @Value("${downloader.cookies-file:}")
    private String cookiesFile;

    @Value("${downloader.js-runtimes:}")
    private String jsRuntimes;

    @Value("${downloader.sleep-requests-sec:0}")
    private double sleepRequestsSec;

    @Value("${downloader.proxy:}")
    private String proxy;

    @Value("${downloader.max-duration-sec:3600}")
    private int maxDurationSec;

    @Value("${downloader.max-size-mb:500}")
    private int maxSizeMB;

    @Value("${downloader.allowed-sites:bilibili.com,douyin.com,youtube.com,youtu.be}")
    private String allowedSites;

    /**
     * 下载视频到本地
     * 
     * @param url 视频URL
     * @return 下载后的本地文件路径
     * @throws Exception 下载失败时抛出异常
     */
    public String downloadVideo(String url) throws Exception {
        System.out.println("========================================");
        System.out.println("开始下载视频: " + safeUrlForLog(url));
        System.out.println("========================================");

        // 1. 验证 URL
        validateUrl(url);

        // 2. 确保上传目录存在
        File uploadDirFile = new File(uploadDir);
        if (!uploadDirFile.exists()) {
            uploadDirFile.mkdirs();
        }

        // 3. 构建 yt-dlp 命令
        List<String> command = buildYtDlpCommand(url);
        System.out.println("执行命令: " + safeCommandForLog(command));

        // 4. 执行下载
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true); // 合并标准输出和错误输出
        
        Process process = pb.start();

        // 5. 读取输出（用于调试和错误信息）
        StringBuilder output = new StringBuilder();
        String downloadedFilePath = null;
        
        // 读取标准输出
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[yt-dlp] " + line);
                output.append(line).append("\n");
                
                // 捕获 yt-dlp 输出的文件路径（--print after_move:filepath）
                if (looksLikeDownloadedFilePath(line)) {
                    downloadedFilePath = line.trim();
                    System.out.println("✓ 捕获到下载文件路径: " + downloadedFilePath);
                }
                
                // 捕获下载进度
                if (line.contains("Destination:") || line.contains("已下载") || line.contains("%")) {
                    System.out.println("  进度: " + line);
                }
            }
        }

        // 6. 等待进程完成
        int exitCode = process.waitFor();
        System.out.println("yt-dlp 退出码: " + exitCode);

        if (exitCode != 0) {
            String errorMsg = "下载失败，退出码: " + exitCode + "\n输出: " + output.toString();
            System.err.println(errorMsg);
            
            // 检查常见错误
            String friendlyMessage = buildFriendlyDownloadError(output.toString());
            if (friendlyMessage != null) {
                throw new Exception(friendlyMessage);
            } else if (output.toString().contains("ERROR: Unable to download")) {
                throw new Exception("无法下载视频，请检查链接是否正确或视频是否可访问");
            } else if (output.toString().contains("HTTP Error 403") || output.toString().contains("Forbidden")) {
                throw new Exception("访问被拒绝（403），可能需要登录或该视频有地区限制");
            } else if (output.toString().contains("HTTP Error 404") || output.toString().contains("Not Found")) {
                throw new Exception("视频不存在（404），请检查链接是否正确");
            } else if (output.toString().contains("network") || output.toString().contains("timeout")) {
                throw new Exception("网络连接失败，请检查网络或尝试配置代理");
            } else {
                throw new Exception("下载失败: " + errorMsg);
            }
        }

        // 7. 验证文件路径
        if (downloadedFilePath == null) {
            // 如果没有从输出中捕获到路径，尝试查找最新文件（兜底方案）
            downloadedFilePath = findDownloadedFile();
            if (downloadedFilePath == null) {
                throw new Exception("下载完成但找不到文件，请检查 yt-dlp 输出");
            }
            System.out.println("⚠ 警告：通过查找最新文件获取路径: " + downloadedFilePath);
        }
        
        // 标准化路径（处理Windows反斜杠）
        downloadedFilePath = downloadedFilePath.replace("\\\\", "\\").trim();
        
        // 验证文件是否存在
        File file = new File(downloadedFilePath);
        if (!file.exists()) {
            // 再次尝试用兜底方案
            System.out.println("⚠ 警告：路径验证失败，尝试查找最新文件");
            downloadedFilePath = findDownloadedFile();
            if (downloadedFilePath == null) {
                throw new Exception("文件下载完成但找不到: " + downloadedFilePath);
            }
            file = new File(downloadedFilePath);
        }

        enforceFileSizeLimit(file);
        file = resolvePlayableDownloadedFile(file);

        System.out.println("✓ 视频下载成功: " + file.getAbsolutePath());
        System.out.println("========================================");

        return file.getAbsolutePath();
    }

    /**
     * 验证 URL 是否合法
     */
    private void validateUrl(String url) throws Exception {
        if (url == null || url.trim().isEmpty()) {
            throw new Exception("URL 不能为空");
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new Exception("URL 必须以 http:// 或 https:// 开头");
        }

        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new Exception("URL 格式不合法");
        }

        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) {
            throw new Exception("URL 缺少有效域名");
        }
        host = host.toLowerCase();

        // 检查是否在允许的站点列表中，避免 evil-bilibili.com 这类 contains 误判。
        String[] allowedSiteList = allowedSites.split(",");
        boolean isAllowed = false;
        for (String site : allowedSiteList) {
            String normalizedSite = site.trim().toLowerCase();
            if (host.equals(normalizedSite) || host.endsWith("." + normalizedSite)) {
                isAllowed = true;
                break;
            }
        }

        if (!isAllowed) {
            throw new Exception("不支持该视频平台，当前仅支持: " + allowedSites);
        }

        System.out.println("✓ URL 验证通过");
    }

    /**
     * 构建 yt-dlp 命令
     */
    private List<String> buildYtDlpCommand(String url) {
        List<String> command = new ArrayList<>();
        
        // 1. yt-dlp 可执行文件路径
        command.add(ytdlpPath);

        // 2. 格式选择：优先下载带音频的单文件，避免把 YouTube DASH 纯视频交给 WhisperX。
        // 字幕分析不需要 1080p，优先 <=720p 可以明显减少下载和后续处理时间。
        command.add("-f");
        command.add("best[ext=mp4][vcodec!=none][acodec!=none][height<=720]/best[vcodec!=none][acodec!=none][height<=720]/best[ext=mp4][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]/bv*[height<=720][ext=mp4]+ba[ext=m4a]/bv*[height<=720]+ba/b");

        // 3. 输出模板（使用视频 ID 和标题，限制文件名长度）
        command.add("-o");
        command.add(uploadDir + "/%(id)s__%(title).80s.%(ext)s");

        // 4. 禁止下载播放列表（只下载单个视频）
        command.add("--no-playlist");

        // 5. 限制文件名字符（避免特殊字符）
        command.add("--restrict-filenames");

        // 6. 合并输出格式为 mp4
        command.add("--merge-output-format");
        command.add("mp4");

        // 7. 打印最终文件路径（用于获取下载的文件名）
        // 使用 after_move:%(filepath)s 来获取实际的最终文件路径
        command.add("--print");
        command.add("after_move:%(filepath)s");

        // 8. 禁用颜色输出（便于解析）
        command.add("--no-color");

        // 9. ffmpeg 路径设置（如果配置了完整路径或目录）。裸命令 ffmpeg 交给 PATH 解析，
        // 否则 yt-dlp 会误报 "ffmpeg-location ffmpeg does not exist"。
        String ytDlpFfmpegLocation = resolveYtDlpFfmpegLocation();
        if (ytDlpFfmpegLocation != null) {
            command.add("--ffmpeg-location");
            command.add(ytDlpFfmpegLocation);
            System.out.println("使用 ffmpeg-location: " + ytDlpFfmpegLocation);
        }

        // 10. YouTube 风控时可配置浏览器 cookies 或 cookies.txt。
        if (cookiesFromBrowser != null && !cookiesFromBrowser.trim().isEmpty()) {
            command.add("--cookies-from-browser");
            command.add(cookiesFromBrowser.trim());
            System.out.println("使用浏览器 cookies: " + cookiesFromBrowser.trim());
        } else if (cookiesFile != null && !cookiesFile.trim().isEmpty()) {
            command.add("--cookies");
            command.add(cookiesFile.trim());
            System.out.println("使用 cookies 文件: " + cookiesFile.trim());
        }

        // 11. YouTube 新版提取可能需要 JS runtime，例如 node 或 deno。
        if (jsRuntimes != null && !jsRuntimes.trim().isEmpty()) {
            command.add("--js-runtimes");
            command.add(jsRuntimes.trim());
            System.out.println("使用 JS runtime: " + jsRuntimes.trim());
        }

        // 12. 轻微降速可降低部分站点的请求频率，但不能绕过登录/验证码风控。
        if (sleepRequestsSec > 0) {
            command.add("--sleep-requests");
            command.add(String.valueOf(sleepRequestsSec));
        }

        // 13. 时长限制（可选）
        if (maxDurationSec > 0) {
            command.add("--match-filter");
            command.add("duration < " + maxDurationSec);
        }

        // 14. 文件大小限制（可选）。同时在下载完成后做二次校验。
        if (maxSizeMB > 0) {
            command.add("--max-filesize");
            command.add(maxSizeMB + "M");
        }

        // 15. 代理设置（如果配置了）
        if (proxy != null && !proxy.trim().isEmpty()) {
            command.add("--proxy");
            command.add(proxy);
            System.out.println("使用代理: " + proxy);
        }

        // 16. 视频 URL
        command.add(url);

        return command;
    }

    private String safeCommandForLog(List<String> command) {
        List<String> safeParts = new ArrayList<>();
        for (String part : command) {
            if (part != null && (part.startsWith("http://") || part.startsWith("https://"))) {
                safeParts.add(safeUrlForLog(part));
            } else {
                safeParts.add(part);
            }
        }
        return String.join(" ", safeParts);
    }

    private String safeUrlForLog(String url) {
        try {
            return UrlNormalizeUtil.safeForLog(UrlNormalizeUtil.normalize(url));
        } catch (Exception e) {
            return "<url-hidden>";
        }
    }

    private String buildFriendlyDownloadError(String output) {
        if (output == null) {
            return null;
        }
        String lower = output.toLowerCase();
        if (lower.contains("sign in to confirm") || lower.contains("not a bot")
            || lower.contains("too many requests") || lower.contains("http error 429")) {
            return "YouTube 触发了访问风控，当前机器需要登录验证或稍后重试。"
                + "如果要稳定下载 YouTube，请先在本机浏览器登录 YouTube，然后设置 DOWNLOADER_COOKIES_FROM_BROWSER=chrome 或 edge 后重启后端。";
        }
        if (lower.contains("private video")) {
            return "该视频是私有视频，无法下载";
        }
        if (lower.contains("video unavailable")) {
            return "该视频不可用，可能已删除、地区受限或需要登录";
        }
        if (lower.contains("no supported javascript runtime")) {
            return "YouTube 解析需要 JavaScript runtime，请安装 Node.js 或 Deno，并设置 DOWNLOADER_JS_RUNTIMES=node 后重启后端。";
        }
        return null;
    }

    private String resolveYtDlpFfmpegLocation() {
        if (ffmpegPath == null || ffmpegPath.trim().isEmpty()) {
            return null;
        }
        String normalized = ffmpegPath.trim();
        if ("ffmpeg".equalsIgnoreCase(normalized) || "ffmpeg.exe".equalsIgnoreCase(normalized)) {
            return null;
        }
        File configured = new File(normalized);
        if (configured.exists()) {
            return configured.getAbsolutePath();
        }
        System.err.println("配置的 ffmpeg 路径不存在，yt-dlp 将使用 PATH: " + normalized);
        return null;
    }

    private boolean looksLikeDownloadedFilePath(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        String lower = trimmed.toLowerCase();
        if (lower.matches(".*\\.f\\d+\\.(mp4|mkv|webm|m4a)$")) {
            return false;
        }
        return (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm"))
            && !lower.startsWith("[download]")
            && !lower.contains("%");
    }

    private File resolvePlayableDownloadedFile(File file) throws Exception {
        if (hasVideoStream(file) && hasAudioStream(file)) {
            return file;
        }

        File playable = findLatestPlayableFile(file);
        if (playable != null) {
            System.out.println("⚠ 捕获到的路径不是可分析视频，改用可播放文件: " + playable.getAbsolutePath());
            return playable;
        }

        if (!hasAudioStream(file)) {
            throw new Exception("下载的视频没有音轨，无法提取字幕。请重试下载；当前文件: " + file.getAbsolutePath());
        }
        throw new Exception("下载的文件不是有效视频，无法分析: " + file.getAbsolutePath());
    }

    private boolean hasVideoStream(File file) {
        return hasMediaStream(file, "v");
    }

    private boolean hasAudioStream(File file) {
        return hasMediaStream(file, "a");
    }

    private boolean hasMediaStream(File file, String streamType) {
        if (file == null || !file.exists() || !file.isFile()) {
            return false;
        }

        String ffprobe = resolveFfprobePath();
        if (ffprobe != null) {
            try {
                ProcessResult result = runCommand(List.of(
                    ffprobe,
                    "-v", "error",
                    "-select_streams", streamType + ":0",
                    "-show_entries", "stream=codec_type",
                    "-of", "csv=p=0",
                    file.getAbsolutePath()
                ), 30);
                if (result.exitCode == 0 && result.output.toLowerCase().contains("audio") && "a".equals(streamType)) {
                    return true;
                }
                if (result.exitCode == 0 && result.output.toLowerCase().contains("video") && "v".equals(streamType)) {
                    return true;
                }
            } catch (Exception e) {
                System.err.println("ffprobe 检查媒体流失败: " + e.getMessage());
            }
        }

        try {
            ProcessResult result = runCommand(List.of(resolveFfmpegPath(), "-hide_banner", "-i", file.getAbsolutePath()), 30);
            String output = result.output.toLowerCase();
            return "a".equals(streamType) ? output.contains("audio:") : output.contains("video:");
        } catch (Exception e) {
            System.err.println("ffmpeg 检查媒体流失败: " + e.getMessage());
            return false;
        }
    }

    private File findLatestPlayableFile(File originalFile) {
        File dir = new File(uploadDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return null;
        }
        String expectedPrefix = mediaFilePrefix(originalFile);

        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return name.startsWith(expectedPrefix)
                && (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm"));
        });

        if (files == null || files.length == 0) {
            return null;
        }

        File latestPlayable = null;
        for (File candidate : files) {
            if (hasVideoStream(candidate) && hasAudioStream(candidate)
                && (latestPlayable == null || candidate.lastModified() > latestPlayable.lastModified())) {
                latestPlayable = candidate;
            }
        }
        return latestPlayable;
    }

    private String mediaFilePrefix(File file) {
        if (file == null) {
            return "";
        }
        String name = file.getName();
        String withoutExt = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        return withoutExt.replaceFirst("\\.f\\d+$", "");
    }

    private String resolveFfmpegPath() {
        return ffmpegPath != null && !ffmpegPath.trim().isEmpty() ? ffmpegPath.trim() : "ffmpeg";
    }

    private String resolveFfprobePath() {
        String ffmpeg = resolveFfmpegPath();
        if ("ffmpeg".equalsIgnoreCase(ffmpeg)) {
            return "ffprobe";
        }

        File ffmpegFile = new File(ffmpeg);
        File parent = ffmpegFile.getParentFile();
        String ffprobeName = ffmpegFile.getName().toLowerCase().endsWith(".exe") ? "ffprobe.exe" : "ffprobe";
        if (parent != null) {
            File ffprobeFile = new File(parent, ffprobeName);
            if (ffprobeFile.exists()) {
                return ffprobeFile.getAbsolutePath();
            }
        }
        return "ffprobe";
    }

    private ProcessResult runCommand(List<String> command, int timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        Thread reader = new Thread(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    if (output.length() < 4000) {
                        output.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
                // 仅用于媒体探测，读取失败时由退出码和输出兜底判断。
            }
        });
        reader.start();

        boolean finished = process.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("命令超时: " + String.join(" ", command));
        }
        reader.join(1000);

        ProcessResult result = new ProcessResult();
        result.exitCode = process.exitValue();
        result.output = output.toString();
        return result;
    }

    private static class ProcessResult {
        private int exitCode;
        private String output;
    }

    private void enforceFileSizeLimit(File file) throws Exception {
        if (maxSizeMB <= 0 || file == null || !file.exists()) {
            return;
        }
        long maxBytes = maxSizeMB * 1024L * 1024L;
        if (file.length() > maxBytes) {
            throw new Exception("下载的视频超过大小限制: " + formatMB(file.length())
                + "MB > " + maxSizeMB + "MB，请调小视频或修改 downloader.max-size-mb");
        }
    }

    private String formatMB(long bytes) {
        return String.format("%.2f", bytes / 1024.0 / 1024.0);
    }

    /**
     * 查找最新下载的文件
     */
    private String findDownloadedFile() throws IOException {
        File dir = new File(uploadDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return null;
        }

        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase();
            return !lower.matches(".*\\.f\\d+\\.(mp4|mkv|webm|m4a)$")
                && (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm"));
        });

        if (files == null || files.length == 0) {
            return null;
        }

        // 找到最新且同时包含视频流和音频流的文件。
        File latestFile = null;
        for (File file : files) {
            if (hasVideoStream(file) && hasAudioStream(file)
                && (latestFile == null || file.lastModified() > latestFile.lastModified())) {
                latestFile = file;
            }
        }

        return latestFile != null ? latestFile.getAbsolutePath() : null;
    }

    /**
     * 检查 yt-dlp 是否可用
     */
    public boolean isYtDlpAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ytdlpPath, "--version");
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            System.err.println("yt-dlp 不可用: " + e.getMessage());
            return false;
        }
    }

    /**
     * 获取 yt-dlp 版本信息
     */
    public String getYtDlpVersion() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ytdlpPath, "--version");
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String version = reader.readLine();
                process.waitFor();
                return version;
            }
        } catch (Exception e) {
            return "未知版本 (错误: " + e.getMessage() + ")";
        }
    }
}
