package com.luoye.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 联网检索：优先 Tavily，否则 DuckDuckGo HTML。 */
@Service
public class WebSearchService {

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final String tavilyKey;

    public WebSearchService(
            ObjectMapper mapper,
            @Value("${luoye.search.tavily-api-key:}") String tavilyKey) {
        this.mapper = mapper;
        this.tavilyKey = tavilyKey;
    }

    public String search(String query) {
        if (query == null || query.isBlank()) {
            return "缺少搜索关键词";
        }
        try {
            if (tavilyKey != null && !tavilyKey.isBlank()) {
                return tavily(query);
            }
            return duckDuckGo(query);
        } catch (Exception e) {
            return "联网搜索失败：" + e.getMessage();
        }
    }

    private String tavily(String query) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of(
                "api_key", tavilyKey,
                "query", query,
                "max_results", 5));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.tavily.com/search"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode results = mapper.readTree(response.body()).path("results");
        List<String> lines = new ArrayList<>();
        int i = 1;
        for (JsonNode node : results) {
            lines.add(i++ + ". " + node.path("title").asText()
                    + "\n" + node.path("url").asText()
                    + "\n" + node.path("content").asText(""));
            if (i > 5) {
                break;
            }
        }
        return lines.isEmpty() ? "没有搜索结果" : String.join("\n\n", lines);
    }

    private String duckDuckGo(String query) throws Exception {
        String url = "https://html.duckduckgo.com/html/?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "LuoYe/0.1")
                .GET()
                .build();
        String html = http.send(request, HttpResponse.BodyHandlers.ofString()).body();
        Matcher matcher = Pattern.compile(
                        "uddg=([^&\"]+).*?result__a[^>]*>(.*?)</a>.*?result__snippet[^>]*>(.*?)</(?:a|td|div)",
                        Pattern.DOTALL)
                .matcher(html);
        List<String> lines = new ArrayList<>();
        int i = 1;
        while (matcher.find() && i <= 5) {
            String link = java.net.URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
            String title = matcher.group(2).replaceAll("<[^>]+>", "").trim();
            String snippet = matcher.group(3).replaceAll("<[^>]+>", "").trim();
            lines.add(i++ + ". " + title + "\n" + link + "\n" + snippet);
        }
        if (lines.isEmpty()) {
            Matcher simple = Pattern.compile("class=\"result__a\"[^>]*>(.*?)</a>", Pattern.DOTALL)
                    .matcher(html);
            while (simple.find() && lines.size() < 5) {
                lines.add((lines.size() + 1) + ". "
                        + simple.group(1).replaceAll("<[^>]+>", "").trim());
            }
        }
        return lines.isEmpty() ? "没有搜索结果（可配置 TAVILY_API_KEY 提高成功率）" : String.join("\n\n", lines);
    }
}
