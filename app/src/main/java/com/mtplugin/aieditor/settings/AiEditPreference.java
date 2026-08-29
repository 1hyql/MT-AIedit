package com.mtplugin.aieditor.settings;

import android.text.InputType;

import com.mtplugin.aieditor.Prefs;
import com.mtplugin.aieditor.ProviderRegistry;
import com.mtplugin.aieditor.ui.AboutDialog;

import bin.mt.plugin.api.PluginContext;
import bin.mt.plugin.api.preference.PluginPreference;

/**
 * 插件主设置界面（PluginPreference）。
 *
 * 所有 addInput / addList 的 Key 均与 {@link Prefs} 中的常量一致，
 * MT 框架会自动把设置值保存到 SharedPreferences，对话框直接读取。
 *
 * 服务商预设只用于：对话框里自动填充常用模型、自动判断接口协议；
 * 接口地址/Key/模型 始终以这里的用户填写值为准（预设服务商留空地址时会自动补默认地址）。
 */
public class AiEditPreference implements PluginPreference {

    @Override
    public void onBuild(PluginContext context, Builder builder) {
        builder.title("{plugin_name}");
        builder.subtitle("在文本编辑器中调用任意 AI 接口");

        // ===== 服务配置 =====
        builder.addHeader("服务配置");

        // 服务商预设（仅影响模型预设与协议判断）
        builder.addList("服务商", Prefs.KEY_PROVIDER)
                .defaultValue("deepseek")
                .summary("切换后对话框会自动带出常用模型，接口地址取下方「接口地址」字段")
                .addItem(presetId("openai"), "openai")
                .addItem(presetId("deepseek"), "deepseek")
                .addItem(presetId("qwen"), "qwen")
                .addItem(presetId("kimi"), "kimi")
                .addItem(presetId("glm"), "glm")
                .addItem(presetId("siliconflow"), "siliconflow")
                .addItem(presetId("ollama"), "ollama")
                .addItem(presetId("claude"), "claude")
                .addItem(presetId("gemini"), "gemini")
                .addItem(presetId("custom"), "custom");

        // 接口协议（仅「自定义」服务商时生效）
        builder.addList("接口协议（自定义服务商时生效）", Prefs.KEY_PROTOCOL)
                .defaultValue("openai")
                .summary("OpenAI 兼容协议可覆盖 OpenAI/DeepSeek/通义/Kimi/GLM/SiliconFlow/Ollama 及任意中转站")
                .addItem("OpenAI 兼容（/chat/completions）", "openai")
                .addItem("Anthropic Claude（/messages）", "claude")
                .addItem("Google Gemini（streamGenerateContent）", "gemini");

        builder.addInput("API Key", Prefs.KEY_API_KEY)
                .hint("sk-...（Ollama 本地可留空）")
                .summary("***********")
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        // 注意：不能加 valueAsSummary()，否则已保存的 Key 会以明文显示在摘要里

        builder.addSwitch("显示 tokens 用量", Prefs.KEY_SHOW_TOKENS)
                .defaultValue(true)
                .summaryOn("请求结束后在状态栏显示本次输入/输出 tokens")
                .summaryOff("不显示 tokens 用量");

        builder.addInput("接口地址", Prefs.KEY_BASE_URL)
                .hint("例：https://api.deepseek.com/v1")
                .valueAsSummary();

        builder.addInput("默认模型", Prefs.KEY_MODEL)
                .defaultValue("deepseek-chat")
                .valueAsSummary();

        // ===== 生成参数 =====
        builder.addHeader("生成参数");

        builder.addInput("Temperature（随机度）", Prefs.KEY_TEMPERATURE)
                .defaultValue("0.2")
                .hint("0~2，越小越稳定")
                .valueAsSummary();

        builder.addInput("最大输出 Tokens", Prefs.KEY_MAX_TOKENS)
                .defaultValue("8192")
                .hint("AI 单次最多生成的 token 数；思考型模型建议调大")
                .valueAsSummary();

        builder.addInput("请求超时（秒）", Prefs.KEY_TIMEOUT_SEC)
                .defaultValue("120")
                .hint("网络请求超时时间")
                .valueAsSummary();

        // ===== 提示词 =====
        builder.addHeader("提示词");

        builder.addInput("System Prompt", Prefs.KEY_SYSTEM_PROMPT)
                .defaultValue(Prefs.DEFAULT_SYSTEM_PROMPT)
                .hint("注入给 AI 的系统提示词")
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                .valueAsSummary();

        // ===== 注意 =====
        builder.addHeader("注意事项");
        builder.addText("使用说明").summary("1. 安装后先在本页配置 AI（服务商选择好，填写 API Key、模型名等）"
                + "2. 打开文本文件，点编辑器顶部「铅笔」按钮，在弹出菜单中找到「AI 编辑」"
                + "（若找不到：先在「自定义」分组里把「AI 编辑」拖到常用位置）"
                + "3. 输入指令 → 发送 → 在预览框确认/修改 AI 输出 → 点「应用」即写回文件并保存。"
                + "提示：AI 输出可在应用前直接修改调整；作用范围（整个文件/选中文本/光标处插入）可按需选择，"
                + "选小范围（如只选中要改的片段）可显著节省上传 tokens。");
        builder.addText("API Key 安全")
                .summary("仅保存在本机，卸载即删除；请遵守各服务商的使用条款。");
        builder.addText("接口地址留空")
                .summary("选择预设服务商时，接口地址留空会自动使用该服务商的默认地址；"
                        + "「自定义」服务商需要手动填写完整地址。");

        // ===== 社区与关于 =====
        builder.addHeader("社区与关于");
        builder.addText("关于与社区")
                .summary("版本信息 · 官网 / QQ频道 / QQ群（点击查看）")
                .onClick((pluginUI, item) -> AboutDialog.show(pluginUI));
    }

    /** 取服务商预设的显示名 */
    private static String presetId(String id) {
        return ProviderRegistry.findById(id).name;
    }
}