package org.example.agent;

import org.bsc.langgraph4j.serializer.StateSerializer;

import java.util.LinkedHashMap;

/**
 * CLI 运行不需要 checkpoint 序列化；Spring AI Message/ChatResponse 也不实现 JDK 序列化。
 * 这里使用浅拷贝隔离图输出状态，避免默认 ObjectStream 序列化失败。
 */
final class ShallowStateSerializer extends StateSerializer<ResearchGraphState> {

    ShallowStateSerializer() {
        super(ResearchGraphState::new);
    }

    @Override
    public ResearchGraphState cloneObject(ResearchGraphState state) {
        return new ResearchGraphState(new LinkedHashMap<>(state.data()));
    }

    @Override
    public String contentType() {
        return "application/x-mini-research-state";
    }

    @Override
    public String writeDataAsString(ResearchGraphState state) {
        throw new UnsupportedOperationException("本 Demo 不持久化 LangGraph checkpoint");
    }

    @Override
    public ResearchGraphState readDataFromString(String string) {
        throw new UnsupportedOperationException("本 Demo 不恢复 LangGraph checkpoint");
    }
}
