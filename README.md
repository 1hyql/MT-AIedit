# MT AI文本编辑（MT AI Editor Plugin）

一个运行在 **MT 管理器**（v2.26.3+，插件系统 v3）里的 AI 插件：

> 在 MT 的**文本编辑器**中打开文件 → 顶部工具栏「**编辑**」菜单 → 点「**AI 编辑**」
> → 输入操作指令 → 发送（**流式**输出）→ 预览确认 → 一键**应用**到文件。

支持**自定义任意常见 AI 的 API 接口**：OpenAI 兼容协议（覆盖 OpenAI / DeepSeek /
通义千问 / Kimi / 智谱 GLM / SiliconFlow / Ollama 本地 / 任意中转站）+ Anthropic
Claude + Google Gemini，全部可在设置界面手动配置 Base URL / Key / 模型 / 参数。

---

## 1. 功能清单

### 1.1 编辑器入口（TextEditorToolMenu）
- 在文本编辑器顶部工具栏「编辑」分组新增「AI 编辑」菜单项（带 Material 图标）。
- 只读模式下自动隐藏；点击后弹出 AI 编辑对话框。

### 1.2 AI 编辑对话框
| 控件 | 说明 |
|---|---|
| 操作指令（多行输入框） | 告诉 AI 要做什么，如「给这个函数加注释」「翻译成英文」 |
| 作用范围下拉 | **整个文件** / **选中文本** / **光标处插入** |
| 服务商下拉 | 10 个预设（OpenAI/DeepSeek/通义/Kimi/GLM/SiliconFlow/Ollama/Claude/Gemini/自定义） |
| 模型输入框 | 默认取设置中的模型；切换服务商时自动带出该服务商的常用模型 |
| 设置按钮 | 直接跳到插件设置界面 |
| 发送 / 停止 | 发送后进入流式输出，可随时停止 |
| 状态栏 | 显示接口地址、请求中 / 完成 N 字符 / 错误信息 |
| AI 输出预览（可编辑） | SSE 流式实时写入，应用前可手动修改 |
| 应用 / 复制 / 关闭 | 预览确认后应用或复制到剪贴板 |

### 1.3 结果应用
- **整个文件**：`replaceText(0, length, result)` 全文替换 + 自动 `save()`；
- **选中文本**：`replaceText(selStart, selEnd, result)` 替换选中部分；
- **光标处插入**：`insertText(cursorPos, result)`；
- 自动剥离 AI 输出首尾的 ```代码块围栏``` 后再应用。

### 1.4 设置界面（PluginPreference，插件管理页点「设置」进入）
- 服务商（列表）→ 影响模型预设与协议自动判断；
- 接口协议（自定义服务商时生效）：OpenAI 兼容 / Anthropic / Gemini；
- API Key、接口地址、默认模型；
- Temperature、最大输出 Tokens、请求超时（秒）；
- System Prompt；
- 常见服务商接口地址速查表、使用说明。

### 1.5 支持的协议与流式解析
| 协议 | 请求端点 | 流式场格式 |
|---|---|---|
| OpenAI 兼容 | `POST {base}/chat/completions`（`stream:true`） | SSE `data: {...}`，取 `choices[0].delta.content` |
| Anthropic | `POST {base}/messages`（`x-api-key` + `anthropic-version`） | SSE `event: content_block_delta`，取 `delta.text` |
| Gemini | `POST {base}/models/{model}:streamGenerateContent?alt=sse` | SSE `data: {...}`，取 `candidates[0].content.parts[].text` |

- 每个客户端同时**兼容非流式响应**（服务端直接返回完整 JSON 也能正确解析）；
- 出错时自动从 `{"error":{"message":...}}` 等常见结构提取可读错误信息。

### 1.6 其它
- 中英文语言包：`assets/strings.mtl` + `assets/strings-zh-CN.mtl`
- 所有配置存于插件专用 SharedPreferences（卸载即清空，Key 不明文外传）
- 完全离线可打包：输出单文件 `.mtp` 插件安装包

---

## 2. 项目结构

```
mt-ai-editor/
├── settings.gradle            # 包含 maven.mt2.cn 插件仓库
├── build.gradle               # 根构建
├── gradle/
│   ├── libs.versions.toml     # AGP 8.13.2 / mt-plugin 3.0.0 / desugar 2.1.5
│   └── wrapper/               # Gradle 8.13 wrapper（来自官方 Demo）
└── app/
    ├── build.gradle           # mtPlugin{} 插件配置（pluginID / interfaces / mainPreference）
    ├── proguard-rules.pro     # 保留 OkHttp / bin.mt.json / 插件接口类
    └── src/main/
        ├── AndroidManifest.xml            # 官方模板：<manifest />（由构建插件自动生成）
        ├── assets/strings.mtl             # 英文语言包
        ├── assets/strings-zh-CN.mtl       # 中文语言包
        ├── resources/icon.png             # 插件图标（可替换）
        └── java/com/mtplugin/aieditor/
            ├── AiEditToolMenu.java        # ★ 文本编辑器工具菜单入口（TextEditorToolMenu）
            ├── ProviderRegistry.java      # 服务商预设（ID/名称/协议/默认地址/常用模型）
            ├── Prefs.java                 # 配置读写封装（设置界面与对话框共用 Key）
            ├── ChatMessage.java           # 对话消息模型
            ├── api/
            │   ├── AiClient.java          # 客户端接口 + 工厂 + 公共工具
            │   ├── OpenAiCompatibleClient.java  # OpenAI 兼容协议
            │   ├── ClaudeClient.java           # Anthropic Messages API
            │   └── GeminiClient.java           # Google Gemini generateContent
            ├── ui/
            │   └── AiEditDialog.java      # ★ AI 编辑对话框（PluginDialog + PluginView）
            └── settings/
                └── AiEditPreference.java  # ★ 插件设置界面
```

