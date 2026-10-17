package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.skill.Skill;
import org.example.skill.SkillRegistry;
import org.example.state.AgentContext;
import org.example.state.FactsStore;
import org.example.state.TaskStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 面向模型的工具集。每个 AgentContext 对应一个新 ResearchTools 实例。 */
public class ResearchTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentContext context;
    private final SkillRegistry skills;
    private final SearchService searchService;
    private final FetchService fetchService;
    private final Path projectRoot;
    private final int defaultMaxResults;
    private final FinalGate finalGate;

    public ResearchTools(
            AgentContext context,
            SkillRegistry skills,
            SearchService searchService,
            FetchService fetchService,
            Path projectRoot,
            int defaultMaxResults,
            FinalGate finalGate
    ) {
        this.context = context;
        this.skills = skills;
        this.searchService = searchService;
        this.fetchService = fetchService;
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.defaultMaxResults = defaultMaxResults;
        this.finalGate = finalGate;
    }

    @Tool(name = "analyze_query", description = "分析用户述求并创建任务计划。必须传 JSON，字段：constraints[], unknowns[], plan, required_facts[{dimension,periods[]}], tasks[{content,depends_on[]}]")
    public String analyzeQuery(
            @ToolParam(description = "结构化计划 JSON。required_facts 会成为最终覆盖度检查表") String planJson
    ) {
        try {
            JsonNode root = MAPPER.readTree(extractJson(planJson));
            List<TaskStore.Task> tasks = normalizeTasks(root.path("tasks"));
            if (tasks.isEmpty()) {
                throw new IllegalArgumentException("计划中没有可执行任务");
            }
            context.tasks().reset(new TaskStore.Header(
                    root.path("plan").asText(""),
                    strings(root.path("constraints")),
                    strings(root.path("unknowns"))
            ), tasks);
            for (JsonNode required : root.path("required_facts")) {
                context.facts().require(required.path("dimension").asText(null), strings(required.path("periods")));
            }
            return "计划已保存。任务快照与事实覆盖缺口会每轮自动注入。请从可执行任务开始。"
                    + "\n当前进度: " + context.tasks().progress();
        } catch (Exception e) {
            throw new IllegalArgumentException("计划 JSON 无效: " + e.getMessage(), e);
        }
    }

    @Tool(name = "update_task", description = "更新任务状态。status 只能是 pending、in_progress、done")
    public String updateTask(
            @ToolParam(description = "任务 ID，如 t1") String id,
            @ToolParam(description = "pending/in_progress/done") String status,
            @ToolParam(description = "完成要点或阻塞原因", required = false) String note
    ) {
        TaskStore.Task updated = context.tasks().update(id, status, note);
        if (updated == null) {
            throw new IllegalArgumentException("未知任务 ID: " + id + "；可用: " + context.tasks().ids());
        }
        return id + " 已更新为 " + updated.status() + "；进度 " + context.tasks().progress();
    }

    @Tool(name = "web_search", description = "联网搜索。检索到关键数据后必须立即调用 record_fact 入账")
    public String webSearch(
            @ToolParam(description = "搜索关键词") String query,
            @ToolParam(description = "逗号分隔的限定域名，可为空", required = false) String domains,
            @ToolParam(description = "返回条数，1-10", required = false) Integer maxResults
    ) {
        List<String> normalizedDomains = normalizeDomains(domains);
        List<String> similar = context.searches().similarTo(query, 5);
        List<SearchService.Result> results = searchService.search(query, normalizedDomains,
                maxResults == null ? defaultMaxResults : maxResults);
        context.searches().record(query, normalizedDomains, results.size());

        StringBuilder text = new StringBuilder("搜索「").append(query).append("」得到 ")
                .append(results.size()).append(" 条结果:\n");
        int index = 1;
        for (SearchService.Result result : results) {
            text.append(index++).append(". ").append(nullToEmpty(result.title()))
                    .append("\n   URL: ").append(nullToEmpty(result.url()))
                    .append("\n   摘要: ").append(truncate(nullToEmpty(result.content()), 500)).append('\n');
        }
        if (!similar.isEmpty()) {
            text.append("\n提示: 该查询与历史检索相近，素材足够时直接入账，不要换措辞重复搜索:\n- ")
                    .append(String.join("\n- ", similar)).append('\n');
        }
        return text.toString();
    }

    @Tool(name = "fetch_url", description = "抓取网页正文。用于核验 web_search 摘要中的关键数据")
    public String fetchUrl(@ToolParam(description = "http/https URL") String url) {
        return fetchService.fetch(url, 12000);
    }

    @Tool(name = "record_fact", description = "把一条关键事实写入事实账本。status: found/proxy/not_found；not_found 必须写 note")
    public String recordFact(
            @ToolParam(description = "指标维度，必须逐字使用覆盖目标") String dimension,
            @ToolParam(description = "时期，必须逐字使用覆盖目标") String period,
            @ToolParam(description = "统计口径，可为空", required = false) String metric,
            @ToolParam(description = "事实值或短摘要") String value,
            @ToolParam(description = "来源 URL 或来源说明") String source,
            @ToolParam(description = "found/proxy/not_found") String status,
            @ToolParam(description = "口径、折算方法或已尝试的检索过程", required = false) String note,
            @ToolParam(description = "是否为关键依赖", required = false) Boolean critical
    ) {
        String normalizedStatus = normalizeStatus(status);
        if (normalizedStatus == null) {
            throw new IllegalArgumentException("status 只能是 found/proxy/not_found");
        }
        if (dimension == null || dimension.isBlank() || period == null || period.isBlank()) {
            throw new IllegalArgumentException("dimension 与 period 必填");
        }
        if ("not_found".equals(normalizedStatus) && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("not_found 必须在 note 写明已尝试的检索关键词与来源");
        }
        if (Boolean.TRUE.equals(critical) && !"found".equals(normalizedStatus)
                && (note == null || !note.contains("保守主方案") || !note.contains("升级条件"))) {
            throw new IllegalArgumentException("critical 且非 found 时，note 必须同时包含保守主方案和升级条件");
        }
        FactsStore.Fact fact = new FactsStore.Fact(dimension, period, metric, value, source,
                normalizedStatus, note, Boolean.TRUE.equals(critical));
        FactsStore.RecordOutcome outcome = context.facts().record(fact);
        String targetHint = context.facts().hitsTarget(dimension, period)
                ? ""
                : "；注意: 该 dimension@period 未命中覆盖目标，请逐字照抄目标标签";
        return "事实已入账(" + outcome + "): " + fact.dimension() + "@" + fact.period()
                + " = " + truncate(value, 200) + targetHint;
    }

    @Tool(name = "read_file", description = "读取项目内文件，优先用于 skill:// 附件；最多返回 12000 字符")
    public String readFile(@ToolParam(description = "项目相对路径或 skill:// 地址") String location) {
        try {
            Path file = location != null && location.startsWith("skill://")
                    ? skills.skillResource(location)
                    : projectRoot.resolve(location).toAbsolutePath().normalize();
            if (!file.startsWith(projectRoot) || !Files.isRegularFile(file)) {
                throw new IllegalArgumentException("文件不存在或越出项目根目录: " + location);
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return content.length() <= 12000 ? content : content.substring(0, 12000) + "…（已截断）";
        } catch (Exception e) {
            throw new IllegalArgumentException("读取失败: " + e.getMessage(), e);
        }
    }

    @Tool(name = "load_skill", description = "加载技能完整工作流程。系统提示只包含技能摘要，匹配技能时必须先调用")
    public String loadSkill(@ToolParam(description = "技能名称") String name) {
        Skill skill = skills.find(name)
                .orElseThrow(() -> new IllegalArgumentException("未知技能: " + name + "；可用: " + skills.names()));
        context.setActiveSkill(skill);
        StringBuilder text = new StringBuilder(skill.instructions());
        if (!skill.files().isEmpty()) {
            text.append("\n\n# 技能附件（按需 read_file）\n");
            skill.files().forEach(file -> text.append("- skill://").append(skill.name()).append('/').append(file).append('\n'));
        }
        if (!skill.schemas().isEmpty()) {
            text.append("\n结构化契约: ").append(String.join(", ", skill.schemas())).append('\n');
        }
        return text.toString();
    }

    @Tool(name = "final_answer", description = "提交最终答案。提交后会自动执行覆盖度、冲突和关键依赖闸门；未通过会返回缺陷清单")
    public String finalAnswer(@ToolParam(description = "完整最终答案") String answer) {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("answer 不能为空");
        }
        context.setBestAnswer(answer);
        String defects = finalGate.verify(answer);
        context.setPendingFinalDefects(defects);
        if (defects == null) {
            context.archive().write("final-result.md", "# 最终结果\n\n" + answer + "\n");
            return "最终校验通过，答案已采纳。";
        }
        return defects;
    }

    private static List<TaskStore.Task> normalizeTasks(JsonNode array) {
        List<TaskStore.Task> parsed = new ArrayList<>();
        Set<Integer> kept = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).path("content").asText("").isBlank()) {
                kept.add(i + 1);
            }
        }
        for (int i = 0; i < array.size(); i++) {
            String content = array.get(i).path("content").asText("").strip();
            if (content.isEmpty()) {
                continue;
            }
            List<String> dependencies = new ArrayList<>();
            for (JsonNode dependency : array.get(i).path("depends_on")) {
                int index = dependency.asInt(-1);
                if (kept.contains(index) && index != i + 1) {
                    dependencies.add("t" + index);
                }
            }
            parsed.add(new TaskStore.Task("t" + (i + 1), content, dependencies, "pending", null));
        }
        return parsed;
    }

    private static List<String> normalizeDomains(String domains) {
        if (domains == null || domains.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : domains.split("[,，]")) {
            String domain = item.strip().toLowerCase()
                    .replaceFirst("^https?://", "")
                    .replaceFirst("^www\\.", "")
                    .split("/")[0];
            if (!domain.isEmpty() && !out.contains(domain)) {
                out.add(domain);
            }
        }
        return out;
    }

    private static String normalizeStatus(String status) {
        String value = status == null ? "found" : status.strip();
        return switch (value) {
            case "found", "proxy", "not_found" -> value;
            default -> null;
        };
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(item -> {
                String value = item.asText("").strip();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            });
        }
        return out;
    }

    private static String extractJson(String text) {
        if (text == null) {
            return "";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text.strip();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int max) {
        String text = nullToEmpty(value).replaceAll("\\s+", " ").strip();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
