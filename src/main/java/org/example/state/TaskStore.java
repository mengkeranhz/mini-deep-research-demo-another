package org.example.state;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务与首次规划基线。模型每轮只能看到 snapshot()，不能直接访问本对象。 */
public final class TaskStore {

    public record Task(String id, String content, List<String> dependsOn, String status, String note) {
    }

    public record Header(String plan, List<String> constraints, List<String> unknowns) {
    }

    private Header header;
    private Header baseline;
    private final List<Task> tasks = new ArrayList<>();

    public void reset(Header newHeader, List<Task> newTasks) {
        if (baseline == null) {
            baseline = newHeader;
        }
        header = newHeader;
        tasks.clear();
        tasks.addAll(newTasks);
    }

    public Task update(String id, String status, String note) {
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            if (!task.id().equals(id)) {
                continue;
            }
            String nextStatus = normalizeStatus(status);
            String nextNote = note == null || note.isBlank() ? task.note() : note.strip();
            Task updated = new Task(task.id(), task.content(), task.dependsOn(), nextStatus, nextNote);
            tasks.set(i, updated);
            return updated;
        }
        return null;
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }

    public boolean allDone() {
        return tasks.stream().allMatch(task -> "done".equals(task.status()));
    }

    public String progress() {
        return tasks.stream().filter(task -> "done".equals(task.status())).count() + "/" + tasks.size() + " 完成";
    }

    public String baseline() {
        if (baseline == null) {
            return null;
        }
        StringBuilder text = new StringBuilder("# 校验基线（首次规划，固定不变）\n");
        appendHeader(text, baseline);
        return text.toString();
    }

    public String ledger() {
        if (header == null && tasks.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder("# 任务进度账本\n\n")
                .append("- 进度: ").append(progress()).append('\n')
                .append("- 更新时间: ").append(LocalDateTime.now()).append("\n\n");
        if (baseline != null) {
            appendHeader(text, baseline);
        }
        if (header != null) {
            text.append("## 当前规划\n");
            appendHeader(text, header);
        }
        text.append("## 任务明细\n");
        for (Task task : tasks) {
            text.append("- ").append(task.id()).append(" [").append(task.status()).append("] ")
                    .append(task.content());
            if (!task.dependsOn().isEmpty()) {
                text.append("；依赖: ").append(String.join(",", task.dependsOn()));
            }
            if (task.note() != null && !task.note().isBlank()) {
                text.append("；备注: ").append(task.note());
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** 每轮重算的紧凑快照：ready/blocked 不落库，避免派生状态陈旧。 */
    public String snapshot() {
        if (tasks.isEmpty()) {
            return null;
        }
        Map<String, String> statusById = new LinkedHashMap<>();
        tasks.forEach(task -> statusById.put(task.id(), task.status()));
        List<String> ready = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        for (Task task : tasks) {
            if ("done".equals(task.status())) {
                continue;
            }
            List<String> waiting = task.dependsOn().stream()
                    .filter(dep -> !"done".equals(statusById.get(dep)))
                    .toList();
            (waiting.isEmpty() ? ready : blocked).add(task.id());
        }

        StringBuilder text = new StringBuilder("# 任务进度快照（自动尾注，不要回复它）\n");
        if (header != null) {
            appendHeader(text, header);
        }
        text.append("进度: ").append(progress())
                .append("；可执行: ").append(ready.isEmpty() ? "无" : String.join(",", ready))
                .append("；阻塞: ").append(blocked.isEmpty() ? "无" : String.join(",", blocked))
                .append('\n');
        for (Task task : tasks) {
            text.append(task.id()).append(" [").append(label(task.status())).append("] ");
            if ("done".equals(task.status())) {
                text.append(truncate(task.note() == null || task.note().isBlank() ? task.content() : task.note(), 80));
            } else {
                text.append(task.content());
                if (task.note() != null && !task.note().isBlank()) {
                    text.append(" —— ").append(task.note());
                }
            }
            text.append('\n');
        }
        return text.toString();
    }

    public List<String> ids() {
        return tasks.stream().map(Task::id).toList();
    }

    private static void appendHeader(StringBuilder text, Header value) {
        if (!value.plan().isBlank()) {
            text.append("- 总体计划: ").append(value.plan()).append('\n');
        }
        value.constraints().forEach(item -> text.append("- 硬约束: ").append(item).append('\n'));
        value.unknowns().forEach(item -> text.append("- 信息缺口: ").append(item).append('\n'));
        text.append('\n');
    }

    private static String normalizeStatus(String status) {
        return switch (status == null ? "" : status.strip()) {
            case "in_progress", "doing" -> "in_progress";
            case "done", "completed" -> "done";
            default -> "pending";
        };
    }

    private static String label(String status) {
        return switch (status) {
            case "done" -> "完成";
            case "in_progress" -> "进行中";
            default -> "待办";
        };
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
