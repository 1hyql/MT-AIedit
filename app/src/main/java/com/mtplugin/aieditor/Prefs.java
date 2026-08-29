package com.mtplugin.aieditor;

import android.content.SharedPreferences;

/**
 * 插件配置读写（存储于 PluginContext.getPreferences() 的 SharedPreferences）。
 * 注意：设置界面（PluginPreference）中的 Key 必须与本文件的 Key 完全一致，
 * 这样用户在设置界面修改的值，对话框才能直接读到。
 */
public final class Prefs {

    public static final String KEY_PROVIDER = "provider";
    public static final String KEY_PROTOCOL = "protocol";
    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_MODEL = "model";
    public static final String KEY_TEMPERATURE = "temperature";
    public static final String KEY_MAX_TOKENS = "max_tokens";
    public static final String KEY_TIMEOUT_SEC = "timeout_sec";
    public static final String KEY_SYSTEM_PROMPT = "system_prompt";
    public static final String KEY_SHOW_TOKENS = "show_tokens_usage";
    /** 当前使用的配置方案：p1 / p2 / p3（p1 复用旧全局 key） */
    public static final String KEY_ACTIVE_PROFILE = "active_profile";

    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个专业的技术编辑助手，擅长代码与各类文本的修改。"
                    + "请严格遵循用户的指令，只输出编辑后的内容本身，不要输出任何解释、前言或代码块围栏。";

    private Prefs() {
    }

    public static boolean getBoolean(SharedPreferences p, String key, boolean def) {
        if (p == null) {
            return def;
        }
        try {
            return p.getBoolean(key, def);
        } catch (Exception ignored) {
            return def;
        }
    }

    public static String getString(SharedPreferences p, String key, String def) {
        return p == null ? def : p.getString(key, def);
    }

    public static int getInt(SharedPreferences p, String key, int def) {
        if (p == null) {
            return def;
        }
        try {
            String v = p.getString(key, null);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    public static float getFloat(SharedPreferences p, String key, float def) {
        if (p == null) {
            return def;
        }
        try {
            String v = p.getString(key, null);
            return v == null ? def : Float.parseFloat(v.trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    public static void putString(SharedPreferences p, String key, String value) {
        if (p != null) {
            p.edit().putString(key, value).apply();
        }
    }

    public static void putInt(SharedPreferences p, String key, int value) {
        if (p != null) {
            p.edit().putString(key, String.valueOf(value)).apply();
        }
    }

    public static void putFloat(SharedPreferences p, String key, float value) {
        if (p != null) {
            p.edit().putString(key, String.valueOf(value)).apply();
        }
    }
}