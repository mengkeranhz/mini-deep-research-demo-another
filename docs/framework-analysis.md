# Java AI 框架选型分析

## 1. 结论

本项目采用 **Spring AI 2.0.x + LangGraph4j 1.9.x**：

- **Spring AI**：负责 OpenAI 兼容模型调用、工具元信息生成、工具执行和统一配置。
- **LangGraph4j**：负责显式有状态循环图：`model -> tools -> router -> model`，让 ReAct 循环、上下文压缩和终答闸门发生在可观察、可测试的图节点中。

不引入 LangChain4j。Demo 的目标是小而稳定，双 Agent 框架会同时带来两套模型、内存、工具与观测抽象，增加兼容成本，却没有对应收益。

## 2. 候选组合

| 组合 | 评价 | 结论 |
| --- | --- | --- |
| Spring AI only | `ChatClient` 的 Tool Calling Advisor 能自动循环，但自动循环把“每一轮边界”藏起来。本项目需要在每轮调用前注入任务/事实快照，在 `final_answer` 前拦截做闸门，因此更适合使用低层 `ChatModel` 自主控制。 | 不采用 |
| Spring AI + LangGraph4j | Spring AI 提供稳定模型与工具层；LangGraph4j 提供显式状态图和条件路由，正好承载自定义 ReAct harness。两者职责清晰，不重复。 | **采用** |
| Spring AI + LangChain4j | LangChain4j 的 AI Services、ChatMemory 和 Tool API 很完整，但与 Spring AI 重叠。若同时引入，工具回调、消息模型、内存和观测需要适配两套体系。 | 不采用 |
| LangChain4j + LangGraph4j | 适合非 Spring 或已以 LangChain4j 为主的技术栈。LangChain4j Agentic 模块仍标记为 experimental，当前不是最小可靠方案。 | 不采用 |
| LangGraph4j only | 只解决图编排，不解决模型供应商适配与工具调用协议。 | 不采用 |

## 3. 选择原因

### 3.1 Spring AI 的边界

本项目刻意使用 Spring AI 的低层 `ChatModel`：

- 每次调用显式传入 `ToolCallingChatOptions` 与 `ToolCallback`；
- 模型返回 tool call 后由本项目的 LangGraph4j `tools` 节点统一执行；
- 每轮都能在调用前生成“尾注快照”，避免把动态状态写进稳定历史；
- `ToolCallingManager` 负责工具结果消息协议，减少手写 provider 细节。

这正是 Spring AI 文档中对“自定义 orchestrator”的建议用法。

### 3.2 LangGraph4j 的边界

LangGraph4j 不负责调用模型，而是提供：

- `AgentState` 与 Channel，保存对话、轮次、最终答案等运行状态；
- `StateGraph` 的节点和条件边，显式表达循环；
- 图结构本身，便于之后扩展 checkpoint、子图和并行节点。

因此框架分工是：**Spring AI = AI 基础设施；LangGraph4j = Agent 状态机**。

## 4. 版本策略

使用当前 Maven Central 稳定版本：

- Spring Boot：4.0.x
- Spring AI：2.0.x
- LangGraph4j：1.9.x

避免 milestone、snapshot 或 experimental 模块。Demo 不启用持久化 checkpoint、远程 Studio、向量库和 MCP，优先保证本地 CLI 可运行、可测试。

