package com.focus.ezviz.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.focus.ezviz.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通过独立算法服务（HTTP）执行分析，适合把 Python 算法部署为常驻服务、GPU 推理集群等场景。
 * <p>约定：POST JSON {video, tasks, sampleFps, roi, lang}，返回分析结果 JSON。</p>
 */
@Component
@ConditionalOnProperty(name = "app.analyzer.mode", havingValue = "http")
public class HttpAnalyzerRunner implements AnalyzerRunner {

    private static final Logger log = LoggerFactory.getLogger(HttpAnalyzerRunner.class);

    private final AppProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public HttpAnalyzerRunner(AppProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public JsonNode analyze(String videoFile, String roi, String tasks) {
        AppProperties.Analyzer cfg = props.getAnalyzer();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("video", videoFile);
            payload.put("tasks", tasks);
            payload.put("sampleFps", cfg.getSampleFps());
            payload.put("roi", roi);
            payload.put("lang", cfg.getLang());

            HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.getHttpUrl()))
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            log.info("调用算法服务: {}", cfg.getHttpUrl());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new AnalyzerException("算法服务返回 HTTP " + response.statusCode() + "：" + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (AnalyzerException e) {
            throw e;
        } catch (Exception e) {
            throw new AnalyzerException("调用算法服务失败: " + e.getMessage(), e);
        }
    }
}
