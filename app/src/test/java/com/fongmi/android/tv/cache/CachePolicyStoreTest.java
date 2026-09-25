package com.fongmi.android.tv.cache;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CachePolicyStoreTest {

    @Test
    public void featureSwitchDefaultsToEnabledWhenUnset() {
        assertTrue(CachePolicyStore.enabledByDefault(null));
    }

    @Test
    public void featureSwitchRespectsExplicitValues() {
        assertTrue(CachePolicyStore.enabledByDefault(true));
        assertFalse(CachePolicyStore.enabledByDefault(false));
    }
}
