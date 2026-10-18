package org.example.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Streams long model calls and folds the chunks back into the same shape as
 * {@link ChatModel#call(Prompt)}. Non-streaming test doubles automatically fall back
 * to the blocking implementation.
 */
public final class ChatModelCalls {

    private ChatModelCalls() {
    }

    public static ChatResponse call(ChatModel chatModel, Prompt prompt) {
        Flux<ChatResponse> stream;
        try {
            stream = chatModel.stream(prompt);
        } catch (UnsupportedOperationException e) {
            return chatModel.call(prompt);
        }

        List<ChatResponse> chunks;
        try {
            chunks = stream.collectList().block();
        } catch (UnsupportedOperationException e) {
            return chatModel.call(prompt);
        }
        if (chunks == null || chunks.isEmpty()) {
            return chatModel.call(prompt);
        }

        // Tool-call chunks are already merged into a complete response by the OpenAI
        // and Anthropic Spring AI implementations. Prefer that response as-is.
        ChatResponse lastWithUsage = null;
        for (int i = chunks.size() - 1; i >= 0; i--) {
            ChatResponse chunk = chunks.get(i);
            if (lastWithUsage == null && hasPromptUsage(chunk)) {
                lastWithUsage = chunk;
            }
            if (chunk != null && chunk.hasToolCalls()) {
                return withFinalUsage(chunk, lastWithUsage);
            }
        }
        return mergeTextChunks(chunks);
    }

    private static ChatResponse mergeTextChunks(List<ChatResponse> chunks) {
        StringBuilder text = new StringBuilder();
        ChatResponse last = null;
        Generation lastGeneration = null;
        for (ChatResponse chunk : chunks) {
            if (chunk == null || chunk.getResult() == null) {
                continue;
            }
            last = chunk;
            lastGeneration = chunk.getResult();
            String value = chunk.getResult().getOutput().getText();
            if (value != null) {
                text.append(value);
            }
        }
        if (last == null || lastGeneration == null) {
            throw new IllegalStateException("模型流式响应中没有有效结果");
        }

        AssistantMessage output = lastGeneration.getOutput();
        AssistantMessage merged = AssistantMessage.builder()
                .content(text.toString())
                .properties(output.getMetadata())
                .build();
        return new ChatResponse(
                List.of(new Generation(merged, lastGeneration.getMetadata())),
                last.getMetadata()
        );
    }

    private static boolean hasPromptUsage(ChatResponse response) {
        return response != null
                && response.getMetadata().getUsage().getPromptTokens() > 0;
    }

    private static ChatResponse withFinalUsage(ChatResponse toolResponse, ChatResponse usageResponse) {
        if (usageResponse == null || usageResponse == toolResponse || hasPromptUsage(toolResponse)) {
            return toolResponse;
        }
        return new ChatResponse(toolResponse.getResults(), usageResponse.getMetadata());
    }
}
