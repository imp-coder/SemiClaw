# Operit 架构分析文档

> 生成日期：2026-03-23
> 项目：Operit - Android AI Agent 应用

---

## 目录

1. [项目定位与竞品分析](#1-项目定位与竞品分析)
2. [AI 模型支持](#2-ai-模型支持)
3. [UI 自动化架构](#3-ui-自动化架构)
4. [记忆系统架构](#4-记忆系统架构)
5. [技术选型对比](#5-技术选型对比)
6. [总结](#6-总结)

---

## 1. 项目定位与竞品分析

### 1.1 项目简介

Operit 是一款 Android 平台的 AI Agent 应用，具备以下核心能力：

- **AI 对话**：支持多种云端模型和本地模型
- **UI 自动化**：通过 Accessibility/Debugger/Root 三层权限实现设备控制
- **记忆系统**：RAG + Knowledge Graph 的长期记忆能力
- **工具调用**：MCP 协议支持，可扩展 Skills
- **语音交互**：语音识别与合成

### 1.2 Android AI Agent 项目 Top 20 (2026年3月)

| 排名 | 项目 | Stars | 语言 | 描述 |
|:---:|------|:-----:|:----:|------|
| 1 | [xszyou/Fay](https://github.com/xszyou/Fay) | 12.6k | Python | 数字人/LLM 连通业务系统的 Agent 框架，支持移动端 |
| 2 | [firerpa/lamda](https://github.com/firerpa/lamda) | 7.7k | Python | 最强大的 Android RPA Agent 框架 |
| 3 | [iflytek/astron-rpa](https://github.com/iflytek/astron-rpa) | 7.3k | Java | Agent-ready RPA 套件，支持 Android 自动化 |
| 4 | [JetBrains/koog](https://github.com/JetBrains/koog) | 3.9k | Kotlin | JetBrains 出品的 JVM AI Agent 框架，支持 Android/iOS |
| 5 | **[AAswordman/Operit](https://github.com/AAswordman/Operit)** | **3.7k** | **Kotlin** | **Android 上最强大的 AI Agent 和 AI 聊天软件** |
| 6 | [minitap-ai/mobile-use](https://github.com/minitap-ai/mobile-use) | 2.3k | Python | AI Agent 像人类一样操作真实 Android/iOS 应用 |
| 7 | [mhss1/MyBrain](https://github.com/mhss1/MyBrain) | 1.9k | Kotlin | 全能生产力应用 + AI 助手 |
| 8 | [callstackincubator/agent-device](https://github.com/callstackincubator/agent-device) | 1.2k | TypeScript | CLI 控制 iOS/Android 设备的 AI Agent |
| 9 | [takahirom/arbigent](https://github.com/takahirom/arbigent) | 540 | Kotlin | Android/iOS/Web 测试 AI Agent |
| 10 | [ImKKingshuk/LockKnife](https://github.com/ImKKingshuk/LockKnife) | 461 | Python | Android 安全研究工具 + AI Agent |
| 11 | [zhixianio/botdrop-android](https://github.com/zhixianio/botdrop-android) | 357 | Java | 在 Android 手机上运行 AI Agent，无需终端 |
| 12 | [qingchencloud/clawapp](https://github.com/qingchencloud/clawapp) | 323 | JavaScript | OpenClaw AI 智能体手机聊天客户端 (PWA + APK) |
| 13 | [Natfii/ZeroClaw-Android](https://github.com/Natfii/ZeroClaw-Android) | 259 | Rust | 24/7 运行 AI Agent，原生 Rust 核心 |
| 14 | [ganeshnikhil/J.A.R.V.I.S.2.0](https://github.com/ganeshnikhil/J.A.R.V.I.S.2.0) | 227 | Python | 开源 AI 助手，支持小模型 + Agent 工具 |
| 15 | [babelcloud/gbox](https://github.com/babelcloud/gbox) | 172 | Go | AI Agent 操作 Android/Browser/Desktop |
| 16 | [eraycc/AutoGLM-TERMUX](https://github.com/eraycc/AutoGLM-TERMUX) | 184 | Shell | 在 Android Termux 快速部署 AutoGLM Agent |
| 17 | [AbuZar-Ansarii/Clawbot](https://github.com/AbuZar-Ansarii/Clawbot) | 190 | - | OpenClaw Android 安装指南 |
| 18 | [SilentCoderHere/aihub](https://github.com/SilentCoderHere/aihub) | 259 | Kotlin | 聚合多 AI 助手的 Android App |
| 19 | [liyupi/openclaw-guide](https://github.com/liyupi/openclaw-guide) | 123 | Astro | OpenClaw 中文文档站 |
| 20 | [rajbreno/PocketCode](https://github.com/rajbreno/PocketCode) | 101 | Shell | 在 Android 上运行 AI 编程 Agent |

### 1.3 Operit 的差异化优势

| 维度 | Operit | 竞品常见方案 |
|------|--------|-------------|
| **运行环境** | 纯 Android 端 | 需要 PC 服务端 / Python 环境 |
| **开发语言** | 纯 Kotlin | Python / TypeScript 居多 |
| **离线能力** | 完全离线可用 | 多数依赖云端 |
| **UI 自动化** | 三级权限可选 | 单一 ADB 方案 |
| **记忆系统** | RAG + 知识图谱 | 简单向量存储 |

---

## 2. AI 模型支持

### 2.1 支持的模型提供商

```
┌─────────────────────────────────────────────────────────────────┐
│                     Operit AI Provider 架构                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐ │
│  │  云端模型    │  │  本地模型    │  │  国内模型               │ │
│  ├─────────────┤  ├─────────────┤  ├─────────────────────────┤ │
│  │ OpenAI      │  │ LLaMA       │  │ 豆包 (Doubao/火山引擎) │ │
│  │ Anthropic   │  │ MNN (已移除)│  │ 阿里云通义              │ │
│  │ Google      │  └─────────────┘  │ 讯飞星火                │ │
│  │ Azure       │                   │ 智谱 AI                 │ │
│  │ DeepSeek    │                   │ 百度文心                │ │
│  │ SiliconFlow │                   └─────────────────────────┘ │
│  └─────────────┘                                               │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                    Provider 抽象层                           ││
│  ├─────────────────────────────────────────────────────────────┤│
│  │  BaseAIProvider                                              ││
│  │      ├── OpenAIProvider                                      ││
│  │      ├── ClaudeProvider                                      ││
│  │      ├── GeminiProvider                                      ││
│  │      ├── DoubaoAIProvider (火山引擎)                         ││
│  │      ├── LlamaProvider (本地)                                ││
│  │      └── ...                                                 ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 火山引擎（豆包）支持详情

**API 端点**：
```kotlin
defaultApiEndpoint = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
```

**支持的模型**：

| 模型 | 类型 | 输入价格 | 输出价格 |
|------|------|----------|----------|
| doubao-seed-1-6 | 文本 | 1.2元/百万token | 12元/百万token |
| doubao-seed-1-8 | 文本 | 1.2元/百万token | 12元/百万token |
| doubao-seed-1-6-thinking | 思考模型 | 1.2元/百万token | 12元/百万token |
| doubao-seed-1-6-vision | 多模态 | 2.4元/百万token | 19.2元/百万token |
| doubao-seedance | 视频生成 | - | 15-24元/次 |
| doubao-seedream | 图像生成 | - | 0.1-0.25元/次 |

**实现文件**：
```
api/chat/llmprovider/DoubaoAIProvider.kt
```

### 2.3 无 LangChain 依赖

Operit **完全自研** AI 调用框架，不依赖 LangChain：

| 功能 | LangChain 方案 | Operit 方案 |
|------|---------------|-------------|
| 模型调用 | LangChain LLM | 自研 Provider 体系 |
| 工具调用 | LangChain Tools | 自研 AITool 体系 |
| Agent 编排 | LangChain Chains | 自研 ToolHandler |
| 扩展协议 | LangChain Plugins | MCP (Model Context Protocol) |

---

## 3. UI 自动化架构

### 3.1 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                      Operit UI 自动化架构                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                      AIToolHandler                        │  │
│  │                    (工具调度中心)                          │  │
│  └─────────────────────────┬────────────────────────────────┘  │
│                            │                                    │
│  ┌─────────────────────────┴────────────────────────────────┐  │
│  │                      UITools 基类                          │  │
│  │                  (StandardUITools)                         │  │
│  └─────────────────────────┬────────────────────────────────┘  │
│                            │                                    │
│         ┌──────────────────┼──────────────────┐                │
│         ▼                  ▼                  ▼                │
│  ┌─────────────┐   ┌─────────────────┐   ┌─────────────────┐  │
│  │ STANDARD    │   │ ACCESSIBILITY   │   │ DEBUGGER/ROOT   │  │
│  │ (基础能力)  │   │ (无障碍服务)    │   │ (Shell 命令)    │  │
│  ├─────────────┤   ├─────────────────┤   ├─────────────────┤  │
│  │ • 截图      │   │ • 点击/长按     │   │ • input tap     │  │
│  │ • 页面信息  │   │ • 滑动          │   │ • am/pm 命令    │  │
│  │ • 简单输入  │   │ • 文本输入      │   │ • screencap     │  │
│  │             │   │ • 全局按键      │   │ • 多屏幕支持    │  │
│  │             │   │ • UI 层次结构   │   │ • 高权限操作    │  │
│  └─────────────┘   └─────────────────┘   └─────────────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 权限层级

| 层级 | 实现 | 能力 | 要求 |
|------|------|------|------|
| **STANDARD** | MediaProjection API | 截图、页面信息 | 普通权限 |
| **ACCESSIBILITY** | AccessibilityService | 点击、滑动、输入、按键 | 用户开启无障碍服务 |
| **DEBUGGER** | Shizuku / ADB | Shell 命令、多屏幕操作 | ADB 调试权限 |
| **ROOT** | libsu | 任意 Shell 命令 | Root 权限 |

### 3.3 核心依赖

```kotlin
// build.gradle.kts

// Shizuku - 提升 Shell 权限到 System/API 级别
implementation(libs.shizuku.api)
implementation(libs.shizuku.provider)

// libsu - Root 权限执行 Shell 命令
implementation("com.github.topjohnwu.libsu:core:6.0.0")
implementation("com.github.topjohnwu.libsu:service:6.0.0")
implementation("com.github.topjohnwu.libsu:nio:6.0.0")
```

### 3.4 关键实现文件

```
core/tools/defaultTool/
├── standard/
│   └── StandardUITools.kt          # 基础 UI 工具
├── accessbility/
│   └── AccessibilityUITools.kt     # 无障碍级别 UI 工具
└── debugger/
    └── DebuggerUITools.kt          # Shell 级别 UI 工具

data/repository/
└── UIHierarchyManager.kt           # UI 层次解析管理器
```

### 3.5 与其他方案对比

| 方案 | Operit | Appium | Airtest | UIAutomator2 |
|------|--------|--------|---------|--------------|
| **运行位置** | Android 端 | PC 服务端 | PC 服务端 | Android 端 |
| **依赖** | 仅系统 API | Appium Server | Airtest IDE | Python 环境 |
| **权限要求** | 分级可选 | ADB | ADB | ADB/Root |
| **离线能力** | ✅ 完全支持 | ❌ 需连接 | ❌ 需连接 | ⚠️ 需配置 |
| **多屏幕支持** | ✅ 原生支持 | ⚠️ 需配置 | ⚠️ 需配置 | ❌ 不支持 |
| **Agent 集成** | ✅ 原生集成 | ⚠️ 需封装 | ⚠️ 需封装 | ⚠️ 需封装 |

---

## 4. 记忆系统架构

### 4.1 架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Operit Memory 系统架构                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │                      MemoryRepository                          │ │
│  │                      (记忆管理核心)                             │ │
│  └───────────────────────────────┬───────────────────────────────┘ │
│                                  │                                  │
│  ┌───────────────────────────────┼───────────────────────────────┐ │
│  │                               │                                │ │
│  │  ┌─────────────┐  ┌───────────────────┐  ┌─────────────────┐  │ │
│  │  │  ObjectBox  │  │ CloudEmbedding    │  │ 混合检索引擎    │  │ │
│  │  │ (本地数据库)│  │ Service           │  │ (RRF 算法)      │  │ │
│  │  └─────────────┘  └───────────────────┘  └─────────────────┘  │ │
│  │                                                               │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │                        数据模型层                              │ │
│  ├───────────────────────────────────────────────────────────────┤ │
│  │                                                               │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐   │ │
│  │  │   Memory    │  │ MemoryLink  │  │   DocumentChunk     │   │ │
│  │  │  (记忆节点) │  │ (关系边)    │  │   (文档分块)        │   │ │
│  │  └─────────────┘  └─────────────┘  └─────────────────────┘   │ │
│  │                                                               │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐   │ │
│  │  │  MemoryTag  │  │  Embedding  │  │ CloudEmbeddingConfig│   │ │
│  │  │ (标签)      │  │ (向量)      │  │ (嵌入配置)          │   │ │
│  │  └─────────────┘  └─────────────┘  └─────────────────────┘   │ │
│  │                                                               │ │
│  └───────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 数据模型

```kotlin
@Entity
class Memory {
    var uuid: String                    // 唯一标识
    var title: String                   // 标题
    var content: String                 // 内容
    var embedding: Embedding?           // 向量嵌入
    var importance: Float = 1.0f        // 重要性权重
    var credibility: Float = 1.0f       // 可信度
    var folderPath: String?             // 文件夹路径
    var isDocumentNode: Boolean = false // 是否为文档节点
    var source: String                  // 来源
    var createdAt: Date                 // 创建时间
    var updatedAt: Date                 // 更新时间

    // 关系
    val tags: ToMany<MemoryTag>         // 标签
    val links: ToMany<MemoryLink>       // 出边（指向其他记忆）
    val backlinks: ToMany<MemoryLink>   // 入边（来自其他记忆）
    val documentChunks: ToMany<DocumentChunk> // 文档分块
}

@Entity
class MemoryLink {
    var type: String                    // 关系类型 (e.g., "causes", "explains")
    var weight: Float                   // 关系权重 (0.0 - 1.0)
    var description: String             // 关系描述

    // 关系端点
    val source: ToOne<Memory>           // 源节点
    val target: ToOne<Memory>           // 目标节点
}

@Entity
class DocumentChunk {
    var content: String                 // 分块内容
    var chunkIndex: Int                 // 分块索引
    var embedding: Embedding?           // 分块向量
    val memory: ToOne<Memory>           // 所属文档
}
```

### 4.3 混合检索算法

```
┌─────────────────────────────────────────────────────────────────────┐
│                     混合检索流水线                                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│   Query                                                             │
│     │                                                               │
│     ▼                                                               │
│   ┌─────────────────────────────────────────────────────────────┐  │
│   │  1. 关键词分词 & 扩展                                        │  │
│   │     - Jieba 分词                                             │  │
│   │     - 大小写标准化                                            │  │
│   │     - 过滤停用词                                              │  │
│   └─────────────────────────────────────────────────────────────┘  │
│     │                                                               │
│     ▼                                                               │
│   ┌─────────────────────────────────────────────────────────────┐  │
│   │  2. 并行检索                                                  │  │
│   │     ┌──────────────┬──────────────┬──────────────┐          │  │
│   │     │ 关键词检索    │ 反向包含检索  │ 语义向量检索  │          │  │
│   │     │ (DB like)    │ (Query 包含) │ (Cosine)     │          │  │
│   │     └──────────────┴──────────────┴──────────────┘          │  │
│   └─────────────────────────────────────────────────────────────┘  │
│     │                                                               │
│     ▼                                                               │
│   ┌─────────────────────────────────────────────────────────────┐  │
│   │  3. RRF (Reciprocal Rank Fusion) 融合                       │  │
│   │                                                              │  │
│   │     score = Σ (1 / (k + rank)) × weight × importance        │  │
│   │                                                              │  │
│   │     - k = 60 (RRF 常数)                                      │  │
│   │     - weight = keyword/semantic/edge 权重                    │  │
│   │     - importance = 记忆重要性                                 │  │
│   └─────────────────────────────────────────────────────────────┘  │
│     │                                                               │
│     ▼                                                               │
│   ┌─────────────────────────────────────────────────────────────┐  │
│   │  4. 图扩展 (Graph Propagation)                               │  │
│   │                                                              │  │
│   │     Top-K 记忆 ──遍历出边/入边──► 关联记忆                    │  │
│   │                                                              │  │
│   │     propagatedScore = sourceScore × edgeWeight × factor     │  │
│   └─────────────────────────────────────────────────────────────┘  │
│     │                                                               │
│     ▼                                                               │
│   ┌─────────────────────────────────────────────────────────────┐  │
│   │  5. 阈值过滤 & 排序                                          │  │
│   │     - relevanceThreshold = 0.025                             │  │
│   │     - 按总得分降序排列                                        │  │
│   └─────────────────────────────────────────────────────────────┘  │
│     │                                                               │
│     ▼                                                               │
│   Results                                                           │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.4 核心实现代码

**余弦相似度计算**：
```kotlin
private fun cosineSimilarity(left: Embedding, right: Embedding): Float {
    val leftVector = left.vector
    val rightVector = right.vector

    if (leftVector.isEmpty() || rightVector.isEmpty() || leftVector.size != rightVector.size) {
        return 0f
    }

    var dot = 0.0
    var leftNorm = 0.0
    var rightNorm = 0.0

    for (index in leftVector.indices) {
        val leftValue = leftVector[index].toDouble()
        val rightValue = rightVector[index].toDouble()
        dot += leftValue * rightValue
        leftNorm += leftValue * leftValue
        rightNorm += rightValue * rightValue
    }

    if (leftNorm <= 0.0 || rightNorm <= 0.0) {
        return 0f
    }

    return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).toFloat()
}
```

**RRF 融合评分**：
```kotlin
// 关键词检索评分
keywordResults.forEachIndexed { index, candidate ->
    val rank = index + 1
    val baseScore = 1.0 / (k + rank)
    val weightedScore = baseScore * memory.importance * effectiveKeywordWeight
    scores[memory.id] = scores.getOrDefault(memory.id, 0.0) + weightedScore
}

// 语义检索评分
semanticResults.forEachIndexed { index, (memory, similarity) ->
    val rank = index + 1
    val rankScore = 1.0 / (k + rank)
    val similarityScore = similarity * effectiveSemanticWeight
    val weightedScore = (rankScore * sqrt(memory.importance)) + similarityScore
    scores[memory.id] = scores.getOrDefault(memory.id, 0.0) + weightedScore
}

// 图扩展评分
sourceMemory.links.forEach { link ->
    val targetMemory = link.target.target
    if (targetMemory != null) {
        val propagatedScore = sourceScore * link.weight * graphPropagationWeight
        scores[targetMemory.id] = scores.getOrDefault(targetMemory.id, 0.0) + propagatedScore
    }
}
```

### 4.5 Embedding 服务

```kotlin
class CloudEmbeddingService {

    suspend fun generateEmbedding(config: CloudEmbeddingConfig, text: String): Embedding? {
        val requestBodyJson = JSONObject()
            .put("model", config.model)
            .put("input", text)
            .toString()

        val request = Request.Builder()
            .url(completeEmbeddingsEndpoint(config.endpoint))
            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .build()

        httpClient.newCall(request).execute().use { response ->
            return parseEmbedding(response.body?.string().orEmpty())
        }
    }
}
```

---

## 5. 技术选型对比

### 5.1 LangChain Memory vs RAG + Knowledge Graph

#### LangChain Memory 架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                      LangChain Memory Types                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  基础缓冲型:                                                         │
│  ├── ConversationBufferMemory          - 全量历史                  │
│  ├── ConversationBufferWindowMemory    - 滑动窗口                  │
│  └── ConversationTokenBufferMemory     - Token 限制               │
│                                                                     │
│  摘要压缩型:                                                         │
│  ├── ConversationSummaryMemory         - 全量摘要                  │
│  └── ConversationSummaryBufferMemory   - 摘要+缓冲混合             │
│                                                                     │
│  知识图谱型:                                                         │
│  ├── ConversationKGMemory              - 知识图谱记忆              │
│  └── ConversationEntityMemory          - 实体记忆                  │
│                                                                     │
│  向量存储型:                                                         │
│  └── VectorStoreRetrieverMemory        - 向量检索记忆              │
│                                                                     │
│  生产级方案:                                                         │
│  ├── MotorheadMemory                   - 长时记忆服务              │
│  ├── ZepMemory                         - 企业级记忆                │
│  └── RedisMemory                       - 分布式存储                │
└─────────────────────────────────────────────────────────────────────┘
```

#### 对比分析

| 维度 | LangChain Memory | RAG + Knowledge Graph (Operit) |
|------|------------------|-------------------------------|
| **设计理念** | 对话历史管理 | 知识存储与推理 |
| **数据结构** | 线性列表 / 滑动窗口 | 图结构（节点+边+属性） |
| **记忆类型** | 短期记忆为主 | 长期记忆 + 结构化知识 |
| **检索方式** | 向量相似度 / 关键词 | 混合检索 + 图遍历 |
| **关系推理** | ⚠️ KGMemory 有限支持 | ✅ 完整多跳推理 |
| **上下文利用** | 直接注入 LLM Context | 检索后组装 Context |
| **离线能力** | ⚠️ 需配置本地向量库 | ✅ 原生离线支持 |
| **Android 支持** | ❌ 需 Python 后端 | ✅ 纯 Kotlin |

#### 适用场景

```
LangChain Memory 更适合：
├── 通用聊天机器人
├── 客服对话系统
├── 简单问答助手
├── 会话日志分析
└── 快速原型开发

RAG + Knowledge Graph 更适合：
├── 企业知识图谱
├── 医疗/法律等专业领域
├── 智能推荐系统
├── 复杂决策支持
├── 多实体关联分析
└── 长期记忆系统（如 Operit）
```

### 5.2 为什么 Operit 选择自研

| 需求 | LangChain Memory | Operit 自研 |
|------|------------------|-------------|
| 用户画像存储 | ❌ 无结构 | ✅ 实体+属性 |
| 工具使用历史 | ❌ 线性列表 | ✅ 关联图谱 |
| 跨会话记忆 | ⚠️ 需额外配置 | ✅ 原生支持 |
| 知识推理 | ⚠️ KGMemory 有限 | ✅ 图遍历 |
| 离线运行 | ❌ 依赖云端 | ✅ 本地 ObjectBox |
| Android 集成 | ❌ 需 Python 后端 | ✅ 纯 Kotlin |

---

## 6. 总结

### 6.1 Operit 技术栈全景

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Operit 技术架构全景                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │                    应用层 (Kotlin + Compose)                 │   │
│  ├─────────────────────────────────────────────────────────────┤   │
│  │  AI Chat │ UI Automation │ Memory │ Skills │ Voice │ MCP   │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                              │                                      │
│  ┌───────────────────────────┴───────────────────────────────────┐ │
│  │                      核心能力层                                │ │
│  ├───────────────────────────────────────────────────────────────┤ │
│  │                                                               │ │
│  │  AI Provider          UI Tools           Memory System        │ │
│  │  ├── OpenAI           ├── Accessibility   ├── RAG Retrieval  │ │
│  │  ├── Claude           ├── Shizuku         ├── Knowledge Graph│ │
│  │  ├── Gemini           ├── Root            ├── Vector Search  │ │
│  │  ├── Doubao (火山)    └── Standard        └── Chunk Storage │ │
│  │  ├── DeepSeek                                                  │ │
│  │  └── LLaMA (本地)                                              │ │
│  │                                                               │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                              │                                      │
│  ┌───────────────────────────┴───────────────────────────────────┐ │
│  │                      基础设施层                                │ │
│  ├───────────────────────────────────────────────────────────────┤ │
│  │                                                               │ │
│  │  数据存储              权限管理            网络通信            │ │
│  │  ├── ObjectBox        ├── Shizuku         ├── OkHttp         │ │
│  │  ├── DataStore        ├── libsu           ├── Retrofit       │ │
│  │  ├── Room             └── Accessibility   └── SSE            │ │
│  │  └── File System                                               │ │
│  │                                                               │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                              │                                      │
│  ┌───────────────────────────┴───────────────────────────────────┐ │
│  │                      依赖库 (无 LangChain)                     │ │
│  ├───────────────────────────────────────────────────────────────┤ │
│  │                                                               │ │
│  │  MCP SDK (官方)      HNSWLib            Jieba       ONNX     │ │
│  │  io.modelcontextprotocol.sdk          (中文分词)  (本地推理)  │ │
│  │                                                               │ │
│  └───────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 核心设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| **AI 框架** | 自研 Provider 体系 | 完全控制、离线优先、Android 原生 |
| **UI 自动化** | 三级权限架构 | 灵活性、渐进式能力、无需 PC |
| **记忆系统** | RAG + Knowledge Graph | 长期记忆、关系推理、知识复用 |
| **数据存储** | ObjectBox | 嵌入式、高性能、支持向量 |
| **扩展协议** | MCP | 官方标准、生态兼容 |

### 6.3 项目优势总结

1. **纯端侧运行** - 无需 PC、无需云服务、完全离线
2. **三级 UI 自动化** - 普通用户到 Root 用户全覆盖
3. **结构化记忆** - RAG + KG 支持 AI 长期记忆与推理
4. **MCP 扩展** - 标准化工具扩展协议
5. **国产模型支持** - 豆包、通义、星火等国内模型原生支持

---

*文档结束*