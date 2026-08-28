package com.mtplugin.aieditor;

import androidx.annotation.NonNull;

/**
 * 对话消息（role + content）。
 * OpenAI 兼容与 Anthropic 协议都使用 (role, content) 结构，Gemini 转换时取 user 文本。
 */
public final class ChatMessage {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @NonNull
    public final String role;

    @NonNull
    public final String content;

    public ChatMessage(@NonNull String role, @NonNull String content) {
        this.role = role;
        this.content = content;
    }

    public static ChatMessage system(@NonNull String content) {
        return new ChatMessage(ROLE_SYSTEM, content);
    }

    public static ChatMessage user(@NonNull String content) {
        return new ChatMessage(ROLE_USER, content);
    }

    public static ChatMessage assistant(@NonNull String content) {
        return new ChatMessage(ROLE_ASSISTANT, content);
    }
}