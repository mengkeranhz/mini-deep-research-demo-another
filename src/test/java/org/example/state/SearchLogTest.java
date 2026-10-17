package org.example.state;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchLogTest {

    @Test
    void detectsSimilarQueryBeforeRecordingCurrentQuery() {
        SearchLog log = new SearchLog();
        log.record("深圳 GDP 2024", List.of(), 5);

        assertThat(log.similarTo("深圳GDP 2024 官方", 3)).hasSize(1);
        assertThat(log.render()).contains("深圳 GDP 2024 ×命中 5");
    }
}
