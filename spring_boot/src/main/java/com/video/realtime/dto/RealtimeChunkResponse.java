package com.video.realtime.dto;

import java.util.ArrayList;
import java.util.List;

public class RealtimeChunkResponse {

    private String sessionId;
    private List<RealtimeSubtitleSegment> segments = new ArrayList<>();
    private String warning;

    public RealtimeChunkResponse() {
    }

    public RealtimeChunkResponse(String sessionId, List<RealtimeSubtitleSegment> segments, String warning) {
        this.sessionId = sessionId;
        this.segments = segments;
        this.warning = warning;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public List<RealtimeSubtitleSegment> getSegments() {
        return segments;
    }

    public void setSegments(List<RealtimeSubtitleSegment> segments) {
        this.segments = segments;
    }

    public String getWarning() {
        return warning;
    }

    public void setWarning(String warning) {
        this.warning = warning;
    }
}
