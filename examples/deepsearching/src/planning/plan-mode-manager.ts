import type { ExecutionGraph } from "./plan-models";
import { parseExecutionGraph } from "./plan-parser";
import { TaskExecutor } from "./task-executor";
import { resolveDeepSearchI18n } from "../i18n";

const EnhancedAIService = Java.com.ai.assistance.operit.api.chat.EnhancedAIService;
const FunctionType = Java.com.ai.assistance.operit.data.model.FunctionType;
const Unit = Java.kotlin.Unit;
const Pair = Java.kotlin.Pair;
const Collections = Java.java.util.Collections;
const InputProcessingStateBase = "com.ai.assistance.operit.data.model.InputProcessingState$";

const TAG = "PlanModeManager";

const THINK_TAG = /<think(?:ing)?>[\s\S]*?(<\/think(?:ing)?>|\z)/gi;
const SEARCH_TAG = /<search>[\s\S]*?(<\/search>|\z)/gi;

function removeThinkingContent(raw: string): string {
  return raw.replace(THINK_TAG, "").replace(SEARCH_TAG, "").trim();
}

function getI18n() {
  const locale = getLang();
  return resolveDeepSearchI18n(locale);
}

async function collectStreamToString(stream: unknown): Promise<string> {
  let buffer = "";
  const collector = {
    emit: function (value: string) {
      buffer += String(value ?? "");
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

function newInputProcessingState(kind: string, message?: string) {
  const base = InputProcessingStateBase;
  if (kind === "Idle") {
    const idleCls = Java.type(base + "Idle");
    return idleCls.INSTANCE;
  }
  if (kind === "Completed") {
    const completedCls = Java.type(base + "Completed");
    return completedCls.INSTANCE;
  }
  return Java.newInstance(base + kind, String(message ?? ""));
}

async function sendPlanningMessage(
  aiService: unknown,
  context: unknown,
  message: string,
  chatHistory: Array<[string, string]>
): Promise<string> {
  const emptyModelParams = Collections.emptyList();
  const onTokensUpdated = (_a: number, _b: number, _c: number) => Unit.INSTANCE;
  const onNonFatalError = (_value: string) => Unit.INSTANCE;
  const stream = await (aiService as { callSuspend: (...args: unknown[]) => Promise<unknown> }).callSuspend(
    "sendMessage",
    context,
    message,
    toKotlinPairList(chatHistory),
    emptyModelParams,
    false,
    true,
    null,
    false,
    onTokensUpdated,
    onNonFatalError
  );
  return collectStreamToString(stream);
}

export class PlanModeManager {
  private taskExecutor: TaskExecutor;
  private isCancelled = false;
  private context: unknown;
  private enhancedAIService: unknown;

  constructor(context: unknown, enhancedAIService: unknown) {
    this.context = context;
    this.enhancedAIService = enhancedAIService;
    this.taskExecutor = new TaskExecutor(context, enhancedAIService);
  }

  cancel() {
    this.isCancelled = true;
    this.taskExecutor.cancelAllTasks();
    try {
      (this.enhancedAIService as { cancelConversation: () => void }).cancelConversation();
    } catch (_e) {}
    console.log(`${TAG} cancel called`);
  }

  shouldUseDeepSearchMode(message: string): boolean {
    const startTime = Date.now();
    const normalized = String(message || "").trim();
    if (!normalized) {
      console.log(`${TAG} shouldUseDeepSearchMode empty message elapsedMs=${Date.now() - startTime}`);
      return false;
    }
    console.log(
      `${TAG} shouldUseDeepSearchMode elapsedMs=${Date.now() - startTime} matched=true mode=always_on`
    );
    return true;
  }

  async executeDeepSearchMode(
    userMessage: string,
    chatHistory: Array<[string, string]>,
    workspacePath: string | null | undefined,
    maxTokens: number,
    tokenUsageThreshold: number,
    onChunk?: (chunk: string) => void
  ): Promise<string> {
    this.isCancelled = false;
    let output = "";
    this.taskExecutor.setChunkEmitter(onChunk);
    const append = (chunk: string) => {
      output += chunk;
      if (onChunk) {
        try {
          onChunk(chunk);
        } catch (_e) {}
      }
    };
    try {
      const i18n = getI18n();
      const processingState = newInputProcessingState(
        "Processing",
        i18n.planModeExecutingDeepSearch
      );
      (this.enhancedAIService as { setInputProcessingState: (s: unknown) => void })
        .setInputProcessingState(processingState);

      const executionGraph = await this.generateExecutionPlan(
        userMessage,
        chatHistory,
        workspacePath,
        maxTokens,
        tokenUsageThreshold
      );

      if (this.isCancelled) {
        append(`<log>🟡 ${i18n.planModeTaskCancelled}</log>\n`);
        return output;
      }

      if (!executionGraph) {
        append(`<error>❌ ${i18n.planModeFailedToGeneratePlan}</error>\n`);
        const idleState = newInputProcessingState("Idle");
        (this.enhancedAIService as { setInputProcessingState: (s: unknown) => void })
          .setInputProcessingState(idleState);
        return output;
      }

      append(`<plan>\n`);
      append(`<graph><![CDATA[${JSON.stringify(executionGraph)}]]></graph>\n`);

      const executingState = newInputProcessingState(
        "Processing",
        i18n.planModeExecutingSubtasks
      );
      (this.enhancedAIService as { setInputProcessingState: (s: unknown) => void })
        .setInputProcessingState(executingState);

      const executionOutput = await this.taskExecutor.executeSubtasks(
        executionGraph,
        userMessage,
        chatHistory,
        workspacePath,
        maxTokens,
        tokenUsageThreshold
      );
      output += executionOutput;

      if (this.isCancelled) {
        append(`<log>🟡 ${i18n.planModeCancelling}</log>\n`);
        append(`</plan>\n`);
        return output;
      }

      append(`<log>🎯 ${i18n.planModeAllTasksCompleted}</log>\n`);
      append(`</plan>\n`);

      const summaryState = newInputProcessingState(
        "Processing",
        i18n.planModeSummarizingResults
      );
      (this.enhancedAIService as { setInputProcessingState: (s: unknown) => void })
        .setInputProcessingState(summaryState);

      const summary = await this.taskExecutor.summarize(
        executionGraph,
        userMessage,
        chatHistory,
        workspacePath,
        maxTokens,
        tokenUsageThreshold
      );
      output += summary;

      const completedState = newInputProcessingState("Completed");
      (this.enhancedAIService as { setInputProcessingState: (s: unknown) => void })
        .setInputProcessingState(completedState);

      return output;
    } catch (e) {
      if (this.isCancelled) {
        append(`<log>🟡 ${getI18n().planModeCancelled}</log>\n`);
      } else {
        append(`<error>❌ ${getI18n().planModeExecutionFailed}: ${String(e)}</error>\n`);
      }
      const idleState = newInputProcessingState("Idle");
      (this.enhancedAIService as { setInputProcessingState: (s: unknown) => void })
        .setInputProcessingState(idleState);
      return output;
    } finally {
      this.isCancelled = false;
      this.taskExecutor.setChunkEmitter(undefined);
    }
  }

  private buildPlanningRequest(userMessage: string): string {
    const i18n = getI18n();
    return `${i18n.planGenerationPrompt}\n\n${i18n.planGenerationUserRequestPrefix}${userMessage}`.trim();
  }

  private async generateExecutionPlan(
    userMessage: string,
    chatHistory: Array<[string, string]>,
    _workspacePath: string | null | undefined,
    _maxTokens: number,
    _tokenUsageThreshold: number
  ): Promise<ExecutionGraph | null> {
    try {
      const planningRequest = this.buildPlanningRequest(userMessage);
      const planningHistory: Array<[string, string]> = [["system", planningRequest]];

      const aiService = await EnhancedAIService.callSuspend(
        "getAIServiceForFunction",
        this.context,
        FunctionType.CHAT
      );

      const planResponseRaw = await sendPlanningMessage(
        aiService,
        this.context,
        getI18n().planGenerateDetailedPlan,
        planningHistory
      );
      const planResponse = removeThinkingContent(String(planResponseRaw ?? "").trim());
      console.log(`${TAG} plan response`, planResponse);

      const graph = parseExecutionGraph(planResponse);
      return graph;
    } catch (e) {
      console.log(`${TAG} generate plan error`, String(e));
      return null;
    }
  }
}
