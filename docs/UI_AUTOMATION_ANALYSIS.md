# Operit UI 自动化架构深度分析

## 概述

Operit 实现了 **三通道 UI 自动化系统**，支持不同权限级别的 UI 操作：

| 权限级别 | 实现方式 | 核心技术 |
|---------|---------|---------|
| **ACCESSIBILITY** | 无障碍服务 | AccessibilityService + GestureDescription |
| **DEBUGGER** | ADB/Shizuku | `input` 命令 + `uiautomator` |
| **ROOT** | Root Shell | `su` + `input` 命令 |

---

## 架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        AIToolHandler                             │
│                    (工具调用统一入口)                              │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                        ToolGetter                                │
│              (根据权限级别选择 UITools 实现)                        │
└──────────────────────────┬──────────────────────────────────────┘
                           │
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│AccessibilityUI  │ │  RootUITools    │ │ DebuggerUI      │
│     Tools       │ │                 │ │   Tools         │
└────────┬────────┘ └────────┬────────┘ └────────┬────────┘
         │                   │                   │
         ▼                   ▼                   ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│UIHierarchy      │ │ RootShell       │ │ ShizukuShell    │
│  Manager        │ │  Executor       │ │  Executor       │
│  (AIDL)         │ │  (libsu)        │ │  (Shizuku API)  │
└────────┬────────┘ └─────────────────┘ └─────────────────┘
         │
         ▼
┌─────────────────┐
│ Accessibility   │
│  Provider APK   │
│ (独立进程服务)    │
└─────────────────┘
```

---

## 一、无障碍服务实现 (ACCESSIBILITY)

### 1.1 架构设计

无障碍服务采用 **独立进程架构**，将无障碍服务放在独立的 APK 中：

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

**核心文件**:
- `AccessibilityUITools.kt` - 无障碍级别的 UI 工具实现
- `UIHierarchyManager.kt` - AIDL 通信管理器
- `IAccessibilityProvider.aidl` - AIDL 接口定义

### 1.2 核心实现

#### 1.2.1 点击操作

```kotlin
// AccessibilityUITools.kt:618-625
private suspend fun performAccessibilityClick(x: Int, y: Int): Boolean {
    return try {
        // 通过 AIDL 调用远程无障碍服务执行点击
        UIHierarchyManager.performClick(context, x, y)
    } catch (e: Exception) {
        AppLogger.e(TAG, "Error performing accessibility click", e)
        return false
    }
}
```

**底层实现** (在无障碍服务 APK 中):
```kotlin
// 使用 GestureDescription 执行手势
val gesture = GestureDescription.Builder()
    .addStroke(StrokeDescription(path, 0, duration))
    .build()
