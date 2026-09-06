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
 * OpenAI 兼容协议客户端（/chat/completions）。
 * 适用于：OpenAI、DeepSeek、通义千问(兼容模式)、Kimi、智谱GLM、SiliconFlow、
 * Ollama(OpenAI 兼容接口) 以及任意 OpenAI 兼容网关/中转站。
 *
 * 支持流式（SSE）与非流式两种响应；通用工具方法统一走 {@link ApiUtils}。
 * 思考型模型（DeepSeek-R1 等）的 reasoning_content 通过 {@link Callback#onReasoning} 单独回调，
 * 不混入正式结果；finish_reason=length 时通过 {@link Callback#onTruncated} 提示截断。
 */
public final class OpenAiCompatibleClient implements AiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String url;
    private final String apiKey;
    private final String model;
    private final float temperature;
    private final int maxTokens;
    private final int timeoutSec;

    public OpenAiCompatibleClient(String baseUrl, String apiKey, String model,
                                  float temperature, int maxTokens, int timeoutSec) {
        this.url = ApiUtils.trimSlash(baseUrl) + "/chat/completions";
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
            JSONArray arr = new JSONArray();
            for (ChatMessage m : messages) {
                arr.add(new JSONObject()
                        .put("role", m.role)
                        .put("content", m.content));
            }

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", arr);
            body.put("temperature", temperature);
            body.put("max_tokens", Math.max(256, maxTokens));
            body.put("stream", true);

            Request.Builder rb = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(JSON, body.toString()));
            if (!apiKey.isEmpty()) {
                rb.header("Authorization", "Bearer " + apiKey);
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
        final boolean[] truncated = {false};
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
                if ("[DONE]".equals(data)) {
                    break;
                }
                try {
                    JSONObject obj = new JSONObject(data);
                    // 流式最后 chunk 常携带 usage（OpenAI 兼容格式）
                    JSONObject usage = ApiUtils.optObject(obj, "usage");
                    if (usage != null) {
                        long pt = ApiUtils.optLong(usage, "prompt_tokens", -1);
                        long ct = ApiUtils.optLong(usage, "completion_tokens", -1);
                        if (pt >= 0 && ct >= 0) {
                            callback.onUsage(pt, ct);
                        }
                    }
                    JSONObject choice = ApiUtils.optObjectAt(ApiUtils.optArray(obj, "choices"), 0);
                    if (choice != null) {
                        // 思考型模型（DeepSeek-R1 等）：reasoning_content 与正式 content 分离
                        JSONObject delta = ApiUtils.optObject(choice, "delta");
                        String reason = delta == null ? null : ApiUtils.optString(delta, "reasoning_content");
                        if (reason == null && delta != null) {
                            reason = ApiUtils.optString(delta, "reasoning");
                        }
                        if (reason != null && !reason.isEmpty()) {
                            callback.onReasoning(reason);
                            if (cancelled.get()) {
                                return;
                            }
                        }
                        String part = delta == null ? null : ApiUtils.optString(delta, "content");
                        if (part == null || part.isEmpty()) {
                            part = ApiUtils.optString(choice, "text");
                        }
                        if (part != null && !part.isEmpty()) {
                            accumulated.append(part);
                            callback.onDelta(part);
                            if (cancelled.get()) {
                                return;
                            }
                        }
                        // 输出被 max_tokens 截断（finish_reason=length）
                        if ("length".equals(ApiUtils.optString(choice, "finish_reason"))) {
                            truncated[0] = true;
                        }
                    }
                } catch (Throwable ignored) {
                    // 忽略无法解析的行
                }
            } else if (line.length() > 0) {
                raw.append(line);
            }
        }

        if (truncated[0]) {
            callback.onTruncated();
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
            String content = extractContent(whole);
            if (content != null) {
                callback.onFinished(content);
            } else {
                callback.onError("响应格式无法解析");
            }
        } catch (Throwable t) {
            callback.onError("响应解析失败：" + t.getMessage());
        }
    }

    /** 从完整 JSON 中提取 choices[0].message.content */
    private String extractContent(String body) throws Exception {
        String text = body.trim();
        if (text.startsWith("[")) {
            JSONArray arr = new JSONArray(text);
            if (arr.size() == 0) {
                return "";
            }
            text = arr.get(0).toString();
        }
        JSONObject obj = new JSONObject(text);
        JSONObject choice = ApiUtils.optObjectAt(ApiUtils.optArray(obj, "choices"), 0);
        if (choice == null) {
            throw new Exception(ApiUtils.extractError(body));
        }
        JSONObject message = ApiUtils.optObject(choice, "message");
        if (message != null) {
            String content = ApiUtils.optString(message, "content");
            if (content != null && !content.isEmpty()) {
                return content;
            }
            content = ApiUtils.optString(message, "text");
            if (content != null && !content.isEmpty()) {
                return content;
            }
        }
        String text2 = ApiUtils.optString(choice, "text");
        if (text2 != null && !text2.isEmpty()) {
            return text2;
        }
        return null;
    }
}
