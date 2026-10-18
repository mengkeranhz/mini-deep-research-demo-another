package org.example.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 双协议（OpenAI / Anthropic）二选一：两个 starter 各自自动装配一个 {@link ChatModel}，
 * 直接注入接口会产生歧义，这里按 {@code research.llm-provider} 用 @Primary 选出实际生效的实现。
 */
@Configuration
public class ChatModelSelectorConfig {

    @Bean
    @Primary
    ChatModel chatModel(
            ObjectProvider<OpenAiChatModel> openAi,
            ObjectProvider<AnthropicChatModel> anthropic,
            ResearchProperties properties) {
        String provider = properties.llmProvider() == null
                ? "openai" : properties.llmProvider().trim().toLowerCase();
        return switch (provider) {
            case "anthropic" -> require(anthropic.getIfAvailable(),
                    "llm-provider=anthropic，但 spring.ai.anthropic 未正确配置");
            case "openai" -> require(openAi.getIfAvailable(),
                    "llm-provider=openai，但 spring.ai.openai 未正确配置");
            default -> throw new IllegalStateException(
                    "research.llm-provider 仅支持 openai/anthropic，当前值: " + properties.llmProvider());
        };
    }

    private static ChatModel require(ChatModel model, String message) {
        if (model == null) {
            throw new IllegalStateException(message);
        }
        return model;
    }
}
