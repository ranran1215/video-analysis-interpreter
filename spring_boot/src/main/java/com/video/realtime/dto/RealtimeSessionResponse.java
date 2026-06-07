package com.video.realtime.dto;

public class RealtimeSessionResponse {

    private String sessionId;
    private String status;
    private String startedAt;
    private String message;

    public RealtimeSessionResponse() {
    }

    public RealtimeSessionResponse(String sessionId, String status, String startedAt, String message) {
        this.sessionId = sessionId;
        this.status = status;
        this.startedAt = startedAt;
        this.message = message;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
