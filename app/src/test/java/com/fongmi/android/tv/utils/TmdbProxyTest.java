package com.fongmi.android.tv.utils;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TmdbProxyTest {

    @Test
    public void normalizesEndpointAndStripsApiOrImageSuffix() {
        assertEquals("https://mirror.example.com/tmdb", TmdbProxy.normalizeConfig("mirror.example.com/tmdb/3"));
        assertEquals("https://mirror.example.com/tmdb", TmdbProxy.normalizeConfig("https://mirror.example.com/tmdb/t/p/"));
        assertEquals("https://a.example.com,https://b.example.com",
                TmdbProxy.normalizeConfig("https://a.example.com;https://a.example.com, b.example.com"));
    }

    @Test
    public void rejectsNonHttpAndKeepsWorkerSentinel() {
        assertEquals("", TmdbProxy.normalizeConfig("ftp://mirror.example.com"));
        assertEquals(TmdbProxy.WORKER_POOL, TmdbProxy.normalizeConfig("WORKER-POOL"));
        assertEquals("", TmdbProxy.normalizeConfig("direct"));
    }

    @Test
    public void workerPoolResolvesToBuiltInEndpoint() {
        Set<String> resolved = new HashSet<>();
        for (int i = 0; i < TmdbProxy.workerPool().size() * 2; i++) {
            resolved.add(TmdbProxy.resolve(TmdbProxy.WORKER_POOL));
        }
        assertEquals(TmdbProxy.workerPool().size(), resolved.size());
        assertTrue(resolved.containsAll(TmdbProxy.workerPool()));
    }

    @Test
    public void nastoolUsesSeparateImageHost() {
        assertEquals("https://img.nastool.org", TmdbProxy.imageHostFor(TmdbProxy.NASTOOL));
        assertEquals("https://mirror.example.com", TmdbProxy.imageHostFor("https://mirror.example.com"));
    }

    @Test
    public void inputAndDisplayValuesKeepBuiltInSelection() {
        assertEquals(TmdbProxy.WORKER_POOL, TmdbProxy.valueForInput("Worker 轮询池（推荐）"));
        assertEquals("NAStool 代理（tmdb.nastool.org + img.nastool.org）", TmdbProxy.displayFor(TmdbProxy.NASTOOL));
        assertFalse(TmdbProxy.values().isEmpty());
    }
    @Test
    public void recognizesOfficialApiHostAsDirectRoute() {
        assertTrue(TmdbProxy.isOfficialApiHost("https://api.tmdb.org"));
        assertTrue(TmdbProxy.isOfficialApiHost("https://api.themoviedb.org/3"));
        assertFalse(TmdbProxy.isOfficialApiHost("https://mirror.example.com"));
    }

}