dispatchGesture(gesture, null, null)
```

#### 1.2.2 滑动操作

```kotlin
// AccessibilityUITools.kt:638-651
private suspend fun performAccessibilitySwipe(
    startX: Int, startY: Int, endX: Int, endY: Int, duration: Int
): Boolean {
    return try {
        UIHierarchyManager.performSwipe(context, startX, startY, endX, endY, duration.toLong())
    } catch (e: Exception) {
        AppLogger.e(TAG, "Error performing accessibility swipe", e)
        return false
    }
}
```

#### 1.2.3 按键操作

```kotlin
// AccessibilityUITools.kt:654-721
override suspend fun pressKey(tool: AITool): ToolResult {
    val keyCode = tool.parameters.find { it.name == "key_code" }?.value

    // 将字符串 keyCode 转换为全局操作常量
    val keyAction = when (keyCode) {
        "KEYCODE_BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
        "KEYCODE_HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
        "KEYCODE_RECENTS" -> AccessibilityService.GLOBAL_ACTION_RECENTS
        "KEYCODE_NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        "KEYCODE_QUICK_SETTINGS" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        else -> null
    }

    if (keyAction != null) {
        val success = UIHierarchyManager.performGlobalAction(context, keyAction)
        // ...
    }
}
```

#### 1.2.4 文本输入

```kotlin
// AccessibilityUITools.kt:377-436
override suspend fun setInputText(tool: AITool): ToolResult {
    val text = tool.parameters.find { it.name == "text" }?.value ?: ""

    // 1. 找到当前焦点的节点 ID
    val focusedNodeId = UIHierarchyManager.findFocusedNodeId(context)

    // 2. 通过无障碍服务设置文本
    val result = UIHierarchyManager.setTextOnNode(context, focusedNodeId, text)
    // ...
}
```

#### 1.2.5 截图

```kotlin
// AccessibilityUITools.kt:723-749
override suspend fun captureScreenshotToFile(tool: AITool): Pair<String?, Pair<Int, Int>?> {
    val file = File(screenshotDir, "$shortName.png")

    // 通过 AIDL 请求无障碍服务截图
    val success = UIHierarchyManager.takeScreenshot(context, file.absolutePath, "png")
    // ...
}
```

### 1.3 UI 层次结构获取

```kotlin
// AccessibilityUITools.kt:55-74
private suspend fun getUIHierarchyWithRetry(): String {
    var retryCount = 0
    var uiXml = ""

    while (retryCount < MAX_RETRY_COUNT) {
        uiXml = UIHierarchyManager.getUIHierarchy(context)
        if (uiXml.isNotEmpty()) {
            return uiXml
        }
        retryCount++
        delay(RETRY_DELAY_MS)
    }
    return uiXml
}
```

**解析后的简化节点结构**:
```kotlin
data class SimplifiedUINode(
    val className: String?,      // 如 "TextView"
    val text: String?,           // 显示文本
    val contentDesc: String?,    // 内容描述
    val resourceId: String?,     // resource-id
    val bounds: String?,         // "[left,top][right,bottom]"
    val isClickable: Boolean,    // 是否可点击
    val children: List<SimplifiedUINode>
)
```

---

## 二、Root/ADB 实现 (ROOT/DEBUGGER)

### 2.1 核心命令映射

| 操作 | Shell 命令 |
|-----|-----------|
| 点击 | `input tap x y` |
| 长按 | `input swipe x y x y 800` |
| 滑动 | `input swipe x1 y1 x2 y2 duration` |
| 按键 | `input keyevent KEYCODE` |
| 文本输入 | 剪贴板 + `input keyevent KEYCODE_PASTE` |
| UI 层次 | `uiautomator dump` + `cat` |
| 截图 | `screencap -p` |

### 2.2 RootUITools 实现

```kotlin
// RootUITools.kt:40-96
override suspend fun tap(tool: AITool): ToolResult {
    val x = tool.parameters.find { it.name == "x" }?.value?.toIntOrNull()
    val y = tool.parameters.find { it.name == "y" }?.value?.toIntOrNull()

    // 显示操作反馈
    withContext(Dispatchers.Main) { overlay.showTap(x, y) }

    // 执行 shell 命令
    val command = "input ${getDisplayArg(tool)}tap $x $y"
    val result = executeUiShellCommand(command)

    return if (result.success) {
        ToolResult(toolName = tool.name, success = true, ...)
    } else {
        ToolResult(toolName = tool.name, success = false, error = result.stderr)
    }
}
```

### 2.3 多显示器支持

```kotlin
// RootUITools.kt:35-38
private fun getDisplayArg(tool: AITool): String {
    val display = tool.parameters.find { it.name.equals("display", ignoreCase = true) }?.value?.trim()
    return if (!display.isNullOrEmpty()) "-d $display " else ""
}
```

**使用示例**:
```kotlin
// 在指定显示器上点击
val command = "input -d 1 tap x y"
```

### 2.4 UI 层次结构获取

```kotlin
// RootUITools.kt:389-435
private suspend fun getUIDataFromShell(tool: AITool): UIData? {
    val displayId = tool.parameters.find { it.name.equals("display", ignoreCase = true) }?.value

    // 1. 使用 uiautomator dump UI 层次
    var dumpResult = if (displayId != null) {
        executeUiShellCommand("uiautomator dump --display-id $displayId /sdcard/window_dump.xml")
    } else {
        executeUiShellCommand("uiautomator dump /sdcard/window_dump.xml")
    }

    // 2. 读取 XML 文件
    val readResult = executeUiShellCommand("cat /sdcard/window_dump.xml")

    // 3. 获取窗口信息
    var windowInfo = getWindowInfoFromShell()  // dumpsys window

    return UIData(readResult.stdout, windowInfo)
}
```

### 2.5 Shell 执行器层次

```
ShellExecutor (接口)
    │
    ├── StandardShellExecutor    (普通应用权限，无 UI 操作)
    │
    ├── AccessibilityShellExecutor (无障碍服务权限)
    │
    ├── DebuggerShellExecutor    (Shizuku/ADB 权限)
    │       └── 使用 Shizuku API 执行命令
    │
    ├── AdminShellExecutor       (设备管理员权限)
    │
    └── RootShellExecutor        (Root 权限)
            ├── libsu 模式: Shell.cmd(command).exec()
            └── exec 模式: Runtime.exec("su -c command")
