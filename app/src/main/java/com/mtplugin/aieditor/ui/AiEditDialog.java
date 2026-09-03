package com.mtplugin.aieditor.ui;

import androidx.annotation.NonNull;

import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
import bin.mt.plugin.api.ui.PluginEditTextWatcher;
import bin.mt.plugin.api.ui.PluginSpinner;
import bin.mt.plugin.api.ui.PluginTextView;
import bin.mt.plugin.api.ui.PluginUI;
import bin.mt.plugin.api.ui.PluginView;
import bin.mt.plugin.api.ui.builder.PluginUIBuilder;
import bin.mt.plugin.api.ui.dialog.PluginDialog;
import bin.mt.plugin.api.util.ThreadUtil;

/**
 * AI 编辑对话框。
 *
 * 界面：指令输入框 + 作用范围 + 服务商/配置方案下拉 + 模型输入 + 设置/关于入口
 *       + 发送/停止 + 流式输出预览 + 复制/导出/应用/关闭。
 *
 * 功能：三种作用范围（全文/选中/光标）、多套配置方案切换、多轮连续对话、
 *       流式输出（可停止，内容保留）、tokens 用量显示、思考型模型进度提示、
 *       token 预估、应用前可手动修改输出。
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

    /** 多轮对话历史上限（条数，user+assistant 各算一条；12 = 最近 6 轮） */
    private static final int MAX_HISTORY = 12;

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

        // ---------- 全局参数（方案不包含的部分） ----------
        final float temperature = Prefs.getFloat(prefs, Prefs.KEY_TEMPERATURE, 0.2f);
        final int maxTokens = Prefs.getInt(prefs, Prefs.KEY_MAX_TOKENS, 8192);
        final int timeoutSec = Prefs.getInt(prefs, Prefs.KEY_TIMEOUT_SEC, 120);
        final String systemPrompt = Prefs.getString(prefs, Prefs.KEY_SYSTEM_PROMPT, Prefs.DEFAULT_SYSTEM_PROMPT);

        // ---------- 配置方案（决定 服务商/Key/地址/模型） ----------
        final String activeProfile = Prefs.getString(prefs, Prefs.KEY_ACTIVE_PROFILE, "p1");
        String[] initCfg = readProfile(prefs, activeProfile); // {providerId, apiKey, baseUrl, model}
        final String initProviderId = initCfg[0];
        final ProviderRegistry.Provider initProvider = ProviderRegistry.findById(initProviderId);
        String initModel = initCfg[3].trim();
        if (initModel.isEmpty() && initProvider != null && !initProvider.models.isEmpty()) {
            initModel = initProvider.models.get(0);
        }
        final String initModelText = initModel;
        final String initBaseUrl = initCfg[2].trim().isEmpty() && initProvider != null
                ? initProvider.defaultBaseUrl : initCfg[2];

        // ---------- 下拉数据 ----------
        int scopeDefault = hasSelection ? SCOPE_SELECTED : SCOPE_FULL;
        int profileDefault = "p2".equals(activeProfile) ? 1 : ("p3".equals(activeProfile) ? 2 : 0);

        // Spinner 当前选中项（SDK 的 Spinner 通过 onItemSelected 回调通知，用数组捕获）
        final int[] scopeSel = {scopeDefault};
        final int[] profileSel = {profileDefault};
        // 当前生效模型（主界面已无模型输入框，模型在「配置」弹窗中设置）
        final String[] currentModel = {initModelText};

        // ---------- 构建视图 ----------
        // 注意：Spinner 的 onItemSelected 在对话框显示时会触发一次，
        // 因此所有控件引用必须在 show() 之前取得，回调里才能安全操作控件。
        PluginView view = pluginUI.buildVerticalLayout()
                .paddingTop(pluginUI.dialogPaddingVertical() / 2)
                // 指令输入（独占一行，完整多行显示，不被水平布局裁剪）
                .addEditBox("instruction")
                .hint(context.getString("dialog_instruction_hint"))
                .textSize(13)
                .minLines(2).maxLines(5)
                .softWrap(PluginEditText.SOFT_WRAP_KEEP_WORD)
                // 作用范围 + 发送按钮（发送按钮紧邻输入框，操作连贯）
                .addHorizontalLayout().children(row -> row
                        .addSpinner("scope")
                        .items(Arrays.asList(
                                context.getString("dialog_scope_full"),
                                context.getString("dialog_scope_selected"),
                                context.getString("dialog_scope_cursor")))
                        .selection(scopeDefault)
                        .layoutWeight(1)
                        .onItemSelected((spinner, position) -> scopeSel[0] = position)
                        .addButton("send").text(context.getString("dialog_send_btn"))
                        .textColor(0xFFFFFFFF)
                        .backgroundColor(0xFF4CAF50)
                )
                // 状态栏
                .addTextView("status").textSize(12).textColor(pluginUI.colorTextSecondary())
                .text(context.getString("dialog_status_idle") + "  " + initBaseUrl)
                // 操作按钮（停止 / 配置）
                .addHorizontalLayout().children(row -> row
                        .addButton("stop").text(context.getString("dialog_stop_btn"))
                        .width(0).layoutWeight(1).enable(false)
                        .addButton("config").text(context.getString("dialog_config_btn"))
                        .width(0).layoutWeight(1)
                )
                // 结果区
                .addTextView("resultLabel").text(context.getString("dialog_result_label"))
                .textSize(12).textColor(pluginUI.colorTextSecondary())
                .addEditBox("result").textSize(12).minLines(6).maxLines(12)
                .softWrap(PluginEditText.SOFT_WRAP_KEEP_WORD)
                // Plan / Act / 执行方案（AI 输出框下方）
                .addHorizontalLayout().children(row -> row
                        .addButton("plan").text(context.getString("dialog_mode_plan"))
                        .width(0).layoutWeight(1)
                        .addButton("act").text(context.getString("dialog_mode_act"))
                        .width(0).layoutWeight(1)
                        .addButton("execute").text(context.getString("dialog_execute_btn"))
                        .width(0).layoutWeight(1)
                )
                // 分割线，分隔输出区与底部操作
                .addView().height(1).widthMatchParent().marginTopDp(6).marginBottomDp(6)
                .backgroundColor(pluginUI.colorDivider())
                // 复制 / 导出 / 关闭 / 应用（视图最底部一行，无边框文字链接，留白；应用在最右）
                .addHorizontalLayout().children(row -> row
                        .addTextView("copy").text(context.getString("dialog_copy_btn"))
                        .textSize(13).layoutWeight(1)
                        .addTextView("export").text(context.getString("dialog_export_btn"))
                        .textSize(13).layoutWeight(1)
                        .addTextView("close").text(context.getString("dialog_close_btn"))
                        .textSize(13).layoutWeight(1)
                        .addTextView("apply").text(context.getString("dialog_apply_btn"))
                        .textSize(13).layoutWeight(1)
                )
                .paddingTopDp(10).paddingBottomDp(14)
                .build();

        // 控件引用（必须在 show 之前取得）
        final PluginEditText instruction = view.requireViewById("instruction");
        final PluginButton configBtn = view.requireViewById("config");
        final PluginTextView status = view.requireViewById("status");
        final PluginButton sendBtn = view.requireViewById("send");
        final PluginButton stopBtn = view.requireViewById("stop");

        final PluginEditText resultBox = view.requireViewById("result");
        final PluginButton planBtn = view.requireViewById("plan");
        final PluginButton actBtn = view.requireViewById("act");
        final PluginButton executeBtn = view.requireViewById("execute");

        // ---------- 对话框（所有操作按钮均在视图底部文字链接行） ----------
        final PluginDialog dialog = pluginUI.buildDialog()
                .setTitle("{plugin_name}")
                .setView(view)
                .show();

        final PluginTextView applyBtn = view.requireViewById("apply");
        final PluginTextView closeBtn = view.requireViewById("close");
        final PluginTextView copyBtn = view.requireViewById("copy");
        final PluginTextView exportBtn = view.requireViewById("export");
        applyBtn.setEnabled(false);
        copyBtn.setEnabled(false);

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        // 本次请求 tokens 用量 [输入, 输出]，-1 表示未知（服务商未返回 usage）
        final long[] lastTokens = new long[]{-1, -1};
        // 输出是否被 max_tokens 截断（思考型模型常见）
        final boolean[] truncated = {false};
        // 本次请求思考内容累计长度（不进入结果）
        final long[] thinkingLen = new long[]{0};
        // 多轮对话历史（user/assistant 对；不含 system）
        final ArrayList<ChatMessage> history = new ArrayList<>();
        final StringBuilder resultText = new StringBuilder();
        // Plan/Act 双模式：plan=只读分析，act=可执行写入（默认 plan，记忆上次选择）
        final String[] mode = {Prefs.getString(prefs, Prefs.KEY_MODE, Prefs.MODE_PLAN)};

        // 输出框编辑控制：Plan 模式永远只读；Act 模式输出未完成时也只读
        final boolean[] streaming = {false};           // 是否正在请求/输出
        final boolean[] editingAllowed = {true};       // 当前是否允许用户修改输出框
        final boolean[] programmaticEdit = {false};    // 程序性 setText 标志（避免误拦截）
        final Runnable refreshEditingState = () ->
                editingAllowed[0] = Prefs.MODE_ACT.equals(mode[0]) && !streaming[0];

        // 拦截非法编辑：Plan 模式 / Act 输出未完成时，用户改输出框会被还原并提示
        resultBox.addTextChangedListener(new PluginEditTextWatcher.Simple() {
            @Override
            public void afterTextChanged(PluginEditText editText, Editable s) {
                if (programmaticEdit[0]) {
                    return;
                }
                if (!editingAllowed[0]) {
                    programmaticEdit[0] = true;
                    editText.setText(resultText);
                    moveCursorToEnd(editText);
                    programmaticEdit[0] = false;
                    pluginUI.showToast(context.getString(
                            Prefs.MODE_ACT.equals(mode[0])
                                    ? "dialog_status_output_pending"
                                    : "dialog_status_plan_readonly_edit"));
                }
            }
        });

        // ---------- 发送（发送按钮与「执行方案」按钮共用） ----------
        // executePending：执行方案模式标记——跳过指令校验、不追加 userMsg（续接指令已注入历史）
        final boolean[] executePending = {false};
        final Runnable sendRunnable = () -> {
            boolean executeMode = executePending[0];
            executePending[0] = false;
            String instructionText = executeMode ? "" : instruction.getText().toString().trim();
            String modelText = currentModel[0];
            int scope = scopeSel[0];

            if (instructionText.isEmpty() && !executeMode) {
                status.setText(context.getString("dialog_status_no_instruction"));
                return;
            }
            if (modelText.isEmpty()) {
                status.setText(context.getString("dialog_status_no_model"));
                return;
            }

            // 按当前配置方案读取连接配置
            String[] cfg = readProfile(prefs, profileId(profileSel[0]));
            final String providerId = cfg[0];
            final String apiKey = cfg[1];
            final ProviderRegistry.Provider provider = ProviderRegistry.findById(providerId);
            final String baseUrl = cfg[2].trim().isEmpty() && provider != null
                    ? provider.defaultBaseUrl : cfg[2].trim();
            final String protocol = "custom".equals(providerId)
                    ? Prefs.getString(prefs, Prefs.KEY_PROTOCOL, ProviderRegistry.Protocol.OPENAI)
                    : provider.protocol;

            if (baseUrl.isEmpty()) {
                status.setText(context.getString("dialog_status_err", "未配置接口地址，请先在设置中填写"));
                return;
            }
            boolean needKey = !("ollama".equals(providerId)
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
            // 按需读取，避免多传文件内容（节约输入 tokens）：
            // 整个文件=读全文；光标插入=只读光标附近窗口；选中文本=不读全文
            String fullText = "";
            int promptCursor = cursorPos;
            if (scope == SCOPE_FULL) {
                fullText = editor.subText(0, fileLen);
            } else if (scope == SCOPE_CURSOR) {
                int winStart = Math.max(0, cursorPos - 150);
                int winEnd = Math.min(fileLen, cursorPos + 100);
                fullText = editor.subText(winStart, winEnd);
                promptCursor = cursorPos - winStart; // 转为窗口内相对位置
            }

            // 构造消息（多轮：system 只在第一轮出现，之后携带历史）
            List<ChatMessage> messages = new ArrayList<>();
            if (history.isEmpty()) {
                messages.add(ChatMessage.system(buildSystemPrompt(systemPrompt, mode[0])));
            }
            messages.addAll(history);
            final ChatMessage userMsg;
            if (executeMode) {
                // 执行方案模式：续接指令已注入历史，无需再构造 userMsg
                userMsg = null;
            } else {
                userMsg = ChatMessage.user(buildUserPrompt(scope, instructionText, selectedText,
                        filePath, fileName, fullText, promptCursor, mode[0]));
                messages.add(userMsg);
            }

            long estTokens = estimateTokens(messages);

            // 创建客户端（按协议选择实现）
            final AiClient client = AiClient.Factory.create(
                    protocol, baseUrl, apiKey, modelText, temperature, maxTokens, timeoutSec);

            // 界面状态
            cancelled.set(false);
            lastTokens[0] = -1;
            lastTokens[1] = -1;
            thinkingLen[0] = 0;
            truncated[0] = false;
            streaming[0] = true;
            refreshEditingState.run();
            resultText.setLength(0);
            programmaticEdit[0] = true;
            resultBox.setText("");
            programmaticEdit[0] = false;
            applyBtn.setEnabled(false);
            applyBtn.setTextColor(0xFF808080);
            copyBtn.setEnabled(false);
            sendBtn.setEnabled(false);
            stopBtn.setEnabled(true);
            if (scope == SCOPE_FULL) {
                status.setText(context.getString("dialog_status_uploading_full", fullText.length(), estTokens));
            } else {
                status.setText(context.getString("dialog_status_sending_tokens", estTokens));
            }

            new Thread(() -> {
                client.chat(messages, new AiClient.Callback() {
                    @Override
                    public void onDelta(@NonNull String deltaText) {
                        // UI 更新必须切回主线程（SDK 的 setEnabled/setText 内部有线程检查）
                        ThreadUtil.runOnUiThread(() -> {
                            resultText.append(deltaText);
                            programmaticEdit[0] = true;
                            resultBox.setText(resultText);
                            programmaticEdit[0] = false;
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
                            boolean success = !cancelled.get() && resultText.length() > 0;
                            if (success) {
                                // 记录到历史（多轮对话），保留最近 6 轮
                                // 执行方案模式 userMsg 为 null（续接指令已在历史中），不重复记录
                                if (userMsg != null) {
                                    history.add(userMsg);
                                }
                                history.add(ChatMessage.assistant(resultText.toString()));
                                // AI 完成后自动清空指令输入框：下次直接输入即自动继续对话（保留历史）
                                instruction.setText("");
                                while (history.size() > MAX_HISTORY) {
                                    history.remove(0);
                                    if (!history.isEmpty() && history.get(0) != null) {
                                        history.remove(0);
                                    }
                                }
                            }
                            if (cancelled.get()) {
                                // 停止后内容保留（可复制/继续修改后应用）
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
                            streaming[0] = false;
                            refreshEditingState.run();
                            boolean hasText = resultText.length() > 0;
                            boolean actReady = hasText && Prefs.MODE_ACT.equals(mode[0]);
                            applyBtn.setEnabled(actReady);
                            // Act 输出结束后「应用」高亮为绿色
                            applyBtn.setTextColor(actReady ? 0xFF4CAF50 : 0xFF808080);
                            copyBtn.setEnabled(hasText);
                            sendBtn.setEnabled(true);
                            stopBtn.setEnabled(false);
                        });
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        ThreadUtil.runOnUiThread(() -> {
                            streaming[0] = false;
                            refreshEditingState.run();
                            sendBtn.setEnabled(true);
                            stopBtn.setEnabled(false);
                            applyBtn.setEnabled(false);
                            copyBtn.setEnabled(resultText.length() > 0);
                            status.setText(context.getString("dialog_status_err", message));
                        });
                    }
                }, cancelled);
            }).start();
        };
        sendBtn.setOnClickListener(v -> sendRunnable.run());

        // ---------- 停止（内容保留） ----------
        stopBtn.setOnClickListener(v -> {
            cancelled.set(true);
            streaming[0] = false;
            refreshEditingState.run();
            sendBtn.setEnabled(true);
            stopBtn.setEnabled(false);
        });

        // ---------- Plan / Act 模式切换 ----------
        final Runnable refreshModeButtons = () -> {
            boolean act = Prefs.MODE_ACT.equals(mode[0]);
            refreshEditingState.run();
            planBtn.setEnabled(act);
            actBtn.setEnabled(!act);
            executeBtn.setEnabled(!act); // Act 模式下「执行方案」不可点
            if (!act) {
                applyBtn.setEnabled(false); // Plan 只读：禁止应用
                applyBtn.setTextColor(0xFF808080);
            } else if (resultText.length() > 0) {
                applyBtn.setEnabled(true);
                applyBtn.setTextColor(0xFF4CAF50);
            }
        };
        planBtn.setOnClickListener(v -> {
            if (Prefs.MODE_ACT.equals(mode[0])) { // 当前 Act -> 切 Plan
                // 仅切换本次会话模式，不写回设置（默认启动模式由设置界面决定）
                mode[0] = Prefs.MODE_PLAN;
                if (!history.isEmpty()) {
                    // 注入续接指令，无缝继承上下文
                    history.add(ChatMessage.user("[模式已切换为 Plan] 请停止写入，只进行分析、方案设计和代码审查，不要输出修改后的完整文件内容。"));
                }
                status.setText(context.getString("dialog_status_mode_plan"));
            }
            refreshModeButtons.run();
        });
        actBtn.setOnClickListener(v -> {
            if (!Prefs.MODE_ACT.equals(mode[0])) { // 当前 Plan -> 切 Act
                // 仅切换本次会话模式，不写回设置（默认启动模式由设置界面决定）
                mode[0] = Prefs.MODE_ACT;
                if (!history.isEmpty()) {
                    // 注入续接指令：无缝继承 Plan 阶段的分析结论
                    history.add(ChatMessage.user("[模式已切换为 Act] 用户已批准方案，请开始执行：基于之前的分析结论，直接输出修改后的完整文件内容，不要输出方案或解释。"));
                }
                status.setText(context.getString("dialog_status_mode_act"));
            }
            refreshModeButtons.run();
        });

        // ---------- 执行方案：切换 Act 并按历史自动执行（Act 模式下按钮禁用） ----------
        executeBtn.setOnClickListener(v -> {
            if (Prefs.MODE_ACT.equals(mode[0])) {
                return; // Act 模式下按钮不可点
            }
            if (history.isEmpty()) {
                status.setText(context.getString("dialog_status_no_plan"));
                return;
            }
            // 切换为 Act 模式（仅本次会话，不写回设置）
            mode[0] = Prefs.MODE_ACT;
            // 注入续接指令：让 AI 基于历史分析结论直接输出修改后的完整内容
            history.add(ChatMessage.user("[模式已切换为 Act] 用户已批准方案，请开始执行：基于之前的分析结论，直接输出修改后的完整文件内容，不要输出方案或解释。"));
            refreshModeButtons.run();
            // 触发一次发送（执行方案模式：不校验输入框、不追加新 userMsg）
            executePending[0] = true;
            sendRunnable.run();
        });
        refreshModeButtons.run(); // 初始按已保存的模式设置按钮状态

        // ---------- 复制（读取预览框实况内容，含手动修改） ----------
        copyBtn.setOnClickListener(v -> {
            String text = resultBox.getText() == null ? "" : resultBox.getText().toString();
            if (!text.isEmpty()) {
                context.setClipboardText(text);
                pluginUI.showToast(context.getString("dialog_copy_btn") + ": " + text.length());
            }
        });

        // ---------- 导出对话（底部文字链接，打开导出弹窗：目录 + 文件名格式） ----------
        exportBtn.setOnClickListener(v -> {
            showExportDialog(pluginUI, context, prefs, fileName, mode[0], currentModel[0], history, resultBox);
        });

        // ---------- 应用（读取预览框实况内容：AI 输出可在应用前手动修改调整） ----------
        applyBtn.setOnClickListener(v -> {
            if (!Prefs.MODE_ACT.equals(mode[0])) {
                // Plan 模式只读：禁止写入文件
                status.setText(context.getString("dialog_status_plan_readonly"));
                return;
            }
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

        // ---------- 配置入口（模型/方案弹窗） ----------
        configBtn.setOnClickListener(v -> {
            cancelled.set(true);
            showConfigDialog(pluginUI, context, prefs, dialog, () -> {
                // 配置保存后：刷新主界面当前方案与模型
                String active = Prefs.getString(prefs, Prefs.KEY_ACTIVE_PROFILE, "p1");
                profileSel[0] = "p2".equals(active) ? 1 : ("p3".equals(active) ? 2 : 0);
                String[] cfg = readProfile(prefs, active);
                currentModel[0] = cfg[3].trim();
                if (currentModel[0].isEmpty()) {
                    ProviderRegistry.Provider pp = ProviderRegistry.findById(cfg[0]);
                    if (pp != null && !pp.models.isEmpty()) {
                        currentModel[0] = pp.models.get(0);
                    }
                }
            });
        });

        // 注意：设置与关于入口已移入「配置」弹窗；打开时不自动弹出输入法
    }

    /* ---------- 配置弹窗 ---------- */

    /** 打开「配置」弹窗：选择配置方案 + 服务商 + 模型，内含设置/关于入口 */
    private static void showConfigDialog(@NonNull PluginUI pluginUI,
                                         @NonNull PluginContext context,
                                         @NonNull SharedPreferences prefs,
                                         @NonNull PluginDialog mainDialog,
                                         @NonNull Runnable onSaved) {
        final List<String> providerNames = ProviderRegistry.allNames();
        final List<String> providerIds = ProviderRegistry.allIds();
        final String activeProfile = Prefs.getString(prefs, Prefs.KEY_ACTIVE_PROFILE, "p1");
        int profileDefault = "p2".equals(activeProfile) ? 1 : ("p3".equals(activeProfile) ? 2 : 0);
        String[] initCfg = readProfile(prefs, activeProfile);
        int providerDefault = providerIds.indexOf(initCfg[0]);
        if (providerDefault < 0) {
            providerDefault = 0;
        }

        final int[] profileSel = {profileDefault};
        final int[] providerSel = {providerDefault};
        final PluginSpinner[] providerSpinnerRef = new PluginSpinner[1];
        final PluginEditText[] modelRef = new PluginEditText[1];

        PluginView view = pluginUI.buildVerticalLayout()
                .paddingTop(pluginUI.dialogPaddingVertical() / 2)
                // 配置方案
                .addTextView("profileLabel").text(context.getString("dialog_profile_label")).textSize(12)
                .addSpinner("profile")
                .items(Arrays.asList(
                        context.getString("dialog_profile_p1") + " · " + providerName(readProfile(prefs, "p1")[0]),
                        context.getString("dialog_profile_p2") + " · " + providerName(readProfile(prefs, "p2")[0]),
                        context.getString("dialog_profile_p3") + " · " + providerName(readProfile(prefs, "p3")[0])))
                .selection(profileDefault)
                .onItemSelected((spinner, position) -> {
                    profileSel[0] = position;
                    // 切换方案：带出该方案服务商与模型
                    String[] cfg = readProfile(prefs, profileId(position));
                    int pi = providerIds.indexOf(cfg[0]);
                    if (pi >= 0) {
                        providerSel[0] = pi;
                        PluginSpinner pv = providerSpinnerRef[0];
                        if (pv != null) {
                            try {
                                pv.setSelection(pi);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    PluginEditText me = modelRef[0];
                    if (me != null) {
                        String m = cfg[3].trim();
                        if (m.isEmpty()) {
                            ProviderRegistry.Provider pp = ProviderRegistry.findById(cfg[0]);
                            if (pp != null && !pp.models.isEmpty()) {
                                m = pp.models.get(0);
                            }
                        }
                        if (!m.isEmpty()) {
                            me.setText(m);
                        }
                    }
                })
                // 服务商
                .addTextView("providerLabel").text(context.getString("dialog_provider_label")).textSize(12)
                .addSpinner("provider")
                .items(providerNames)
                .selection(providerDefault)
                .onItemSelected((spinner, position) -> {
                    int previous = providerSel[0];
                    providerSel[0] = position;
                    // 切换服务商自动带出默认模型（用户自定义过的模型不覆盖）
                    PluginEditText me = modelRef[0];
                    if (me != null) {
                        String current = me.getText().toString().trim();
                        List<String> models = ProviderRegistry.findById(providerIds.get(position)).models;
                        List<String> oldModels = ProviderRegistry.findById(providerIds.get(previous)).models;
                        boolean shouldFill = models != null && !models.isEmpty()
                                && !current.isEmpty()
                                && oldModels != null && oldModels.contains(current);
                        if (shouldFill) {
                            me.setText(models.get(0));
                        }
                    }
                })
                // 模型
                .addTextView("modelLabel").text(context.getString("dialog_model_label")).textSize(12)
                .addEditText("model").text(initCfg[3]).hint(context.getString("dialog_model_hint"))
                // 设置 / 关于
                .addHorizontalLayout().children(row -> row
                        .addButton("cfgSettings").text(context.getString("dialog_settings_btn"))
                        .width(0).layoutWeight(1)
                        .addButton("cfgAbout").text(context.getString("dialog_about_btn"))
                        .width(0).layoutWeight(1)
                )
                .build();

        final PluginEditText modelEdit = view.requireViewById("model");
        modelRef[0] = modelEdit;
        providerSpinnerRef[0] = view.requireViewById("provider");

        final PluginDialog cfgDialog = pluginUI.buildDialog()
                .setTitle(context.getString("dialog_config_btn"))
                .setView(view)
                .setPositiveButton(context.getString("dialog_ok_btn"), (dialog, which) -> {
                    // 保存：写入当前方案的服务商与模型 + 切换默认方案
                    String profile = profileId(profileSel[0]);
                    Prefs.putString(prefs, Prefs.KEY_ACTIVE_PROFILE, profile);
                    Prefs.putString(prefs, profileKeyPrefix(profile) + "provider",
                            providerIds.get(providerSel[0]));
                    String m = modelEdit.getText() == null ? "" : modelEdit.getText().toString().trim();
                    Prefs.putString(prefs, profileKeyPrefix(profile) + "model", m);
                    onSaved.run();
                    dialog.dismiss();
                })
                .setNegativeButton(context.getString("dialog_cancel_btn"), (dialog, which) -> dialog.dismiss())
                .show();

        // 配置弹窗内的 设置/关于 入口
        view.requireViewById("cfgSettings").setOnClickListener(v -> {
            cfgDialog.dismiss();
            mainDialog.dismiss();
            pluginUI.showPreference(null); // null = 主设置界面
        });
        view.requireViewById("cfgAbout").setOnClickListener(v -> {
            cfgDialog.dismiss();
            mainDialog.dismiss();
            AboutDialog.show(pluginUI);
        });
    }

    /* ---------- 配置方案 ---------- */

    private static String providerName(String providerId) {
        try {
            return ProviderRegistry.findById(providerId).name;
        } catch (Throwable ignored) {
            return providerId;
        }
    }

    private static String profileId(int index) {
        return index == 1 ? "p2" : (index == 2 ? "p3" : "p1");
    }

    private static String profileKeyPrefix(String profile) {
        // p1 复用旧全局 key（兼容旧版本用户配置）；p2/p3 使用 p2_ / p3_ 前缀
        return "p1".equals(profile) ? "" : profile + "_";
    }

    /** 读取方案配置：返回 [providerId, apiKey, baseUrl, model] */
    private static String[] readProfile(SharedPreferences prefs, String profile) {
        String prefix = profileKeyPrefix(profile);
        return new String[]{
                Prefs.getString(prefs, prefix + "provider", "deepseek"),
                Prefs.getString(prefs, prefix + "api_key", ""),
                Prefs.getString(prefs, prefix + "base_url", ""),
                Prefs.getString(prefs, prefix + "model", "")
        };
    }

    /* ---------- 提示词构造 ---------- */

    private static String buildUserPrompt(int scope, String instruction, String selectedText,
                                          String filePath, String fileName, String fullText,
                                          int cursorPos, String mode) {
        String path = TextUtils.isEmpty(filePath) ? (TextUtils.isEmpty(fileName) ? "未知" : fileName) : filePath;
        String name = TextUtils.isEmpty(fileName) ? "未知" : fileName;
        if (scope == SCOPE_FULL) {
            if (Prefs.MODE_PLAN.equals(mode)) {
                // Plan 模式：只分析、出方案，不要输出原文
                return "你在帮助用户分析一个文本文件（当前为 Plan 只读模式）。"
                        + "请根据用户【指令】给出问题分析与修改方案，说明涉及的位置与改法。"
                        + "不要输出原文，也不要输出修改后的完整文件内容，不要输出任何代码块围栏。\n\n"
                        + "文件路径：" + path + "\n"
                        + "文件名：" + name + "\n\n"
                        + "【指令】\n" + instruction + "\n\n"
                        + "【当前文件内容】\n" + fullText;
            }
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
        // SCOPE_CURSOR：fullText 为光标附近窗口，cursorPos 为窗口内相对偏移
        int len = fullText.length();
        int pos = Math.max(0, Math.min(cursorPos, len));
        String before = fullText.substring(0, pos);
        String after = fullText.substring(pos, len);
        return "你在帮助用户向文件中插入内容。请严格按照用户【指令】生成要插入的文本，"
                + "只输出需要插入的内容本身，不要输出任何解释、说明或代码块围栏。\n\n"
                + "【指令】\n" + instruction + "\n\n"
                + "【插入点之前的内容（节选）】\n" + before + "\n\n"
                + "【插入点之后的内容（节选）】\n" + after;
    }

    /* ---------- Plan / Act ---------- */

    /** 按当前模式动态构建 System Prompt：Plan=只读分析，Act=执行写入 */
    private static String buildSystemPrompt(String base, String mode) {
        if (Prefs.MODE_ACT.equals(mode)) {
            return base + "\n\n【当前模式：Act（执行）】用户已批准方案，请开始执行："
                    + "直接输出修改后的完整内容，严格按之前 Plan 的分析结论执行，不要输出方案或解释。";
        }
        return base + "\n\n【当前模式：Plan（只读分析）】你只能进行分析、方案设计和代码审查，"
                + "不要输出原文，也不要输出修改后的完整文件内容（文件不会被修改）。"
                + "请只输出问题分析与修改方案（含涉及位置与改法），不要包含任何原文内容。";
    }

    /* ---------- 工具 ---------- */

    /** 粗略估算 tokens：中文/全角按 1 token/字符，其余按 4 字符/token */
    private static long estimateTokens(List<ChatMessage> messages) {
        long total = 0;
        for (ChatMessage m : messages) {
            if (m != null && m.content != null) {
                total += estimateTokens(m.content);
            }
        }
        return total;
    }

    private static long estimateTokens(String s) {
        long cjk = 0;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 0x2E80 && c <= 0x9FFF) {
                cjk++;
            }
        }
        return cjk + (len - cjk + 3) / 4;
    }

    /** 移动光标到末尾（附带滚动跟随），保证流式输出最下方内容可见 */
    private static void moveCursorToEnd(PluginEditText editText) {
        try {
            int len = editText.getText().length();
            editText.setSelection(len);
        } catch (Throwable ignored) {
            // 极端情况下忽略，不影响主流程
        }
    }

    /** Plan 模式引导语兜底：仅当输出包含可执行修改方案且未提示切换 Act 时，追加引导句 */


    /** 打开导出弹窗：可指定目录 + 文件名格式（{title}/{date}/{time}），目录自动记忆 */
    private static void showExportDialog(@NonNull PluginUI pluginUI,
                                         @NonNull PluginContext context,
                                         @NonNull SharedPreferences prefs,
                                         @NonNull String title,
                                         @NonNull String mode,
                                         @NonNull String model,
                                         @NonNull ArrayList<ChatMessage> history,
                                         @NonNull PluginEditText resultBox) {
        String savedDir = Prefs.getString(prefs, Prefs.KEY_EXPORT_DIR, "");
        String savedName = Prefs.getString(prefs, Prefs.KEY_EXPORT_NAME, Prefs.DEFAULT_EXPORT_NAME);

        PluginView view = pluginUI.buildVerticalLayout()
                .paddingTop(pluginUI.dialogPaddingVertical() / 2)
                // 目录
                .addTextView("dirLabel").text(context.getString("dialog_export_dir_label")).textSize(12)
                .addEditText("exportDir").text(savedDir)
                .hint(context.getFilesDir() == null ? "" : context.getFilesDir().getAbsolutePath())
                // 选择目录（常用目录快捷选择，仍可手动输入）
                .addTextView("pickDir").text(context.getString("dialog_export_pick"))
                .textSize(13).textColor(0xFF4CAF50).paddingTopDp(2)
                // 文件名格式
                .addTextView("nameLabel").text(context.getString("dialog_export_name_label")).textSize(12)
                .addEditText("exportName").text(savedName)
                .hint(Prefs.DEFAULT_EXPORT_NAME)
                // 占位符说明
                .addTextView("phHint").text(context.getString("dialog_export_ph")).textSize(12)
                .textColor(pluginUI.colorTextSecondary())
                // 实时预览
                .addTextView("preview").textSize(12).textColor(pluginUI.colorTextSecondary())
                .build();

        final PluginEditText dirEdit = view.requireViewById("exportDir");
        final PluginEditText nameEdit = view.requireViewById("exportName");
        final PluginTextView preview = view.requireViewById("preview");
        final PluginTextView pickDir = view.requireViewById("pickDir");
        pickDir.setOnClickListener(v -> showDirPicker(pluginUI, context, prefs, dirEdit));

        final Runnable updatePreview = () -> {
            String dir = dirEdit.getText() == null ? "" : dirEdit.getText().toString().trim();
            if (dir.isEmpty()) {
                File fd = context.getFilesDir();
                dir = fd == null ? "" : fd.getAbsolutePath();
            }
            String name = resolveExportName(nameEdit.getText() == null ? "" : nameEdit.getText().toString(), title);
            preview.setText(context.getString("dialog_export_preview") + "  " + dir + "/" + name);
        };
        PluginEditTextWatcher.Simple watcher = new PluginEditTextWatcher.Simple() {
            @Override
            public void afterTextChanged(PluginEditText editText, Editable s) {
                updatePreview.run();
            }
        };
        dirEdit.addTextChangedListener(watcher);
        nameEdit.addTextChangedListener(watcher);
        updatePreview.run();

        pluginUI.buildDialog()
                .setTitle(context.getString("dialog_export_title"))
                .setView(view)
                .setPositiveButton(context.getString("dialog_ok_btn"), (dialog, which) -> {
                    String dir = dirEdit.getText() == null ? "" : dirEdit.getText().toString().trim();
                    if (dir.isEmpty()) {
                        File fd = context.getFilesDir();
                        if (fd == null) {
                            pluginUI.showToast(context.getString("dialog_export_fail") + "no dir");
                            return;
                        }
                        dir = fd.getAbsolutePath();
                    }
                    File dirFile = new File(dir);
                    if (!dirFile.exists() && !dirFile.mkdirs()) {
                        pluginUI.showToast(context.getString("dialog_export_fail") + dir);
                        return;
                    }
                    String name = resolveExportName(
                            nameEdit.getText() == null ? "" : nameEdit.getText().toString(), title);
                    // 记忆目录与文件名格式
                    Prefs.putString(prefs, Prefs.KEY_EXPORT_DIR, dirFile.getAbsolutePath());
                    Prefs.putString(prefs, Prefs.KEY_EXPORT_NAME,
                            nameEdit.getText() == null ? "" : nameEdit.getText().toString().trim());
                    exportConversationTo(pluginUI, dirFile, name, mode, model, history, resultBox);
                    dialog.dismiss();
                })
                .setNegativeButton(context.getString("dialog_cancel_btn"), (dialog, which) -> dialog.dismiss())
                .show();
    }

    /** 目录快捷选择：常用目录列表，点击填入（保留手动输入） */
    private static void showDirPicker(@NonNull PluginUI pluginUI,
                                      @NonNull PluginContext context,
                                      @NonNull SharedPreferences prefs,
                                      @NonNull PluginEditText dirEdit) {
        String saved = Prefs.getString(prefs, Prefs.KEY_EXPORT_DIR, "");
        File filesDir = context.getFilesDir();
        String[] dirs = new String[]{
                "/sdcard/Download",
                "/sdcard/Documents",
                "/sdcard/AIedit",
                saved == null || saved.isEmpty() ? (filesDir == null ? "/sdcard" : filesDir.getAbsolutePath()) : saved
        };
        // 去重
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String d : dirs) {
            if (d != null && !d.isEmpty()) {
                set.add(d);
            }
        }
        // 动态构建选择列表（builder 链式追加，不能往已构建的 PluginView 上添加控件）
        // 用数组持有弹窗引用（show 之前无法取得），列表项点击后设置目录并自动关闭
        final PluginDialog[] pickDialog = new PluginDialog[1];
        PluginUIBuilder builder = pluginUI.buildVerticalLayout()
                .paddingTop(pluginUI.dialogPaddingVertical() / 2);
        for (String d : set) {
            final String dir = d;
            builder = builder.addTextView("dir_" + Math.abs(dir.hashCode()))
                    .text(dir).textSize(14).paddingTopDp(6).paddingBottomDp(6)
                    .onClick(v -> {
                        dirEdit.setText(dir);
                        if (pickDialog[0] != null) {
                            pickDialog[0].dismiss();
                        }
                    });
        }
        pickDialog[0] = pluginUI.buildDialog()
                .setTitle(context.getString("dialog_export_pick"))
                .setView(builder.build())
                .setNegativeButton(context.getString("dialog_cancel_btn"), (dialog, which) -> dialog.dismiss())
                .show();
    }

    /** 按占位符解析文件名：{title}/{date}/{time}，并清理非法字符 */
    private static String resolveExportName(String format, String title) {
        String s = format == null ? "" : format.trim();
        if (s.isEmpty()) {
            s = Prefs.DEFAULT_EXPORT_NAME;
        }
        String t = title == null || title.trim().isEmpty() ? "conversation" : title.trim();
        if (t.contains(".")) {
            t = t.substring(0, t.lastIndexOf('.'));
        }
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String time = new SimpleDateFormat("HHmmss", Locale.getDefault()).format(new Date());
        s = s.replace("{title}", t).replace("{date}", date).replace("{time}", time);
        return s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** 导出会话到指定文件 */
    private static void exportConversationTo(@NonNull PluginUI pluginUI,
                                             @NonNull File dir,
                                             @NonNull String name,
                                             @NonNull String mode,
                                             @NonNull String model,
                                             @NonNull ArrayList<ChatMessage> history,
                                             @NonNull PluginEditText resultBox) {
        try {
            File file = new File(dir, name);
            StringBuilder sb = new StringBuilder();
            sb.append("===== AI文本编辑 会话记录 =====\n");
            sb.append("时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date())).append("\n");
            sb.append("模式：").append(Prefs.MODE_ACT.equals(mode) ? "Act（执行）" : "Plan（只读）").append("\n");
            sb.append("模型：").append(model == null ? "" : model).append("\n\n");
            for (ChatMessage m : history) {
                if (m == null || m.content == null) {
                    continue;
                }
                sb.append(ChatMessage.ROLE_USER.equals(m.role) ? "[用户] " : "[AI] ")
                        .append(m.content).append("\n\n");
            }
            String cur = resultBox.getText() == null ? "" : resultBox.getText().toString();
            if (cur.length() > 0) {
                sb.append("[AI 当前输出] ").append(cur).append("\n");
            }
            BufferedWriter w = new BufferedWriter(new FileWriter(file));
            w.write(sb.toString());
            w.close();
            pluginUI.showToast(pluginUI.getContext().getString("dialog_export_done")
                    + "\n" + file.getAbsolutePath());
        } catch (Throwable t) {
            pluginUI.showToast(pluginUI.getContext().getString("dialog_export_fail") + t.getMessage());
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
