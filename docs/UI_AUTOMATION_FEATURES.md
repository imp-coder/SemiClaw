# Operit UI 自动化功能文档

## 概述

Operit 实现了完整的 Android UI 自动化系统，支持多权限级别的 UI 操作，并集成了 AI 驱动的自动化代理（AutoGLM），能够理解屏幕内容并自主完成任务。

---

## 一、功能场景展示

### 1. 基础 UI 操作

| 操作类型 | 支持动作 | 应用场景 |
|---------|---------|---------|
| **点击** | 单击、长按 | 打开应用、点击按钮、选择选项 |
| **滑动** | 上下左右滑动、自定义轨迹 | 滚动列表、切换页面、下拉刷新 |
| **输入** | 文本输入、清空输入框 | 搜索框输入、聊天消息、表单填写 |
| **按键** | 返回、Home、最近任务、通知栏 | 系统导航、快捷操作 |

### 2. UI 信息获取

| 功能 | 说明 | 应用场景 |
|------|------|---------|
| **截图** | 全屏截图、指定区域截图 | 屏幕内容分析、AI 视觉理解 |
| **UI 层次结构** | 获取当前界面的控件树 | 元素定位、自动化测试 |
| **窗口信息** | 获取当前包名、Activity 名 | 状态判断、应用识别 |

### 3. AutoGLM 自动化代理

AI 驱动的自动化代理，能够自主理解屏幕并完成任务：

```
用户指令: "帮我打开微信发消息给张三"

执行流程:
┌─────────────────────────────────────────────────────────────┐
│ Step 1: 截图 → AI 分析 → 发现桌面 → 执行 Launch(微信)       │
│ Step 2: 截图 → AI 分析 → 微信已打开 → 执行 Tap(联系人)      │
│ Step 3: 截图 → AI 分析 → 聊天列表 → 执行 Tap(张三)          │
│ Step 4: 截图 → AI 分析 → 聊天界面 → 执行 Type(消息内容)     │
│ Step 5: 截图 → AI 分析 → 已输入 → 执行 Tap(发送按钮)        │
│ Step 6: 截图 → AI 分析 → 任务完成 → finish()                │
└─────────────────────────────────────────────────────────────┘
```

**支持的任务类型**：

| 任务类型 | 示例 |
|---------|------|
| 应用操作 | 打开应用、关闭应用、清理后台 |
| 信息查询 | 查看天气、搜索网页、查看新闻 |
| 社交互动 | 发送消息、回复评论、点赞 |
| 系统设置 | 开关 WiFi、调整亮度、设置闹钟 |
| 内容创作 | 写备忘录、发朋友圈、编辑文档 |

### 4. 虚拟屏幕支持

支持在虚拟显示器上执行自动化任务：

```
┌─────────────────────────────────────────────────────────────┐
│                        主屏幕                                │
│   ┌─────────────────────────────────────────────────────┐   │
│   │                                                     │   │
│   │              用户正常使用                            │   │
│   │              不受自动化干扰                          │   │
│   │                                                     │   │
│   └─────────────────────────────────────────────────────┘   │
│                                                             │
│   ┌─────────────────┐                                      │
│   │  虚拟屏幕窗口    │  ← AI 在后台执行任务                │
│   │  (可最小化)      │    用户可随时查看进度               │
│   └─────────────────┘                                      │
└─────────────────────────────────────────────────────────────┘
```

**优势**：
- 后台执行自动化，不影响前台使用
- 多任务并行：同时执行多个自动化任务
- 隐私保护：敏感操作在虚拟屏幕进行

### 5. 多平台搜索

支持多个搜索引擎和图片搜索：

| 搜索类型 | 支持平台 |
|---------|---------|
| 网页搜索 | 百度、必应、搜狗、夸克 |
| 图片搜索 | Bing Images、DuckDuckGo、Pexels、Pixabay、Wikimedia |

### 6. 工具包系统

支持用户自定义工具包脚本：

