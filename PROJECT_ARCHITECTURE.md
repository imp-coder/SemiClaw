# Operit AI 项目架构文档

## 项目概述

**Operit AI** 是移动端首个功能完备的 AI 智能助手应用，完全独立运行于 Android 设备上。它不仅仅是聊天界面，更是与 Android 权限和各种工具深度融合的全能助手。

- **包名**: `com.ai.assistance.operit`
- **最低 SDK**: Android 8.0 (API 26)
- **目标 SDK**: Android 14 (API 34)
- **当前版本**: 1.9.1

---

## 项目模块架构

项目采用多模块架构，共包含 **8 个模块**：

```
Operit/
├── app/                    # 主应用模块
├── terminal/               # Ubuntu 终端模块
├── mnn/                    # MNN 本地推理引擎
├── llama/                  # llama.cpp 本地推理引擎
├── quickjs/                # QuickJS JavaScript 引擎
├── dragonbones/            # DragonBones 骨骼动画
├── mmd/                    # MMD 3D 模型渲染
└── showerclient/           # 跨进程通信客户端
```

---

## 模块详解

### 1. app 模块 - 主应用

**路径**: `app/src/main/java/com/ai/assistance/operit/`

核心业务逻辑所在，包含以下子包：

| 子包 | 功能说明 |
|------|----------|
| `api/` | API 层 - 包含聊天、语音、语音识别等 API 接口 |
| `core/` | 核心模块 - 应用入口、工具系统、代理系统等 |
| `data/` | 数据层 - 数据库、DAO、Repository、MCP/Skill 等 |
| `ui/` | UI 层 - Compose 界面、主题、组件、各功能页面 |
| `services/` | 服务层 - 悬浮窗服务、聊天服务、语音服务等 |
| `plugins/` | 插件系统 - 工具箱、工具包、生命周期钩子等 |
| `integrations/` | 集成模块 - Tasker 集成、Intent 处理等 |
| `util/` | 工具类 - 日志、扩展函数等 |
| `provider/` | ContentProvider - 数据共享 |
| `widget/` | 桌面小部件 |

#### 核心文件

- `OperitApplication.kt` - 应用入口，初始化各组件
- `AIToolHandler.kt` - 工具调用核心处理器
- `ToolRegistration.kt` - 40+ 内置工具的注册定义
- `FloatingChatService.kt` - 悬浮窗聊天服务

#### UI 功能模块 (`ui/features/`)

| 模块 | 功能 |
|------|------|
| `chat/` | 聊天界面 |
| `settings/` | 设置页面 |
| `memory/` | 记忆库管理 |
| `packages/` | 工具包管理 |
| `toolbox/` | 工具箱 |
| `workflow/` | 工作流编辑器 |
| `permission/` | 权限管理 |
| `assistant/` | 助手人设配置 |

---

### 2. terminal 模块 - Ubuntu 终端

**命名空间**: `com.ai.assistance.operit.terminal`

提供完整的 Ubuntu 24 Linux 环境支持：

| 核心文件 | 功能 |
|----------|------|
| `TerminalManager.kt` | 终端生命周期管理，PTY 会话管理 |
| `SessionManager.kt` | 终端会话管理 |
| `Pty.kt` | JNI 桥接，伪终端实现 |
| `TerminalEnv.kt` | Ubuntu 环境配置 |

**特性**:
- 支持 vim、Python、Node.js 等工具
- SSH 服务器/客户端
- FTP 服务器
- Chroot 环境

---

### 3. mnn 模块 - MNN 本地推理

**命名空间**: `com.ai.assistance.mnn`

阿里巴巴 MNN (Mobile Neural Network) 推理引擎的 Android 封装：

| 核心文件 | 功能 |
|----------|------|
| `MNNLlmSession.kt` | LLM 推理会话管理 |
| `MNNLlmNative.kt` | JNI 本地方法接口 |
| `MNNNetInstance.kt` | 神经网络实例管理 |
| `MNNModule.kt` | MNN 模块加载 |

**特性**:
- 支持本地 LLM 模型运行
- 完全离线 AI 推理
- ARM NEON 优化

---

### 4. llama 模块 - llama.cpp 推理

**命名空间**: `com.ai.assistance.llama`

llama.cpp 的 Android 封装，支持 GGUF 格式模型：

- 本地 LLM 推理
- 支持 GGUF 模型格式
- CPU 优化推理

---

### 5. quickjs 模块 - JavaScript 引擎

**命名空间**: `com.ai.assistance.quickjs`

QuickJS JavaScript 引擎的 Android 封装：

| 核心文件 | 功能 |
|----------|------|
| `OperitQuickJsEngine.kt` | QuickJS 引擎封装 |
| `QuickJsNativeRuntime.kt` | JNI 运行时 |
| `QuickJsNativeHostDispatcher.kt` | 宿主函数调度器 |

**用途**:
- 执行工具包脚本
- 工作流脚本执行
- 动态代码运行

---

### 6. dragonbones 模块 - 骨骼动画

**命名空间**: `com.dragonbones`

DragonBones 骨骼动画渲染引擎：

| 核心文件 | 功能 |
|----------|------|
| `DragonBonesView.kt` | Compose 动画视图 |
| `DragonBonesController.kt` | 动画控制逻辑 |
| `JniBridge.kt` | JNI 桥接 |

**用途**:
- 桌宠动画显示
- 角色卡动画效果

---

### 7. mmd 模块 - 3D 模型渲染

**命名空间**: `com.ai.assistance.mmd`

MMD (MikuMikuDance) 3D 模型渲染支持：

