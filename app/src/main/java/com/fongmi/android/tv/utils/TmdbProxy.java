package com.fongmi.android.tv.utils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** TMDB API 与图片线路的地址归一化和内置线路定义。 */
public final class TmdbProxy {

    public static final String OFFICIAL_API = "https://api.tmdb.org";
    public static final String OFFICIAL_IMAGE = "https://image.tmdb.org";
    /** 实测 API 与 /t/p 图片路径均可用的公共镜像。 */
    public static final String ITV666 = "http://tmdb.itv666.cc";

    private static final String REMOVED_WORKER_POOL = "worker-pool";
    private static final String REMOVED_NASTOOL = "https://tmdb.nastool.org";
    private static final Pattern POOL_SEPARATOR = Pattern.compile("[,，;；\\s]+");

    private static final List<Option> API_OPTIONS = List.of(
            new Option(OFFICIAL_API, "官方 API（直连）"),
            new Option(ITV666, "itv666 API 代理"));
    private static final List<Option> IMAGE_OPTIONS = List.of(
            new Option(OFFICIAL_IMAGE, "官方图片（直连）"),
            new Option(ITV666, "itv666 图片代理"));

    private TmdbProxy() {
    }

    public static List<Option> apiOptions() {
        return API_OPTIONS;
    }

    public static List<Option> imageOptions() {
        return IMAGE_OPTIONS;
    }

    public static List<String> apiValues() {
        return values(API_OPTIONS);
    }

    public static List<String> imageValues() {
        return values(IMAGE_OPTIONS);
    }

    public static String[] labels(List<Option> options) {
        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) labels[i] = options.get(i).label;
        return labels;
    }

    /** 将单个地址归一化；兼容旧配置中的候选串时只取第一个有效地址，失效 Worker/NAStool 线路直接丢弃。 */
    public static String normalizeConfig(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || "direct".equalsIgnoreCase(text) || isRemovedRoute(text)) return "";

        for (String item : POOL_SEPARATOR.split(text)) {
            if (isRemovedRoute(item)) continue;
            String host = normalizeHost(item);
            if (host != null) return host;
        }
        return "";
    }

    public static String valueForInput(String value, List<Option> options) {
        String text = value == null ? "" : value.trim();
        for (Option option : options) {
            if (option.label.equals(text)) return option.value;
        }
        return normalizeConfig(text);
    }

    public static String displayFor(String value, List<Option> options) {
        String normalized = normalizeConfig(value);
        for (Option option : options) {
            if (option.value.equals(normalized)) return option.label;
        }
        return normalized;
    }

    /** 兼容旧版 proxyBase 配置：旧配置仍可解析，但新 UI 不再生成该字段。 */
    public static String resolve(String value) {
        List<String> pool = parsePool(value);
        return pool.isEmpty() ? "" : pool.get(0);
    }

    public static boolean contains(String configured, String resolved) {
        if (resolved == null || resolved.isEmpty()) return parsePool(configured).isEmpty();
        return parsePool(configured).contains(resolved);
    }

    public static String imageHostFor(String resolvedApiHost) {
        return normalizeHost(resolvedApiHost);
    }

    public static boolean isOfficialApiHost(String value) {
        String normalized = normalizeHost(value);
        return OFFICIAL_API.equalsIgnoreCase(normalized)
                || "https://api.themoviedb.org".equalsIgnoreCase(normalized);
    }

    public static boolean isOfficialImageHost(String value) {
        String normalized = normalizeHost(value);
        return OFFICIAL_IMAGE.equalsIgnoreCase(normalized)
                || "https://images.tmdb.org".equalsIgnoreCase(normalized)
                || "https://media.themoviedb.org".equalsIgnoreCase(normalized);
    }

    public static boolean isRemovedRoute(String value) {
        if (value == null) return false;
        String text = value.trim();
        return REMOVED_WORKER_POOL.equalsIgnoreCase(text)
                || REMOVED_NASTOOL.equalsIgnoreCase(trimTrailingSlash(text));
    }

    private static List<String> parsePool(String value) {
        String normalized = normalizeConfig(value);
        if (normalized.isEmpty()) return List.of();
        List<String> hosts = new ArrayList<>();
        for (String item : normalized.split(",")) {
            String host = normalizeHost(item);
            if (host != null) hosts.add(host);
        }
        return hosts;
    }

    private static List<String> values(List<Option> options) {
        List<String> values = new ArrayList<>();
        for (Option option : options) values.add(option.value);
        return Collections.unmodifiableList(values);
    }

    private static String normalizeHost(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty() || isRemovedRoute(text)) return null;
        if (!text.contains("://")) text = "https://" + text;
        try {
            URI uri = new URI(text);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if ((scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)))
                    || host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                return null;
            }
            String authority = uri.getRawAuthority();
            String path = uri.getPath() == null ? "" : trimTrailingSlash(uri.getPath());
            if (path.matches(".*/(?:w\\d+|h\\d+|original)$")) path = path.substring(0, path.lastIndexOf('/'));
            if (path.endsWith("/3")) path = path.substring(0, path.length() - 2);
            if (path.endsWith("/t/p")) path = path.substring(0, path.length() - 4);
            return scheme.toLowerCase(Locale.ROOT) + "://" + authority + path;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) return "";
        String result = value;
        while (result.endsWith("/") && !result.isEmpty()) result = result.substring(0, result.length() - 1);
        return result;
    }

    public static final class Option {
        public final String value;
        public final String label;

        private Option(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }
}
