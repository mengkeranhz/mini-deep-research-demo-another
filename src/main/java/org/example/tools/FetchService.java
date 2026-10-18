package org.example.tools;

import org.example.config.ResearchProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;

/** 简单网页正文抓取，输出统一截断，避免长页面污染上下文。 */
@Service
public class FetchService {

    private final int retries;
    private final RestClient client;

    public FetchService(ResearchProperties properties, ClientHttpRequestFactory requestFactory) {
        this.retries = properties.effectiveHttp().retries();
        this.client = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    public String fetch(String url, int maxChars) {
        URI uri = URI.create(url == null ? "" : url.strip());
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("只允许 http/https URL");
        }
        String html = HttpRetries.call("抓取 " + uri.getHost(), retries, () -> client.get()
                .uri(uri)
                .header(HttpHeaders.USER_AGENT, "mini-deep-research-demo-v2/1.0")
                .retrieve()
                .body(String.class));
        if (html == null || html.isBlank()) {
            return "页面内容为空";
        }
        Document document = Jsoup.parse(html);
        String text = document.text()
                .replaceAll("\\s+", " ")
                .strip();
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…（已截断）";
    }
}
