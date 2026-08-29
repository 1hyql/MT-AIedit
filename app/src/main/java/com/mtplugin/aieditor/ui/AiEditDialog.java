package com.mtplugin.aieditor.ui;

import androidx.annotation.NonNull;

import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mtplugin.aieditor.ChatMessage;
import com.mtplugin.aieditor.Prefs;
import com.mtplugin.aieditor.ProviderRegistry;
import com.mtplugin.aieditor.api.AiClient;
import com.mtplugin.aieditor.ui.AboutDialog;

import bin.mt.plugin.api.PluginContext;
import bin.mt.plugin.api.editor.TextEditor;
import bin.mt.plugin.api.ui.PluginButton;
import bin.mt.plugin.api.ui.PluginEditText;
import bin.mt.plugin.api.ui.PluginTextView;
import bin.mt.plugin.api.ui.PluginUI;
import bin.mt.plugin.api.ui.PluginView;
import bin.mt.plugin.api.ui.dialog.PluginDialog;
import bin.mt.plugin.api.util.ThreadUtil;

/**
 * AI 编辑对话框。
 *
 * 界面：指令输入框 + 作用范围(全文/选中/光标) + 服务商下拉 + 模型输入 + 设置入口
 *       + 发送/停止 + 流式输出预览 + 应用/复制/关闭。
 *
 * 流程：输入指令 → 发送（后台线程请求 AI，SSE 流式显示）→ 预览确认 → 应用/关闭。
 *
 * 说明：网络请求在后台线程执行；所有 UI 更新（setText/setEnabled 等）必须通过
 *       ThreadUtil.runOnUiThread 切回主线程（SDK 内部有 UI 线程检查）。
 */
public final class AiEditDialog {

    /** 作用范围 */
    private static final int SCOPE_FULL = 0;
    private static final int SCOPE_SELECTED = 1;
    private static final int SCOPE_CURSOR = 2;

    /** 整个文件模式的最大字符数（约 10 万字符 ≈ 数万 tokens，防止超上下文/过慢） */
    private static final int MAX_FULL_CHARS = 100000;

    private AiEditDialog() {
    }

