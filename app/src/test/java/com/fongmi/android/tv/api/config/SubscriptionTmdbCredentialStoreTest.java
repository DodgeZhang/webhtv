package com.fongmi.android.tv.api.config;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SubscriptionTmdbCredentialStoreTest {

    private static final String KEY_A = "0123456789abcdef0123456789abcdef";
    private static final String KEY_B = "fedcba9876543210fedcba9876543210";

    @Before
    @After
    public void clearStore() {
        SubscriptionTmdbCredentialStore.clear();
    }

    @Test
    public void acceptsOnlyCurrentSubscriptionAndEpoch() {
        SubscriptionTmdbCredentialStore.Scope scope = SubscriptionTmdbCredentialStore.beginSubscription(1, "https://source.a/config/", "test");

        assertTrue(SubscriptionTmdbCredentialStore.accept(KEY_A, 1, "https://source.a/config", scope.getEpoch(), "site-a", "vod-1"));
        assertFalse(SubscriptionTmdbCredentialStore.accept(KEY_B, 2, "https://source.a/config", scope.getEpoch(), "site-b", "vod-2"));
        assertFalse(SubscriptionTmdbCredentialStore.accept(KEY_B, 1, "https://source.b/config", scope.getEpoch(), "site-b", "vod-2"));
        assertFalse(SubscriptionTmdbCredentialStore.accept(KEY_B, 1, "https://source.a/config", scope.getEpoch() + 1, "site-b", "vod-2"));
        assertEquals(KEY_A, SubscriptionTmdbCredentialStore.snapshot(1, "https://source.a/config", scope.getEpoch()).getApiKey());
    }

    @Test
    public void sameSubscriptionAndNormalizedUrlPreserveCredential() {
        SubscriptionTmdbCredentialStore.Scope first = SubscriptionTmdbCredentialStore.beginSubscription(7, " https://source.example/config/ ", "first");
        assertTrue(SubscriptionTmdbCredentialStore.accept(KEY_A, 7, "https://source.example/config", first.getEpoch(), "site-a", "vod-1"));

        SubscriptionTmdbCredentialStore.Scope reloaded = SubscriptionTmdbCredentialStore.beginSubscription(7, "https://source.example/config", "reload");

        assertEquals(first.getEpoch(), reloaded.getEpoch());
        assertEquals(KEY_A, SubscriptionTmdbCredentialStore.snapshot(7, "https://source.example/config", reloaded.getEpoch()).getApiKey());
    }

    @Test
    public void switchingSubscriptionClearsKeyAndReturningDoesNotRestoreIt() {
        SubscriptionTmdbCredentialStore.Scope a = SubscriptionTmdbCredentialStore.beginSubscription(1, "https://source.a/config", "a");
        assertTrue(SubscriptionTmdbCredentialStore.accept(KEY_A, 1, "https://source.a/config", a.getEpoch(), "site-a", "vod-1"));

        SubscriptionTmdbCredentialStore.Scope b = SubscriptionTmdbCredentialStore.beginSubscription(2, "https://source.b/config", "b");
        assertNotEquals(a.getEpoch(), b.getEpoch());
        assertFalse(SubscriptionTmdbCredentialStore.snapshot(1, "https://source.a/config", a.getEpoch()).isPresent());
        assertTrue(SubscriptionTmdbCredentialStore.snapshot(2, "https://source.b/config", b.getEpoch()).isEmpty());

        SubscriptionTmdbCredentialStore.Scope aAgain = SubscriptionTmdbCredentialStore.beginSubscription(1, "https://source.a/config", "a-again");
        assertNotEquals(a.getEpoch(), aAgain.getEpoch());
        assertTrue(SubscriptionTmdbCredentialStore.snapshot(1, "https://source.a/config", aAgain.getEpoch()).isEmpty());
    }

    @Test
    public void clearRejectsOldEpochAndFutureAcceptUsesNewEpoch() {
        SubscriptionTmdbCredentialStore.Scope old = SubscriptionTmdbCredentialStore.beginSubscription(3, "https://source.c/config", "old");
        assertTrue(SubscriptionTmdbCredentialStore.accept(KEY_A, 3, "https://source.c/config", old.getEpoch(), "site-c", "vod-1"));

        SubscriptionTmdbCredentialStore.clear();
        SubscriptionTmdbCredentialStore.Scope current = SubscriptionTmdbCredentialStore.currentScope();

        assertNotEquals(old.getEpoch(), current.getEpoch());
        assertFalse(SubscriptionTmdbCredentialStore.accept(KEY_B, 3, "https://source.c/config", old.getEpoch(), "site-c", "vod-2"));
        assertTrue(SubscriptionTmdbCredentialStore.accept(KEY_B, 3, "https://source.c/config", current.getEpoch(), "site-c", "vod-2"));
        assertEquals(KEY_B, SubscriptionTmdbCredentialStore.snapshot(3, "https://source.c/config", current.getEpoch()).getApiKey());
        assertFalse(SubscriptionTmdbCredentialStore.snapshot(3, "https://source.c/config", current.getEpoch()).getFingerprint().contains(KEY_B));
    }
}