- 3D 角色模型显示
- 基于 C++ 原生渲染

---

### 8. showerclient 模块 - 跨进程通信

**命名空间**: `com.ai.assistance.showerclient`

用于悬浮窗与主应用的跨进程通信：

- AIDL 接口定义
- 协程支持的异步通信

---

## 核心技术栈

### UI 框架
- **Jetpack Compose** - 现代声明式 UI
- **Material 3** - 设计系统
- **Compose Navigation** - 导航

### 数据存储
- **ObjectBox** - 对象数据库
- **Room** - SQLite ORM
- **DataStore** - 键值存储

### 网络与 API
- **OkHttp** - HTTP 客户端
- **Retrofit** - REST API
- **MCP SDK** - Model Context Protocol

### AI 能力
- **MNN** - 阿里本地推理引擎
- **llama.cpp** - GGUF 模型推理
- **ONNX Runtime** - 多模型支持
- **ML Kit** - OCR 文字识别
- **MediaPipe** - 文本嵌入

### 系统集成
- **Shizuku** - 免 Root 系统权限
- **libsu** - Root 权限管理
- **Tasker Plugin** - 自动化集成

### 原生开发
- **CMake** - C++ 构建系统
- **JNI** - Java Native Interface
- **NDK** - Native Development Kit

---

## 工具系统架构

### 工具分类 (`core/tools/`)

```
core/tools/
├── AIToolHandler.kt        # 工具调用核心
├── ToolRegistration.kt     # 工具注册定义
├── ToolResultDataClasses.kt # 结果数据类
├── defaultTool/            # 默认工具实现
├── mcp/                    # MCP 工具适配
├── skill/                  # Skill 协议实现
├── packTool/               # 工具包系统
├── agent/                  # AutoGLM 代理
├── javascript/             # JS 脚本工具
├── calculator/             # 计算器工具
└── system/                 # 系统工具
```

### 内置工具类型

| 类别 | 工具示例 |
|------|----------|
| 文件操作 | 读写文件、搜索、解压缩、格式转换 |
| 网络请求 | HTTP 请求、网页访问、文件下载 |
| 系统操作 | 安装应用、权限管理、自动化点击 |
| 媒体处理 | 视频转换、OCR、图像理解、相机拍照 |
| 开发工具 | Web 开发、代码编辑、终端操作 |
| AI 创作 | 绘图、图片搜索 |

---

## 数据层架构

### 数据库结构 (`data/`)

```
data/
├── db/          # 数据库配置
├── dao/         # 数据访问对象
├── model/       # 数据模型
├── repository/  # 数据仓库
├── preferences/ # 偏好设置
├── mcp/         # MCP 数据管理
├── skill/       # Skill 数据管理
└── backup/      # 备份恢复
```

### 主要数据实体

- 聊天消息历史
- 角色卡/人设配置
- 记忆库条目
- 工作流定义
- 工具包配置

---

## 快速上手指南

### 环境要求

1. **Android Studio**: Hedgehog (2023.1.1) 或更高版本
2. **JDK**: 17
3. **NDK**: 25.x 或更高
4. **CMake**: 3.22.1

### 构建步骤

1. 克隆项目
```bash
git clone https://github.com/AAswordman/Operit.git
cd Operit
```

2. 下载依赖库
   - 从 [Google Drive](https://drive.google.com/drive/folders/1g-Q_i7cf6Ua4KX9ZM6V282EEZvTVVfF7) 下载 `libs` 文件夹
   - 放入 `app/libs/` 目录

3. 配置签名 (可选)
   - 复制 `local.properties.example` 为 `local.properties`
   - 填入签名配置

4. 构建项目
```bash
./gradlew assembleDebug
```

### 关键入口点

| 入口 | 文件路径 |
|------|----------|
| Application | `app/.../core/application/OperitApplication.kt` |
| 主界面 | `app/.../ui/main/MainActivity.kt` |
| 工具注册 | `app/.../core/tools/ToolRegistration.kt` |
| 悬浮窗服务 | `app/.../services/FloatingChatService.kt` |
| 终端管理 | `terminal/.../TerminalManager.kt` |
| MNN 推理 | `mnn/.../MNNLlmSession.kt` |

---

## 调试技巧

### 日志系统
使用 `AppLogger` 进行日志输出：
```kotlin
AppLogger.d("TAG", "Debug message")
```

### 常见问题

1. **NDK 构建失败**: 确保 NDK 版本正确，检查 CMake 配置
2. **依赖冲突**: 检查 `packaging` 配置中的排除规则
3. **签名问题**: 确保 `local.properties` 配置正确

---

## 扩展开发

### 添加新工具

1. 在 `ToolRegistration.kt` 中定义工具
2. 在 `AIToolHandler.kt` 中实现处理逻辑
3. 在 `ToolResultDataClasses.kt` 中定义结果类型

### 添加新功能模块

1. 在 `ui/features/` 下创建新目录
2. 实现 ViewModel 和 Compose UI
3. 在 `ui/main/` 中添加导航

### 集成新的本地模型

1. 参考 `mnn/` 或 `llama/` 模块结构
2. 实现 JNI 接口
3. 在主模块中封装 Kotlin API

---

## 相关资源

- **官方文档**: https://aaswordman.github.io/OperitWeb
- **GitHub**: https://github.com/AAswordman/Operit
- **问题反馈**: https://github.com/AAswordman/Operit/issues
- **开源共创指南**: `docs/CONTRIBUTING.md`
- **脚本开发指南**: `docs/SCRIPT_DEV_GUIDE.md`

---

## 许可证

本项目采用 GNU LGPLv3 许可证。