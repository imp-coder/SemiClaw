import type { ExecutionGraph, TaskNode } from "./plan-models";
import { topologicalSort, validateExecutionGraph } from "./plan-parser";
import { resolveDeepSearchI18n } from "../i18n";
const FunctionType = Java.com.ai.assistance.operit.data.model.FunctionType;
const PromptFunctionType = Java.com.ai.assistance.operit.data.model.PromptFunctionType;
const SystemPromptConfig = Java.com.ai.assistance.operit.core.config.SystemPromptConfig;
const Unit = Java.kotlin.Unit;
const Pair = Java.kotlin.Pair;

const TAG = "TaskExecutor";

const TOOL_TAG = /<tool\b[\s\S]*?<\/tool>/gi;
const TOOL_SELF_CLOSING = /<tool\b[^>]*\/>/gi;
const TOOL_RESULT_TAG = /<tool_result\b[\s\S]*?<\/tool_result>/gi;
const TOOL_RESULT_SELF = /<tool_result\b[^>]*\/>/gi;
const STATUS_TAG = /<status\b[\s\S]*?<\/status>/gi;
const STATUS_SELF = /<status\b[^>]*\/>/gi;
const THINK_TAG = /<think(?:ing)?>[\s\S]*?(<\/think(?:ing)?>|\z)/gi;
const SEARCH_TAG = /<search>[\s\S]*?(<\/search>|\z)/gi;

function removeThinkingContent(raw: string): string {
  return raw.replace(THINK_TAG, "").replace(SEARCH_TAG, "").trim();
}

function stripMarkup(text: string): string {
  return text
    .replace(TOOL_TAG, "")
    .replace(TOOL_SELF_CLOSING, "")
    .replace(TOOL_RESULT_TAG, "")
    .replace(TOOL_RESULT_SELF, "")
    .replace(STATUS_TAG, "")
    .replace(STATUS_SELF, "")
    .trim();
}

function extractFinalNonToolAssistantContent(raw: string): string {
  const noThinking = removeThinkingContent(raw.trim());
  const lastToolLike = /(<tool\s+name="([^"]+)"[\s\S]*?<\/tool>)|(<tool_result([^>]*)>[\s\S]*?<\/tool_result>)/gi;
  let lastMatch: RegExpExecArray | null = null;
  let match: RegExpExecArray | null;
  while ((match = lastToolLike.exec(noThinking)) !== null) {
    lastMatch = match;
  }
  const tail = lastMatch ? noThinking.substring((lastMatch.index || 0) + lastMatch[0].length) : noThinking;
  const tailStripped = stripMarkup(tail);
  if (tailStripped) return tailStripped;

  const fullStripped = stripMarkup(noThinking);
  if (!fullStripped) return "";
  const parts = fullStripped.split(/\n\s*\n+/).map(s => s.trim()).filter(Boolean);
  return parts.length > 0 ? parts[parts.length - 1] : fullStripped;
}

function getI18n() {
  const locale = getLang();
  return resolveDeepSearchI18n(locale);
}

async function collectStreamToString(
  stream: unknown,
  onChunk?: (chunk: string) => void
): Promise<string> {
  let buffer = "";
  const collector = {
    emit: function (value: string) {
      const chunk = String(value ?? "");
      buffer += chunk;
      if (onChunk) {
        try {
          onChunk(chunk);
        } catch (_e) {}
      }
      return Unit.INSTANCE;
    }
  };
  await (stream as { callSuspend: (...args: unknown[]) => Promise<unknown> }).callSuspend(
    "collect",
    collector
  );
  return buffer;
}

function toKotlinPairList(history: Array<[string, string]>): unknown {
  const list: unknown[] = [];
  (history || []).forEach((item) => {
    const role = item && item.length > 0 ? String(item[0] ?? "") : "";
    const content = item && item.length > 1 ? String(item[1] ?? "") : "";
    list.push(new Pair(role, content));
  });
  return list;
}