```

### 2.6 RootShellExecutor 实现

```kotlin
// RootShellExecutor.kt:356-475
override suspend fun executeCommand(
    command: String,
    identity: ShellIdentity
): ShellExecutor.CommandResult = withContext(Dispatchers.IO) {

    when (identity) {
        ShellIdentity.SHELL -> {
            // 使用 shell 用户身份执行 (需要特殊 launcher)
            val launcherPath = ensureShellLauncherInstalled()
            if (useExecMode) {
                val process = Runtime.getRuntime().exec(buildSuExecCommand("$launcherPath $command"))
                // ...
            } else {
                val shellResult = Shell.cmd("$launcherPath $command").exec()
                // ...
            }
        }
        ShellIdentity.ROOT, ShellIdentity.DEFAULT, ShellIdentity.APP -> {
            // 直接以 Root 身份执行
            if (useExecMode) {
                executeCommandWithExec(actualCommand)  // Runtime.exec("su -c ...")
            } else {
                val shellResult = Shell.cmd(actualCommand).exec()  // libsu
                // ...
            }
        }
    }
}
```

---

## 三、AutoGLM 自动点击代理

### 3.1 架构

```
┌─────────────────────────────────────────────────────────────┐
│                    PhoneAgent                                │
│              (AI 驱动的自动化代理)                            │
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

### 3.2 PhoneAgent 核心流程

```kotlin
// PhoneAgent.kt
class PhoneAgent(
    private val context: Context,
    private val config: AgentConfig,      // maxSteps 等配置
    private val uiService: AIService,     // 视觉语言模型
    private val actionHandler: ActionHandler,
    val agentId: String = "default"
) {
    suspend fun run(
        task: String,
        systemPrompt: String,
        onStep: (StepResult) -> Unit,
        isPausedFlow: MutableStateFlow<Boolean>
    ): String {
        while (_stepCount < config.maxSteps) {
            // 1. 截取当前屏幕
            val screenshot = captureScreenshot()

            // 2. 发送给 AI 模型分析
            val response = uiService.sendMessageWithImage(
                systemPrompt + task,
                screenshot
            )

            // 3. 解析 AI 返回的动作
            val action = parseAction(response)

            // 4. 执行动作
            val result = actionHandler.executeAction(action)

            // 5. 检查是否完成
            if (action.metadata == "finish") {
                return result.message
            }

            _stepCount++
        }
    }
}
```

### 3.3 ActionHandler 实现

```kotlin
// ActionHandler.kt (推断)
class ActionHandler(
    private val context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val toolImplementations: ToolImplementations
) {
    suspend fun executeAction(action: ParsedAgentAction): StepResult {
        return when (action.actionName) {
            "tap" -> {
                val x = action.fields["x"]?.toInt()
                val y = action.fields["y"]?.toInt()
                toolImplementations.tap(AITool("tap", listOf(...)))
            }
            "swipe" -> {
                toolImplementations.swipe(AITool("swipe", listOf(...)))
            }
            "type" -> {
                toolImplementations.setInputText(AITool("set_input_text", listOf(...)))
            }
            "press_key" -> {
                toolImplementations.pressKey(AITool("press_key", listOf(...)))
            }
            "finish" -> {
                StepResult(success = true, finished = true, ...)
            }
        }
    }
}
```

### 3.4 虚拟屏幕支持

