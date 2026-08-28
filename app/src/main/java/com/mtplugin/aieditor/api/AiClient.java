package com.mtplugin.aieditor.api;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mtplugin.aieditor.ChatMessage;
import com.mtplugin.aieditor.ProviderRegistry;

/**
 * AI 对话客户端统一接口。
 * 所有客户端都是「同步阻塞」实现：内部做网络 IO + 流式解析，
 * 由调用方（对话框）放到后台线程执行。
 *
 * 说明：通用的工具方法（trimSlash/buildClient/optString 等）集中在 {@link ApiUtils}，
 * 不要在本接口里定义 static 方法后由实现类用简单名调用（Java 不允许）。
 */
public interface AiClient {

    /**
     * 流式/结果回调。onDelta 可能被调用 0..N 次；结束后只会调用 onFinished 或 onError 之一。
     */
    interface Callback {
        /** 流式增量文本（可能为空片段） */
        void onDelta(@NonNull String deltaText);

        /** 全部完成：fullText 为累计的完整输出 */
        void onFinished(@NonNull String fullText);

        /** 失败 */
        void onError(@NonNull String message);
    }

    /**
     * 发起一次对话请求（阻塞）。
     *
     * @param messages  消息列表（含 system / user）
     * @param callback  回调
     * @param cancelled 取消标记：置 true 后尽快中断（调用方线程继续运行）
     */
    void chat(@NonNull List<ChatMessage> messages,
              @NonNull Callback callback,
              @NonNull AtomicBoolean cancelled);

    /**
     * 客户端工厂：根据协议类型创建对应客户端。
     */
    class Factory {

        private Factory() {
        }

        /**
         * @param protocol    ProviderRegistry.Protocol 中的值（openai / claude / gemini）
         * @param baseUrl     接口根地址，例如 https://api.deepseek.com/v1
         * @param apiKey      API Key（无 Key 的本地服务可传空字符串）
         * @param model       模型名
         * @param temperature 采样温度
         * @param maxTokens   最大输出 token 数
         * @param timeoutSec  请求超时（秒）
         */
        @NonNull
        public static AiClient create(@NonNull String protocol,
                                      @NonNull String baseUrl,
                                      @NonNull String apiKey,
                                      @NonNull String model,
                                      float temperature,
                                      int maxTokens,
                                      int timeoutSec) {
            switch (protocol) {
                case ProviderRegistry.Protocol.CLAUDE:
                    return new ClaudeClient(baseUrl, apiKey, model, temperature, maxTokens, timeoutSec);
                case ProviderRegistry.Protocol.GEMINI:
                    return new GeminiClient(baseUrl, apiKey, model, temperature, maxTokens, timeoutSec);
                case ProviderRegistry.Protocol.OPENAI:
                default:
                    return new OpenAiCompatibleClient(baseUrl, apiKey, model, temperature, maxTokens, timeoutSec);
            }
        }
    }
}