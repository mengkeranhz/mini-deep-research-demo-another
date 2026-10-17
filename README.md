# mini-deep-research-demo-v2

基于 **Spring AI 2.0 + LangGraph4j 1.9** 的最小 Deep Research Agent Demo。

## 运行

```bash
export OPENAI_API_KEY=...
export OPENAI_BASE_URL=https://open.bigmodel.cn/api/paas/v4
export OPENAI_MODEL=glm-5.3
export TAVILY_API_KEY=...

mvn spring-boot:run -Dspring-boot.run.arguments="--query='对比 2024 与 2025 深圳GDP数据'"
```

也可以省略 `--query`，程序会提示输入。

## 文档

- [`docs/framework-analysis.md`](docs/framework-analysis.md)：Java AI 框架选型。
- [`docs/original-analysis.md`](docs/original-analysis.md)：原手写 Agent Harness 的设计要点分析。
- [`docs/design.md`](docs/design.md)：架构与原项目设计要点映射。

## 核心机制

- LangGraph4j 显式 ReAct 状态图；
- Spring AI `ChatModel` 低层工具调用；
- 任务、事实、搜索三类外部账本；
- 动态尾注快照，不污染稳定对话；
- 超阈值上下文压缩与重建；
- `final_answer` 终答闸门；
- Markdown Skill 渐进加载；
- 每次运行自动归档到 `runs/run-*`。

