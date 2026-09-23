package com.fongmi.android.tv.cache;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class CachePolicyEngine {

    private static final Set<CacheModuleId> LIGHT = EnumSet.of(
            CacheModuleId.EPG,
            CacheModuleId.TEMP_FILES,
            CacheModuleId.LEGACY_FILES
    );
    private static final Set<CacheModuleId> STANDARD = EnumSet.of(
            CacheModuleId.EPG,
            CacheModuleId.TEMP_FILES,
            CacheModuleId.LEGACY_FILES,
            CacheModuleId.GLIDE,
            CacheModuleId.LYRICS,
            CacheModuleId.KARAOKE,
            CacheModuleId.WEBHOME_EXT,
            CacheModuleId.WEBHOME_RAW
    );
    private static final Set<CacheModuleId> DEEP = EnumSet.allOf(CacheModuleId.class);

    private CachePolicyEngine() {
    }

    public static CacheCleanupPlan plan(CacheCleanupMode mode) {
        return new CacheCleanupPlan(mode == null ? CacheCleanupMode.LIGHT : mode,
                List.copyOf(modules(mode == null ? CacheCleanupMode.LIGHT : mode)));
    }

    public static CacheCleanupPlan module(CacheModuleId id) {
        return new CacheCleanupPlan(CacheCleanupMode.MODULE, id == null ? List.of() : List.of(id));
    }

    public static boolean allowsAutomatic(CacheModuleId id) {
        return id != null && switch (id) {
            case LYRICS, KARAOKE, WEBHOME_EXT, EPG, TEMP_FILES, LEGACY_FILES -> true;
            default -> false;
        };
    }

    public static CacheCleanupStatus directCleanupStatus(CacheModuleId id, boolean playing) {
        if (id == null) return CacheCleanupStatus.NOT_ALLOWED;
        return switch (id) {
            case EXO, MPV_HLS, MPV_DEMUXER, MPV_RUNTIME, KARAOKE -> playing
                    ? CacheCleanupStatus.DEFERRED : CacheCleanupStatus.COMPLETED;
            case PLUGIN_SCRIPTS -> CacheCleanupStatus.NOT_ALLOWED;
            default -> CacheCleanupStatus.COMPLETED;
        };
    }

    private static List<CacheModuleId> modules(CacheCleanupMode mode) {
        Set<CacheModuleId> selected = switch (mode) {
            case LIGHT -> LIGHT;
            case STANDARD -> STANDARD;
            case DEEP -> DEEP;
            case MODULE -> Set.of();
        };
        ArrayList<CacheModuleId> ordered = new ArrayList<>();
        for (CacheModuleId id : CacheModuleId.values()) if (selected.contains(id)) ordered.add(id);
        return ordered;
    }
}
