package com.fongmi.android.tv.service;

import com.fongmi.android.tv.api.config.SubscriptionTmdbCredentialStore;
import com.fongmi.android.tv.bean.TmdbConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TmdbServiceCredentialScopeTest {

    private static final String SOURCE_KEY = "0123456789abcdef0123456789abcdef";

    @Before
    @After
    public void clearStore() {
        SubscriptionTmdbCredentialStore.clear();
        TmdbService.clearAuthFailuresForTest();
    }

    @Test
    public void transientCredentialRejectsNonOfficialTransportBeforeRequest() throws Exception {
        TmdbConfig config = transientConfig();
        set(config, "apiBase", "https://mirror.example.com/3");
        TmdbService service = new TmdbService();

        assertThrows(IllegalStateException.class, () -> service.searchRaw("query", config));
    }

    @Test
    public void transientCredentialIsClearedAfterUnauthorizedResponse() {
        SubscriptionTmdbCredentialStore.Scope scope = SubscriptionTmdbCredentialStore.beginSubscription(4, "https://source.example/config", "test");
        assertTrue(SubscriptionTmdbCredentialStore.accept(SOURCE_KEY, 4, "https://source.example/config", scope.getEpoch(), "site", "vod"));
        TmdbConfig config = TmdbConfig.effective(TmdbConfig.objectFrom("{}"), SubscriptionTmdbCredentialStore.snapshot(scope));
        TmdbService service = new TmdbService();

        RuntimeException failure = service.httpFailure(config, 403, "forbidden");

        assertTrue(failure instanceof TmdbService.AuthException);
        assertTrue(SubscriptionTmdbCredentialStore.snapshot(scope).isEmpty());
        assertFalse(SubscriptionTmdbCredentialStore.isCurrent(scope));
    }

    @Test
    public void userCredentialIsNotClearedByUnauthorizedResponse() {
        TmdbConfig user = TmdbConfig.objectFrom("{\"apiKey\":\"user-key-0123456789\"}");
        TmdbService service = new TmdbService();

        service.httpFailure(user, 401, "unauthorized");

        assertTrue(user.isReady());
        assertFalse(user.isTransientSubscriptionCredential());
    }

    private static TmdbConfig transientConfig() {
        SubscriptionTmdbCredentialStore.Scope scope = SubscriptionTmdbCredentialStore.beginSubscription(5, "https://source.example/config", "test");
        assertTrue(SubscriptionTmdbCredentialStore.accept(SOURCE_KEY, 5, "https://source.example/config", scope.getEpoch(), "site", "vod"));
        return TmdbConfig.effective(TmdbConfig.objectFrom("{}"), SubscriptionTmdbCredentialStore.snapshot(scope));
    }

    private static void set(TmdbConfig config, String name, String value) throws Exception {
        Field field = TmdbConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(config, value);
    }
}
