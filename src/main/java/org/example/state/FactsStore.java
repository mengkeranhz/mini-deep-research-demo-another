package org.example.state;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 事实账本：覆盖目标、结构化事实、缺口与冲突的最终权威。 */
public final class FactsStore {

    public record Fact(
            String dimension,
            String period,
            String metric,
            String value,
            String source,
            String status,
            String note,
            boolean critical
    ) {
        public Fact {
            dimension = canonicalDimension(dimension);
            metric = metric == null ? "" : metric.strip();
            value = value == null ? "" : value.strip();
            source = source == null ? "" : source.strip();
            status = status == null ? "found" : status.strip();
            note = note == null ? "" : note.strip();
        }
    }

    public enum RecordOutcome {NEW, UPDATED, UNCHANGED}

    private final Map<String, List<String>> targets = new LinkedHashMap<>();
    private final Map<String, Fact> facts = new LinkedHashMap<>();
    private final Map<String, Fact> recentChanges = new LinkedHashMap<>();

    public void require(String dimension, List<String> periods) {
        if (dimension == null || dimension.isBlank()) {
            return;
        }
        String dim = canonicalDimension(dimension);
        LinkedHashSet<String> merged = new LinkedHashSet<>(targets.getOrDefault(dim, List.of()));
        periods.forEach(period -> {
            if (period != null && !period.isBlank()) {
                merged.add(period.strip());
            }
        });
        targets.put(dim, new ArrayList<>(merged));
    }

    public RecordOutcome record(Fact fact) {
        String key = storageKey(fact);
        Fact old = facts.get(key);
        if (old == null) {
            facts.put(key, fact);
            recentChanges.put(key, fact);
            return RecordOutcome.NEW;
        }
        if (old.equals(fact)) {
            return RecordOutcome.UNCHANGED;
        }
        facts.put(key, fact);
        recentChanges.put(key, fact);
        return RecordOutcome.UPDATED;
    }

    public boolean isEmpty() {
        return targets.isEmpty() && facts.isEmpty();
    }

    public String coverageLine() {
        if (targets.isEmpty()) {
            return facts.size() + " 条事实";
        }
        List<String> missing = missingPeriods();
        int targetCount = targets.values().stream().mapToInt(List::size).sum();
        return "覆盖 " + (targetCount - missing.size()) + "/" + targetCount + " 个目标格子"
                + (missing.isEmpty() ? "" : "；缺口: " + String.join("、", missing));
    }

