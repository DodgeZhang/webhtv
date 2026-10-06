package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.github.catvod.utils.Path;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Remembers which dynamically loaded spider was touched last, so the crash page can name the
 * suspect source instead of only showing an obfuscated stack frame.
 *
 * <p>An external CatVod jar can fault from a layout callback the host never wrapped in a
 * try/catch, and that jar is not in this repository. The host cannot fix such a jar, but it can
 * tell the user which source to switch away from. The crash page runs in a separate
 * {@code :error_activity} process, so this has to be persisted rather than kept in memory.
 *
 * <p>Deliberately not stored in {@code Prefers}: {@code Backup.include()} falls through to
 * {@code options.isSpider()} for unknown keys, so a preference here would be exported to other
 * devices. A file under {@code Path.cache()} is cleared by the existing cache-cleanup entry.
 *
 * <p>This class never swallows an exception and never disables a source. Every failure is
 * reduced to a null result.
 */
public final class SpiderCrashBreadcrumb {

    private static final String FILE_NAME = "spider-last.txt";
    private static final String FIELD_SEPARATOR = " ";
    private static final int MAX_LENGTH = 512;

    private static volatile String last;

    private SpiderCrashBreadcrumb() {
    }

    /**
     * Records the spider about to be initialised. Called before {@code spider.init(...)} so the
     * breadcrumb names the source that was in flight when a later callback faulted.
     */
    public static void record(String siteKey, String api, String jar, String jarKey) {
        String text = format(siteKey, api, jarKey, jar);
        if (text.isEmpty() || text.equals(last)) return;
        last = text;
        write(cacheDir(), text);
    }

    /**
     * Returns the breadcrumb to append to the crash page, or an empty string when unavailable.
     */
    public static String read() {
        return read(cacheDir());
    }

    static String format(String siteKey, String api, String jarKey, String jar) {
        StringBuilder builder = new StringBuilder();
        append(builder, "site", siteKey);
        append(builder, "api", api);
        append(builder, "jar", jarKey);
        append(builder, "src", source(jar));
        return builder.length() > MAX_LENGTH ? builder.substring(0, MAX_LENGTH) : builder.toString();
    }

    private static void append(StringBuilder builder, String name, String value) {
        if (TextUtils.isEmpty(value)) return;
        if (builder.length() > 0) builder.append(FIELD_SEPARATOR);
        builder.append(name).append('=').append(clean(value));
    }

    /** Keeps the breadcrumb single-line and printable even for a hostile jar url. */
    private static String clean(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            builder.append(c < ' ' || c == 0x7f ? '_' : c);
        }
        return builder.toString();
    }

    /** Reduces a jar url to something short and non-identifying; the md5 is already recorded. */
    private static String source(String jar) {
        if (TextUtils.isEmpty(jar)) return "";
        if (jar.startsWith("http")) return "http";
        if (jar.startsWith("file")) return "file";
        return "local";
    }

    private static File cacheDir() {
        try {
            return Path.cache();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void write(File dir, String text) {
        if (dir == null) return;
        File target = new File(dir, FILE_NAME);
        File temp = new File(dir, FILE_NAME + ".tmp");
        try {
            Files.write(temp.toPath(), text.getBytes(StandardCharsets.UTF_8));
            if (!temp.renameTo(target)) Files.write(target.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            // A missing breadcrumb only costs diagnostics; it must never affect playback.
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    static String read(File dir) {
        if (dir == null) return "";
        File target = new File(dir, FILE_NAME);
        try {
            if (!target.exists() || target.length() <= 0 || target.length() > MAX_LENGTH) return "";
            return new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }
}
