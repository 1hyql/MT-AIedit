package com.mtplugin.aieditor.ui;

import androidx.annotation.NonNull;

import bin.mt.plugin.api.PluginContext;
import bin.mt.plugin.api.ui.PluginUI;
import bin.mt.plugin.api.ui.PluginView;
import bin.mt.plugin.api.ui.dialog.PluginDialog;

/**
 * 关于/社区对话框。
 *
 * SDK 未提供 markdown 渲染组件，因此使用 PluginView 结构化排版：
 * 标题加粗、分割线、分区小标题与可点击链接行，以呈现接近卡片化的展示效果。
 *
 * 内容包含：插件名与版本、作者、社区官网、QQ 频道及两个交流群。
 * 链接行点击行为：官网打开浏览器，群号复制到剪贴板。
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
                .addTextView("author").text("👤  " + context.getString("about_author") + "：皓月千里")
                .textSize(12).textColor(pluginUI.colorTextSecondary()).paddingBottomDp(6)
                // 分割线 (---)
                .addView().height(1).widthMatchParent().backgroundColor(pluginUI.colorDivider())
                // 分区小标题 (##)
                .addTextView("secTitle").text(context.getString("about_community"))
                .bold().textSize(13).paddingTopDp(8).paddingBottomDp(2)
                .addTextView("webTitle").text("🌐  " + context.getString("about_website"))
                .textSize(14).paddingTopDp(4)
                .onClick(v -> context.openBrowser(website))
                .addTextView("webSub").text(website)
                .textSize(12).textColor(pluginUI.colorTextSecondary()).paddingBottomDp(4)
                .addTextView("channelTitle").text("📢  " + context.getString("about_qq_channel"))
                .textSize(14).paddingTopDp(4)
                .onClick(v -> copy(pluginUI, channel,
                        context.getString("about_copy") + " " + context.getString("about_qq_channel")))
                .addTextView("channelSub").text(channel)
                .textSize(12).textColor(pluginUI.colorTextSecondary()).paddingBottomDp(4)
                .addTextView("group1Title").text("👥  " + context.getString("about_group1"))
                .textSize(14).paddingTopDp(4)
                .onClick(v -> copy(pluginUI, group1, context.getString("about_copy") + " " + group1))
                .addTextView("group1Sub").text(group1)
                .textSize(12).textColor(pluginUI.colorTextSecondary()).paddingBottomDp(4)
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