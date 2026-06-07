package com.video.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;

@Service
public class DifyService {

    @Value("${dify.api-url}")
    private String difyApiUrl;

    @Value("${dify.api-key}")
    private String difyApiKey;

    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new Gson();

    public String analyzeSubtitle(String subtitleText, String language) throws IOException {
        System.out.println("========================================");
        System.out.println("【Dify API 调用】准备发送字幕文本");
        System.out.println("字幕文本长度: " + (subtitleText != null ? subtitleText.length() : "null"));
        System.out.println("分析语言: " + language);
        System.out.println("========================================");
        
        JsonObject payload = new JsonObject();
        
        JsonObject inputs = new JsonObject();
        inputs.addProperty("subtitle_data", subtitleText);
        inputs.addProperty("language", language != null ? language : "zh");
        payload.add("inputs", inputs);
        
        payload.addProperty("response_mode", "blocking");
        payload.addProperty("user", "java-backend");

        RequestBody body = RequestBody.create(
            payload.toString(),
            MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
            .url(getDifyWorkflowUrl())
            .addHeader("Authorization", "Bearer " + difyApiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No response body";
                throw new IOException("Dify API 调用失败: " + response.code() + " - " + errorBody);
            }
            
            String responseBody = response.body().string();
            System.out.println("========================================");
            System.out.println("【Dify API 响应】收到响应");
            System.out.println("响应长度: " + responseBody.length());
            System.out.println("完整响应内容:");
            System.out.println(responseBody);
            System.out.println("========================================");
            
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
            
            if (jsonResponse.has("data") && 
                jsonResponse.getAsJsonObject("data").has("outputs") &&
                jsonResponse.getAsJsonObject("data").getAsJsonObject("outputs").has("text")) {
                String aiResult = jsonResponse.getAsJsonObject("data")
                                  .getAsJsonObject("outputs")
                                  .get("text").getAsString();
                
                // 智能提取 JSON，忽略模型返回的多余文本
                int jsonStart = aiResult.indexOf('{');
                int jsonEnd = aiResult.lastIndexOf('}');
                
                if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                    aiResult = aiResult.substring(jsonStart, jsonEnd + 1);
                } else {
                    // 如果找不到有效的 JSON，就抛出异常
                    throw new IOException("在 Dify 响应中未找到有效的 JSON 对象: " + responseBody);
                }
                
                return aiResult;
            }
            
            throw new IOException("Dify 返回数据格式异常: " + responseBody);
        }
    }

    public String translateAnalysis(String summary, String highlightsJson, String targetLanguage) throws IOException {
        System.out.println("========================================");
        System.out.println("【Dify 翻译】准备翻译内容");
        System.out.println("目标语言: " + targetLanguage);
        System.out.println("========================================");
        
        // 构建翻译提示词
        String translationPrompt = targetLanguage.equals("en") 
            ? "Please translate the following video analysis summary and highlights to English. Keep the JSON structure and time values unchanged. Only translate the text content.\n\n"
            : "请将以下视频分析总结和高光片段翻译成中文。保持 JSON 结构和时间值不变，只翻译文本内容。\n\n";
        
        translationPrompt += "Summary: " + summary + "\n\n";
        translationPrompt += "Highlights: " + highlightsJson + "\n\n";
        translationPrompt += targetLanguage.equals("en")
            ? "Output format: Pure JSON with 'summary' (string) and 'highlights' (array). No extra text."
            : "输出格式：纯 JSON，包含 'summary'（字符串）和 'highlights'（数组）。不要任何额外文字。";
        
        JsonObject payload = new JsonObject();
        JsonObject inputs = new JsonObject();
        inputs.addProperty("subtitle_data", translationPrompt);
        inputs.addProperty("language", targetLanguage);
        payload.add("inputs", inputs);
        payload.addProperty("response_mode", "blocking");
        payload.addProperty("user", "java-backend-translate");

        RequestBody body = RequestBody.create(
            payload.toString(),
            MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
            .url(getDifyWorkflowUrl())
            .addHeader("Authorization", "Bearer " + difyApiKey)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No response body";
                throw new IOException("Dify 翻译调用失败: " + response.code() + " - " + errorBody);
            }
            
            String responseBody = response.body().string();
            System.out.println("【Dify 翻译响应】收到响应，长度: " + responseBody.length());
            
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
            
            if (jsonResponse.has("data") && 
                jsonResponse.getAsJsonObject("data").has("outputs") &&
                jsonResponse.getAsJsonObject("data").getAsJsonObject("outputs").has("text")) {
                String aiResult = jsonResponse.getAsJsonObject("data")
                                  .getAsJsonObject("outputs")
                                  .get("text").getAsString();
                
                int jsonStart = aiResult.indexOf('{');
                int jsonEnd = aiResult.lastIndexOf('}');
                
                if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                    aiResult = aiResult.substring(jsonStart, jsonEnd + 1);
                } else {
                    throw new IOException("翻译响应中未找到有效的 JSON 对象");
                }
                
                return aiResult;
            }
            
            throw new IOException("Dify 翻译返回数据格式异常: " + responseBody);
        }
    }

    private String getDifyWorkflowUrl() throws IOException {
        if (difyApiKey == null || difyApiKey.trim().isEmpty()) {
            throw new IOException("未配置 Dify API Key，请设置环境变量 DIFY_API_KEY");
        }
        if (difyApiUrl == null || difyApiUrl.trim().isEmpty()) {
            throw new IOException("未配置 Dify API URL，请设置 dify.api-url 或环境变量 DIFY_API_URL");
        }

        String url = difyApiUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        // 兼容旧配置：如果只配置到 /v1，自动补成工作流运行接口。
        if (url.endsWith("/v1")) {
            return url + "/workflows/run";
        }
        return url;
    }
}
