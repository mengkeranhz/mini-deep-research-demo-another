package org.example.state;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 检索行为账本，用于抑制语义相同的重复搜索。 */
public final class SearchLog {

    public record Entry(String query, List<String> domains, int hits) {
    }

    private static final int MAX_ENTRIES = 100;
    private final List<Entry> entries = new ArrayList<>();

    public List<String> similarTo(String query, int limit) {
        List<String> found = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && found.size() < limit; i--) {
            Entry entry = entries.get(i);
            if (similar(entry.query(), query)) {
                found.add(format(entry));
            }
        }
        return found;
    }

    public void record(String query, List<String> domains, int hits) {
        if (query != null && !query.isBlank()) {
            entries.add(new Entry(query.strip(), List.copyOf(domains), hits));
            trim();
        }
    }

    public String render() {
        if (entries.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder("# 已检索清单（避免换措辞重复检索）\n");
        for (int i = 0; i < entries.size(); i++) {
            text.append(i + 1).append(". ").append(format(entries.get(i))).append('\n');
        }
        return text.toString();
    }

    public String snapshot() {
        if (entries.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder("# 搜索进度（自动尾注，不要回复它）\n")
                .append("共 ").append(entries.size()).append(" 次检索；最近:\n");
        int start = Math.max(0, entries.size() - 5);
        for (int i = start; i < entries.size(); i++) {
            text.append("- ").append(format(entries.get(i))).append('\n');
        }
        return text.toString();
    }

    static boolean similar(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.contains(b) || b.contains(a)) {
            return true;
        }
        Set<String> as = tokens(left);
        Set<String> bs = tokens(right);
        if (as.isEmpty() || bs.isEmpty()) {
            return false;
        }
        Set<String> intersection = new LinkedHashSet<>(as);
        intersection.retainAll(bs);
        Set<String> union = new LinkedHashSet<>(as);
        union.addAll(bs);
        return !intersection.isEmpty() && (double) intersection.size() / union.size() >= 0.5;
    }

    private static String format(Entry entry) {
        return entry.query() + (entry.domains().isEmpty() ? "" : "（限定 " + String.join(",", entry.domains()) + "）")
                + " ×命中 " + entry.hits();
    }

    private void trim() {
        if (entries.size() > MAX_ENTRIES) {
            entries.subList(0, entries.size() - MAX_ENTRIES).clear();
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static Set<String> tokens(String value) {
        return new LinkedHashSet<>(List.of(value.toLowerCase(Locale.ROOT).split("\\s+")));
    }
}