```javascript
// 示例：自定义搜索工具
async function search_baidu(query) {
    const url = `https://www.baidu.com/s?wd=${encodeURIComponent(query)}`;
    const response = await Tools.Net.visit(url);
    return response.content;
}
```

---

## 二、技术方案

### 1. 三通道 UI 自动化架构

根据设备权限自动选择最优实现方式：

```
┌─────────────────────────────────────────────────────────────┐
│                     AIToolHandler                           │
│                   (工具调用统一入口)                         │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                     ToolGetter                              │
│             (根据权限级别选择 UITools 实现)                  │
└──────────────────────────┬──────────────────────────────────┘
                           │
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│AccessibilityUI  │ │  DebuggerUI     │ │  RootUITools    │
│     Tools       │ │     Tools       │ │                 │
│  (无障碍服务)    │ │  (Shizuku/ADB)  │ │   (Root权限)    │
└────────┬────────┘ └────────┬────────┘ └────────┬────────┘
         │                   │                   │
         ▼                   ▼                   ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│ GestureDescription│ │  input 命令    │ │  su + input    │
│  (AIDL跨进程)    │ │  uiautomator   │ │  screencap     │
└─────────────────┘ └─────────────────┘ └─────────────────┘
```

| 权限级别 | 实现方式 | 能力范围 |
|---------|---------|---------|
| **ACCESSIBILITY** | 无障碍服务 + GestureDescription | 基础 UI 操作，无需 Root |
| **DEBUGGER** | Shizuku/ADB + input 命令 | 完整 UI 操作，多显示器支持 |
| **ROOT** | Root Shell + su 命令 | 完全控制，后台执行 |

### 2. 无障碍服务架构

采用独立进程架构，将无障碍服务放在独立的 APK 中：

```
主应用 (Operit)                    无障碍服务提供者 APK
┌──────────────────┐              ┌──────────────────┐
│ UIHierarchyManager│◄──AIDL────►│AccessibilityProvider│
│                  │              │    Service        │
│ AccessibilityUI  │              │                  │
│     Tools        │              │   ┌──────────────┐│
└──────────────────┘              │   │Accessibility ││
                                  │   │  Service Impl││
                                  │   └──────────────┘│
                                  └──────────────────┘
```

**优势**：
- **进程隔离**：无障碍服务崩溃不影响主应用
- **权限分离**：敏感操作在独立进程执行
- **模块化**：无障碍服务可独立更新

### 3. AIDL 接口定义

```kotlin
interface IAccessibilityProvider {
    String getUiHierarchy();                    // 获取 UI 层次结构
    boolean performClick(int x, int y);         // 执行点击
    boolean performLongPress(int x, int y);     // 执行长按
    boolean performSwipe(...);                  // 执行滑动
    boolean performGlobalAction(int actionId);  // 执行全局操作
    String findFocusedNodeId();                 // 查找焦点节点
    boolean setTextOnNode(String nodeId, String text); // 设置文本
    boolean takeScreenshot(String path, String format); // 截图
    boolean isAccessibilityServiceEnabled();    // 检查服务状态
    String getCurrentActivityName();            // 获取当前 Activity
}
```

### 4. Shell 命令映射

| 操作 | Shell 命令 |
|-----|-----------|
| 点击 | `input tap x y` |
| 长按 | `input swipe x y x y 800` |
| 滑动 | `input swipe x1 y1 x2 y2 duration` |
| 按键 | `input keyevent KEYCODE` |
| 文本输入 | 剪贴板 + `input keyevent KEYCODE_PASTE` |
| UI 层次 | `uiautomator dump` + `cat` |
| 截图 | `screencap -p` |

### 5. AI 代理架构

```
┌─────────────────────────────────────────────────────────────┐
│                    PhoneAgent                                │
│              (AI 驱动的自动化代理)                           │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐     │
│  │ AIService   │───►│ActionHandler│───►│UITools      │     │
│  │ (视觉语言模型)│    │ (动作解析)   │    │ (执行操作)   │     │
│  └─────────────┘    └─────────────┘    └─────────────┘     │
│         │                                      │            │
│         ▼                                      ▼            │
│  ┌─────────────┐                       ┌─────────────┐     │
│  │ 截图 → 图片  │                       │ 点击/滑动等  │     │
│  │ 屏幕理解     │                       │             │     │
│  └─────────────┘                       └─────────────┘     │
└─────────────────────────────────────────────────────────────┘
```

**工作流程**：
1. 截取当前屏幕
2. 发送给云端 VLM 模型分析
3. 解析 AI 返回的动作指令
4. 执行动作（Tap/Swipe/Type 等）
5. 检查是否完成，否则回到步骤 1

### 6. 网络搜索实现

使用 WebView 爬虫方式，而非官方 API：

```javascript
async function search_baidu(query) {
    const url = `https://www.baidu.com/s?wd=${encodeURIComponent(query)}`;
    const response = await Tools.Net.visit(url);  // WebView 访问
    return parseSearchResults(response.content);   // 解析结果
}
```

**优势**：无需 API Key，免费使用，支持多平台
**劣势**：可能被反爬拦截，依赖页面结构

---

## 三、后续完善点

### 1. 性能优化

#### 1.1 截图优化

| 优化项 | 当前状态 | 优化方案 | 预期提升 |
|--------|---------|---------|---------|
| 分辨率缩放 | 默认 100% | 降至 50-70% | 减少 75% 数据量 |
| 图片质量 | 默认 90% | 降至 60-70% | 减少 30% 文件大小 |
| 格式选择 | PNG/JPG | WebP 格式 | 更高压缩率 |

#### 1.2 延迟优化

| 延迟位置 | 当前值 | 优化方案 |
|---------|--------|---------|
| UI 隐藏延迟 | 200ms | 动态检测 UI 稳定状态 |
| 动作后延迟 | 500ms | 根据动作类型动态调整 |
| 启动后延迟 | 1000ms | 检测应用加载完成 |

#### 1.3 并行化优化

```kotlin
// 当前：串行执行
delay(200)        // 隐藏 UI
captureScreen()   // 截图
uploadImage()     // 上传
waitForAI()       // 等待 AI

