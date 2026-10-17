package org.example.state;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStoreTest {

    @Test
    void snapshotCalculatesReadyAndBlockedTasks() {
        TaskStore store = new TaskStore();
        store.reset(
                new TaskStore.Header("研究深圳GDP", List.of("地区：深圳"), List.of("口径未知")),
                List.of(
                        new TaskStore.Task("t1", "检索GDP", List.of(), "done", "已入账"),
                        new TaskStore.Task("t2", "汇总", List.of("t1"), "pending", null),
                        new TaskStore.Task("t3", "校验", List.of("t2"), "pending", null)
                )
        );

        assertThat(store.progress()).isEqualTo("1/3 完成");
        assertThat(store.snapshot()).contains("可执行: t2", "阻塞: t3", "硬约束: 地区：深圳");
    }

    @Test
    void baselineIsFixedWhenReplanning() {
        TaskStore store = new TaskStore();
        store.reset(new TaskStore.Header("第一版", List.of("A"), List.of()),
                List.of(new TaskStore.Task("t1", "任务", List.of(), "pending", null)));
        store.reset(new TaskStore.Header("第二版", List.of("B"), List.of()),
                List.of(new TaskStore.Task("t1", "新任务", List.of(), "pending", null)));

        assertThat(store.baseline()).contains("第一版", "硬约束: A").doesNotContain("第二版");
    }
}
