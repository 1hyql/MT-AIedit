package com.mtplugin.aieditor.ui;

import androidx.annotation.NonNull;

import bin.mt.plugin.api.PluginContext;
import bin.mt.plugin.api.ui.PluginUI;
import bin.mt.plugin.api.ui.PluginView;
import bin.mt.plugin.api.ui.dialog.PluginDialog;

/**
 * 「关于/社区」对话框。
 *
 * SDK 没有 markdown 渲染组件，这里用 PluginView 结构化排版（标题加粗 + 分割线 +
 * 分区小标题 + 可点击链接行），呈现接近 Markdown 卡片的美化效果：
 *
 * # AI文本编辑
 * v1.0.2 · 在 MT 文本编辑器中调用任意 AI 接口编辑文件
 * ------------------------------------------
 * ## 社区与支持
 * 🌐 社区官网     https://1hyql.github.io/termux   ← 点击打开浏览器
 * 📢 QQ频道        termux2024                      ← 点击复制
 * 👥 QQ一群        369925819                       ← 点击复制
 * 👥 QQ二群        1034443023                      ← 点击复制
 */
public final class AboutDialog {

    private AboutDialog() {
    }

    public static void show(@NonNull PluginUI pluginUI) {
        final PluginContext context = pluginUI.getContext();
        final String version = context.getPluginVersionName();
        final String website = "https://1hyql.github.io/termux";
        final String channel = "termux2024";
        final String group1 = "369925819";
        final String group2 = "1034443023";

        PluginView view = pluginUI.buildVerticalLayout()
                .paddingTop(pluginUI.dialogPaddingVertical() / 2)
                // 标题（markdown 风格 #）
                .addTextView("title").text("{plugin_name}").bold().textSize(17)
                // 副标题（版本 + 简介）
                .addTextView("subtitle")
                .text("v" + version + "  ·  " + context.getString("about_tagline"))
                .textSize(12).textColor(pluginUI.colorTextSecondary()).paddingBottomDp(6)
                // 作者
                .addTextView("author").text("👤  " + context.getString("about_author") + "：皓月千里")
                .textSize(12).textColor(pluginUI.colorTextSecondary()).paddingBottomDp(6)
                // 分割线 (---)
                .addView().height(1).widthMatchParent().backgroundColor(pluginUI.colorDivider())
                // 分区小标题 (##)
                .addTextView("secTitle").text(context.getString("about_community"))
                .bold().textSize(13).paddingTopDp(8).paddingBottomDp(2)
                // 官网
                .addTextView("webTitle").text("🌐  " + context.getString("about_website"))
                .textSize(14).paddingTopDp(4)
                .onClick(v -> context.openBrowser(website))
                .addTextView("webSub").text(website)
                .textSize(12).textColor(pluginUI.colorTextSecondary()).paddingBottomDp(4)
                // QQ频道
                .addTextView("channelTitle").text("📢  " + context.getString("about_qq_channel"))
                .textSize(14).paddingTopDp(4)
                .onClick(v -> copy(pluginUI, channel,
                        context.getString("about_copy") + " " + context.getString("about_qq_channel")))
                .addTextView("channelSub").text(channel)
                .textSize(12).textColor(pluginUI.colorTextSecondary()).paddingBottomDp(4)
                // QQ一群
                .addTextView("group1Title").text("👥  " + context.getString("about_group1"))
                .textSize(14).paddingTopDp(4)
                .onClick(v -> copy(pluginUI, group1, context.getString("about_copy") + " " + group1))
                .addTextView("group1Sub").text(group1)
                .textSize(12).textColor(pluginUI.colorTextSecondary()).paddingBottomDp(4)
                // QQ二群
                .addTextView("group2Title").text("👥  " + context.getString("about_group2"))
                .textSize(14).paddingTopDp(4)
                .onClick(v -> copy(pluginUI, group2, context.getString("about_copy") + " " + group2))
                .addTextView("group2Sub").text(group2)
                .textSize(12).textColor(pluginUI.colorTextSecondary())
                .build();

        pluginUI.buildDialog()
                .setTitle("{plugin_name}")
                .setView(view)
                .setPositiveButton(context.getString("dialog_close_btn"),
                        (dialog, which) -> dialog.dismiss())
                .show();
    }

    private static void copy(PluginUI pluginUI, String content, CharSequence toast) {
        pluginUI.getContext().setClipboardText(content);
        pluginUI.showToast(toast);
    }
}