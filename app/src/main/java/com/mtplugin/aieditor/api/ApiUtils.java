package com.mtplugin.aieditor.api;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import bin.mt.json.JSONArray;
import bin.mt.json.JSONObject;
import bin.mt.json.JSONValue;
import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * API 客户端共享工具类。
 *
 * 注意：这些方法不能放在 AiClient 接口里再被实现类用简单名调用——
 * Java 接口的 static 方法不会进入实现类作用域，必须通过接口名或工具类调用，
 * 因此统一集中在此类，客户端调用一律写 ApiUtils.xxx。
 * （ApiUtils 与客户端同包，无需 import。）
 */
public final class ApiUtils {

    private ApiUtils() {
    }

    /* =============== bin.mt.json 安全取值（mnJson 风格） =============== */

    /** 取字符串成员；缺失或非字符串返回 null（不抛异常） */
    public static long optLong(JSONObject obj, String name, long def) {
        if (obj == null || !obj.contains(name)) {
            return def;
        }
        try {
            Object v = obj.get(name);
            if (v instanceof Number) {
                return ((Number) v).longValue();
            }
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) {
                return def;
            }
            return Long.parseLong(s);
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static String optString(JSONObject obj, String name) {
        if (obj == null) {
            return null;
        }
        JSONValue v = obj.get(name);
        return (v != null && v.isString()) ? v.asString() : null;
    }

    /** 取对象成员；缺失或非对象返回 null */
    public static JSONObject optObject(JSONObject obj, String name) {
        if (obj == null) {
            return null;
        }
        JSONValue v = obj.get(name);
        return (v != null && v.isObject()) ? v.asObject() : null;
    }

    /** 取数组成员；缺失或非数组返回 null */
    public static JSONArray optArray(JSONObject obj, String name) {
        if (obj == null) {
            return null;
        }
        JSONValue v = obj.get(name);
        return (v != null && v.isArray()) ? v.asArray() : null;
    }

    /** 取数组第 index 项为对象；越界或非对象返回 null */
    public static JSONObject optObjectAt(JSONArray arr, int index) {
        if (arr == null || index < 0 || index >= arr.size()) {
            return null;
        }
        JSONValue v = arr.get(index);
        return (v != null && v.isObject()) ? v.asObject() : null;
    }

    /* =============== HTTP 工具 =============== */

    /** 去掉末尾的 / */
    public static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 组装 OkHttpClient，统一超时 */
    public static OkHttpClient buildClient(int timeoutSec) {
        int t = Math.max(15, timeoutSec);
        return new OkHttpClient.Builder()
                .connectTimeout(t, TimeUnit.SECONDS)
                .writeTimeout(t, TimeUnit.SECONDS)
                .readTimeout(t, TimeUnit.SECONDS)
                .callTimeout(t + 60, TimeUnit.SECONDS)
                .build();
    }

    /** 读取错误响应体（失败不抛异常） */
    public static String readErrorBody(Response response) {
        try {
            if (response != null && response.body() != null) {
                return response.body().string();
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    /**
     * 从错误响应体里尽量提取一行可读的错误信息。
     * 兼容常见格式：{"error":{"message":"..."}}、{"error":"..."}、{"message":"..."} 或纯文本。
     */
    public static String extractError(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        String text = body.trim();
        if (text.startsWith("{")) {
            try {
                JSONObject obj = new JSONObject(text);
                JSONObject error = optObject(obj, "error");
                if (error != null) {
                    String msg = optString(error, "message");
                    if (msg != null && !msg.isEmpty()) {
                        return msg;
                    }
                    // Claude 风格：error 对象里是 type/message
                    msg = optString(error, "type");
                    if (msg != null && !msg.isEmpty()) {
                        String detail = optString(error, "message");
                        return detail != null && !detail.isEmpty() ? detail : msg;
                    }
                }
                String msg = optString(obj, "message");
                if (msg != null && !msg.isEmpty()) {
                    return msg;
                }
                JSONValue ev = obj.get("error");
                if (ev != null && ev.isString()) {
                    return ev.asString();
                }
            } catch (Throwable ignored) {
                // 继续按纯文本处理
            }
        }
        String s = text.replace('\n', ' ').trim();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    /** 兼容 Android 5.0+ 的 URL 编码 */
    public static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}