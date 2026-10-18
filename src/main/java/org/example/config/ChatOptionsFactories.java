package org.example.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * 构建带工具回调的 per-prompt options。
 *
 * <p>Spring AI 2.x 各 ChatModel 只读取自家类型的 ChatOptions：Anthropic 对通用
 * {@code ToolCallingChatOptions} 会静默丢弃（工具不进请求），OpenAI 直接断言失败。
 * 且 per-prompt options 不与模型默认 options 合并，model/maxTokens 必须显式复制，
 * 否则 Anthropic 请求缺 max_tokens 会被网关拒绝。
 */
public final class ChatOptionsFactories {

    private ChatOptionsFactories() {
    }

    public static ToolCallingChatOptions toolCallingOptions(ChatModel chatModel, ToolCallback... callbacks) {
        if (chatModel instanceof AnthropicChatModel anthropicModel) {
            AnthropicChatOptions defaults = anthropicModel.getOptions();
            return AnthropicChatOptions.builder()
                    .model(defaults.getModel())
                    .maxTokens(defaults.getMaxTokens())
                    .temperature(defaults.getTemperature())
                    .toolCallbacks(callbacks)
                    .build();
        }
        if (chatModel instanceof OpenAiChatModel openAiModel) {
            OpenAiChatOptions defaults = openAiModel.getOptions();
            return OpenAiChatOptions.builder()
                    .model(defaults.getModel())
                    .temperature(defaults.getTemperature())
                    .toolCallbacks(callbacks)
                    .build();
        }
        return ToolCallingChatOptions.builder().toolCallbacks(callbacks).build();
    }
}