    public static void show(@NonNull PluginUI pluginUI, @NonNull TextEditor editor) {
        final PluginContext context = pluginUI.getContext();
        final SharedPreferences prefs = context.getPreferences();

        // ---------- 读取编辑器状态 ----------
        final boolean hasSelection = editor.hasTextSelected();
        final int selStart = editor.getSelectionStart();
        final int selEnd = editor.getSelectionEnd();
        final String selectedText = hasSelection ? editor.subText(selStart, selEnd) : "";
        final String filePath = editor.getFilePath();
        final String fileName = editor.getFileName();
        // 注意：fullText（全文）不在打开时读取，延迟到发送时再取（避免大文件卡顿）
        final int cursorPos = editor.getSelectionStart();

        // ---------- 读取配置 ----------
        final String providerId = Prefs.getString(prefs, Prefs.KEY_PROVIDER, "deepseek");
        final ProviderRegistry.Provider provider = ProviderRegistry.findById(providerId);
        String baseUrlTmp = Prefs.getString(prefs, Prefs.KEY_BASE_URL, "");
        final String baseUrl = baseUrlTmp.trim().isEmpty() ? provider.defaultBaseUrl : baseUrlTmp;
        String modelTmp = Prefs.getString(prefs, Prefs.KEY_MODEL, "");
        final String model = modelTmp.trim().isEmpty() && !provider.models.isEmpty()
                ? provider.models.get(0) : modelTmp;
        final String apiKey = Prefs.getString(prefs, Prefs.KEY_API_KEY, "");
        final float temperature = Prefs.getFloat(prefs, Prefs.KEY_TEMPERATURE, 0.2f);
        final int maxTokens = Prefs.getInt(prefs, Prefs.KEY_MAX_TOKENS, 8192);
        final int timeoutSec = Prefs.getInt(prefs, Prefs.KEY_TIMEOUT_SEC, 120);
        final String systemPrompt = Prefs.getString(prefs, Prefs.KEY_SYSTEM_PROMPT, Prefs.DEFAULT_SYSTEM_PROMPT);
        final String protocol;
        if ("custom".equals(providerId)) {
            protocol = Prefs.getString(prefs, Prefs.KEY_PROTOCOL, ProviderRegistry.Protocol.OPENAI);
        } else {
            protocol = provider.protocol;
        }

        // ---------- 下拉数据 ----------
        final List<String> providerNames = ProviderRegistry.allNames();
        final List<String> providerIds = ProviderRegistry.allIds();
        int providerIndexTmp = providerIds.indexOf(providerId);
        final int providerIndex = providerIndexTmp < 0 ? 0 : providerIndexTmp;
        int scopeDefault = hasSelection ? SCOPE_SELECTED : SCOPE_FULL;

        // Spinner 当前选中项（SDK 的 Spinner 通过 onItemSelected 回调通知，用数组捕获）
        final int[] scopeSel = {scopeDefault};
        final int[] providerSel = {providerIndex};
        // 注意：局部变量作用域从声明点开始，builder 链里的 lambda 不能引用其后声明的变量。
        // 因此用一个提前声明的数组容器保存 modelEdit 引用，build 之后赋值。
        final PluginEditText[] modelEditRef = new PluginEditText[1];

        // ---------- 构建视图 ----------
        // 注意：Spinner 的 onItemSelected 在对话框显示时会触发一次，
        // 因此所有控件引用必须在 show() 之前取得，回调里才能安全操作控件。
        PluginView view = pluginUI.buildVerticalLayout()
                .paddingTop(pluginUI.dialogPaddingVertical() / 2)
                // 指令输入
                .addEditBox("instruction")
                .hint(context.getString("dialog_instruction_hint"))
                .textSize(13)
                .minLines(2).maxLines(5)
                .softWrap(PluginEditText.SOFT_WRAP_KEEP_WORD)
                // 作用范围 + 服务商
                .addHorizontalLayout().children(row -> row
                        .addSpinner("scope")
                        .items(Arrays.asList(
                                context.getString("dialog_scope_full"),
                                context.getString("dialog_scope_selected"),
                                context.getString("dialog_scope_cursor")))
                        .selection(scopeDefault)
                        .onItemSelected((spinner, position) -> scopeSel[0] = position)
                        .addSpinner("provider")
                        .items(providerNames)
                        .selection(providerIndex)
                        .onItemSelected((spinner, position) -> {
                            // 先记录选中
                            int previous = providerSel[0];
                            providerSel[0] = position;
                            // 切换服务商时自动带出该服务商第一个常用模型（用户自定义过的模型不覆盖）
                            PluginEditText modelEditView = modelEditRef[0];
                            if (modelEditView != null) {
                                String current = modelEditView.getText().toString().trim();
                                List<String> models = ProviderRegistry.findById(providerIds.get(position)).models;
                                List<String> oldModels = ProviderRegistry.findById(providerIds.get(previous)).models;
                                boolean shouldFill = models != null && !models.isEmpty()
                                        && !current.isEmpty()
                                        && oldModels != null && oldModels.contains(current);
                                if (shouldFill) {
                                    modelEditView.setText(models.get(0));
                                }
                            }
                        })
                )
                // 模型 + 设置
                .addHorizontalLayout().children(row -> row
                        .addEditText("model").width(0).layoutWeight(1)
                        .text(model).hint(context.getString("dialog_model_hint"))
                        .addButton("settings").text(context.getString("dialog_settings_btn"))
                        .addButton("about").text(context.getString("dialog_about_btn"))
                )
                // 状态栏
                .addTextView("status").textSize(12).textColor(pluginUI.colorTextSecondary())
                .text(context.getString("dialog_status_idle") + "  " + baseUrl)
                // 操作按钮
                .addHorizontalLayout().children(row -> row
                        .addButton("send").text(context.getString("dialog_send_btn"))
                        .width(0).layoutWeight(1)
                        .addButton("stop").text(context.getString("dialog_stop_btn"))
                        .width(0).layoutWeight(1).enable(false)
                )
                // 结果区
                .addTextView("resultLabel").text(context.getString("dialog_result_label"))
                .textSize(12).textColor(pluginUI.colorTextSecondary())
                .addEditBox("result").textSize(12).minLines(6).maxLines(12)
                .softWrap(PluginEditText.SOFT_WRAP_KEEP_WORD)
                .build();

        // 控件引用（必须在 show 之前取得）
        final PluginEditText instruction = view.requireViewById("instruction");
        final PluginEditText modelEdit = view.requireViewById("model");
        modelEditRef[0] = modelEdit; // 供 builder 里的 onItemSelected lambda 使用
        final PluginButton settingsBtn = view.requireViewById("settings");
        final PluginButton aboutBtn = view.requireViewById("about");
        final PluginTextView status = view.requireViewById("status");
        final PluginButton sendBtn = view.requireViewById("send");
        final PluginButton stopBtn = view.requireViewById("stop");
        final PluginEditText resultBox = view.requireViewById("result");

        // ---------- 对话框（需要先 show 才能 getXxxButton） ----------
        final PluginDialog dialog = pluginUI.buildDialog()
                .setTitle("{plugin_name}")
                .setView(view)
                // 按钮 listener 统一传 null 后手动接管，避免自动关闭
                .setPositiveButton(context.getString("dialog_apply_btn"), null)
                .setNegativeButton(context.getString("dialog_close_btn"), null)
                .setNeutralButton(context.getString("dialog_copy_btn"), null)
                .show();

        final PluginButton applyBtn = dialog.getPositiveButton();
        final PluginButton closeBtn = dialog.getNegativeButton();
        final PluginButton copyBtn = dialog.getNeutralButton();
        applyBtn.setEnabled(false);
        copyBtn.setEnabled(false);

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        // 本次请求 tokens 用量 [输入, 输出]，-1 表示未知（服务商未返回 usage）
        final long[] lastTokens = new long[]{-1, -1};
        // 输出是否被 max_tokens 截断（思考型模型常见）
        final boolean[] truncated = {false};
        // 本次请求思考内容累计长度（不进入结果）
        final long[] thinkingLen = new long[]{0};
        final StringBuilder resultText = new StringBuilder();

        // ---------- 发送 ----------
        sendBtn.setOnClickListener(v -> {
            String instructionText = instruction.getText().toString().trim();
            String modelText = modelEdit.getText().toString().trim();
            int scope = scopeSel[0];

            if (instructionText.isEmpty()) {
                status.setText(context.getString("dialog_status_no_instruction"));
                return;
            }
            if (modelText.isEmpty()) {
                status.setText(context.getString("dialog_status_no_model"));
                return;
            }
            if (baseUrl.trim().isEmpty()) {
                status.setText(context.getString("dialog_status_err", "未配置接口地址，请先在设置中填写"));
                return;
            }
            boolean needKey = !(providerId.equals("ollama")
                    || baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")
                    || baseUrl.contains("192.168.") || baseUrl.contains("10."));
            if (needKey && apiKey.trim().isEmpty()) {
                status.setText(context.getString("dialog_status_no_key"));
                return;
            }
            if (scope == SCOPE_SELECTED && !hasSelection) {
                status.setText(context.getString("dialog_status_need_selection"));
                return;
            }
            // 整个文件模式：过大保护（防止超模型上下文/传输过慢）
            int fileLen = editor.length();
            if (scope == SCOPE_FULL && fileLen > MAX_FULL_CHARS) {
                status.setText(context.getString("dialog_status_file_too_large", fileLen));
                return;
            }
            // 全文只在需要时读取（对话框打开时不再预读）
            String fullText = (scope == SCOPE_FULL || scope == SCOPE_CURSOR)
                    ? editor.subText(0, fileLen) : "";

            // 构造消息
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system(systemPrompt));
            messages.add(ChatMessage.user(buildUserPrompt(scope, instructionText, selectedText,
                    filePath, fileName, fullText, cursorPos)));

            // 创建客户端（按协议选择实现）
            final AiClient client = AiClient.Factory.create(
                    protocol, baseUrl, apiKey, modelText, temperature, maxTokens, timeoutSec);

            // 界面状态
            cancelled.set(false);
            lastTokens[0] = -1;
            lastTokens[1] = -1;
            thinkingLen[0] = 0;
            truncated[0] = false;
            resultText.setLength(0);
            resultBox.setText("");
            applyBtn.setEnabled(false);
            copyBtn.setEnabled(false);
            sendBtn.setEnabled(false);
            stopBtn.setEnabled(true);
            if (scope == SCOPE_FULL) {
                status.setText(context.getString("dialog_status_uploading_full", fullText.length()));
            } else {
                status.setText(context.getString("dialog_status_sending"));
            }

            new Thread(() -> {
                client.chat(messages, new AiClient.Callback() {
                    @Override
                    public void onDelta(@NonNull String deltaText) {
                        // UI 更新必须切回主线程（SDK 的 setEnabled/setText 内部有线程检查）
                        ThreadUtil.runOnUiThread(() -> {
                            resultText.append(deltaText);
                            resultBox.setText(resultText);
                            // 关键：把光标移到末尾，让视图滚动跟随输出，
                            // 否则 setText 全量刷新会把光标/滚动重置到顶部，看不到下方内容
                            moveCursorToEnd(resultBox);
                        });
                    }

                    @Override
                    public void onUsage(long promptTokens, long completionTokens) {
                        lastTokens[0] = promptTokens;
                        lastTokens[1] = completionTokens;
                    }

                    @Override
                    public void onReasoning(@NonNull String reasoningText) {
                        // 思考型模型（OpenAI 兼容）：思考内容不写入结果，仅累计并在状态栏提示进度
                        if (reasoningText != null && reasoningText.length() > 0) {
                            thinkingLen[0] += reasoningText.length();
                            ThreadUtil.runOnUiThread(() ->
                                    status.setText(context.getString("dialog_status_thinking", thinkingLen[0])));
                        }
                    }

                    @Override
                    public void onTruncated() {
                        truncated[0] = true;
                    }

                    @Override
                    public void onThinking(@NonNull String thinkingText) {
                        thinkingLen[0] += thinkingText.length();
                        ThreadUtil.runOnUiThread(() ->
                                status.setText(context.getString("dialog_status_thinking", thinkingLen[0])));
                    }

                    @Override
                    public void onFinished(@NonNull String ignored) {
                        ThreadUtil.runOnUiThread(() -> {
                            if (cancelled.get()) {
                                status.setText(context.getString("dialog_status_stopped", resultText.length()));
                            } else if (resultText.length() == 0 && thinkingLen[0] > 0) {
                                // 模型只返回了思考内容（如 max_tokens 太小、请求被截断）
                                status.setText(context.getString("dialog_status_only_thinking"));
                            } else {
                                String done = context.getString("dialog_status_done", resultText.length());
                                if (truncated[0]) {
                                    done += " · " + context.getString("dialog_status_truncated_hint");
                                }
                                if (Prefs.getBoolean(prefs, Prefs.KEY_SHOW_TOKENS, true)
                                        && lastTokens[0] >= 0 && lastTokens[1] >= 0) {
                                    done += " · " + context.getString("dialog_tokens_usage",
                                            lastTokens[0], lastTokens[1]);
                                }
                                status.setText(done);
                            }
                            boolean hasText = resultText.length() > 0;
                            applyBtn.setEnabled(hasText);
                            copyBtn.setEnabled(hasText);
                            sendBtn.setEnabled(true);
                            stopBtn.setEnabled(false);
                        });
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        ThreadUtil.runOnUiThread(() -> {
                            sendBtn.setEnabled(true);
                            stopBtn.setEnabled(false);
                            applyBtn.setEnabled(false);
                            copyBtn.setEnabled(resultText.length() > 0);
                            status.setText(context.getString("dialog_status_err", message));
                        });
                    }
                }, cancelled);
            }).start();
        });

