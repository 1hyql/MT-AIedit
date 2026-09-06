package com.mtplugin.aieditor;

import androidx.annotation.NonNull;

import android.graphics.drawable.Drawable;

import com.mtplugin.aieditor.ui.AboutDialog;
import com.mtplugin.aieditor.ui.AiEditDialog;

import bin.mt.plugin.api.drawable.MaterialIcons;
import bin.mt.plugin.api.editor.BaseTextEditorToolMenu;
import bin.mt.plugin.api.editor.TextEditor;
import bin.mt.plugin.api.ui.PluginUI;

/**
 * 文本编辑器「编辑」工具栏菜单项：AI 编辑。
 *
 * 用户入口：MT 管理器文本编辑器顶部工具栏的「编辑」菜单中的「AI 编辑」项。
 * 菜单项右侧的按钮（onPluginButtonClick）弹出「关于/社区」对话框。
 */
public class AiEditToolMenu extends BaseTextEditorToolMenu {

    @NonNull
    @Override
    public String name() {
        // 本地化文案，见 assets/strings*.mtl
        return "{menu_ai_edit}";
    }

    @NonNull
    @Override
    public Drawable icon() {
        try {
            return MaterialIcons.get("auto_awesome");
        } catch (Throwable t) {
            // 图标名兜底，保证不崩溃
            return MaterialIcons.get("edit");
        }
    }

    @Override
    public boolean checkVisible(@NonNull TextEditor editor) {
        // 只读模式下不显示
        return editor != null && !editor.isReadOnly();
    }

    @Override
    public void onMenuClick(@NonNull PluginUI pluginUI, @NonNull TextEditor editor) {
        AiEditDialog.show(pluginUI, editor);
    }

    @Override
    public void onPluginButtonClick(@NonNull PluginUI pluginUI) {
        // ⓘ 按钮：关于/社区对话框（官网、QQ频道、群号）
        AboutDialog.show(pluginUI);
    }
}