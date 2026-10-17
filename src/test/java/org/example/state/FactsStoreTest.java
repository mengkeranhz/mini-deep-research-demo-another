package org.example.state;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FactsStoreTest {

    @Test
    void gateReportsMissingTargetAndLabelSuspect() {
        FactsStore store = new FactsStore();
        store.require("GDP", List.of("2024", "2025"));
        store.record(new FactsStore.Fact("GDP", "2024年", "", "3.5%", "official", "found", "", false));

        assertThat(store.gateReport())
                .contains("GDP@2024：覆盖目标要求但账本无记录")
                .contains("GDP@2025：覆盖目标要求但账本无记录")
                .contains("疑似标签错位");
    }

    @Test
    void gateReportsConflictAndNotFoundWithoutEvidence() {
        FactsStore store = new FactsStore();
        store.require("人口", List.of("2025"));
        store.record(new FactsStore.Fact("人口", "2025", "常住", "100", "a", "found", "", false));
        store.record(new FactsStore.Fact("人口", "2025", "常住", "101", "b", "found", "", false));
        store.record(new FactsStore.Fact("就业", "2025", "", "", "x", "not_found", "", false));

        assertThat(store.gateReport()).contains("同一事实存在冲突", "没有说明已尝试的关键词与来源");
    }

    @Test
    void criticalAssumptionsOnlyIncludeUnverifiedCriticalFacts() {
        FactsStore store = new FactsStore();
        store.record(new FactsStore.Fact("门票", "2026", "", "需预约", "official", "found", "", true));
        store.record(new FactsStore.Fact("路况", "2026", "", "未知", "x", "proxy", "", true));

        assertThat(store.criticalAssumptions()).contains("路况@2026").doesNotContain("门票@2026");
    }
}
