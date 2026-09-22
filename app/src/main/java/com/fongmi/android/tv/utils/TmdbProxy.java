package com.fongmi.android.tv.utils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * TMDB 访问线路配置。
 *
 * <p>这里的代理是 TMDB endpoint/mirror base，而不是 GitHub 那种把完整原始 URL
 * 拼在通用前缀后面的代理。代理端需要能够接收 {@code /3/...} API 路径和
 * {@code /t/p/...} 图片路径；Worker 型线路通常两种路径同域提供，NAStool 则由
 * {@link #imageHostFor(String)} 映射到独立图床。</p>
 */
public final class TmdbProxy {

    public static final String OFFICIAL_API = "https://api.tmdb.org";
    public static final String WORKER_POOL = "worker-pool";
    public static final String ITV666 = "http://tmdb.itv666.cc";
    public static final String NASTOOL = "https://tmdb.nastool.org";

    private static final Pattern POOL_SEPARATOR = Pattern.compile("[,，;；\\s]+");
    private static final AtomicInteger ROTATION = new AtomicInteger();
    private static final List<String> BUILTIN_WORKER_POOL = List.of(
            "https://tmdb.power0721.workers.dev",
            "https://tmdb.swust-oj.workers.dev",
            "https://tmdb.8866033.workers.dev",
            "https://tmdb.power348045.workers.dev",
            "https://tmdb.harold348047.workers.dev",
            "https://tmdb.ai-09b.workers.dev",
            "https://tmdb.root-df0.workers.dev",
            "https://tmdb.atv-8c1.workers.dev",
            "https://tmdb.odd-math-a42b.workers.dev",
            "https://tmdb.test-d2c.workers.dev",
            "https://tmdb.code-a96.workers.dev",
            "https://tmdb.claude-b79.workers.dev");

    private static final List<Option> OPTIONS = List.of(
            new Option("", "官方 API（直连）"),
            new Option(WORKER_POOL, "Worker 轮询池（推荐）"),
            new Option(ITV666, "itv666 代理（tmdb.itv666.cc）"),
            new Option(NASTOOL, "NAStool 代理（tmdb.nastool.org + img.nastool.org）"));

    private TmdbProxy() {
    }

    public static List<String> workerPool() {
        return BUILTIN_WORKER_POOL;
    }

    public static List<Option> options() {
        return OPTIONS;
    }

    public static List<String> values() {
        List<String> values = new ArrayList<>();
        for (Option option : OPTIONS) values.add(option.value);
        return Collections.unmodifiableList(values);
    }

    public static List<String> labels() {
        List<String> labels = new ArrayList<>();
        for (Option option : OPTIONS) labels.add(option.label);
        return Collections.unmodifiableList(labels);
    }

    /** 将预设、单个自定义地址或逗号/分号分隔的自定义地址池归一化。 */
    public static String normalizeConfig(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || "direct".equalsIgnoreCase(text) || "官方 API（直连）".equals(text)) return "";
        if (WORKER_POOL.equalsIgnoreCase(text)) return WORKER_POOL;

        Set<String> hosts = new LinkedHashSet<>();
        for (String item : POOL_SEPARATOR.split(text)) {
            String host = normalizeHost(item);
            if (host != null) hosts.add(host);
        }
        return String.join(",", hosts);
    }

    /** 判断输入是否为官方 TMDB API 地址；官方地址不作为代理保存。 */
    public static boolean isOfficialApiHost(String value) {
        String normalized = normalizeHost(value);
        return "https://api.tmdb.org".equalsIgnoreCase(normalized)
                || "https://api.themoviedb.org".equalsIgnoreCase(normalized);
    }

    /** 将 UI 展示值转换为持久化值；未知文本按自定义地址池处理。 */
    public static String valueForInput(String value) {
        String text = value == null ? "" : value.trim();
        for (Option option : OPTIONS) {
            if (option.label.equals(text)) return option.value;
        }
        return normalizeConfig(text);
    }

    /** 返回给 UI 的可读值；自定义地址池保留原地址。 */
    public static String displayFor(String value) {
        String normalized = normalizeConfig(value);
        for (Option option : OPTIONS) {
            if (option.value.equals(normalized)) return option.label;
        }
        return normalized;
    }

    /** 从配置池中为当前配置对象选择一个稳定线路。 */
    public static String resolve(String value) {
        List<String> pool = parsePool(value);
        if (pool.isEmpty()) return "";
        int index = Math.floorMod(ROTATION.getAndIncrement(), pool.size());
        return pool.get(index);
    }

    /** 判断已解析线路是否仍属于当前配置池，供配置对象重复 sanitize 时复用。 */
    public static boolean contains(String configured, String resolved) {
        if (resolved == null || resolved.isEmpty()) return parsePool(configured).isEmpty();
        return parsePool(configured).contains(resolved);
    }

    /** 返回图片请求应使用的代理 host；NAStool 的图床与 API 分离。 */
    public static String imageHostFor(String resolvedApiHost) {
        if (NASTOOL.equalsIgnoreCase(trimTrailingSlash(resolvedApiHost))) return "https://img.nastool.org";
        return normalizeHost(resolvedApiHost);
    }

    private static List<String> parsePool(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return List.of();
        if (WORKER_POOL.equalsIgnoreCase(text)) return BUILTIN_WORKER_POOL;

        Set<String> hosts = new LinkedHashSet<>();
        for (String item : POOL_SEPARATOR.split(text)) {
            String host = normalizeHost(item);
            if (host != null) hosts.add(host);
        }
        return new ArrayList<>(hosts);
    }

    private static String normalizeHost(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty()) return null;
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
            String path = uri.getPath() == null ? "" : uri.getPath();
            path = trimTrailingSlash(path);
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
        while (result.endsWith("/") && result.length() > 0) {
            result = result.substring(0, result.length() - 1);
        }
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