async function sendMessage(
  enhancedAIService: unknown,
  options: {
    message: string;
    chatHistory: Array<[string, string]>;
    workspacePath?: string | null;
    maxTokens: number;
    tokenUsageThreshold: number;
    customSystemPromptTemplate?: string | null;
    isSubTask: boolean;
    proxySenderName?: string | null;
    onToolInvocation?: (toolName: string) => void;
    onChunk?: (chunk: string) => void;
  }
): Promise<string> {
  const onNonFatalError = (_value: string) => Unit.INSTANCE;
  const onToolInvocation = options.onToolInvocation
    ? (toolName: string) => {
      options.onToolInvocation?.(toolName);
      return Unit.INSTANCE;
    }
    : null;

  const stream = await (enhancedAIService as { callSuspend: (...args: unknown[]) => Promise<unknown> }).callSuspend(
    "sendMessage",
    options.message,
    null,
    toKotlinPairList(options.chatHistory),
    options.workspacePath ?? null,
    null,
    FunctionType.CHAT,
    PromptFunctionType.CHAT,
    false,
    false,
    false,
    options.maxTokens,
    options.tokenUsageThreshold,
    onNonFatalError,
    null,
    options.customSystemPromptTemplate ?? null,
    options.isSubTask,
    null,
    null,
    null,
    false,
    null,
    options.proxySenderName ?? null,
    onToolInvocation,
    null,
    null,
    true
  );
  return collectStreamToString(stream, options.onChunk);
}

export class TaskExecutor {
  private taskResults: Record<string, string> = {};
  private isCancelled = false;
  private context: unknown;
  private enhancedAIService: unknown;
  private onChunk?: (chunk: string) => void;

  constructor(context: unknown, enhancedAIService: unknown, onChunk?: (chunk: string) => void) {
    this.context = context;
    this.enhancedAIService = enhancedAIService;
    this.onChunk = onChunk;
  }

  setChunkEmitter(onChunk?: (chunk: string) => void) {
    this.onChunk = onChunk;
  }

  private emitChunk(chunk: string) {
    if (!chunk || !this.onChunk) return;
    try {
      this.onChunk(chunk);
    } catch (_e) {}
  }

  cancelAllTasks() {
    this.isCancelled = true;
    this.taskResults = {};
  }

  async executeSubtasks(
    graph: ExecutionGraph,
    originalMessage: string,
    chatHistory: Array<[string, string]>,
    workspacePath: string | null | undefined,
    maxTokens: number,
    tokenUsageThreshold: number
  ): Promise<string> {
    this.isCancelled = false;
    this.taskResults = {};

    const validation = validateExecutionGraph(graph);
    if (!validation.ok) {
      return `<error>❌ ${getI18n().planErrorGraphValidationFailed}: ${validation.error}</error>\n`;
    }

    const sortedTasks = topologicalSort(graph);
    if (sortedTasks.length === 0) {
      return `<error>❌ ${getI18n().planErrorTopologicalSortFailed}</error>\n`;
    }

    let output = "";
    const startLog = `<log>📋 ${getI18n().planLogStartingExecution(String(sortedTasks.length))}</log>\n`;
    output += startLog;
    this.emitChunk(startLog);

    const completed = new Set<string>();
    const pending = [...sortedTasks];

    while (pending.length > 0 && !this.isCancelled) {
      const ready = pending.filter(task => (task.dependencies || []).every(dep => completed.has(dep)));
      if (ready.length === 0) {
        output += `<error>❌ ${getI18n().planErrorNoExecutableTasks}</error>\n`;
        break;
      }

      for (const task of ready) {
        const res = await this.executeTask(task, originalMessage, chatHistory, workspacePath, maxTokens, tokenUsageThreshold);
        output += res;
        if (this.isCancelled) break;
      }

      if (this.isCancelled) break;

      ready.forEach(task => {
        completed.add(task.id);
        const idx = pending.findIndex(t => t.id === task.id);
        if (idx >= 0) pending.splice(idx, 1);
      });
    }

    this.isCancelled = false;
    return output;
  }

  async summarize(
    graph: ExecutionGraph,
    originalMessage: string,
    chatHistory: Array<[string, string]>,
    workspacePath: string | null | undefined,
    maxTokens: number,
    tokenUsageThreshold: number
  ): Promise<string> {
    try {
      return await this.executeFinalSummary(graph, originalMessage, chatHistory, workspacePath, maxTokens, tokenUsageThreshold);
    } catch (e) {
      console.log(`${TAG} summary error`, String(e));
      return `${getI18n().planErrorSummaryFailed}: ${String(e)}`;
    }
  }

