package org.example.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.example.config.ResearchProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/** Tavily 搜索客户端。缺 key 时明确失败，不做假装联网的 mock。 */
@Service
public class SearchService {

    public record Result(String title, String url, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TavilyResponse(List<TavilyItem> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TavilyItem(String title, String url, String content) {
    }

    private final String apiKey;
    private final int retries;
    private final RestClient client;

    public SearchService(ResearchProperties properties, ClientHttpRequestFactory requestFactory) {
        this.apiKey = properties.tavilyApiKey();
        this.retries = properties.effectiveHttp().retries();
        this.client = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl("https://api.tavily.com")
                .build();
    }

    public List<Result> search(String query, List<String> domains, int maxResults) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 TAVILY_API_KEY，无法执行 web_search");
        }
        int limit = Math.max(1, Math.min(10, maxResults));
        // 超时与代理在 ResearchHttpConfig 统一配置；瞬时失败在这里重试，避免一次网络抖动打死整个 run。
        TavilyResponse response = HttpRetries.call("Tavily 搜索「" + query + "」", retries, () -> client.post()
                .uri("/search")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .body(new Request(query, "basic", domains, limit))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                }));
        if (response == null || response.results() == null) {
            return List.of();
        }
        return response.results().stream()
                .map(item -> new Result(item.title(), item.url(), item.content()))
                .toList();
    }

    private record Request(String query, String search_depth, List<String> include_domains, int max_results) {
    }
}