    public String snapshot() {
        if (isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder("# 事实账本快照（自动尾注，不要回复它）\n")
                .append(facts.size()).append(" 条事实；").append(coverageLine()).append('\n');
        if (!recentChanges.isEmpty()) {
            text.append("上次快照以来新增/变更:\n");
            recentChanges.values().forEach(fact -> text.append("- ")
                    .append(fact.dimension()).append('@').append(fact.period()).append(" = ")
                    .append(snapshotValue(fact.value())).append(" (").append(fact.status()).append(")\n"));
        }
        Map<String, Long> byDimension = new LinkedHashMap<>();
        facts.values().forEach(fact -> byDimension.merge(fact.dimension(), 1L, Long::sum));
        text.append("维度索引:\n");
        byDimension.forEach((dimension, count) -> text.append("- ").append(dimension).append(" ×").append(count).append('\n'));
        text.append("检索前先对照维度索引；新事实立即 record_fact 入账；最终数据必须与账本一致。\n");
        recentChanges.clear();
        return text.toString();
    }

    public String ledger() {
        if (isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder("# 事实账本（最终权威依据）\n")
                .append("- 更新时间: ").append(LocalDateTime.now()).append('\n');
        if (!targets.isEmpty()) {
            text.append("\n## 覆盖目标\n");
            targets.forEach((dimension, periods) -> text.append("- ")
                    .append(dimension).append(": ").append(String.join("、", periods)).append('\n'));
        }
        text.append("\n## 已入账事实\n");
        int index = 1;
        for (Fact fact : facts.values()) {
            text.append(index++).append(". [").append(fact.dimension()).append("] ").append(fact.period());
            if (!fact.metric().isBlank()) {
                text.append(" | ").append(fact.metric());
            }
            text.append(" = ").append(fact.value().isBlank() ? "（未获得）" : fact.value())
                    .append(" | ").append(fact.status());
            if (fact.critical()) {
                text.append(" | critical");
            }
            if (!fact.source().isBlank()) {
                text.append(" | 来源: ").append(fact.source());
            }
            if (!fact.note().isBlank()) {
                text.append(" | ").append(fact.note());
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** 确定性终答闸门：缺口、放弃过早和多来源/多口径冲突。 */
    public String gateReport() {
        List<String> gaps = new ArrayList<>();
        for (Map.Entry<String, List<String>> target : targets.entrySet()) {
            for (String period : target.getValue()) {
                if (!hitsTarget(target.getKey(), period)) {
                    List<String> suspects = suspectsFor(target.getKey(), period);
                    gaps.add(target.getKey() + "@" + period + "：覆盖目标要求但账本无记录"
                            + (suspects.isEmpty() ? "" : "；疑似标签错位: " + String.join("、", suspects)));
                }
            }
        }
        facts.values().stream()
                .filter(fact -> "not_found".equals(fact.status()) && fact.note().isBlank())
                .forEach(fact -> gaps.add(fact.dimension() + "@" + fact.period()
                        + "：声明未检索到，但没有说明已尝试的关键词与来源"));

        Map<String, List<Fact>> semantic = new LinkedHashMap<>();
        facts.values().forEach(fact -> semantic.computeIfAbsent(semanticKey(fact), key -> new ArrayList<>()).add(fact));
        semantic.forEach((key, values) -> {
            if (values.size() < 2) {
                return;
            }
            Set<String> evidence = new LinkedHashSet<>();
            values.forEach(value -> evidence.add(value.value() + "|" + value.status()));
            if (evidence.size() > 1) {
                gaps.add(key + "：同一事实存在冲突 " + String.join("；", evidence)
                        + "；终答前必须复核并只采用可靠一方");
            }
        });
        return gaps.isEmpty() ? null : String.join("\n", gaps);
    }

    public String criticalAssumptions() {
        List<String> critical = facts.values().stream()
                .filter(fact -> fact.critical() && !"found".equals(fact.status()))
                .map(fact -> fact.dimension() + "@" + fact.period() + " = " + fact.value()
                        + " (" + fact.status() + ")")
                .toList();
        return critical.isEmpty() ? null : String.join("\n", critical);
    }

    public boolean hitsTarget(String dimension, String period) {
        String dim = canonicalDimension(dimension);
        if (!targets.containsKey(dim)) {
            return true;
        }
        return facts.values().stream()
                .anyMatch(fact -> fact.dimension().equals(dim) && fact.period().equals(period == null ? "" : period.strip()));
    }

    public List<String> nearTargets(String dimension) {
        String dim = canonicalDimension(dimension);
        return targets.keySet().stream()
                .filter(item -> !item.equals(dim) && normalized(item).contains(normalized(dim)))
                .toList();
    }

    private List<String> suspectsFor(String dimension, String period) {
        return facts.values().stream()
                .filter(fact -> fact.dimension().equals(dimension) || normalized(fact.dimension()).contains(normalized(dimension)))
                .map(fact -> fact.dimension() + "@" + fact.period())
                .limit(2)
                .toList();
    }

    private static String storageKey(Fact fact) {
        return String.join("|", fact.dimension(), fact.period(), fact.metric(), fact.source());
    }

    private static String semanticKey(Fact fact) {
        return String.join("|", fact.dimension(), fact.period(), fact.metric());
    }

    public static String canonicalDimension(String dimension) {
        if (dimension == null) {
            return "";
        }
        String value = dimension.strip();
        int chinese = value.indexOf('：');
        int ascii = value.indexOf(':');
        int index = chinese >= 0 ? chinese : ascii;
        if (chinese >= 0 && ascii >= 0 && ascii < chinese) {
            index = ascii;
        }
        return index > 0 && index < value.length() - 1
                ? value.substring(0, index) + "·" + value.substring(index + 1).strip()
                : value;
    }

    private static String snapshotValue(String value) {
        return value == null || value.isBlank() ? "（未获得）" : truncate(value, 180);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String normalized(String value) {
        return value.toLowerCase().replaceAll("[\\s：:·，,/-]", "");
    }

    private List<String> missingPeriods() {
        List<String> missing = new ArrayList<>();
        targets.forEach((dimension, periods) -> periods.forEach(period -> {
            if (!hitsTarget(dimension, period)) {
                missing.add(dimension + "@" + period);
            }
        }));
        return missing;
    }
}
