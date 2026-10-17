package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "research")
public record ResearchProperties(
        int maxRounds,
        int contextTokenThreshold,
        String tavilyApiKey,
        int tavilyMaxResults,
        String projectRoot,
        String archiveRoot
) {
}
