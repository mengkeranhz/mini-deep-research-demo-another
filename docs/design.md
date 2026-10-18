# mini-deep-research-demo-v2 设计方案

## 1. 目标与范围

将原手写 Java 21 Agent Harness 重实现为基于 **Spring AI + LangGraph4j** 的最小可用 Deep Research Demo。

保留原项目最关键的机制：

1. ReAct 循环；
2. 外部任务进度、事实账本、搜索日志；
3. 每轮尾部注入瘦身快照；
4. 超阈值上下文压缩与重建；
5. `final_answer` 终答闸门；
6. 技能渐进式加载；
7. 运行归档。

为遵循最小可靠原则，v2 第一版不实现：

- 并发子 Agent；
- 高德、渲染卡片、本地代码执行等领域工具；
- Anthropic/OpenAI 双协议手写客户端；
- PDF、图片和向量检索。

这些能力都可以在现有接口上追加，不影响核心架构。

## 2. 总体架构

```text
Spring Boot CLI
   |
   |--> ResearchAgent
   |      |
   |      |--> LangGraph4j StateGraph
   |      |       |-- model: 构造 Prompt / 调 Spring AI ChatModel / 处理纯文本
   |      |       |-- tools: ToolCallingManager 执行 Spring AI ToolCallback
   |      |       `-- router: final accepted / continue / END
   |      |
   |      |--> ContextCompressor: 总结并重建对话
   |      `--> FinalGate: 覆盖度、冲突、关键依赖、LLM 复核
   |
   |--> AgentContext: TaskStore + FactsStore + SearchLog + SkillState + Archive
   `--> ResearchTools: analyze/update/search/fetch/record/read/load/final
