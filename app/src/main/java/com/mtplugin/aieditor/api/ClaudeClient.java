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
 * Anthropic Claude Messages API 客户端（/messages）。
 * 使用 x-api-key 请求头 + anthropic-version 版本头，支持 stream 流式输出。
 * 通用工具方法统一走 {@link ApiUtils}。
 */
public final class ClaudeClient implements AiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String url;
    private final String apiKey;
    private final String model;
    private final float temperature;
    private final int maxTokens;
    private final int timeoutSec;

    public ClaudeClient(String baseUrl, String apiKey, String model,
                        float temperature, int maxTokens, int timeoutSec) {
        this.url = ApiUtils.trimSlash(baseUrl) + "/messages";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutSec = timeoutSec;
    }

    @Override
    public void chat(@NonNull List<ChatMessage> messages,
                     @NonNull Callback callback,
                     @NonNull AtomicBoolean cancelled) {
        try {
            String system = null;
            JSONArray arr = new JSONArray();
            for (ChatMessage m : messages) {
                if (ChatMessage.ROLE_SYSTEM.equals(m.role)) {
                    system = m.content;
                } else {
                    arr.add(new JSONObject()
                            .put("role", "user".equals(m.role) ? "user" : "assistant")
                            .put("content", m.content));
                }
            }

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", Math.max(256, maxTokens));
            body.put("stream", true);
            body.put("temperature", temperature);
            if (system != null && !system.isEmpty()) {
                body.put("system", system);
            }
            body.put("messages", arr);

            Request.Builder rb = new Request.Builder()
                    .url(url)
                    .header("anthropic-version", "2023-06-01")
                    .post(RequestBody.create(JSON, body.toString()));
            if (!apiKey.isEmpty()) {
                rb.header("x-api-key", apiKey);
            }
            // 某些 Claude 代理不需要 Key 时允许留空

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
                    JSONObject obj = new JSONObject(data);
                    // Claude Messages 的 usage 随 message_start / message_delta 返回
                    JSONObject usage = ApiUtils.optObject(obj, "usage");
                    if (usage != null) {
                        long pt = ApiUtils.optLong(usage, "input_tokens", -1);
                        long ct = ApiUtils.optLong(usage, "output_tokens", -1);
                        if (pt >= 0 && ct >= 0) {
                            callback.onUsage(pt, ct);
                        }
                    }
                    // 输出被 max_tokens 截断（stop_reason=max_tokens）
                    if ("max_tokens".equals(ApiUtils.optString(obj, "stop_reason"))) {
                        callback.onTruncated();
                    }
                    String type = ApiUtils.optString(obj, "type");
                    if ("content_block_delta".equals(type)) {
                        JSONObject delta = ApiUtils.optObject(obj, "delta");
                        String deltaType = delta == null ? null : ApiUtils.optString(delta, "type");
                        if ("thinking_delta".equals(deltaType)) {
                            // Claude thinking 块：单独回调，不混入结果
                            String thinking = ApiUtils.optString(delta, "thinking");
                            if (thinking != null && !thinking.isEmpty()) {
                                callback.onThinking(thinking);
                                if (cancelled.get()) {
                                    return;
                                }
                                continue;
                            }
                        }
                        String text = delta == null ? null : ApiUtils.optString(delta, "text");
                        if (text != null && !text.isEmpty()) {
                            accumulated.append(text);
                            callback.onDelta(text);
                            if (cancelled.get()) {
                                return;
                            }
                        }
                    } else if ("error".equals(type)) {
                        JSONObject err = ApiUtils.optObject(obj, "error");
                        String msg = err == null ? null : ApiUtils.optString(err, "message");
                        callback.onError(msg == null || msg.isEmpty() ? "未知错误" : msg);
                        return;
                    }
                } catch (Throwable ignored) {
                    // 忽略无法解析的行
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
            JSONObject obj = new JSONObject(whole);
            String text = extractContent(obj);
            if (text != null) {
                callback.onFinished(text);
            } else {
                callback.onError("响应格式无法解析");
            }
        } catch (Throwable t) {
            callback.onError("响应解析失败：" + t.getMessage());
        }
    }

    /** 从非流式 JSON 中提取 content[0].text */
    private String extractContent(JSONObject obj) throws Exception {
        JSONObject first = ApiUtils.optObjectAt(ApiUtils.optArray(obj, "content"), 0);
        if (first == null) {
            JSONObject err = ApiUtils.optObject(obj, "error");
            if (err != null) {
                String msg = ApiUtils.optString(err, "message");
                throw new Exception(msg == null || msg.isEmpty() ? "未知错误" : msg);
            }
            return null;
        }
        return ApiUtils.optString(first, "text");
    }
}