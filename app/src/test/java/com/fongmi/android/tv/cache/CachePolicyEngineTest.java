package com.fongmi.android.tv.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class CachePolicyEngineTest {

    @Test
    public void lightPlanContainsOnlyLowRiskModules() {
        assertEquals(List.of(CacheModuleId.EPG, CacheModuleId.TEMP_FILES, CacheModuleId.LEGACY_FILES),
                CachePolicyEngine.plan(CacheCleanupMode.LIGHT).modules());
    }

    @Test
    public void standardPlanNeverContainsPlaybackCache() {
        List<CacheModuleId> modules = CachePolicyEngine.plan(CacheCleanupMode.STANDARD).modules();
        assertFalse(modules.contains(CacheModuleId.EXO));
        assertFalse(modules.contains(CacheModuleId.MPV_HLS));
        assertFalse(modules.contains(CacheModuleId.MPV_DEMUXER));
        assertFalse(modules.contains(CacheModuleId.MPV_RUNTIME));
    }

    @Test
    public void deepPlanCoversEveryModuleExactlyOnce() {
        List<CacheModuleId> modules = CachePolicyEngine.plan(CacheCleanupMode.DEEP).modules();
        assertEquals(CacheModuleId.values().length, modules.size());
        assertEquals(CacheModuleId.values().length, modules.stream().distinct().count());
    }

    @Test
    public void modulePlanIsExplicitAndSingleModule() {
        assertEquals(List.of(CacheModuleId.LYRICS),
                CachePolicyEngine.module(CacheModuleId.LYRICS).modules());
    }

    @Test
    public void playbackModulesAreDeferredWhilePlaying() {
        assertEquals(CacheCleanupStatus.DEFERRED,
                CachePolicyEngine.directCleanupStatus(CacheModuleId.EXO, true));
        assertEquals(CacheCleanupStatus.DEFERRED,
                CachePolicyEngine.directCleanupStatus(CacheModuleId.MPV_HLS, true));
        assertEquals(CacheCleanupStatus.COMPLETED,
                CachePolicyEngine.directCleanupStatus(CacheModuleId.EXO, false));
    }

    @Test
    public void automaticCleanupExcludesPlaybackAndOwnerManagedModules() {
        assertTrue(CachePolicyEngine.allowsAutomatic(CacheModuleId.LYRICS));
        assertTrue(CachePolicyEngine.allowsAutomatic(CacheModuleId.EPG));
        assertFalse(CachePolicyEngine.allowsAutomatic(CacheModuleId.EXO));
        assertFalse(CachePolicyEngine.allowsAutomatic(CacheModuleId.WEBHOME_RAW));
        assertFalse(CachePolicyEngine.allowsAutomatic(CacheModuleId.GLIDE));
        assertFalse(CachePolicyEngine.allowsAutomatic(CacheModuleId.PLUGIN_SCRIPTS));
    }
}