        // ---------- 停止 ----------
        stopBtn.setOnClickListener(v -> {
            cancelled.set(true);
            sendBtn.setEnabled(true);
            stopBtn.setEnabled(false);
        });

        // ---------- 复制（读取预览框实况内容，含手动修改） ----------
        copyBtn.setOnClickListener(v -> {
            String text = resultBox.getText() == null ? "" : resultBox.getText().toString();
            if (!text.isEmpty()) {
                context.setClipboardText(text);
                pluginUI.showToast(context.getString("dialog_copy_btn") + ": " + text.length());
            }
        });

        // ---------- 应用（读取预览框实况内容：AI 输出可在应用前手动修改调整） ----------
        applyBtn.setOnClickListener(v -> {
            String result = sanitizeResult(resultBox.getText() == null ? "" : resultBox.getText().toString());
            if (result.isEmpty()) {
                status.setText(context.getString("dialog_status_no_result"));
                return;
            }
            int scope = scopeSel[0];
            try {
                if (scope == SCOPE_FULL) {
                    editor.replaceText(0, editor.length(), result);
                } else if (scope == SCOPE_SELECTED) {
                    if (selStart >= 0 && selEnd >= selStart) {
                        editor.replaceText(selStart, selEnd, result);
                    }
                } else {
                    editor.insertText(cursorPos, result);
                }
                editor.save(null);
                pluginUI.showToast(context.getString("dialog_status_saved"));
                dialog.dismiss();
            } catch (Throwable t) {
                status.setText(context.getString("dialog_status_err", t.toString()));
            }
        });

