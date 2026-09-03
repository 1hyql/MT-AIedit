package com.mtplugin.aieditor.settings;

import android.text.InputType;

import com.mtplugin.aieditor.Prefs;
import com.mtplugin.aieditor.ProviderRegistry;
import com.mtplugin.aieditor.ui.AboutDialog;

import bin.mt.plugin.api.PluginContext;
import bin.mt.plugin.api.preference.PluginPreference;
import bin.mt.plugin.api.ui.PluginUI;

/**
 * 插件主设置界面（PluginPreference）。
 *
 * 结构：
 *  - 配置方案：默认使用哪套（p1/p2/p3），对话框里也可直接切换
 *  - 方案 1/2/3 连接配置：服务商 + API Key + 接口地址 + 模型（p1 复用旧全局 key，兼容旧版本）
 *  - 生成参数：temperature / max_tokens / 超时（全局）
 *  - 系统提示词预设：一键应用内置模板
 *
 * 所有 addInput / addList 的 Key 均与 {@link Prefs} 中的常量一致，
 * MT 框架会自动把设置值保存到 SharedPreferences，对话框直接读取。
 */
public class AiEditPreference implements PluginPreference {

    @Override
    public void onBuild(PluginContext context, Builder builder) {
        builder.title("{plugin_name}");
        builder.subtitle("在文本编辑器中调用任意 AI 接口");

        // ===== 配置方案 =====
        builder.addHeader("配置方案");
        builder.addList("默认启动模式", Prefs.KEY_MODE)
                .defaultValue(Prefs.MODE_PLAN)
                .summary("对话框打开时默认处于哪种模式；Plan=只读分析，Act=可执行写入")
                .addItem("Plan（只读分析）", Prefs.MODE_PLAN)
                .addItem("Act（执行写入）", Prefs.MODE_ACT);
        builder.addList("默认配置方案", Prefs.KEY_ACTIVE_PROFILE)
                .defaultValue("p1")
                .summary("对话框打开时默认使用哪套方案；也可在对话框内直接切换")
                .addItem("方案 1", "p1")
                .addItem("方案 2", "p2")
                .addItem("方案 3", "p3");

        // ===== 方案 1 =====
        builder.addHeader("方案 1 · 连接");
        addProviderList(builder, "服务商", Prefs.KEY_PROVIDER);
        builder.addList("接口协议（自定义服务商时生效）", Prefs.KEY_PROTOCOL)
                .defaultValue("openai")
                .summary("OpenAI 兼容可覆盖 OpenAI/DeepSeek/通义/Kimi/GLM/SiliconFlow/Ollama 及任意中转站")
                .addItem("OpenAI 兼容（/chat/completions）", "openai")
                .addItem("Anthropic Claude（/messages）", "claude")
                .addItem("Google Gemini（streamGenerateContent）", "gemini");
        builder.addInput("API Key", Prefs.KEY_API_KEY)
                .hint("sk-...（Ollama 本地可留空）")
                .summary("***********")
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        // 注意：不能加 valueAsSummary()，否则已保存的 Key 会以明文显示在摘要里
        builder.addInput("接口地址", Prefs.KEY_BASE_URL)
                .hint("例：https://api.deepseek.com/v1")
                .valueAsSummary();
        builder.addInput("默认模型", Prefs.KEY_MODEL)
                .defaultValue("deepseek-chat")
                .valueAsSummary();
        builder.addSwitch("显示 tokens 用量", Prefs.KEY_SHOW_TOKENS)
                .defaultValue(true)
                .summaryOn("请求结束后在状态栏显示本次输入/输出 tokens")
                .summaryOff("不显示 tokens 用量");

        // ===== 方案 2 =====
        builder.addHeader("方案 2 · 连接");
        addProviderList(builder, "服务商", "p2_provider");
        builder.addInput("API Key", "p2_api_key")
                .hint("sk-...（Ollama 本地可留空）")
                .summary("***********")
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.addInput("接口地址", "p2_base_url")
                .hint("留空自动用该服务商默认地址")
                .valueAsSummary();
        builder.addInput("模型", "p2_model")
                .defaultValue("deepseek-chat")
                .valueAsSummary();

        // ===== 方案 3 =====
        builder.addHeader("方案 3 · 连接");
        addProviderList(builder, "服务商", "p3_provider");
        builder.addInput("API Key", "p3_api_key")
                .hint("sk-...（Ollama 本地可留空）")
                .summary("***********")
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.addInput("接口地址", "p3_base_url")
                .hint("留空自动用该服务商默认地址")
                .valueAsSummary();
        builder.addInput("模型", "p3_model")
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

        // ===== 系统提示词预设 =====
        builder.addHeader("系统提示词预设");
        builder.addText("默认编辑助手")
                .summary("标准：按指令编辑，只输出内容")
                .onClick((pluginUI, item) -> applyPreset(pluginUI, "默认编辑助手", Prefs.DEFAULT_SYSTEM_PROMPT));
        builder.addText("代码助手")
                .summary("专注代码修改，保持原有风格")
                .onClick((pluginUI, item) -> applyPreset(pluginUI, "代码助手",
                        "你是一名资深软件工程师，擅长阅读和修改代码。请严格遵循用户的指令修改代码，"
                                + "只输出修改后的代码本身，不要输出任何解释、说明或代码块围栏。"
                                + "注意保持原有代码风格与缩进。"));
        builder.addText("翻译助手")
                .summary("将内容翻译为目标语言，只输出译文")
                .onClick((pluginUI, item) -> applyPreset(pluginUI, "翻译助手",
                        "你是一名专业翻译。请将用户提供的内容翻译成目标语言，只输出译文本身，"
                                + "不要输出任何解释、说明或代码块围栏。"));
        builder.addText("润色助手")
                .summary("让表达更通顺、专业、简洁")
                .onClick((pluginUI, item) -> applyPreset(pluginUI, "润色助手",
                        "你是一名文字编辑，擅长润色和改写。请优化用户提供的文本，使表达更通顺、专业、简洁，"
                                + "只输出润色后的文本，不要输出任何解释。"));
        builder.addText("精简助手")
                .summary("保留核心信息，大幅精简")
                .onClick((pluginUI, item) -> applyPreset(pluginUI, "精简助手",
                        "你是一名文档精简专家。请在保留核心信息的前提下，尽可能精简用户提供的文本，"
                                + "只输出精简结果，不要输出任何解释。"));

        // ===== 提示词 =====
        builder.addHeader("提示词");
        builder.addInput("System Prompt（自定义）", Prefs.KEY_SYSTEM_PROMPT)
                .defaultValue(Prefs.DEFAULT_SYSTEM_PROMPT)
                .hint("覆盖预设；留空使用默认编辑助手")
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                .valueAsSummary();

        // ===== 注意 =====
        builder.addHeader("注意事项");
        builder.addText("使用说明").summary("1. 安装后先在本页配置 AI（选择配置方案，填写 API Key、模型名等）"
                + "2. 打开文本文件，点编辑器顶部「铅笔」按钮，在弹出菜单中找到「AI 编辑」"
                + "（若找不到：先在「自定义」分组里把「AI 编辑」拖到常用位置）"
                + "3. 输入指令 → 发送 → 在预览框确认/修改 AI 输出 → 点「应用」即写回文件并保存。"
                + "提示：AI 输出可在应用前直接修改调整；作用范围（整个文件/选中文本/光标处插入）可按需选择，"
                + "选小范围（如只选中要改的片段）可显著节省上传 tokens；多轮对话可用「新对话」清空历史。");
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

    /** 服务商下拉（方案共用） */
    private void addProviderList(Builder builder, String title, String key) {
        builder.addList(title, key)
                .defaultValue("deepseek")
                .summary("切换后对话框会自动带出常用模型")
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
    }

    /** 点击应用系统提示词预设 */
    private static void applyPreset(PluginUI pluginUI, String name, String template) {
        Prefs.putString(pluginUI.getContext().getPreferences(), Prefs.KEY_SYSTEM_PROMPT, template);
        pluginUI.showToast("已应用提示词预设：" + name);
    }

    /** 取服务商预设的显示名 */
    private static String presetId(String id) {
        return ProviderRegistry.findById(id).name;
    }
}
