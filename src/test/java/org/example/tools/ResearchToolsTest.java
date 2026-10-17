package org.example.tools;

import org.example.skill.SkillRegistry;
import org.example.state.AgentContext;
import org.example.state.RunArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchToolsTest {

    @TempDir
    Path tempDir;

    @Test
    void analyzeQueryCreatesTasksAndFactTargets() {
        ResearchTools tools = new ResearchTools(context(), new SkillRegistry(tempDir),
                null, null, tempDir, 5, null);

        String result = tools.analyzeQuery("""
                {"plan":"检索后汇总","constraints":["地区：深圳"],"unknowns":["口径"],
                "required_facts":[{"dimension":"GDP","periods":["2024","2025"]}],
                "tasks":[{"content":"检索","depends_on":[]},{"content":"汇总","depends_on":[1]}]}
                """);

        assertThat(result).contains("计划已保存", "0/2 完成");
    }

    @Test
    void recordFactValidatesCriticalFallback() {
        ResearchTools tools = new ResearchTools(context(), new SkillRegistry(tempDir),
                null, null, tempDir, 5, null);

        assertThatThrownBy(() -> tools.recordFact("路况", "2026", "", "未知", "x", "proxy", "", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("保守主方案");

        String result = tools.recordFact("路况", "2026", "", "未知", "x", "proxy", "保守主方案：A；升级条件：B", true);
        assertThat(result).contains("事实已入账(NEW)");
    }

    private AgentContext context() {
        return new AgentContext(new RunArchive(tempDir.resolve("runs")));
    }
}