        // ---------- 关闭 ----------
        closeBtn.setOnClickListener(v -> dialog.dismiss());

        // ---------- 设置入口 ----------
        settingsBtn.setOnClickListener(v -> {
            cancelled.set(true);
            dialog.dismiss();
            pluginUI.showPreference(null); // null = 主设置界面
        });

        // ---------- 关于/社区入口 ----------
        aboutBtn.setOnClickListener(v -> {
            cancelled.set(true);
            dialog.dismiss();
            AboutDialog.show(pluginUI);
        });

        instruction.requestFocusAndShowIME();
    }

    /* ---------- 提示词构造 ---------- */

    private static String buildUserPrompt(int scope, String instruction, String selectedText,
                                          String filePath, String fileName, String fullText,
                                          int cursorPos) {
        String path = TextUtils.isEmpty(filePath) ? (TextUtils.isEmpty(fileName) ? "未知" : fileName) : filePath;
        String name = TextUtils.isEmpty(fileName) ? "未知" : fileName;
        if (scope == SCOPE_FULL) {
            return "你在帮助用户编辑一个文本文件。请严格按照用户【指令】修改，只输出修改后的完整文件内容。\n"
                    + "不要输出任何解释、说明、前言或代码块围栏（不要用 ``` 包裹）。"
                    + "如果无需修改，请原样输出文件内容。\n\n"
                    + "文件路径：" + path + "\n"
                    + "文件名：" + name + "\n\n"
                    + "【指令】\n" + instruction + "\n\n"
                    + "【当前文件内容】\n" + fullText;
        }
        if (scope == SCOPE_SELECTED) {
            return "你在帮助用户编辑文件中的一段文本。请严格按照用户【指令】修改下面给出的选中内容，"
                    + "只输出修改后的内容（用于替换选中部分），不要输出任何解释、说明或代码块围栏。\n\n"
                    + "【指令】\n" + instruction + "\n\n"
                    + "【选中内容】\n" + selectedText;
        }
        // SCOPE_CURSOR：插入点前后各取一小段作为上下文
        int len = fullText.length();
        String before = len == 0 ? "" : fullText.substring(Math.max(0, cursorPos - 150), Math.min(len, cursorPos));
        String after = len == 0 ? "" : fullText.substring(Math.min(len, cursorPos), Math.min(len, cursorPos + 100));
        return "你在帮助用户向文件中插入内容。请严格按照用户【指令】生成要插入的文本，"
                + "只输出需要插入的内容本身，不要输出任何解释、说明或代码块围栏。\n\n"
                + "【指令】\n" + instruction + "\n\n"
                + "【插入点之前的内容（节选）】\n" + before + "\n\n"
                + "【插入点之后的内容（节选）】\n" + after;
    }

    /* ---------- 工具 ---------- */

    /** 移动光标到末尾（附带滚动跟随），保证流式输出最下方内容可见 */
    private static void moveCursorToEnd(PluginEditText editText) {
        try {
            int len = editText.getText().length();
            editText.setSelection(len);
        } catch (Throwable ignored) {
            // 极端情况下忽略，不影响主流程
        }
    }

    /** 去掉 AI 输出首尾包裹的 ```代码块围栏（仅当整个输出首尾都是围栏时） */
    static String sanitizeResult(String text) {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("```")) {
            int idx = t.indexOf('\n');
            if (idx >= 0) {
                t = t.substring(idx + 1);
            } else {
                t = t.substring(3);
            }
        }
        if (t.endsWith("```")) {
            t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }
}