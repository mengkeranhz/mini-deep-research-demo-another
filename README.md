# mini-deep-research-demo-v2

基于 **Spring AI 2.0 + LangGraph4j 1.9** 的最小 Deep Research Agent Demo。

## 运行

内置 OpenAI / Anthropic 双协议，通过 `LLM_PROVIDER` 选择（默认 `openai`）。两套协议读同一把 key：优先 `LLM_API_KEY`，回退 `OPENAI_API_KEY`（兼容旧配置，不用改名）。

### OpenAI 兼容通道（默认）

```bash
export LLM_API_KEY=...
export TAVILY_API_KEY=...

mvn spring-boot:run -Dspring-boot.run.arguments="--query='对比 2024 与 2025 深圳GDP数据'"
```

### Anthropic 通道（智谱 Coding Plan 额度挂在 `/api/anthropic` 时）

```bash
export LLM_PROVIDER=anthropic
export LLM_API_KEY=...
export TAVILY_API_KEY=...

mvn spring-boot:run -Dspring-boot.run.arguments="--query='对比 2024 与 2025 深圳GDP数据'"
```

### 环境变量一览

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `LLM_PROVIDER` | `openai` | 协议选择：`openai` \| `anthropic` |
| `LLM_API_KEY` | 回退 `OPENAI_API_KEY` | 协议中性的 key 名 |
| `OPENAI_BASE_URL` | `https://open.bigmodel.cn/api/coding/paas/v4` | OpenAI 通道地址 |
| `ANTHROPIC_BASE_URL` | `https://open.bigmodel.cn/api/anthropic` | Anthropic 通道地址 |
| `LLM_MODEL` | 回退 `OPENAI_MODEL`（`glm-5.3`） | 两通道共用模型名 |
| `LLM_TIMEOUT` | `900s` | LLM 单次请求超时。Spring AI OpenAI 客户端默认只有 60s，长推理容易触发 `InterruptedIOException: timeout` |
| `LLM_MAX_RETRIES` | `1` | LLM SDK 连接层重试次数；长请求超时后过多重试会放大等待时间 |
| `LLM_REASONING_EFFORT` | `low` | GLM-5.3 支持 `low/high/max`，官方默认 `max`。Agent 的工具路由属于高频小决策，默认用 `low` 可避免隐藏深推理拖满超时；模型不支持该参数时可设为空 |
| `LLM_MAX_TOKENS` | `32768` | OpenAI 通道单次输出上限，避免 GLM-5.3 按最大 128K 输出长期生成 |
| `RESEARCH_CONTEXT_TOKEN_THRESHOLD` | `16000` | 触发上下文压缩的 inputTokens 阈值 |
| `TAVILY_MAX_RESULTS` | `5` | 每次 `web_search` 默认结果数；过大会快速污染上下文 |
| `RESEARCH_HTTP_PROXY_HOST` / `RESEARCH_HTTP_PROXY_PORT` | 空（直连） | 检索出站（Tavily + fetch_url）HTTP 代理，如 Clash `127.0.0.1` / `7897` |
| `RESEARCH_HTTP_RETRIES` | `2` | 检索瞬时故障（超时、429、5xx）重试次数 |
| `RESEARCH_HTTP_RESPONSE_TIMEOUT` | `30s` | 检索出站响应超时 |

检索出站默认直连并显式忽略 JVM 系统代理（IDEA 运行配置会注入本地代理，境外线路不稳时会把 Tavily 拖死）。晚高峰直连境外仍不稳时，设 `RESEARCH_HTTP_PROXY_HOST/PORT` 走代理。LLM 通道流量不走 `research.http.*`，其超时由 `LLM_TIMEOUT` 控制。

LLM 主循环、上下文压缩和终答复核都使用流式接口接收、再在本地聚合成 `ChatResponse`，避免长答案在网关侧完整缓冲。工具执行阶段限制并行工具调用，并在上下文压缩后要求模型先完成一个最小动作，防止直接生成超长终稿。

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
