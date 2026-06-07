package com.video.controller;

import com.video.dto.VideoAnalysisResponse;
import com.video.dto.VideoUrlRequest;
import com.video.service.VideoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/video")
@CrossOrigin(origins = "*")
public class VideoController {

    @Autowired
    private VideoService videoService;

    @Value("${video.upload-dir}")
    private String uploadDir;

    @PostMapping("/upload")
    public ResponseEntity<VideoAnalysisResponse> uploadAndAnalyze(@RequestParam("file") MultipartFile file, 
                                                              @RequestParam("language") String language) {
        try {
            VideoAnalysisResponse response = videoService.uploadAndAnalyze(file, language);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(buildFailedResponse("处理失败", e.getMessage()));
        }
    }

    @GetMapping("/analysis/{videoId}")
    public ResponseEntity<?> getVideoAnalysis(@PathVariable Long videoId) {
        try {
            VideoAnalysisResponse response = videoService.getVideoAnalysis(videoId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(404).body("视频不存在: " + e.getMessage());
        }
    }

    @GetMapping("/play/{fileName}")
    public ResponseEntity<Resource> playVideo(@PathVariable String fileName) {
        try {
            File file = resolveUploadFile(fileName);
            if (!file.exists()) {
                return ResponseEntity.notFound().build();
            }

            Resource resource = new FileSystemResource(file);
            
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + sanitizeFileName(fileName) + "\"")
                .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/subtitle/{fileName}")
    public ResponseEntity<String> getSubtitle(@PathVariable String fileName) {
        try {
            // 构建字幕文件路径：去掉扩展名，加上 _subtitle.json
            String baseFileName = fileName.contains(".") 
                ? fileName.substring(0, fileName.lastIndexOf('.')) 
                : fileName;
            String subtitleFileName = baseFileName + "_subtitle.json";
            
            File subtitleFile = resolveUploadFile(subtitleFileName);
            
            System.out.println("查找字幕文件: " + subtitleFile.getAbsolutePath());
            
            if (!subtitleFile.exists()) {
                return ResponseEntity.status(404).body("{\"error\": \"字幕文件不存在\"}");
            }

            // 读取文件内容
            String content = new String(java.nio.file.Files.readAllBytes(subtitleFile.toPath()), 
                                       java.nio.charset.StandardCharsets.UTF_8);
            
            System.out.println("字幕文件大小: " + content.length() + " 字符");
            
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(content);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("{\"error\": \"读取字幕失败: " + e.getMessage() + "\"}");
        }
    }

    private File resolveUploadFile(String fileName) {
        Path uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path requestedFile = uploadRoot.resolve(fileName).normalize();
        if (!requestedFile.startsWith(uploadRoot)) {
            throw new SecurityException("非法文件路径");
        }
        return requestedFile.toFile();
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replace("\"", "").replace("\r", "").replace("\n", "");
    }

    @GetMapping("/reanalyze/{videoId}")
    public ResponseEntity<?> reanalyzeVideo(@PathVariable Long videoId, 
                                           @RequestParam("language") String language) {
        try {
            VideoAnalysisResponse response = videoService.reanalyzeWithLanguage(videoId, language);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("重新分析失败: " + e.getMessage());
        }
    }

    @PostMapping("/upload-url")
    public ResponseEntity<VideoAnalysisResponse> uploadByUrl(@RequestBody VideoUrlRequest request) {
        try {
            System.out.println("收到 URL 上传请求");
            VideoAnalysisResponse response = videoService.downloadAndAnalyze(request.getUrl(), request.getLanguage());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(buildFailedResponse("下载或处理失败", e.getMessage()));
        }
    }

    private VideoAnalysisResponse buildFailedResponse(String summaryPrefix, String message) {
        VideoAnalysisResponse errorResponse = new VideoAnalysisResponse();
        errorResponse.setSummary(summaryPrefix + ": " + message);
        errorResponse.setStatus("failed");
        errorResponse.setStage("处理失败");
        errorResponse.setProgress(-1);
        errorResponse.setErrorMessage(message);
        errorResponse.setDownloadDurationMs(0L);
        errorResponse.setSubtitleDurationMs(0L);
        errorResponse.setAlignDurationMs(0L);
        errorResponse.setAnalysisDurationMs(0L);
        errorResponse.setTotalDurationMs(0L);
        return errorResponse;
    }
}
