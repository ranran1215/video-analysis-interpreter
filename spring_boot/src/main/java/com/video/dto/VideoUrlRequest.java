package com.video.dto;

/**
 * 视频URL上传请求
 */
public class VideoUrlRequest {
    private String url;
    private String language;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }
}
