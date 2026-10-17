package org.example.agent;

import org.bsc.langgraph4j.state.AgentState;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** LangGraph4j 运行状态。稳定消息历史不含每轮动态尾注。 */
@SuppressWarnings("unchecked")
public final class ResearchGraphState extends AgentState {

    public ResearchGraphState(Map<String, Object> data) {
        super(data);
    }

    public List<Message> messages() {
        return (List<Message>) value("messages").orElseGet(ArrayList::new);
    }

    public String request() {
        return (String) value("request").orElse("");
    }

    public String systemPrompt() {
        return (String) value("systemPrompt").orElse("");
    }

    public int round() {
        return (Integer) value("round").orElse(0);
    }

    public String finalAnswer() {
        return (String) value("finalAnswer").orElse("");
    }

    public boolean finalAccepted() {
        return (Boolean) value("finalAccepted").orElse(false);
    }

    public boolean lastHadToolCalls() {
        return (Boolean) value("lastHadToolCalls").orElse(false);
    }

    public boolean lastFinalRejected() {
        return (Boolean) value("lastFinalRejected").orElse(false);
    }

    public long lastInputTokens() {
        return (Long) value("lastInputTokens").orElse(0L);
    }

    public ChatResponse lastResponse() {
        return (ChatResponse) value("lastResponse").orElse(null);
    }

    public List<Message> lastPromptMessages() {
        return (List<Message>) value("lastPromptMessages").orElseGet(ArrayList::new);
    }
}
