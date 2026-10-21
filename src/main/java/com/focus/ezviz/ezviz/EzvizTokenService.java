package com.focus.ezviz.ezviz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.focus.ezviz.config.AppProperties;
import com.focus.ezviz.domain.EzvizAccount;
import com.focus.ezviz.repository.EzvizAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * 萤石开放平台 accessToken 管理。
 * <p>接口：POST /api/lapp/token/get，appKey + appSecret 换取 accessToken，有效期 7 天。</p>
 */
@Service
public class EzvizTokenService {

    private static final Logger log = LoggerFactory.getLogger(EzvizTokenService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AppProperties props;
    private final EzvizAccountRepository accountRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public EzvizTokenService(AppProperties props, EzvizAccountRepository accountRepository) {
        this.props = props;
        this.accountRepository = accountRepository;
        this.httpClient = HttpSupport.newClient(props.getEzviz().getConnectTimeoutSeconds());
    }

    /**
     * 获取可用 token，必要时自动刷新并回写数据库。
     */
    public String getToken(EzvizAccount account, boolean forceRefresh) {
        if (!forceRefresh && account.getAccessToken() != null && !account.getAccessToken().isBlank()
                && account.getTokenExpiresAt() != null
                && account.getTokenExpiresAt().isAfter(LocalDateTime.now().plusMinutes(5))) {
            return account.getAccessToken();
        }
        if (account.getAppKey() == null || account.getAppKey().isBlank()
                || account.getAppSecret() == null || account.getAppSecret().isBlank()) {
            if (account.getAccessToken() != null && !account.getAccessToken().isBlank()) {
                return account.getAccessToken();
            }
            throw new EzvizApiException("config", "萤石 appKey/appSecret 未配置");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("appKey", account.getAppKey());
        params.put("appSecret", account.getAppSecret());
        JsonNode root = postForm("/api/lapp/token/get", null, params, "获取 accessToken 失败");

        JsonNode data = root.path("data");
        String token = data.path("accessToken").asText(null);
        if (token == null || token.isBlank()) {
            throw new EzvizApiException("token", "萤石未返回 accessToken：" + root);
        }
        long expireTime = data.path("expireTime").asLong(0);
        LocalDateTime expiresAt = expireTime > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(expireTime), ZONE)
                : LocalDateTime.now().plusDays(6);

        account.setAccessToken(token);
        account.setTokenExpiresAt(expiresAt);
        account.setUpdatedAt(LocalDateTime.now());
        accountRepository.save(account);
        log.info("萤石账号[{}] accessToken 已刷新，有效期至 {}", account.getName(), expiresAt);
        return token;
    }

    public JsonNode postForm(String path, Map<String, String> headers, Map<String, ?> form, String errorPrefix) {
        try {
            HttpRequest.Builder builder = HttpSupport.request(
                    URI.create(props.getEzviz().getBaseUrl() + path),
                    headers,
                    Duration.ofSeconds(props.getEzviz().getReadTimeoutSeconds()));
            builder.header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
            builder.POST(HttpRequest.BodyPublishers.ofString(HttpSupport.form(form)));
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String body = HttpSupport.readBody(response);
            if (response.statusCode() / 100 != 2) {
                throw new EzvizApiException("http_" + response.statusCode(),
                        errorPrefix + "，HTTP " + response.statusCode() + "：" + abbreviate(body));
            }
            JsonNode root = objectMapper.readTree(body);
            String code = root.has("code") ? root.path("code").asText()
                    : root.path("meta").path("code").asText();
            if (!"200".equals(code)) {
                String msg = root.has("msg") ? root.path("msg").asText()
                        : root.path("meta").path("message").asText("");
                throw new EzvizApiException(code, errorPrefix + "：" + code + " " + msg);
            }
            return root;
        } catch (EzvizApiException e) {
            throw e;
        } catch (Exception e) {
            throw new EzvizApiException(errorPrefix + "：" + e.getMessage(), e);
        }
    }

    public JsonNode get(String pathWithQuery, Map<String, String> headers, String errorPrefix) {
        try {
            HttpRequest request = HttpSupport.request(
                            URI.create(props.getEzviz().getBaseUrl() + pathWithQuery),
                            headers,
                            Duration.ofSeconds(props.getEzviz().getReadTimeoutSeconds()))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = HttpSupport.readBody(response);
            if (response.statusCode() / 100 != 2) {
                throw new EzvizApiException("http_" + response.statusCode(),
                        errorPrefix + "，HTTP " + response.statusCode() + "：" + abbreviate(body));
            }
            JsonNode root = objectMapper.readTree(body);
            String code = root.has("code") ? root.path("code").asText()
                    : root.path("meta").path("code").asText();
            if (!"200".equals(code)) {
                throw new EzvizApiException(code, errorPrefix + "：" + code + " " + root.path("msg").asText(""));
            }
            return root;
        } catch (EzvizApiException e) {
            throw e;
        } catch (Exception e) {
            throw new EzvizApiException(errorPrefix + "：" + e.getMessage(), e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
