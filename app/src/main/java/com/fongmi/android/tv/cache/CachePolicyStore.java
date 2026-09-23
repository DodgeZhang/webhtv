package com.fongmi.android.tv.cache;

import com.github.catvod.utils.Prefers;

public final class CachePolicyStore {

    private static final String PREFIX = "cache_mgmt_";
    private static final long UNLIMITED = 0L;

    private CachePolicyStore() {
    }

    public static long getLimit(CacheModuleId id) {
        return Math.max(UNLIMITED, Prefers.getLong(key(id), defaultLimit(id)));
    }

    public static void putLimit(CacheModuleId id, long bytes) {
        Prefers.put(key(id), Math.max(UNLIMITED, bytes));
    }

    public static int getRetentionDays() {
        return clamp(Prefers.getInt(PREFIX + "retention_days", 30), 1, 3650);
    }

    public static void putRetentionDays(int days) {
        Prefers.put(PREFIX + "retention_days", clamp(days, 1, 3650));
    }

    public static boolean isAutoCleanupEnabled() {
        return Prefers.getBoolean(PREFIX + "auto_enabled", false);
    }

    public static void putAutoCleanupEnabled(boolean enabled) {
        Prefers.put(PREFIX + "auto_enabled", enabled);
    }

    public static long defaultLimit(CacheModuleId id) {
        return switch (id) {
            case GLIDE, LYRICS, KARAOKE, WEBHOME_EXT, PLUGIN_SCRIPTS -> 256L * 1024L * 1024L;
            case EPG, TEMP_FILES, LEGACY_FILES -> 128L * 1024L * 1024L;
            default -> UNLIMITED;
        };
    }

    private static String key(CacheModuleId id) {
        return PREFIX + "limit_" + id.id().replace('.', '_');
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
