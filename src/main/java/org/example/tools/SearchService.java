package org.example.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
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
    private final RestClient client;

    public SearchService(org.example.config.ResearchProperties properties) {
        this.apiKey = properties.tavilyApiKey();
        this.client = RestClient.builder()
                .baseUrl("https://api.tavily.com")
                .build();
    }

    public List<Result> search(String query, List<String> domains, int maxResults) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 TAVILY_API_KEY，无法执行 web_search");
        }
        int limit = Math.max(1, Math.min(10, maxResults));
        TavilyResponse response = client.post()
                .uri("/search")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .body(new Request(query, "basic", domains, limit))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
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