// 优化：并行执行
async { hideUI() }           // UI 淡出
val cached = preCaptured()   // 使用预截图
uploadAndProcess(cached)     // 上传处理
```

### 2. 历史记录管理

#### 2.1 问题

当前每步都累积历史，导致 token 越来越多，影响速度和成本。

#### 2.2 优化方案

```kotlin
// 滑动窗口 + 保留首条
private fun trimHistory(maxSteps: Int = 5) {
    if (_contextHistory.size <= maxSteps * 2 + 2) return

    // 保留第一条用户消息（原始任务目标）
    val firstUserMessage = _contextHistory.firstOrNull { it.first == "user" }

    // 保留最近 N 轮对话
    val recentHistory = _contextHistory.takeLast(maxSteps * 2)

    _contextHistory.clear()
    firstUserMessage?.let { _contextHistory.add(it) }
    _contextHistory.addAll(recentHistory)
}
```

#### 2.3 按任务复杂度调整

| 任务复杂度 | 历史保留步数 | 说明 |
|-----------|-------------|------|
| 简单（<5步） | 3 步 | 快速完成任务 |
| 中等（5-10步） | 5 步 | 平衡速度和上下文 |
| 复杂（>10步） | 7 步 | 保持任务连贯性 |

### 3. 错误恢复机制

#### 3.1 当前问题

- 操作失败后无自动重试
- 异常情况无恢复策略
- 用户无法干预执行过程

#### 3.2 优化方案

```kotlin
// 自动重试机制
suspend fun executeWithRetry(
    action: ParsedAgentAction,
    maxRetries: Int = 3
): ActionExecResult {
    repeat(maxRetries) { attempt ->
        val result = executeAction(action)
        if (result.success) return result

        // 根据错误类型决定是否重试
        if (shouldRetry(result)) {
            delay(500 * (attempt + 1))  // 指数退避
        }
    }
    return fail("Max retries exceeded")
}

// 状态快照与恢复
data class AgentSnapshot(
    val stepCount: Int,
    val lastAction: ParsedAgentAction?,
    val contextHistory: List<Pair<String, String>>
)

