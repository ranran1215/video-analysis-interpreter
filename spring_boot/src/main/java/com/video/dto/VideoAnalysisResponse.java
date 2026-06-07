package com.video.dto;

import lombok.Data;
import java.util.List;

@Data
public class VideoAnalysisResponse {
    private Long videoId;
    private String videoUrl;
    private String summary;
    private String status;
    private String stage;
    private Integer progress;
    private String errorMessage;
    private Long downloadDurationMs;
    private Long subtitleDurationMs;
    private Long alignDurationMs;
    private Long analysisDurationMs;
    private Long totalDurationMs;
    private List<SubtitleSegment> subtitles;
    private List<Highlight> highlights;

    @Data
    public static class SubtitleSegment {
        private Double start;
        private Double end;
        private String text;
        private String sourceText;
        private String translatedText;
    }

    @Data
    public static class Highlight {
        private String title;
        private Double start;
        private Double end;
        private String description;
    }
}
