package com.video.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class UrlNormalizeUtil {

    private static final Set<String> TRACKING_QUERY_PARAMS = new HashSet<>();

    static {
        TRACKING_QUERY_PARAMS.add("utm_source");
        TRACKING_QUERY_PARAMS.add("utm_medium");
        TRACKING_QUERY_PARAMS.add("utm_campaign");
        TRACKING_QUERY_PARAMS.add("utm_term");
        TRACKING_QUERY_PARAMS.add("utm_content");
        TRACKING_QUERY_PARAMS.add("fbclid");
        TRACKING_QUERY_PARAMS.add("gclid");
    }

    private UrlNormalizeUtil() {
    }

    public static String normalize(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("URL 不能为空");
        }

        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL 格式不合法: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || scheme.trim().isEmpty()) {
            throw new IllegalArgumentException("URL 必须以 http:// 或 https:// 开头");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("URL 必须以 http:// 或 https:// 开头");
        }

        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("URL 缺少有效域名");
        }

        String normalizedQuery = normalizeQuery(uri.getRawQuery());
        return buildUrl(
            scheme,
            uri.getRawUserInfo(),
            host.toLowerCase(Locale.ROOT),
            uri.getPort(),
            uri.getRawPath(),
            normalizedQuery
        );
    }

    public static String hashNormalizedUrl(String normalizedUrl) {
        if (normalizedUrl == null || normalizedUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("normalizedUrl 不能为空");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedUrl.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("URL 哈希计算失败: " + e.getMessage(), e);
        }
    }

    public static String safeForLog(String normalizedUrl) {
        if (normalizedUrl == null || normalizedUrl.trim().isEmpty()) {
            return "";
        }

        try {
            URI uri = new URI(normalizedUrl);
            StringBuilder sb = new StringBuilder();
            sb.append(uri.getScheme()).append("://");
            sb.append(uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "<unknown-host>");
            if (uri.getPort() >= 0) {
                sb.append(':').append(uri.getPort());
            }
            if (uri.getRawPath() != null) {
                sb.append(uri.getRawPath());
            }
            String rawQuery = uri.getRawQuery();
            if (rawQuery != null && !rawQuery.isEmpty()) {
                List<String> redactedParams = new ArrayList<>();
                for (String part : rawQuery.split("&", -1)) {
                    if (part.isEmpty()) {
                        continue;
                    }
                    String key = queryKey(part);
                    redactedParams.add(part.contains("=") ? key + "=***" : key);
                }
                if (!redactedParams.isEmpty()) {
                    sb.append('?').append(String.join("&", redactedParams));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "<normalized-url-hidden>";
        }
    }

    private static String normalizeQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return null;
        }

        List<String> keptParams = new ArrayList<>();
        for (String part : rawQuery.split("&", -1)) {
            if (part == null || part.isEmpty()) {
                continue;
            }

            String key = queryKey(part);
            String normalizedKey = decodeQueryPart(key).toLowerCase(Locale.ROOT);
            if (TRACKING_QUERY_PARAMS.contains(normalizedKey)) {
                continue;
            }
            keptParams.add(part);
        }

        if (keptParams.isEmpty()) {
            return null;
        }

        keptParams.sort(Comparator
            .comparing((String part) -> decodeQueryPart(queryKey(part)).toLowerCase(Locale.ROOT))
            .thenComparing(part -> part));
        return String.join("&", keptParams);
    }

    private static String queryKey(String queryPart) {
        int equalsIndex = queryPart.indexOf('=');
        return equalsIndex >= 0 ? queryPart.substring(0, equalsIndex) : queryPart;
    }

    private static String decodeQueryPart(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }

    private static String buildUrl(String scheme, String rawUserInfo, String host, int port, String rawPath, String rawQuery) {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://");
        if (rawUserInfo != null && !rawUserInfo.isEmpty()) {
            sb.append(rawUserInfo).append('@');
        }
        if (host.contains(":") && !host.startsWith("[") && !host.endsWith("]")) {
            sb.append('[').append(host).append(']');
        } else {
            sb.append(host);
        }
        if (port >= 0) {
            sb.append(':').append(port);
        }
        if (rawPath != null && !rawPath.isEmpty()) {
            sb.append(rawPath);
        }
        if (rawQuery != null && !rawQuery.isEmpty()) {
            sb.append('?').append(rawQuery);
        }
        return sb.toString();
    }
}
