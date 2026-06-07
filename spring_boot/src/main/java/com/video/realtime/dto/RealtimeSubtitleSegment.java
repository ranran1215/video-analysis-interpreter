package com.video.realtime.dto;

public class RealtimeSubtitleSegment {

    private String sessionId;
    private long startMs;
    private long endMs;
    private String originalText;
    private String translatedText;
    private String status;
    private String updatedAt;

    public RealtimeSubtitleSegment() {
    }

    public RealtimeSubtitleSegment(String sessionId, long startMs, long endMs, String originalText,
                                   String translatedText, String status, String updatedAt) {
        this.sessionId = sessionId;
        this.startMs = startMs;
        this.endMs = endMs;
        this.originalText = originalText;
        this.translatedText = translatedText;
        this.status = status;
        this.updatedAt = updatedAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getStartMs() {
        return startMs;
    }

    public void setStartMs(long startMs) {
        this.startMs = startMs;
    }

    public long getEndMs() {
        return endMs;
    }

    public void setEndMs(long endMs) {
        this.endMs = endMs;
    }

    public String getOriginalText() {
        return originalText;
    }

    public void setOriginalText(String originalText) {
        this.originalText = originalText;
    }

    public String getTranslatedText() {
        return translatedText;
    }

    public void setTranslatedText(String translatedText) {
        this.translatedText = translatedText;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
