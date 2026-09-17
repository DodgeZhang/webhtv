package com.fongmi.android.tv.utils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Creates a stable, non-sensitive identifier for one HLS media segment. */
public final class AdBlockSegmentKey {

    private AdBlockSegmentKey() {
    }

    public static String create(String playlistUrl, String segmentUrl, int segmentIndex,
                                double startSeconds, double durationSeconds) {
        String seed = normalizeUrl(playlistUrl) + '\n'
                + normalizeUrl(segmentUrl) + '\n'
                + segmentIndex + '\n'
                + Math.round(safe(startSeconds) * 1000d) + '\n'
                + Math.round(safe(durationSeconds) * 1000d);
        return "hls-" + sha256(seed).substring(0, 32);
    }

    public static String legacy(String siteKey, String pipeline, String adDomain, String ruleId,
                                double startSeconds, double durationSeconds) {
        String seed = "legacy\n" + safe(siteKey) + '\n' + safe(pipeline) + '\n'
                + safe(adDomain) + '\n' + safe(ruleId) + '\n'
                + Math.round(safe(startSeconds) * 1000d) + '\n'
                + Math.round(safe(durationSeconds) * 1000d);
        return "legacy-" + sha256(seed).substring(0, 32);
    }

    private static String normalizeUrl(String value) {
        String input = safe(value).trim();
        if (input.isEmpty()) return "";
        try {
            URI uri = URI.create(input);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            StringBuilder result = new StringBuilder();
            if (!scheme.isEmpty()) result.append(scheme).append("://");
            if (!host.isEmpty()) result.append(host);
            if (uri.getPort() >= 0) result.append(':').append(uri.getPort());
            if (uri.getRawPath() != null) result.append(uri.getRawPath());
            return result.length() == 0 ? input : result.toString();
        } catch (RuntimeException ignored) {
            int query = input.indexOf('?');
            int fragment = input.indexOf('#');
            int end = input.length();
            if (query >= 0) end = Math.min(end, query);
            if (fragment >= 0) end = Math.min(end, fragment);
            return input.substring(0, end);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is required by the Android runtime", e);
        }
    }

    private static double safe(double value) {
        return Double.isFinite(value) ? Math.max(0d, value) : 0d;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
