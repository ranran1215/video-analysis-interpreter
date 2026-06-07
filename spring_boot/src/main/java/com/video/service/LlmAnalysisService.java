package com.video.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Service
public class LlmAnalysisService {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_ERROR_BODY_LENGTH = 1200;

    @Value("${llm.api-url}")
    private String apiUrl;

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${llm.model}")
    private String model;

    @Value("${llm.timeout-sec:120}")
    private int timeoutSec;

    @Value("${llm.temperature:0.2}")
    private double temperature;

    @Value("${llm.max-input-chars:20000}")
    private int maxInputChars;

    private final Gson gson = new Gson();

    public String analyzeSubtitle(String subtitleText, String language) throws IOException {
        String outputLanguage = resolveOutputLanguage(language);
        String limitedSubtitle = limitSubtitleText(subtitleText);

        String prompt = buildAnalysisPrompt(limitedSubtitle, outputLanguage);
        String content = callChatCompletion(prompt, "java-backend-analysis");
        return normalizeAnalysisJson(content);
    }

    public String translateAnalysis(String summary, String highlightsJson, String targetLanguage) throws IOException {
        String outputLanguage = "en".equalsIgnoreCase(targetLanguage) ? "英文" : "中文";

        StringBuilder prompt = new StringBuilder();
        prompt.append("请把以下视频分析结果翻译为").append(outputLanguage).append("。\n");
        prompt.append("只输出严格 JSON，不要 Markdown，不要代码块，不要解释文字。\n");
        prompt.append("保持 JSON 结构不变，保持所有时间字段数值不变，只翻译 summary、title、description 等文本内容。\n");
        prompt.append("输出格式必须是：{\"summary\":\"...\",\"highlights\":[{\"startTime\":0.0,\"endTime\":0.0,\"title\":\"...\",\"description\":\"...\"}]}\n\n");
        prompt.append("summary:\n").append(summary == null ? "" : summary).append("\n\n");
        prompt.append("highlights:\n").append(highlightsJson == null ? "[]" : highlightsJson).append("\n");

        String content = callChatCompletion(prompt.toString(), "java-backend-translate");
        return normalizeAnalysisJson(content);
    }

    public String callStrictJson(String userPrompt, String userId, String systemPrompt) throws IOException {
        return callChatCompletion(userPrompt, userId, systemPrompt);
    }

    private String callChatCompletion(String userPrompt, String userId) throws IOException {
        return callChatCompletion(userPrompt, userId, "你是一个视频内容分析助手，只输出严格 JSON。");
    }