  private async executeTask(
    task: TaskNode,
    originalMessage: string,
    _chatHistory: Array<[string, string]>,
    workspacePath: string | null | undefined,
    maxTokens: number,
    tokenUsageThreshold: number
  ): Promise<string> {
    if (this.isCancelled) {
      return `<update id="${task.id}" status="FAILED" error="${getI18n().planErrorTaskCancelled}"/>\n`;
    }

    const outputParts: string[] = [];
    let toolCount = 0;

    const initialUpdate = `<update id="${task.id}" status="IN_PROGRESS" tool_count="0"/>\n`;
    outputParts.push(initialUpdate);
    this.emitChunk(initialUpdate);

    const contextInfo = this.buildTaskContext(task, originalMessage);
    const fullInstruction = this.buildFullInstruction(task, contextInfo);

    try {
      const raw = await sendMessage(this.enhancedAIService, {
        message: fullInstruction,
        chatHistory: [],
        workspacePath: workspacePath ?? null,
        maxTokens,
        tokenUsageThreshold,
        customSystemPromptTemplate: String(
          SystemPromptConfig.SUBTASK_AGENT_PROMPT_TEMPLATE || ""
        ),
        isSubTask: true,
        onToolInvocation: (toolName: string) => {
          toolCount += 1;
          const progressUpdate = `<update id="${task.id}" status="IN_PROGRESS" tool_count="${toolCount}"/>\n`;
          outputParts.push(progressUpdate);
          this.emitChunk(progressUpdate);
        }
      });

      const finalText = extractFinalNonToolAssistantContent(raw);
      this.taskResults[task.id] = finalText;
      const completedUpdate = `<update id="${task.id}" status="COMPLETED" tool_count="${toolCount}"/>\n`;
      outputParts.push(completedUpdate);
      this.emitChunk(completedUpdate);
    } catch (e) {
      const errMsg = String(e || "Unknown error").replace(/"/g, "&quot;");
      const failedUpdate = `<update id="${task.id}" status="FAILED" tool_count="${toolCount}" error="${errMsg}"/>\n`;
      outputParts.push(failedUpdate);
      this.emitChunk(failedUpdate);
      this.taskResults[task.id] = getI18n().taskErrorExecutionFailed(String(e || ""));
    }

    return outputParts.join("");
  }

  private buildTaskContext(task: TaskNode, originalMessage: string): string {
    let contextText = "";
    contextText += `${getI18n().taskContextOriginalRequest(originalMessage)}\n`;
    contextText += `${getI18n().taskContextCurrentTask(task.name)}\n`;

    if ((task.dependencies || []).length > 0) {
      contextText += `${getI18n().taskContextDependencyResults}\n`;
      task.dependencies.forEach(depId => {
        const depResult = this.taskResults[depId];
        if (depResult) {
          contextText += `${getI18n().taskContextTaskResult(depId, depResult)}\n`;
        }
      });
    }

    return contextText;
  }

  private buildFullInstruction(task: TaskNode, contextInfo: string): string {
    return getI18n().taskInstructionWithContext(contextInfo, task.instruction).trim();
  }

  private async executeFinalSummary(
    graph: ExecutionGraph,
    originalMessage: string,
    chatHistory: Array<[string, string]>,
    workspacePath: string | null | undefined,
    maxTokens: number,
    tokenUsageThreshold: number
  ): Promise<string> {
    const summaryContext = this.buildSummaryContext(originalMessage, graph);
    const i18n = getI18n();
    const fullSummaryInstruction = `${summaryContext}\n\n${i18n.finalSummaryInstructionPrefix}\n${graph.finalSummaryInstruction}\n\n${i18n.finalSummaryInstructionSuffix}`;

    return sendMessage(this.enhancedAIService, {
      message: fullSummaryInstruction,
      chatHistory,
      workspacePath: workspacePath ?? null,
      maxTokens,
      tokenUsageThreshold,
      customSystemPromptTemplate: null,
      isSubTask: false,
      onChunk: (chunk: string) => this.emitChunk(chunk)
    });
  }

  private buildSummaryContext(originalMessage: string, graph: ExecutionGraph): string {
    let contextText = "";
    contextText += `${getI18n().taskContextOriginalRequest(originalMessage)}\n`;

    const allDependencyIds = new Set<string>();
    (graph.tasks || []).forEach(task => (task.dependencies || []).forEach(dep => allDependencyIds.add(dep)));
    const allTaskIds = new Set<string>((graph.tasks || []).map(t => t.id));
    const leafTaskIds = Array.from(allTaskIds).filter(id => !allDependencyIds.has(id));

    contextText += `${getI18n().taskSummaryKeyResults}\n`;

    const taskIdsToSummarize = leafTaskIds.length > 0 ? leafTaskIds : Array.from(allTaskIds);
    taskIdsToSummarize.forEach(taskId => {
      const result = this.taskResults[taskId];
      if (result) {
        const task = (graph.tasks || []).find(t => t.id === taskId);
        const taskName = task ? task.name : taskId;
        contextText += `- ${taskName}: ${result}\n\n`;
      }
    });

    return contextText;
  }
}
