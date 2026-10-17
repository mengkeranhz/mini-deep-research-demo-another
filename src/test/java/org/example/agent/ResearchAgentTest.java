package org.example.agent;

import org.example.skill.SkillRegistry;
import org.example.state.AgentContext;
import org.example.state.RunArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void runsModelToolRouterLoopAndAcceptsFinalAnswer() {
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-final", "function", "final_answer", "{\"answer\":\"图回路验证通过\"}"
                )))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(output)));
        ChatModel chatModel = prompt -> response;

        AgentContext context = new AgentContext(new RunArchive(tempDir.resolve("runs")));
        ResearchAgent agent = new ResearchAgent(
                chatModel,
                3,
                1000,
                context,
                new SkillRegistry(tempDir),
                null,
                null,
                tempDir,
                3
        );

        String result = agent.run("验证最小回路");

        assertThat(result).isEqualTo("图回路验证通过");
        assertThat(context.isFinalAccepted()).isTrue();
    }
}
