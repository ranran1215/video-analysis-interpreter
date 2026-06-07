package com.video.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SubtitleTranslationService {

    private static final int MAX_SEGMENTS_PER_BATCH = 30;
    private static final int MAX_CHARS_PER_BATCH = 4000;
    private static final String SYSTEM_PROMPT = "你是字幕翻译助手，只输出严格 JSON。";

    @Autowired
    private LlmAnalysisService llmAnalysisService;

    private final Gson gson = new Gson();

    public String translateSegments(String subtitleJson, String targetLanguage) {
        return translateSegmentsInternal(subtitleJson, targetLanguage, false);
    }

    public String translateSegmentsStrict(String subtitleJson, String targetLanguage) throws IOException {
        try {
            return translateSegmentsInternal(subtitleJson, targetLanguage, true);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            throw e;
        }
    }

    public String buildFallbackTranslatedSubtitleJson(String subtitleJson, String targetLanguage) {
        List<SubtitleSegment> segments = readSourceSegments(parseSubtitleObject(subtitleJson));
        return buildTranslatedSubtitleJson(resolveTargetLanguageCode(targetLanguage), segments, null);
    }

    private String translateSegmentsInternal(String subtitleJson, String targetLanguage, boolean failOnBatchError) {
        JsonObject subtitleObject = parseSubtitleObject(subtitleJson);
        List<SubtitleSegment> segments = readSourceSegments(subtitleObject);
        String targetLanguageCode = resolveTargetLanguageCode(targetLanguage);

        if (segments.isEmpty()) {
            return buildTranslatedSubtitleJson(targetLanguageCode, segments, null);
        }

        if (isChineseTarget(targetLanguageCode) && isChineseSource(readString(subtitleObject, "language"))) {
            return buildTranslatedSubtitleJson(targetLanguageCode, segments, null);
        }

        Map<Integer, String> translations = new HashMap<>();
        for (List<SubtitleSegment> batch : buildBatches(segments)) {
            try {
                translations.putAll(translateBatch(batch, targetLanguageCode));
            } catch (Exception e) {
                System.err.println("[WARN] 字幕翻译批次失败，已回退原文: " + e.getMessage());
                if (failOnBatchError) {
                    if (e instanceof RuntimeException) {
                        throw (RuntimeException) e;
                    }
                    throw new RuntimeException(e);
                }
                for (SubtitleSegment segment : batch) {
                    translations.put(segment.index, segment.text);
                }
            }
        }

        return buildTranslatedSubtitleJson(targetLanguageCode, segments, translations);
    }

    private Map<Integer, String> translateBatch(List<SubtitleSegment> batch, String targetLanguageCode) throws IOException {
        JsonArray input = new JsonArray();
        Set<Integer> expectedIndexes = new HashSet<>();
        for (SubtitleSegment segment : batch) {
            expectedIndexes.add(segment.index);
            JsonObject item = new JsonObject();
            item.addProperty("index", segment.index);
            item.addProperty("start", segment.start);
            item.addProperty("end", segment.end);
            item.addProperty("text", segment.text);
            input.add(item);
        }

        String prompt = buildTranslationPrompt(input, batch.size(), targetLanguageCode);
        String content = llmAnalysisService.callStrictJson(prompt, "java-backend-subtitle-translate", SYSTEM_PROMPT);
        JsonArray translatedArray = parseFirstJsonArray(content);
        if (translatedArray.size() != batch.size()) {
            throw new IOException("字幕翻译返回数组长度不匹配: expected=" + batch.size() + ", actual=" + translatedArray.size());
        }

        Map<Integer, String> result = new HashMap<>();
        for (JsonElement element : translatedArray) {
            if (!element.isJsonObject()) {
                throw new IOException("字幕翻译数组中存在非对象元素");
            }
            JsonObject object = element.getAsJsonObject();
            Integer index = readInteger(object, "index");
            String translatedText = readString(object, "translatedText");
            if (index == null || !expectedIndexes.contains(index)) {
                throw new IOException("字幕翻译返回了未知 index: " + index);
            }
            if (translatedText == null) {
                throw new IOException("字幕翻译结果缺少 translatedText: index=" + index);
            }
            result.put(index, translatedText);
        }

        if (result.size() != batch.size()) {
            throw new IOException("字幕翻译结果 index 不完整");
        }
        return result;
    }

    private String buildTranslationPrompt(JsonArray input, int expectedCount, String targetLanguageCode) {
        String targetLanguageName = isChineseTarget(targetLanguageCode) ? "中文" : targetLanguageCode;
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是字幕翻译助手。\n");
        prompt.append("请把输入字幕翻译为自然、简洁、适合字幕显示的").append(targetLanguageName).append("。\n");
        prompt.append("技术词汇尽量保持准确；对未完成句子可以自然翻译，但不要扩写。\n\n");
        prompt.append("严格要求：\n");
        prompt.append("1. 只翻译 text 内容。\n");
        prompt.append("2. 不要改 start/end，不要重新生成时间。\n");
        prompt.append("3. 只输出 JSON 数组，不要 Markdown，不要代码块，不要解释。\n");
        prompt.append("4. 输出数组长度必须与输入一致，必须是 ").append(expectedCount).append(" 条。\n");
        prompt.append("5. 每个输出对象只需要包含 index 和 translatedText。\n");
        prompt.append("6. 返回格式示例：[{\"index\":0,\"translatedText\":\"大家好\"}]\n\n");
        prompt.append("输入字幕：\n");
        prompt.append(gson.toJson(input));
        return prompt.toString();
    }

    private List<List<SubtitleSegment>> buildBatches(List<SubtitleSegment> segments) {
        List<List<SubtitleSegment>> batches = new ArrayList<>();
        List<SubtitleSegment> current = new ArrayList<>();
        int currentChars = 0;

        for (SubtitleSegment segment : segments) {
            int segmentChars = segment.text != null ? segment.text.length() : 0;
            boolean shouldStartNewBatch = !current.isEmpty()
                && (current.size() >= MAX_SEGMENTS_PER_BATCH || currentChars + segmentChars > MAX_CHARS_PER_BATCH);
            if (shouldStartNewBatch) {
                batches.add(current);
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(segment);
            currentChars += segmentChars;
        }

        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private String buildTranslatedSubtitleJson(String language, List<SubtitleSegment> segments, Map<Integer, String> translations) {
        JsonObject result = new JsonObject();
        result.addProperty("language", language);
        JsonArray outputSegments = new JsonArray();
        for (SubtitleSegment segment : segments) {
            String sourceText = segment.text != null ? segment.text : "";
            String translatedText = translations != null && translations.containsKey(segment.index)
                ? translations.get(segment.index)
                : sourceText;

            JsonObject output = new JsonObject();
            output.addProperty("start", segment.start);
            output.addProperty("end", segment.end);
            output.addProperty("sourceText", sourceText);
            output.addProperty("translatedText", translatedText != null ? translatedText : sourceText);
            outputSegments.add(output);
        }
        result.add("segments", outputSegments);
        return gson.toJson(result);
    }

    private JsonObject parseSubtitleObject(String subtitleJson) {
        if (subtitleJson == null || subtitleJson.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(subtitleJson);
            if (parsed != null && parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (Exception e) {
            System.err.println("[WARN] 字幕 JSON 解析失败，翻译轨道将回退为空: " + e.getMessage());
        }
        return new JsonObject();
    }

    private List<SubtitleSegment> readSourceSegments(JsonObject subtitleObject) {
        List<SubtitleSegment> result = new ArrayList<>();
        if (subtitleObject == null || !subtitleObject.has("segments") || !subtitleObject.get("segments").isJsonArray()) {
            return result;
        }

        JsonArray segments = subtitleObject.getAsJsonArray("segments");
        for (int i = 0; i < segments.size(); i++) {
            JsonElement element = segments.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            SubtitleSegment segment = new SubtitleSegment();
            segment.index = i;
            segment.start = readDouble(object, "start");
            segment.end = readDouble(object, "end");
            segment.text = defaultString(readString(object, "text"));
            result.add(segment);
        }
        return result;
    }

    private JsonArray parseFirstJsonArray(String content) throws IOException {
        String jsonText = extractFirstJsonArray(content);
        try {
            JsonElement parsed = JsonParser.parseString(jsonText);
            if (parsed != null && parsed.isJsonArray()) {
                return parsed.getAsJsonArray();
            }
        } catch (Exception e) {
            throw new IOException("字幕翻译返回内容不是有效 JSON 数组: " + e.getMessage(), e);
        }
        throw new IOException("字幕翻译返回内容中未找到 JSON 数组");
    }

    private String extractFirstJsonArray(String content) throws IOException {
        if (content == null || content.trim().isEmpty()) {
            throw new IOException("字幕翻译返回内容为空");
        }

        int start = content.indexOf('[');
        if (start < 0) {
            throw new IOException("字幕翻译返回内容中未找到 JSON 数组");
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
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return content.substring(start, i + 1);
                }
            }
        }
        throw new IOException("字幕翻译返回内容中的 JSON 数组不完整");
    }

    private String resolveTargetLanguageCode(String targetLanguage) {
        if (targetLanguage == null || targetLanguage.trim().isEmpty()) {
            return "zh";
        }
        return targetLanguage.trim().toLowerCase();
    }

    private boolean isChineseTarget(String targetLanguage) {
        return targetLanguage != null && targetLanguage.toLowerCase().startsWith("zh");
    }

    private boolean isChineseSource(String sourceLanguage) {
        return sourceLanguage != null && sourceLanguage.toLowerCase().startsWith("zh");
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

    private Integer readInteger(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        try {
            return object.get(name).getAsInt();
        } catch (Exception e) {
            return null;
        }
    }

    private double readDouble(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return 0.0;
        }
        try {
            return object.get(name).getAsDouble();
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private static class SubtitleSegment {
        private int index;
        private double start;
        private double end;
        private String text;
    }
}