```

## 3. 原项目设计要点映射

| 原设计要点 | v2 实现 |
| --- | --- |
| React Agent Loop | LangGraph4j 图：`model -> tools -> model`，由条件边循环 |
| 上下文去噪 | 动态任务/事实/搜索快照只附加到本次 Prompt 尾部，不写入稳定消息历史 |
| 上下文压缩 | input token 超阈值时先总结纯文本执行稿，再以原始述求 + 摘要 + 技能 + 事实账本 + 搜索日志 + 旧草稿重建 |
| 计划进度 | `TaskStore` 保存任务、依赖、首次规划基线；每轮计算 ready/blocked |
| 事实账本 | `FactsStore` 保存覆盖目标与结构化事实；终答前执行覆盖/缺口/冲突/关键依赖检查 |
| 搜索进度 | `SearchLog` 记录 query/domain/hits，使用 Jaccard 近似提示重复检索 |
| 工具 | Spring AI `@Tool` + `ToolCallbacks.from()` + `ToolCallingManager` |
| Skill | 扫描 `skills/*/SKILL.md`；系统提示只列 name/description，`load_skill` 后才加载全文 |
| 终答闸门 | `final_answer` 不直接结束；先过确定性闸门，再由无工具 LLM 复核 |
| 审计归档 | 每次运行写入 `runs/run-*`，保存请求、提示词、任务/事实/搜索账本、压缩记录与最终结果 |

## 4. Agent 循环

### 4.1 `model` 节点

1. 读取稳定消息历史；
2. 若达到上下文阈值，先执行压缩；
3. 生成一个动态 `UserMessage` 尾注：当前时间、任务快照、事实快照、搜索摘要；
4. 调用 Spring AI `ChatModel`；
5. 将 Assistant 输出追加回稳定历史；
6. 无工具输出时按状态分流：
   - 无校验依据：接受简单答案；
   - 任务完成：过终答闸门；
   - 任务未完成：注入“继续执行”的纠偏消息；
   - 超轮次：返回带错误说明的最佳草稿。

尾注不进入历史，因此不会形成旧快照堆积，也有利于请求前缀缓存。

### 4.2 `tools` 节点

1. 使用 Spring AI `ToolCallingManager` 执行模型请求的工具；
2. 只把 Assistant tool call 与 ToolResponse 写回稳定历史，不写入动态尾注；
3. `final_answer` 内部调用终答闸门：
   - 通过：设置 `finalAccepted=true`，router 到 END；
   - 不通过：返回缺陷清单，router 回 model 继续修复。

## 5. 上下文管理

### 5.1 去噪

每轮注入：

- 当前时间；
- 任务进度：计划、约束、ready/blocked、任务状态；
- 事实账本瘦身快照：计数、覆盖缺口、最近新增、维度索引；
- 搜索日志摘要：最近查询与重复检索提醒。

不注入：

- 全量任务文本；
- 全量事实明细；
- 全量工具结果；
- 旧快照。

全量事实与任务账本只在压缩重建和终答校验时进入上下文。

### 5.2 压缩

触发条件：

- 上一次模型响应的 input tokens 超过配置阈值；
- 且上一轮不是终答刚被拒，避免缺陷清单被摘要稀释。

重建优先级：

```text
原始任务述求
> 执行摘要
> 上次终答缺陷
> 已加载技能全文
> 全量事实账本
> 搜索日志
> 旧答案草稿
> 继续指令
```

事实账本被声明为最终权威，防止旧草稿或摘要锚定模型。

## 6. 工具

| 工具 | 作用 |
| --- | --- |
| `analyze_query` | 解析 JSON 计划，写入任务与覆盖目标 |
| `update_task` | 更新任务状态与备注 |
| `web_search` | Tavily 搜索并记录搜索日志 |
| `fetch_url` | 抓取网页正文并压缩到安全长度 |
| `record_fact` | 立即入账事实，支持 found/proxy/not_found/critical |
| `read_file` | 受根目录约束地读取技能或运行附件 |
| `load_skill` | 加载匹配技能的完整流程 |
| `final_answer` | 提交终答，先过闸门 |

## 7. 终答闸门

顺序：

1. **覆盖度闸门**：所有 `dimension × period` 覆盖目标必须有事实；`not_found` 必须说明检索过程；
2. **冲突闸门**：同一语义事实多来源数值不一致时必须先复核；
3. **关键依赖闸门**：critical 且非 found 的事实不能直接支撑主方案，答案必须包含保守主方案和升级条件；
4. **LLM 复核**：使用无工具模型，对照首次规划基线、任务约束、技能终检和事实账本检查五类 BLOCKER：事实错误、账本遗漏、硬约束违反、关键缺口未声明、技能/关键依赖违反。

补充性意见不阻塞，符合“够用即可”原则。

## 8. 配置与安全

- API key 只从环境变量读取；
- 不提交任何真实 key；
- 文件读取限制在项目根目录内；
- URL 只允许 http/https；
- 工具异常以错误性 tool result 回传模型，避免一次工具失败直接终止整个研究；
- 归档不写入 API key。

## 9. 测试策略

- `TaskStoreTest`：基线固化、依赖 ready/blocked、进度快照；
- `FactsStoreTest`：覆盖目标、标签提示、缺口、冲突、critical；
- `SearchLogTest`：近似查询和日志渲染；
- `SkillRegistryTest`：frontmatter 解析与目录约束；
- `ResearchAgentTest`：用假 `ChatModel` 返回 `final_answer` tool call，覆盖 LangGraph4j `model -> tools -> END`、Spring AI ToolCallback 与终答闸门；
- 图与模型调用通过 Maven 编译和离线单元测试验证，不在测试中调用真实 LLM。

## 10. 实现备注

- Spring AI 2.x 各 ChatModel 只读取自家类型的 per-prompt ChatOptions（Anthropic 对通用 `ToolCallingChatOptions` 静默丢弃，OpenAI 断言失败），且不与模型默认 options 合并；因此 `ChatOptionsFactories` 按选中的模型构建 `AnthropicChatOptions` / `OpenAiChatOptions` 并复制默认的 model/maxTokens/temperature 再挂工具回调。OpenAI / Anthropic 双协议由官方 starter 各自自动装配 `ChatModel`，`ChatModelSelectorConfig` 按 `research.llm-provider`（环境变量 `LLM_PROVIDER`）用 `@Primary` 二选一，业务代码只注入通用 `ChatModel` 接口。
- LangGraph4j 默认使用 JDK 序列化克隆状态，而 Spring AI Message 不是 `Serializable`；本 Demo 不启用 checkpoint，使用浅拷贝 `StateSerializer` 隔离节点输出。
- 图递归上限设为业务轮次上限的 3 倍，容纳 `model -> tools -> model` 一轮两节点和纯文本纠偏轮。
- 工具名显式声明为 snake_case，避免 Java 方法名 camelCase 与提示词/模型输出不一致。

