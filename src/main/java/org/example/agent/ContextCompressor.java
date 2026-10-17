package org.example.agent;

import org.example.state.AgentContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/** 超阈值时总结纯文本执行稿，并用外部账本重建稳定历史。 */
public final class ContextCompressor {

    private final ChatModel chatModel;
    private final int threshold;

    public ContextCompressor(ChatModel chatModel, int threshold) {
        this.chatModel = chatModel;
        this.threshold = threshold;
    }

    public boolean needed(long inputTokens, boolean finalRejectedJustNow) {
        return inputTokens > threshold && !finalRejectedJustNow;
    }

    public List<Message> compress(String systemPrompt, String request, List<Message> messages, AgentContext context) {
        String transcript = transcript(messages);
        String summary;
        try {
            summary = chatModel.call(new Prompt(List.of(
                    new SystemMessage("你是研究执行进度摘要器。保留未完成任务、已检索对象、关键结论、冲突和用户约束，删除重复过程细节。直接输出摘要。"),
                    new UserMessage(transcript)
            ))).getResult().getOutput().getText();
        } catch (Exception e) {
            summary = "摘要生成失败，以下使用任务与事实账本恢复进度：" + context.tasks().snapshot();
        }

        StringBuilder rebuilt = new StringBuilder("原始任务述求：\n").append(request);
        if (summary != null && !summary.isBlank()) {
            rebuilt.append("\n\n之前的执行进度摘要：\n").append(summary);
        }
        if (context.pendingFinalDefects() != null && !context.pendingFinalDefects().isBlank()) {
            rebuilt.append("\n\n【上次终答缺陷，最高优先级】\n").append(context.pendingFinalDefects());
        }
        if (context.activeSkill() != null) {
            rebuilt.append("\n\n【已加载技能，继续严格执行】\n").append(context.activeSkill().instructions());
        }
        String facts = context.facts().ledger();
        if (facts != null) {
            rebuilt.append("\n\n【事实账本，最终权威依据】\n").append(facts);
        }
        String searches = context.searches().render();
        if (searches != null) {
            rebuilt.append('\n').append(searches);
        }
        if (context.bestAnswer() != null && !context.bestAnswer().isBlank()) {
            rebuilt.append("\n\n【旧答案草稿，仅供结构参考；与账本冲突时以账本为准】\n").append(context.bestAnswer());
        }
        rebuilt.append("\n\n请基于以上进度继续完成任务；后续数据一律以事实账本为准。");

        context.archive().write("context-compression.md", "# 上下文压缩\n\n"
                + "- 触发前 inputTokens 阈值: " + threshold + "\n\n## 摘要\n\n" + summary + "\n");
        return new ArrayList<>(List.of(new SystemMessage(systemPrompt), new UserMessage(rebuilt.toString())));
    }

    private static String transcript(List<Message> messages) {
        StringBuilder text = new StringBuilder();
        for (Message message : messages) {
            if (message instanceof SystemMessage) {
                continue;
            }
            if (message instanceof UserMessage user) {
                text.append("用户: ").append(truncate(user.getText(), 2000)).append('\n');
            } else if (message instanceof AssistantMessage assistant) {
                String value = assistant.getText() == null || assistant.getText().isBlank()
                        ? "（调用工具）" : assistant.getText();
                text.append("助手: ").append(truncate(value, 2000)).append('\n');
            } else if (message instanceof ToolResponseMessage tool) {
                String toolText = tool.getResponses().stream()
                        .map(ToolResponseMessage.ToolResponse::responseData)
                        .reduce("", (left, right) -> left + "\n" + right);
                text.append("工具结果: ").append(truncate(toolText, 600)).append('\n');
            }
        }
        return text.toString();
    }

    private static String truncate(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