---

## 3. 构建

### 前置条件
- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17+
- 设备装有 **MT 管理器 2.26.3+** 且为 **VIP**（插件系统 v3 仅 VIP 可用）
- Android SDK（compileSdk 36）

### 免电脑：GitHub Actions 云构建（推荐，无需本机装任何开发环境）
工程内置了 `.github/workflows/build-mtp.yml`，把项目推到 GitHub 即可在网站服务器上完成编译：

1. 注册/登录 GitHub（手机浏览器即可）→ 右上角 **+** → **New repository**（公开或私有都行）→ Create；
2. 进入仓库 → **Add file → Upload files** → 把本工程所有文件/文件夹拖进去 → Commit；
3. 打开仓库 **Actions** 页签，第一次会自动跑一次构建；以后改完代码后也可在 Actions 里点 **Run workflow** 手动构建；
4. 构建完成后进该次运行 → **Artifacts → mtai-editor-mtp** 下载 `.mtp`；
5. 把 `.mtp` 传到手机 → MT 管理器 → 工具 → 插件管理 → 安装。

> 好处：改插件名/图标/文案/模型列表都可以直接在 GitHub 网页上编辑，保存后自动重新打包，全程不碰命令行。
> 注意：GitHub Actions 免费额度对个人项目足够；只想自用也可选私有仓库。

### 本机编译打包（有电脑时）
```bash
# 在项目根目录
./gradlew app:packageReleaseMtp
```
打包成功后在 `app/build/outputs/mt-plugin/` 下找到 `*.mtp`，
把 `.mtp` 文件传到手机，在 MT 管理器 → 侧边栏 → 工具 → **插件管理** 中安装。

### 通过 Android Studio 一键调试（官方方式）
1. Android Studio 打开本项目，选择 `app` 模块，直接点 Run；
2. 会安装一个名为 *MT Plugin Pusher* 的小应用并自动拉起 MT 的插件安装界面；
3. 点「安装」即可。（此方式生成的包带 testOnly 标记，仅本机测试用。）

> 说明：`pushTarget = "auto"` 会自动把 .mtp 推给设备上的正式版/共存版 MT。

---

## 4. 使用

### 第一步：安装后先配置 AI（必做，否则发不出去）
1. MT → 侧边栏 → 工具 → **插件管理** → 找到「AI文本编辑」→ 点「**设置**」
2. 在设置页完成三件事：
   - **服务商**：选一个预设（如 `DeepSeek`），或选「自定义」；
   - **API Key**：填你的密钥（必填，Ollama 本地可留空）；
   - **默认模型**：确认模型名（如 `deepseek-chat`；切换服务商时对话框会自动带出常用模型）；
   - （接口地址可以留空，会自动用所选服务商的默认地址）

### 第二步：在文件中打开「AI 编辑」对话框
1. 用 MT 打开任意文本文件（代码 / txt / md 等）；
2. 点编辑器**顶部的「铅笔」按钮**（编辑工具菜单）；
3. 在弹出的菜单里找到「**AI 编辑**」：
   - 找到 → 直接点击，对话框弹出；
   - **找不到** → 先在「**自定义**」分组里把「AI 编辑」拖到常用（可用）位置，下次点铅笔即可见。

### 第三步：让 AI 编辑文件
1. 在对话框里选作用范围：**整个文件 / 选中文本（先选中文字）/ 光标处插入**；
2. 输入指令，如「给这段代码加注释」「翻译成英文」；
3. 点「**发送**」→ 结果**流式**显示在预览框（可随时「停止」）；
4. **AI 输出可以直接在预览框里修改调整**，确认无误后点「**应用**」→ 内容写回文件并自动保存；也可「复制」到剪贴板。

### 温馨提示
- **作用范围按需选，能省 tokens**：三个模式会把不同内容发给 AI——「整个文件」发送全文（最费 token）、「选中文本」只发送选中的片段（最省）、「光标处插入」发送光标前后的小段上下文。大文件建议只选中要改的部分用「选中文本」；
- **应用前可改**：AI 返回的内容在预览框里是**可编辑**的，先手动调整好再点「应用」；
- 首次使用建议先在设置里把「服务商 + Key + 模型」配好，对话框里一般只需要输入指令即可；
- 「**关于**」按钮（AI 编辑对话框内，或设置页「关于与社区」入口）可打开社区信息（官网、QQ频道、群号）。

