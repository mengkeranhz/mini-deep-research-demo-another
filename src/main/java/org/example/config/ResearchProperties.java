package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "research")
public record ResearchProperties(
        String llmProvider,
        int maxRounds,
        int contextTokenThreshold,
        String tavilyApiKey,
        int tavilyMaxResults,
        @DefaultValue Http http,
        String projectRoot,
        String archiveRoot
) {

    /** yml 未配 research.http.* 时兜底，避免空指针。 */
    public Http effectiveHttp() {
        return http != null ? http : new Http(null, null, 0, null, 0);
    }

    /** 检索出站 HTTP 参数。compact 构造器统一兜底非法值。 */
    public record Http(
            @DefaultValue("10s") Duration connectTimeout,
            @DefaultValue("30s") Duration responseTimeout,
            @DefaultValue("2") int retries,
            @DefaultValue("") String proxyHost,
            @DefaultValue("0") int proxyPort
    ) {

        public Http {
            connectTimeout = positiveOrDefault(connectTimeout, Duration.ofSeconds(10));
            responseTimeout = positiveOrDefault(responseTimeout, Duration.ofSeconds(30));
            retries = Math.max(0, retries);
            proxyHost = proxyHost == null ? "" : proxyHost.strip();
            proxyPort = Math.max(0, proxyPort);
        }

        public boolean proxied() {
            return !proxyHost.isEmpty() && proxyPort > 0;
        }

        private static Duration positiveOrDefault(Duration value, Duration fallback) {
            return value == null || value.isZero() || value.isNegative() ? fallback : value;
        }
    }
}