    private String callChatCompletion(String userPrompt, String userId, String systemPrompt) throws IOException {
        String endpoint = requireConfigured(apiUrl, "LLM_API_URL 未配置。");
        String key = requireConfigured(apiKey, "LLM_API_KEY 未配置。");
        String modelName = requireConfigured(model, "LLM_MODEL 未配置。");

        JsonObject payload = new JsonObject();
        payload.addProperty("model", modelName);
        payload.addProperty("temperature", temperature);

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt == null || systemPrompt.trim().isEmpty()
            ? "你是一个视频内容分析助手，只输出严格 JSON。"
            : systemPrompt);
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userPrompt);
        messages.add(user);

        payload.add("messages", messages);

        RequestBody body = RequestBody.create(gson.toJson(payload), JSON_MEDIA_TYPE);
        Request request = new Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer " + key)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build();

        System.out.println("========================================");
        System.out.println("【LLM API 调用】准备发送请求");
        System.out.println("模型: " + modelName);
        System.out.println("请求角色: " + userId);
        System.out.println("Prompt 长度: " + (userPrompt != null ? userPrompt.length() : 0));
        System.out.println("========================================");

        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .callTimeout(timeoutSec, TimeUnit.SECONDS)
            .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("LLM API 调用失败: " + response.code() + " - " + sanitizeForLog(responseBody));
            }

            JsonObject jsonResponse = parseJsonObject(responseBody, "LLM API 响应不是有效 JSON");
            JsonArray choices = jsonResponse.has("choices") && jsonResponse.get("choices").isJsonArray()
                ? jsonResponse.getAsJsonArray("choices")
                : null;
            if (choices == null || choices.size() == 0) {
                throw new IOException("LLM API 响应缺少 choices[0].message.content");
            }

            JsonObject firstChoice = choices.get(0).isJsonObject() ? choices.get(0).getAsJsonObject() : null;
            JsonObject message = firstChoice != null && firstChoice.has("message") && firstChoice.get("message").isJsonObject()
                ? firstChoice.getAsJsonObject("message")
                : null;
            if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
                throw new IOException("LLM API 响应缺少 choices[0].message.content");
            }

            String content = message.get("content").getAsString();
            System.out.println("【LLM API 响应】收到响应，content 长度: " + content.length());
            return content;
        }
    }

    private String buildAnalysisPrompt(String subtitleText, String outputLanguage) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请根据以下带时间戳的视频字幕生成视频总结和高光片段。\n\n");
        prompt.append("输出要求：\n");
        prompt.append("1. 只输出 JSON，不要 Markdown，不要代码块，不要任何解释文字。\n");
        prompt.append("2. summary 必须是字符串。\n");
        prompt.append("3. highlights 必须是数组，数量 3 到 8 个；如果没有明显高光，返回空数组 []。\n");
        prompt.append("4. 每个高光对象包含 startTime、endTime、title、description。\n");
        prompt.append("5. startTime/endTime 必须尽量来自字幕中的时间戳，单位为秒，使用数字。\n");
        prompt.append("6. 输出语言使用").append(outputLanguage).append("。\n");
        prompt.append("7. JSON 示例：{\"summary\":\"视频总结文本\",\"highlights\":[{\"startTime\":12.3,\"endTime\":25.6,\"title\":\"高光标题\",\"description\":\"高光描述\"}]}\n\n");
        prompt.append("字幕内容：\n");
        prompt.append(subtitleText == null ? "" : subtitleText);
        return prompt.toString();
    }

    private String normalizeAnalysisJson(String modelContent) throws IOException {
        String jsonText = extractFirstJsonObject(modelContent);
        JsonObject object = parseJsonObject(jsonText, "LLM 返回内容中未找到可解析的 JSON 对象");

        JsonObject normalized = new JsonObject();
        String summary = readString(object, "summary");
        if (summary == null) {
            throw new IOException("LLM 返回 JSON 缺少 summary 字符串");
        }
        normalized.addProperty("summary", summary);
        normalized.add("highlights", normalizeHighlights(object));
        return gson.toJson(normalized);
    }

    private JsonArray normalizeHighlights(JsonObject object) throws IOException {
        JsonArray normalized = new JsonArray();
        if (!object.has("highlights") || object.get("highlights").isJsonNull()) {
            return normalized;
        }
        if (!object.get("highlights").isJsonArray()) {
            throw new IOException("LLM 返回 JSON 中 highlights 不是数组");
        }

        JsonArray highlights = object.getAsJsonArray("highlights");
        for (JsonElement element : highlights) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject source = element.getAsJsonObject();
            double start = readDouble(source, "startTime", readDouble(source, "start", 0.0));
            double end = readDouble(source, "endTime", readDouble(source, "end", 0.0));

            JsonObject highlight = new JsonObject();
            highlight.addProperty("start", start);
            highlight.addProperty("end", end);
            highlight.addProperty("startTime", start);
            highlight.addProperty("endTime", end);
            highlight.addProperty("title", defaultString(readString(source, "title")));
            highlight.addProperty("description", defaultString(readString(source, "description")));
            normalized.add(highlight);
        }
        return normalized;
    }

    private String limitSubtitleText(String subtitleText) {
        if (subtitleText == null) {
            return "";
        }
        int limit = maxInputChars > 0 ? maxInputChars : 20000;
        if (subtitleText.length() <= limit) {
            return subtitleText;
        }
        System.err.println("[WARN] 字幕过长，已截断: " + subtitleText.length() + " -> " + limit);
        return subtitleText.substring(0, limit) + "\n\n[字幕过长，后续内容已截断]";
    }

    private String resolveOutputLanguage(String language) {
        if ("en".equalsIgnoreCase(language)) {
            return "英文";
        }
        return "中文";
    }

    private String extractFirstJsonObject(String content) throws IOException {
        if (content == null || content.trim().isEmpty()) {
            throw new IOException("LLM 返回内容为空");
        }

        int start = content.indexOf('{');
        if (start < 0) {
            throw new IOException("LLM 返回内容中未找到 JSON 对象");
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(start, i + 1);
                }
            }
        }

        throw new IOException("LLM 返回内容中的 JSON 对象不完整");
    }

    private JsonObject parseJsonObject(String value, String errorMessage) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(value);
            if (parsed != null && parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (Exception e) {
            throw new IOException(errorMessage + ": " + e.getMessage(), e);
        }
        throw new IOException(errorMessage);
    }

    private String requireConfigured(String value, String message) throws IOException {
        if (value == null || value.trim().isEmpty()) {
            throw new IOException(message);
        }
        return value.trim();
    }

    private String readString(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        try {
            return object.get(name).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private double readDouble(JsonObject object, String name, double defaultValue) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return defaultValue;
        }
        try {
            JsonElement value = object.get(name);
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                return value.getAsDouble();
            }
            String text = value.getAsString().replaceAll("[^0-9.\\-]", "");
            if (text.isEmpty()) {
                return defaultValue;
            }
            return Double.parseDouble(text);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String sanitizeForLog(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value;
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            sanitized = sanitized.replace(apiKey.trim(), "***");
        }
        if (sanitized.length() <= MAX_ERROR_BODY_LENGTH) {
            return sanitized;
        }
        return sanitized.substring(0, MAX_ERROR_BODY_LENGTH) + "...";
    }
}
