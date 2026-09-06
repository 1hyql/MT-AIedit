package com.mtplugin.aieditor.api;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mtplugin.aieditor.ChatMessage;

import bin.mt.json.JSONArray;
import bin.mt.json.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Google Gemini 客户端（streamGenerateContent）。
 * API Key 通过 x-goog-api-key 请求头（同时附带 query 参数双保险）传递。
 * 支持 alt=sse 流式响应；对非流式 JSON（对象或数组）也能解析。
 * 通用工具方法统一走 {@link ApiUtils}。
 */
public final class GeminiClient implements AiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String url;
    private final String apiKey;
    private final String model;
    private final float temperature;
    private final int maxTokens;
    private final int timeoutSec;

    public GeminiClient(String baseUrl, String apiKey, String model,
                        float temperature, int maxTokens, int timeoutSec) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutSec = timeoutSec;
        // URLEncoder.encode(String, Charset) 需要 API 33+，这里用兼容所有版本的 String 重载
        this.url = ApiUtils.trimSlash(baseUrl) + "/models/" + ApiUtils.urlEncode(model)
                + ":streamGenerateContent?alt=sse&key=" + ApiUtils.urlEncode(this.apiKey);
    }

    @Override
    public void chat(@NonNull List<ChatMessage> messages,
                     @NonNull Callback callback,
                     @NonNull AtomicBoolean cancelled) {
        try {
            String system = null;
            StringBuilder userText = new StringBuilder();
            for (ChatMessage m : messages) {
                if (ChatMessage.ROLE_SYSTEM.equals(m.role)) {
                    system = m.content;
                } else if (ChatMessage.ROLE_USER.equals(m.role)) {
                    if (userText.length() > 0) {
                        userText.append('\n');
                    }
                    userText.append(m.content);
                }
            }

            JSONObject generationConfig = new JSONObject()
                    .put("temperature", temperature)
                    .put("maxOutputTokens", maxTokens);

            JSONObject body = new JSONObject();
            JSONArray parts = new JSONArray();
            parts.add(new JSONObject().put("text", userText.toString()));
            JSONObject content = new JSONObject();
            content.put("role", "user");
            content.put("parts", parts);
            JSONArray contents = new JSONArray();
            contents.add(content);
            body.put("contents", contents);
            if (system != null && !system.isEmpty()) {
                JSONArray sysParts = new JSONArray();
                sysParts.add(new JSONObject().put("text", system));
                body.put("systemInstruction", new JSONObject().put("parts", sysParts));
            }
            body.put("generationConfig", generationConfig);

            Request.Builder rb = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(JSON, body.toString()));
            if (!apiKey.isEmpty()) {
                rb.header("x-goog-api-key", apiKey);
            }

            OkHttpClient client = ApiUtils.buildClient(timeoutSec);
            try (Response response = client.newCall(rb.build()).execute()) {
                if (!response.isSuccessful()) {
                    String err = ApiUtils.extractError(ApiUtils.readErrorBody(response));
                    callback.onError("HTTP " + response.code() + (err.isEmpty() ? "" : "：" + err));
                    return;
                }
                if (response.body() == null) {
                    callback.onError("响应为空");
                    return;
                }
                parseBody(response, callback, cancelled);
            }
        } catch (Throwable t) {
            if (cancelled.get()) {
                return;
            }
            callback.onError(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void parseBody(Response response, Callback callback, AtomicBoolean cancelled) throws IOException {
        StringBuilder accumulated = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        okio.BufferedSource source = response.body().source();
        String line;
        while ((line = source.readUtf8Line()) != null) {
            if (cancelled.get()) {
                return;
            }
            if (line.startsWith("data:")) {
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                try {
                    // Gemini 的 usageMetadata 位于每块顶层（流式一般在最后一块返回）
                    JSONObject gemObj = new JSONObject(data);
                    JSONObject um = ApiUtils.optObject(gemObj, "usageMetadata");
                    if (um != null) {
                        long pt = ApiUtils.optLong(um, "promptTokenCount", -1);
                        long ct = ApiUtils.optLong(um, "candidatesTokenCount", -1);
                        if (pt >= 0 && ct >= 0) {
                            callback.onUsage(pt, ct);
                        }
                    }
                    // 输出被 max_tokens 截断（finishReason=MAX_TOKENS）
                    JSONObject cand = ApiUtils.optObjectAt(ApiUtils.optArray(gemObj, "candidates"), 0);
                    if (cand != null && "MAX_TOKENS".equals(ApiUtils.optString(cand, "finishReason"))) {
                        callback.onTruncated();
                    }
                    String text = extractTextFromCandidate(data);
                    if (text != null && !text.isEmpty()) {
                        accumulated.append(text);
                        callback.onDelta(text);
                        if (cancelled.get()) {
                            return;
                        }
                    }
                } catch (Throwable ignored) {
                }
            } else if (line.length() > 0) {
                raw.append(line);
            }
        }

        if (accumulated.length() > 0) {
            callback.onFinished(accumulated.toString());
            return;
        }

        String whole = raw.toString();
        if (whole.isEmpty()) {
            whole = ApiUtils.readErrorBody(response);
        }
        if (whole.trim().isEmpty()) {
            callback.onFinished("");
            return;
        }
        try {
            String text = extractTextFromCandidate(whole);
            if (text != null) {
                callback.onFinished(text);
            } else {
                callback.onError("响应格式无法解析");
            }
        } catch (Throwable t) {
            callback.onError("响应解析失败：" + t.getMessage());
        }
    }

    /**
     * 从 Gemini 单个候选 JSON（对象或数组）里提取拼接后的文本。
     * Gemini 流式响应每行是一个对象；非流式可能是一个对象，也可能是对象数组。
     */
    private String extractTextFromCandidate(String json) throws Exception {
        String text = json.trim();
        if (text.startsWith("[")) {
            JSONArray arr = new JSONArray(text);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                String part = candidateText(ApiUtils.optObjectAt(arr, i));
                if (part != null) {
                    sb.append(part);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return candidateText(new JSONObject(text));
    }

    private String candidateText(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        JSONObject first = ApiUtils.optObjectAt(ApiUtils.optArray(obj, "candidates"), 0);
        if (first == null) {
            return null;
        }
        JSONObject content = ApiUtils.optObject(first, "content");
        if (content == null) {
            return null;
        }
        JSONArray parts = ApiUtils.optArray(content, "parts");
        if (parts == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String t = ApiUtils.optString(ApiUtils.optObjectAt(parts, i), "text");
            if (t != null) {
                sb.append(t);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}