```kotlin
// AutoGlmViewModel.kt:45-104
fun executeTask(task: String, useVirtualScreen: Boolean = false) {
    if (useVirtualScreen) {
        // 1. 启动 Shower 服务器
        ShowerServerManager.ensureServerStarted(context)

        // 2. 创建虚拟显示器
        ShowerController.ensureDisplay(agentId, context, width, height, dpi)

        // 3. 获取显示器 ID
        val displayId = ShowerController.getDisplayId(agentId)

        // 4. 在虚拟屏幕上执行任务
        val agent = PhoneAgent(
            context = context,
            config = agentConfig,
            uiService = uiService,
            actionHandler = actionHandler,
            agentId = agentId  // 使用特定 agentId
        )

        agent.run(task, systemPrompt, ...)
    }
}
```

---

## 四、权限级别选择逻辑

### 4.1 ToolGetter 实现

```kotlin
// ToolGetter.kt (推断)
object ToolGetter {
    fun getUITools(context: Context): StandardUITools {
        val level = androidPermissionPreferences.getPreferredPermissionLevel()

        return when (level) {
            AndroidPermissionLevel.ROOT -> RootUITools(context)
            AndroidPermissionLevel.ADMIN -> AdminUITools(context)
            AndroidPermissionLevel.DEBUGGER -> DebuggerUITools(context)
            AndroidPermissionLevel.ACCESSIBILITY -> AccessibilityUITools(context)
            AndroidPermissionLevel.STANDARD -> StandardUITools(context)
        }
    }
}
```

### 4.2 权限级别定义

```kotlin
enum class AndroidPermissionLevel {
    STANDARD,       // 普通应用权限，无 UI 操作能力
    ACCESSIBILITY,  // 无障碍服务，支持点击/滑动/按键
    DEBUGGER,       // ADB/Shizuku 权限，支持 input 命令
    ADMIN,          // 设备管理员权限
    ROOT            // Root 权限，完全控制
}
```

---

## 五、操作对比表

| 功能 | Accessibility | Root/ADB | 备注 |
|-----|--------------|----------|------|
| 点击 | ✅ GestureDescription | ✅ `input tap` | |
| 长按 | ✅ GestureDescription | ✅ `input swipe` | ADB 使用滑动模拟 |
| 滑动 | ✅ GestureDescription | ✅ `input swipe` | |
| 按键 | ✅ performGlobalAction | ✅ `input keyevent` | 无障碍仅支持系统键 |
| 文本输入 | ✅ AccessibilityNode | ✅ 剪贴板+粘贴 | |
| UI 层次 | ✅ AccessibilityNode | ✅ uiautomator dump | |
| 截图 | ✅ takeScreenshot | ✅ screencap | |
| 多显示器 | ❌ | ✅ `-d` 参数 | |
| 后台执行 | ❌ 需前台 | ✅ 虚拟屏幕 | |
| 需要 Root | ❌ | ✅ (ROOT级别) | |

---

## 六、关键文件索引

| 文件路径 | 功能说明 |
|---------|---------|
| `core/tools/defaultTool/accessbility/AccessibilityUITools.kt` | 无障碍 UI 操作实现 |
| `core/tools/defaultTool/root/RootUITools.kt` | Root Shell UI 操作 |
| `core/tools/defaultTool/debugger/DebuggerUITools.kt` | Shizuku/ADB UI 操作 |
| `data/repository/UIHierarchyManager.kt` | 无障碍服务 AIDL 通信 |
| `core/tools/system/shell/RootShellExecutor.kt` | Root Shell 执行器 |
| `core/tools/system/shell/DebuggerShellExecutor.kt` | Shizuku Shell 执行器 |
| `core/tools/agent/PhoneAgent.kt` | AutoGLM 代理核心 |
| `core/tools/agent/ActionHandler.kt` | 动作处理器 |
| `ui/features/toolbox/screens/autoglm/AutoGlmViewModel.kt` | AutoGLM UI 层 |

---

## 七、总结

Operit 的 UI 自动化系统设计精妙，具有以下特点：

1. **多通道架构**: 根据设备权限自动选择最优实现方式
2. **进程隔离**: 无障碍服务独立进程，避免主应用崩溃影响
3. **AIDL 通信**: 跨进程安全调用无障碍服务
4. **AI 驱动**: AutoGLM 使用视觉语言模型理解屏幕并决策
5. **虚拟屏幕**: 支持后台自动化，不影响前台使用
6. **灵活的 Shell 执行**: 支持 libsu 和传统 exec 两种 Root 执行方式