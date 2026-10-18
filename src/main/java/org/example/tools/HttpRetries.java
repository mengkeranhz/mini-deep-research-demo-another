package org.example.tools;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Supplier;

/** 出站请求瞬时故障重试：只重试网络类错误与 429/5xx，线性退避。 */
final class HttpRetries {

    private HttpRetries() {
    }

    static <T> T call(String what, int retries, Supplier<T> action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= retries + 1; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (!transientFailure(e)) {
                    throw e;
                }
                last = e;
                if (attempt <= retries) {
                    sleep(what, attempt);
                }
            }
        }
        throw new IllegalStateException(what + " 连续 " + (retries + 1) + " 次瞬时失败（超时或网络抖动）；"
                + "可稍后重试，或配置 research.http.proxy-host/port 走代理: " + last.getMessage(), last);
    }

    private static boolean transientFailure(RuntimeException e) {
        if (e instanceof ResourceAccessException) {
            return true;
        }
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return false;
    }

    private static void sleep(String what, int attempt) {
        try {
            Thread.sleep(attempt * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(what + " 重试等待被中断", e);
        }
    }
}
