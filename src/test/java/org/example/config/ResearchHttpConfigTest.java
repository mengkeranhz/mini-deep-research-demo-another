package org.example.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchHttpConfigTest {

    @Test
    void buildsDirectFactoryWhenHttpSectionMissing() {
        ResearchProperties properties = new ResearchProperties(
                "openai", 25, 24000, "key", 25, null, "/tmp", "/tmp");

        ClientHttpRequestFactory factory = new ResearchHttpConfig().researchHttpRequestFactory(properties);

        assertThat(factory).isNotNull();
    }

    @Test
    void buildsProxiedFactoryFromConfig() {
        ResearchProperties.Http proxied = new ResearchProperties.Http(
                null, null, 2, "127.0.0.1", 7897);
        ResearchProperties properties = new ResearchProperties(
                "openai", 25, 24000, "key", 25, proxied, "/tmp", "/tmp");

        ClientHttpRequestFactory factory = new ResearchHttpConfig().researchHttpRequestFactory(properties);

        assertThat(factory).isNotNull();
    }
}