// 支持从断点恢复
fun restoreFromSnapshot(snapshot: AgentSnapshot) { ... }
```

### 4. 智能等待机制

#### 4.1 当前问题

固定延迟无法适应不同设备和应用，低端设备可能不够，高端设备浪费时间。

#### 4.2 优化方案

```kotlin
// 检测 UI 稳定状态
suspend fun waitForUiStable(maxWaitMs: Long = 500) {
    var lastHash = 0
    var stableCount = 0

    while (stableCount < 3) {  // 连续 3 帧相同
        val currentHash = captureScreenHash()
        if (currentHash == lastHash) {
            stableCount++
        } else {
            stableCount = 0
        }
        lastHash = currentHash
        delay(50)
    }
}

// 检测元素出现
suspend fun waitForElement(selector: String, timeout: Long = 5000) {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < timeout) {
        if (findElement(selector) != null) return
        delay(100)
    }
    throw TimeoutException("Element not found: $selector")
}
```

### 5. 本地模型支持

#### 5.1 当前状态

- 依赖云端模型，有网络延迟
- 需要 API 费用
- 隐私数据上传云端

#### 5.2 优化方案

```
┌─────────────────────────────────────────────────────────────┐
│                    模型选择策略                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────────┐      ┌─────────────────┐              │
│  │ 本地 VLM 模型   │      │ 云端 VLM 模型   │              │
│  │ (MNN/llama.cpp) │      │ (GPT-4o/Claude) │              │
│  └────────┬────────┘      └────────┬────────┘              │
│           │                        │                        │
│           ▼                        ▼                        │
│  ┌─────────────────────────────────────────────────┐       │
│  │              ModelRouter                        │       │
│  │  - 简单操作 → 本地模型（快、隐私）               │       │
│  │  - 复杂任务 → 云端模型（准确、强大）             │       │
│  │  - 无网络时 → 自动切换本地模型                   │       │
│  └─────────────────────────────────────────────────┘       │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 6. 多模态输入支持

#### 6.1 当前状态

- 仅支持视觉输入（截图）
- 无法处理语音、视频等

#### 6.2 扩展方向

| 输入类型 | 应用场景 | 技术方案 |
|---------|---------|---------|
| 语音 | 语音助手、通话 | Whisper/本地 ASR |
| 视频 | 长时任务监控 | 关键帧提取 |
| 多截图 | 复杂页面分析 | 图像拼接/对比 |

### 7. 任务编排与调度

#### 7.1 当前问题

- 单任务执行，无法并行
- 无任务队列管理
- 缺少定时执行能力

#### 7.2 优化方案

```
┌─────────────────────────────────────────────────────────────┐
│                    TaskScheduler                            │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ Task Queue  │  │  Scheduler  │  │  Executor   │         │
│  │             │→ │             │→ │  (多线程)   │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
│         │                                    │              │
│         ▼                                    ▼              │
│  ┌─────────────┐                     ┌─────────────┐       │
│  │ 定时任务    │                     │ 执行状态    │       │
│  │ - 每日签到  │                     │ - 进度跟踪  │       │
│  │ - 定时提醒  │                     │ - 错误日志  │       │
│  └─────────────┘                     └─────────────┘       │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 四、关键文件索引

| 文件路径 | 功能说明 |
|---------|---------|
| `core/tools/agent/PhoneAgent.kt` | AI 自动化代理核心 |
| `core/tools/agent/ActionHandler.kt` | 动作处理器 |
| `core/tools/defaultTool/accessibility/AccessibilityUITools.kt` | 无障碍 UI 操作 |
| `core/tools/defaultTool/root/RootUITools.kt` | Root Shell UI 操作 |
| `core/tools/defaultTool/debugger/DebuggerUITools.kt` | Shizuku/ADB UI 操作 |
| `data/repository/UIHierarchyManager.kt` | AIDL 通信管理 |
| `data/preferences/DisplayPreferencesManager.kt` | 截图参数设置 |
| `app/src/main/assets/packages/various_search.js` | 多平台搜索工具包 |

---

## 五、参考资料

- [UI 自动化架构分析](./UI_AUTOMATION_ANALYSIS.md)
- [项目架构文档](./PROJECT_ARCHITECTURE.md)
- [脚本开发指南](./SCRIPT_DEV_GUIDE.md)
- [GitHub 仓库](https://github.com/AAswordman/Operit)