### 常见服务商速查
| 服务商 | 接口地址 | 模型示例 |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | `gpt-4o`、`gpt-4o-mini` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat`、`deepseek-reasoner` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus`、`qwen-max` |
| Kimi | `https://api.moonshot.cn/v1` | `moonshot-v1-32k` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-plus`、`glm-4-flash` |
| SiliconFlow | `https://api.siliconflow.cn/v1` | `deepseek-ai/DeepSeek-V3` |
| Claude | `https://api.anthropic.com/v1` | `claude-3-7-sonnet-latest` |
| Gemini | `https://generativelanguage.googleapis.com/v1beta` | `gemini-2.5-flash` |
| Ollama（本地） | `http://<电脑局域网IP>:11434/v1`（可不填 Key） | `qwen2.5`、`llama3.1` |

> 任意 OpenAI 兼容网关/中转：服务商选「自定义」，接口协议选「OpenAI 兼容」，地址填网关地址。

---

## 5. 设计说明 / 关键技术点

### 5.1 MT 插件系统 v3（SDK 3）
- 基于官方模板：`bin.mt.plugin` Gradle 插件 `3.0.0`（仓库 `https://maven.mt2.cn`）；
- `AndroidManifest.xml` 只需 `<manifest />`，插件声明全部由 `mtPlugin {}` 生成；
- 对外接口在 `mtPlugin.interfaces` 注册：`TextEditorToolMenu` 类型会被自动识别为**编辑器工具菜单**；
- 主设置界面在 `mtPlugin.mainPreference` 注册；
- **OkHttp（3.12.13）、`bin.mt.json`（mnJson 风格，API 与 org.json 不同：`getString(name, default)` / `JSON.parse` / `JSONValue.isXxx()/asXxx()`）、androidx 注解**均由 `bin.mt.plugin:api:3.0.0` 以传递依赖形式自动提供，无需在 build.gradle 里额外声明（与官方 translator-deepseek 模块一致）。

### 5.2 线程模型
- 网络请求在 `new Thread(...)` 后台线程执行（同步 OkHttp + SSE 逐行读取）；
- MT 插件 UI 对象允许在后台线程更新（官方 Demo 中 `LoadingDialog.setSecondaryMessage` 即从 worker 线程调用）；
- 停止按钮通过 `AtomicBoolean` 标记中断，客户端在每次读行前检查。

### 5.3 配置一致性
- 设置界面与对话框共用 `Prefs` 中的常量 Key 操作同一个 `SharedPreferences`；
- 全面覆盖：基地址/模型/Key/温度/最大Tokens/超时/System Prompt 均可自定义。

### 5.4 边界情况处理
- 非流式响应兜底解析；响应 JSON 解析失败给出可读错误；
- 局域网地址（localhost/127.0.0.1/192.168./10.）自动豁免 API Key 必填检查；
- 只读编辑器不显示菜单；未选中文本时「选中文本」范围给出提示；
- 中文路径/中文文件名不受影响（仅作为提示词内容传给模型）。

---

## 6. 常见问题 FAQ

**Q1：安装时报“插件版本过低/无法安装”？**
MT 插件安装要求 MT 管理器 ≥ 2.26.3 且 VIP；正式版与共存版（canary）是两套签名体系，
请确认 pushTarget / 安装到的是同一个 MT。

**Q2：为什么我搜不到菜单项？**
`interfaces` 必须包含 `com.mtplugin.aieditor.AiEditToolMenu` 且重新打包安装；
只读模式下菜单自动隐藏属正常行为。

**Q3：发送后报错 “HTTP 401/404 …”？**
401 一般是 Key 错误或没有权限；404 一般是接口地址多了/少了 `/v1` 或模型名不对。
对照第 4 节速查表检查地址与模型名，或看服务商后台的模型 ID。

**Q4：怎么替换插件图标？**
替换 `app/src/main/resources/icon.png` 后重新打包。

**Q5：菜单图标想换一个？**
改 `AiEditToolMenu.icon()` 里的 Material 图标名，可用图标见 https://mt2.cn/icons 。

**Q6：API Key 安全吗？**
仅保存在本机插件专用 SharedPreferences 里，卸载即删除；发送请求时仅随 Header/URL 传给所配置的接口地址。

**Q7：大文件会很费 token？**
「整个文件」模式会把全文作为上下文。大文件建议：只选中要修改的片段选「选中文本」，
或用分页查看/拆分后再处理。

---

## 7. 相关资源

- MT 插件 v3 官方文档：https://mt2.cn/guide/plugin-v3/plugin-intro.html
- 官方 Demo：https://gitee.com/L-JINBIN/mt-plugin-v3-demo
- MT 插件 Maven 仓库：https://maven.mt2.cn
- MT 官方论坛：https://bbs.binmt.cc

## 8. 免责声明

本插件仅用于合法的个人文件编辑；请遵守所用 AI 服务商的使用条款与内容合规要求。