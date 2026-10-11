package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.BuildConfig;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AppVersion {

    /**
     * Release tags carry a build stamp, for example {@code v5.6.0-202609221328} (stable) or
     * {@code v5.6.0-beta-202610091755} (beta). The stamp is the release moment, so it is the only
     * reliable way to order two builds that publish the same {@code versionCode}.
     */
    private static final Pattern BUILD_STAMP = Pattern.compile("(?<!\\d)(\\d{12,14})(?!\\d)");

    private AppVersion() {
    }

    public static String fullName() {
        String tag = BuildConfig.BUILD_TAG == null ? "" : BuildConfig.BUILD_TAG.trim();
        if (tag.isEmpty()) tag = BuildConfig.VERSION_NAME + "-" + BuildConfig.BUILD_TIME;
        return stripPrefix(tag);
    }

    public static boolean isCurrent(String name) {
        return stripPrefix(name).equals(stripPrefix(fullName()));
    }

    /** True only when {@code target} is a strictly newer build than the running one. */
    public static boolean isNewer(String target) {
        return isNewer(fullName(), target);
    }

    /**
     * Orders two release tags by their build stamp.
     *
     * <p>An older or identical tag is never an update. That matters because the stable and beta
     * channels of the same release line can publish the same {@code versionCode}: treating
     * "different tag" as "newer" made an older package look installable over a newer one, so the
     * app kept offering a downgrade and the two channels overwrote each other in a loop.
     *
     * <p>Tags without a usable stamp keep the historical "different means update" behavior.
     */
    public static boolean isNewer(String current, String target) {
        String currentTag = stripPrefix(current);
        String targetTag = stripPrefix(target);
        if (targetTag.isEmpty() || targetTag.equals(currentTag)) return false;
        String currentStamp = buildStamp(currentTag);
        String targetStamp = buildStamp(targetTag);
        if (currentStamp.isEmpty() || targetStamp.isEmpty()) return true;
        if (targetStamp.length() != currentStamp.length()) return true;
        return targetStamp.compareTo(currentStamp) > 0;
    }

    /** Returns the trailing build stamp ({@code yyyyMMddHHmm[ss]}) of a release tag, or {@code ""}. */
    public static String buildStamp(String value) {
        if (value == null) return "";
        Matcher matcher = BUILD_STAMP.matcher(value);
        String stamp = "";
        while (matcher.find()) stamp = matcher.group(1);
        return stamp;
    }

    public static String stripPrefix(String value) {
        if (value == null) return "";
        value = value.trim();
        return value.startsWith("v") && value.length() > 1 && Character.isDigit(value.charAt(1)) ? value.substring(1) : value;
    }
}
