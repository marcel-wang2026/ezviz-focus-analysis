package com.focus.ezviz.ezviz;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 轻量 HTTP 工具，基于 JDK HttpClient。自动识别 HTTP_PROXY / HTTPS_PROXY 环境变量，
 * 便于在受限网络环境下访问萤石开放平台。
 */
public final class HttpSupport {

    private HttpSupport() {
    }

    public static HttpClient newClient(int connectTimeoutSeconds) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1);
        ProxySelector proxy = fromEnvironment();
        if (proxy != null) {
            builder.proxy(proxy);
        }
        return builder.build();
    }

    private static ProxySelector fromEnvironment() {
        String raw = firstNonBlank(System.getenv("HTTPS_PROXY"), System.getenv("https_proxy"),
                System.getenv("HTTP_PROXY"), System.getenv("http_proxy"));
        if (raw == null) {
            return null;
        }
        try {
            String value = raw.trim();
            String hostPort = value.contains("://") ? value.substring(value.indexOf("://") + 3) : value;
            if (hostPort.contains("@")) {
                hostPort = hostPort.substring(hostPort.indexOf('@') + 1);
            }
            if (hostPort.endsWith("/")) {
                hostPort = hostPort.substring(0, hostPort.length() - 1);
            }
            int idx = hostPort.lastIndexOf(':');
            String host = idx > 0 ? hostPort.substring(0, idx) : hostPort;
            int port = idx > 0 ? Integer.parseInt(hostPort.substring(idx + 1)) : 80;
            return ProxySelector.of(new InetSocketAddress(host, port));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    public static HttpRequest.Builder request(URI uri, Map<String, String> headers, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout);
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (k != null && v != null) {
                    builder.header(k, v);
                }
            });
        }
        return builder;
    }

    public static String form(Map<String, ?> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (v == null) {
                return;
            }
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(urlEncode(k)).append('=').append(urlEncode(String.valueOf(v)));
        });
        return sb.toString();
    }

    public static String urlEncode(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || c == ':' || c == '/') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return sb.toString();
    }

    public static String readBody(HttpResponse<String> response) {
        return response.body() == null ? "" : response.body().trim();
    }
}
