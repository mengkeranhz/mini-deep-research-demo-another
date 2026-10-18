package org.example.tools;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpRetriesTest {

    @Test
    void retriesTransientFailuresUntilSuccess() {
        AtomicInteger attempts = new AtomicInteger();

        String result = HttpRetries.call("搜索", 2, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ResourceAccessException("read timeout");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void throwsImmediatelyOnNonTransientStatus() {
        RestClientResponseException badRequest =
                new RestClientResponseException("400", 400, "Bad Request", null, null, null);

        assertThatThrownBy(() -> HttpRetries.call("搜索", 3, () -> {
            throw badRequest;
        })).isSameAs(badRequest);
    }

    @Test
    void exhaustsRetriesWithSummary() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> HttpRetries.call("搜索", 1, () -> {
            attempts.incrementAndGet();
            throw new ResourceAccessException("read timeout");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 次瞬时失败")
                .hasMessageContaining("research.http.proxy-host");
        assertThat(attempts).hasValue(2);
    }
}
