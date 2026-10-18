package org.example.agent;

import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncCommandAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.Command;
import org.bsc.langgraph4j.state.Channels;
import org.example.config.ChatOptionsFactories;
import org.example.skill.SkillRegistry;
import org.example.state.AgentContext;
import org.example.state.RunArchive;
import org.example.tools.FinalGate;
import org.example.tools.ResearchTools;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Spring AI 模型/工具层 + LangGraph4j 显式 ReAct 状态机。 */
public final class ResearchAgent {

    public static final String MODEL_NODE = "model";
    public static final String TOOLS_NODE = "tools";

    private final ChatModel chatModel;
    private final AgentContext context;
    private final SkillRegistry skillRegistry;
    private final ResearchTools tools;
    private final ToolCallingManager toolCallingManager;
    private final ToolCallingChatOptions toolOptions;
    private final ContextCompressor compressor;
    private final int maxRounds;

    public ResearchAgent(
            ChatModel chatModel,
            int maxRounds,
            int contextTokenThreshold,
            AgentContext context,
            SkillRegistry skills,
            org.example.tools.SearchService searchService,
            org.example.tools.FetchService fetchService,
            java.nio.file.Path projectRoot,
            int defaultSearchResults
    ) {
        this.chatModel = chatModel;
        this.context = context;
        this.skillRegistry = skills;
        this.maxRounds = Math.max(1, maxRounds);
        this.tools = new ResearchTools(context, skills, searchService, fetchService,
                projectRoot, defaultSearchResults, new FinalGate(chatModel, context));
        // 工具异常默认会重抛并打死整个 run；改为把错误文本回给模型，让它换关键词/来源自行调整。
        this.toolCallingManager = ToolCallingManager.builder()
                .toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(false))
                .build();
        this.toolOptions = ChatOptionsFactories.toolCallingOptions(chatModel, ToolCallbacks.from(tools));
        this.compressor = new ContextCompressor(chatModel, contextTokenThreshold);
    }

    public String run(String request) {
        String systemPrompt = PromptTemplates.systemPrompt(skillRegistry.promptSection());
        context.archive().write("request.md", "# 原始述求\n\n" + request + "\n");
        context.archive().write("system-prompt.md", "# 系统提示词\n\n" + systemPrompt + "\n");

        StateGraph<ResearchGraphState> graph;
        try {
            graph = new StateGraph<>(
                    Map.of(
                            "messages", Channels.base(ArrayList::new),
                            "round", Channels.base(() -> 0),
                            "finalAnswer", Channels.base(() -> ""),
                            "finalAccepted", Channels.base(() -> false),
                            "lastHadToolCalls", Channels.base(() -> false),
                            "lastFinalRejected", Channels.base(() -> false),
                            "lastInputTokens", Channels.base(() -> 0L)
                    ),
                    new ShallowStateSerializer()
            )
                    .addNode(MODEL_NODE, AsyncNodeAction.node_async(this::modelNode))
                    .addNode(TOOLS_NODE, AsyncNodeAction.node_async(this::toolsNode))
                    .addEdge(StateGraph.START, MODEL_NODE)
                    .addConditionalEdges(MODEL_NODE, AsyncCommandAction.command_async((state, config) -> {
                        if (state.finalAccepted()) {
                            return new Command(StateGraph.END);
                        }
                        return new Command(state.lastHadToolCalls() ? TOOLS_NODE : MODEL_NODE);
                    }), Map.of(
                            StateGraph.END, StateGraph.END,
                            TOOLS_NODE, TOOLS_NODE,
                            MODEL_NODE, MODEL_NODE
                    ))
                    .addConditionalEdges(TOOLS_NODE, AsyncCommandAction.command_async((state, config) ->
                            new Command(state.finalAccepted() ? StateGraph.END : MODEL_NODE)),
                            Map.of(StateGraph.END, StateGraph.END, MODEL_NODE, MODEL_NODE));
        } catch (Exception e) {
            throw new IllegalStateException("构建 LangGraph4j 研究图失败", e);
        }

        ResearchGraphState result;
        try {
            result = graph.compile()
                    .invoke(GraphInput.args(Map.of(
                            "request", request,
                            "systemPrompt", systemPrompt,
                            "messages", List.of(new SystemMessage(systemPrompt), new UserMessage(request))
                    )), RunnableConfig.builder().recursionLimit(maxRounds * 3).build())
                    .orElseThrow(() -> new IllegalStateException("研究图没有返回状态"));
        } catch (GraphStateException | RuntimeException e) {
            archiveFailure(e);
            throw new IllegalStateException("执行 LangGraph4j 研究图失败", e);
        }
        archive(result);
        String answer = result.finalAnswer();
        return answer == null || answer.isBlank()
                ? "研究未产生最终答案。请查看归档: " + context.archive().directory()
                : answer;
    }

    private Map<String, Object> modelNode(ResearchGraphState state) {
        if (state.round() >= maxRounds) {
            String fallback = context.bestAnswer() == null || context.bestAnswer().isBlank()
                    ? "超过最大轮次 " + maxRounds + "，未获得通过终答闸门的答案。"
                    : context.bestAnswer() + "\n\n（说明：达到最大轮次，以下为最完整草稿，可能未通过全部校验。）";
            return Map.of("finalAccepted", true, "finalAnswer", fallback);
        }

        List<Message> messages = new ArrayList<>(state.messages());
        if (compressor.needed(state.lastInputTokens(), state.lastFinalRejected())) {
            messages = compressor.compress(state.systemPrompt(), state.request(), messages, context);
        }

        UserMessage snapshot = new UserMessage(tailSnapshot());
        List<Message> callMessages = new ArrayList<>(messages);
        callMessages.add(snapshot);
        Prompt prompt = new Prompt(callMessages, toolOptions);
        ChatResponse response = ChatModelCalls.call(chatModel, prompt);
        AssistantMessage output = response.getResult().getOutput();
        String text = output.getText() == null ? "" : output.getText();
        List<Message> nextMessages = new ArrayList<>(messages);
        nextMessages.add(output);

        long inputTokens = response.getMetadata() == null || response.getMetadata().getUsage() == null
                ? 0 : response.getMetadata().getUsage().getPromptTokens();
        boolean hasToolCalls = response.hasToolCalls();
        boolean rejected = false;
        String finalAnswer = state.finalAnswer();
        boolean accepted = false;

        if (!hasToolCalls) {
            if (text.isBlank()) {
                nextMessages.add(new UserMessage("上一轮没有文本也没有工具调用。请继续执行任务；完成后调用 final_answer。"));
            } else if (context.tasks().isEmpty() && context.facts().isEmpty()) {
                context.setBestAnswer(text);
                finalAnswer = text;
                accepted = true;
            } else if (context.tasks().isEmpty() || context.tasks().allDone()) {
                context.setBestAnswer(text);
                String defects = new FinalGate(chatModel, context).verify(text);
                if (defects == null) {
                    finalAnswer = text;
                    accepted = true;
                } else {
                    rejected = true;
                    nextMessages.add(new UserMessage(defects));
                }
            } else {
                nextMessages.add(new UserMessage(PromptTemplates.CONTINUE_WHEN_TASKS_PENDING));
            }
        }

        List<Message> promptWithAssistant = new ArrayList<>(callMessages);
        promptWithAssistant.add(output);
        return Map.of(
                "messages", nextMessages,
                "round", state.round() + 1,
                "finalAnswer", finalAnswer == null ? "" : finalAnswer,
                "finalAccepted", accepted,
                "lastHadToolCalls", hasToolCalls,
                "lastFinalRejected", rejected,
                "lastInputTokens", inputTokens,
                "lastResponse", response,
                "lastPromptMessages", promptWithAssistant
        );
    }

    private Map<String, Object> toolsNode(ResearchGraphState state) {
        ChatResponse response = state.lastResponse();
        if (response == null || !response.hasToolCalls()) {
            return Map.of("lastHadToolCalls", false);
        }
        Prompt prompt = new Prompt(state.lastPromptMessages(), toolOptions);
        ToolExecutionResult execution = toolCallingManager.executeToolCalls(prompt, response);
        List<Message> conversation = execution.conversationHistory();
        int assistantIndex = -1;
        for (int i = 0; i < conversation.size(); i++) {
            if (conversation.get(i) instanceof AssistantMessage) {
                assistantIndex = i;
            }
        }
        List<Message> nextMessages = new ArrayList<>(state.messages());
        if (assistantIndex >= 0) {
            nextMessages.addAll(conversation.subList(assistantIndex + 1, conversation.size()));
        }

        boolean accepted = context.isFinalAccepted();
        String finalAnswer = accepted ? context.bestAnswer() : state.finalAnswer();
        return Map.of(
                "messages", nextMessages,
                "finalAccepted", accepted,
                "finalAnswer", finalAnswer == null ? "" : finalAnswer,
                "lastHadToolCalls", false,
                "lastFinalRejected", !accepted && context.pendingFinalDefects() != null,
                "lastPromptMessages", List.of()
        );
    }

    private String tailSnapshot() {
        StringBuilder text = new StringBuilder("[当前自动状态尾注，非用户消息，不要回复它] 时间: ")
                .append(LocalDateTime.now()).append('\n');
        append(text, context.tasks().snapshot());
        append(text, context.facts().snapshot());
        append(text, context.searches().snapshot());
        return text.toString();
    }

    private static void append(StringBuilder text, String value) {
        if (value != null && !value.isBlank()) {
            text.append("\n").append(value).append('\n');
        }
    }

    private void archive(ResearchGraphState state) {
        RunArchive archive = context.archive();
        archive.write("task-ledger.md", context.tasks().ledger());
        archive.write("fact-ledger.md", context.facts().ledger());
        archive.write("search-ledger.md", context.searches().render());
        if (context.activeSkill() != null) {
            archive.write("skill.md", "# 已加载技能: " + context.activeSkill().name()
                    + "\n\n" + context.activeSkill().instructions() + "\n");
        }
        if (context.bestAnswer() != null && !context.bestAnswer().isBlank()) {
            archive.write("best-answer-draft.md", "# 最完整答案草稿\n\n" + context.bestAnswer() + "\n");
        }
        archive.write("conversation.md", renderConversation(state.messages()));
        archive.write("run-summary.md", "# Run Summary\n\n"
                + "- 状态: " + (state.finalAccepted() ? "complete" : "failed") + '\n'
                + "- 轮次: " + state.round() + "/" + maxRounds + '\n'
                + "- 事实覆盖: " + context.facts().coverageLine() + '\n'
                + "- 归档: " + archive.directory() + "\n");
        archive.write("archive-index.md", archive.markdownIndex());
    }

    private void archiveFailure(Exception error) {
        RunArchive archive = context.archive();
        archive.write("task-ledger.md", context.tasks().ledger());
        archive.write("fact-ledger.md", context.facts().ledger());
        archive.write("search-ledger.md", context.searches().render());
        archive.write("last-error.md", "# 最近错误\n\n" + error.getMessage() + "\n");
        archive.write("run-summary.md", "# Run Summary\n\n"
                + "- 状态: failed\n"
                + "- 事实覆盖: " + context.facts().coverageLine() + "\n"
                + "- 错误: " + error.getMessage() + "\n"
                + "- 归档: " + archive.directory() + "\n");
        archive.write("archive-index.md", archive.markdownIndex());
    }

    private static String renderConversation(List<Message> messages) {
        StringBuilder text = new StringBuilder("# 完整对话（不含每轮动态尾注）\n\n");
        for (Message message : messages) {
            text.append("## ").append(message.getMessageType()).append('\n')
                    .append(message.getText() == null ? "" : message.getText()).append("\n\n");
        }
        return text.toString();
    }
}
