package com.fongmi.android.tv.utils;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a one-segment HLS media playlist without retaining it on disk. */
public final class HlsPreviewManifest {

    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\\\"([^\\\"]+)\\\"");

    private HlsPreviewManifest() {
    }

    public static String create(String baseUrl, String source, String targetUri,
                                 double targetStartSeconds, double targetDurationSeconds) {
        if (empty(baseUrl) || empty(source) || empty(targetUri)) return "";
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = List.of(normalized.split("\n", -1));
        String target = resolve(baseUrl, targetUri);
        String version = "";
        String targetDuration = "";
        String mediaSequence = "";
        String discontinuitySequence = "";
        String key = "";
        String map = "";
        String discontinuity = "";
        double start = 0d;
        double duration = -1d;
        int index = 0;
        int mediaIndex = 0;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-VERSION:")) version = line;
            else if (line.startsWith("#EXT-X-TARGETDURATION:")) targetDuration = line;
            else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) mediaSequence = line;
            else if (line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")) discontinuitySequence = line;
            else if (line.startsWith("#EXT-X-KEY:")) key = rewriteUriAttribute(baseUrl, line);
            else if (line.startsWith("#EXT-X-MAP:")) map = rewriteUriAttribute(baseUrl, line);
            else if (line.equals("#EXT-X-DISCONTINUITY")) discontinuity = line;
            else if (line.startsWith("#EXTINF:")) duration = parseDuration(line);
            else if (!line.isEmpty() && !line.startsWith("#") && duration >= 0d) {
                String resolved = resolve(baseUrl, line);
                boolean match = resolved.equals(target)
                        && Math.abs(start - targetStartSeconds) < 0.02d
                        && Math.abs(duration - targetDurationSeconds) < 0.02d;
                if (match) {
                    String sequence = sequenceFor(mediaSequence, mediaIndex);
                    List<String> output = new ArrayList<>();
                    output.add("#EXTM3U");
                    if (!version.isEmpty()) output.add(version);
                    if (!targetDuration.isEmpty()) output.add(targetDuration);
                    if (!sequence.isEmpty()) output.add(sequence);
                    if (!discontinuitySequence.isEmpty()) output.add(discontinuitySequence);
                    if (!key.isEmpty()) output.add(key);
                    if (!map.isEmpty()) output.add(map);
                    if (!discontinuity.isEmpty()) output.add(discontinuity);
                    output.add(lineForDuration(duration));
                    output.add(resolved);
                    output.add("#EXT-X-ENDLIST");
                    return String.join("\n", output) + "\n";
                }
                start += duration;
                duration = -1d;
                mediaIndex++;
                index++;
                discontinuity = "";
            }
        }
        return "";
    }

    private static String sequenceFor(String mediaSequence, int index) {
        if (mediaSequence.isEmpty()) return "";
        try {
            long value = Long.parseLong(mediaSequence.substring(mediaSequence.indexOf(':') + 1).trim());
            return "#EXT-X-MEDIA-SEQUENCE:" + (value + index);
        } catch (RuntimeException ignored) {
            return mediaSequence;
        }
    }

    private static String rewriteUriAttribute(String baseUrl, String line) {
        Matcher matcher = URI_ATTRIBUTE.matcher(line);
        if (!matcher.find()) return line;
        String replacement = "URI=\"" + resolve(baseUrl, matcher.group(1)) + "\"";
        return line.substring(0, matcher.start()) + replacement + line.substring(matcher.end());
    }

    private static String resolve(String baseUrl, String value) {
        try {
            return URI.create(baseUrl).resolve(value).toString();
        } catch (RuntimeException ignored) {
            return value;
        }
    }

    private static double parseDuration(String line) {
        try {
            String value = line.substring("#EXTINF:".length());
            int comma = value.indexOf(',');
            return Double.parseDouble(comma < 0 ? value : value.substring(0, comma));
        } catch (RuntimeException ignored) {
            return -1d;
        }
    }

    private static String lineForDuration(double duration) {
        return String.format(Locale.US, "#EXTINF:%.3f,", duration);
    }

    private static boolean empty(String value) {
        return value == null || value.isBlank();
    }
}
