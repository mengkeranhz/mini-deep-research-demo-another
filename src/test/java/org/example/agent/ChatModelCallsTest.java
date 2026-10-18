package org.example.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatModelCallsTest {

    @Test
    void mergesTextChunksAndKeepsFinalMetadata() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("streaming test");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(
                        response("青甘"),
                        response("大环线", finalMetadata())
                );
            }
        };

        ChatResponse merged = ChatModelCalls.call(model, new Prompt("test"));

        assertThat(merged.getResult().getOutput().getText()).isEqualTo("青甘大环线");
        assertThat(merged.getMetadata().getId()).isEqualTo("chat-1");
        assertThat(merged.getMetadata().getUsage().getPromptTokens()).isEqualTo(123);
    }

    @Test
    void keepsCompleteToolCallResponse() {
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "web_search", "{}")))
                .build();
        ChatResponse toolResponse = new ChatResponse(List.of(new Generation(output)));
        ChatResponse usageResponse = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").build())), finalMetadata());
        ChatModel streamingModel = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("streaming test");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(response("ignored"), toolResponse, usageResponse);
            }
        };

        ChatResponse merged = ChatModelCalls.call(streamingModel, new Prompt("test"));

        assertThat(merged.getResult().getOutput().getToolCalls()).isEqualTo(output.getToolCalls());
        assertThat(merged.getMetadata().getUsage().getPromptTokens()).isEqualTo(123);
    }

    @Test
    void fallsBackWhenModelDoesNotSupportStreaming() {
        ChatResponse expected = response("blocking");
        ChatModel model = prompt -> expected;

        assertThat(ChatModelCalls.call(model, new Prompt("test"))).isSameAs(expected);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content(text)
                .build())));
    }

    private static ChatResponse response(String text, ChatResponseMetadata metadata) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content(text)
                .build())), metadata);
    }

    private static ChatResponseMetadata finalMetadata() {
        return ChatResponseMetadata.builder()
                .id("chat-1")
                .usage(new TestUsage(123, 8))
                .build();
    }

    private record TestUsage(int promptTokens, int completionTokens) implements Usage {
        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }
}
