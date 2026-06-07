package com.video.entity;

import javax.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "videos", indexes = {
    @Index(name = "idx_video_file_hash", columnList = "fileHash"),
    @Index(name = "idx_video_url_hash", columnList = "urlHash"),
    @Index(name = "idx_video_status", columnList = "status"),
    @Index(name = "idx_video_upload_time", columnList = "uploadTime")
})
public class Video {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fileName;
    
    private String filePath;
    
    private String videoUrl;

    @Column(length = 2048)
    private String sourceUrl;

    @Column(length = 2048)
    private String normalizedUrl;

    @Column(length = 64)
    private String urlHash;
    
    @Lob
    private String subtitleData;

    @Lob
    private String translatedSubtitleData;
    
    @Lob
    private String aiSummary;
    
    @Lob
    private String highlights;
    
    private LocalDateTime uploadTime;
    
    private Long fileSize;
    
    @Column(length = 64, unique = true)
    private String fileHash;
    
    private String status;

    private String stage;

    private Integer progress;

    @Lob
    private String errorMessage;

    private Long downloadDurationMs;

    private Long subtitleDurationMs;

    private Long alignDurationMs;

    private Long analysisDurationMs;

    private Long totalDurationMs;
}
