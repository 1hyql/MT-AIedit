package com.mtplugin.aieditor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 常见 AI 服务商预设注册表。
 *
 * 每个预设包含：唯一 ID、显示名、接口协议类型、默认接口地址、常用模型列表。
 * 实际使用的 接口地址/Key/模型 始终以「设置界面」里用户填写的为准，
 * 这里的预设只用于：对话框里的下拉选项 / 切换服务商时自动填充常用模型。
 */
public final class ProviderRegistry {

    /** 接口协议类型 */
    public interface Protocol {
        String OPENAI = "openai";  // OpenAI 兼容 /chat/completions
        String CLAUDE = "claude";  // Anthropic Messages API
        String GEMINI = "gemini";  // Google Gemini generateContent
    }

    /** 服务商预设 */
    public static final class Provider {
        public final String id;
        public final String name;
        public final String protocol;
        public final String defaultBaseUrl;
        public final List<String> models;

        Provider(String id, String name, String protocol, String defaultBaseUrl, List<String> models) {
            this.id = id;
            this.name = name;
            this.protocol = protocol;
            this.defaultBaseUrl = defaultBaseUrl;
            this.models = models;
        }
    }

    private static final List<Provider> PRESETS = Arrays.asList(
            new Provider("openai", "OpenAI", Protocol.OPENAI,
                    "https://api.openai.com/v1",
                    Arrays.asList("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano", "o3-mini")),
            new Provider("deepseek", "DeepSeek", Protocol.OPENAI,
                    "https://api.deepseek.com/v1",
                    Arrays.asList("deepseek-chat", "deepseek-reasoner")),
            new Provider("qwen", "通义千问", Protocol.OPENAI,
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    Arrays.asList("qwen-plus", "qwen-turbo", "qwen-max", "qwen-long", "qwen3-max")),
            new Provider("kimi", "Kimi / 月之暗面", Protocol.OPENAI,
                    "https://api.moonshot.cn/v1",
                    Arrays.asList("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k", "kimi-latest")),
            new Provider("glm", "智谱 GLM", Protocol.OPENAI,
                    "https://open.bigmodel.cn/api/paas/v4",
                    Arrays.asList("glm-4-plus", "glm-4-air", "glm-4-flash", "glm-4-long")),
            new Provider("siliconflow", "SiliconFlow", Protocol.OPENAI,
                    "https://api.siliconflow.cn/v1",
                    Arrays.asList("deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1",
                            "Qwen/Qwen2.5-72B-Instruct", "THUDM/glm-4-9b-chat")),
            new Provider("ollama", "Ollama（本地）", Protocol.OPENAI,
                    "http://192.168.1.100:11434/v1",
                    Arrays.asList("llama3.1", "qwen2.5", "deepseek-r1", "gemma2")),
            new Provider("claude", "Anthropic Claude", Protocol.CLAUDE,
                    "https://api.anthropic.com/v1",
                    Arrays.asList("claude-3-7-sonnet-latest", "claude-3-5-sonnet-latest",
                            "claude-3-5-haiku-latest", "claude-opus-4-1")),
            new Provider("gemini", "Google Gemini", Protocol.GEMINI,
                    "https://generativelanguage.googleapis.com/v1beta",
                    Arrays.asList("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash", "gemini-1.5-pro")),
            // 完全自定义：协议/地址/模型全部来自设置界面
            new Provider("custom", "自定义", Protocol.OPENAI,
                    "",
                    Arrays.asList(""))
    );

    // 阿里云百炼（通义千问 OpenAI 兼容）、智谱等均已内置；
    // 其它任意 OpenAI 兼容网关/中转站，选「自定义」手动填地址即可。

    private static final Provider CUSTOM = findByIdInternal("custom");

    private ProviderRegistry() {
    }

    public static List<Provider> all() {
        return PRESETS;
    }

    /** 对话框/设置界面用：所有服务商的显示名 */
    public static List<String> allNames() {
        List<String> names = new ArrayList<>(PRESETS.size());
        for (Provider p : PRESETS) {
            names.add(p.name);
        }
        return names;
    }

    /** 按 ID 查找预设，找不到返回「自定义」 */
    public static Provider findById(String id) {
        Provider p = findByIdInternal(id);
        return p != null ? p : CUSTOM;
    }

    private static Provider findByIdInternal(String id) {
        if (id == null) {
            return null;
        }
        for (Provider p : PRESETS) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** 服务商 ID 列表（与 allNames() 顺序一致），用于 Spinner 下标映射 */
    public static List<String> allIds() {
        List<String> ids = new ArrayList<>(PRESETS.size());
        for (Provider p : PRESETS) {
            ids.add(p.id);
        }
        return ids;
    }
}