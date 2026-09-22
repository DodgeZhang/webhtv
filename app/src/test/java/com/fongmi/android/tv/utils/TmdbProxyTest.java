package com.fongmi.android.tv.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TmdbProxyTest {

    @Test
    public void normalizesValidEndpointAndAddressPool() {
        assertEquals("https://mirror.example.com/tmdb", TmdbProxy.normalizeConfig("mirror.example.com/tmdb/3"));
        assertEquals("https://a.example.com",
                TmdbProxy.normalizeConfig("https://a.example.com;https://a.example.com, b.example.com"));
    }

    @Test
    public void removesKnownInvalidRoutes() {
        assertEquals("", TmdbProxy.normalizeConfig("worker-pool"));
        assertEquals("", TmdbProxy.normalizeConfig("https://tmdb.nastool.org"));
        assertEquals("", TmdbProxy.normalizeConfig("ftp://mirror.example.com"));
        assertTrue(TmdbProxy.isRemovedRoute("worker-pool"));
        assertTrue(TmdbProxy.isRemovedRoute("https://tmdb.nastool.org/"));
    }

    @Test
    public void exposesOnlyTestedBuiltInApiAndImageRoutes() {
        assertEquals(2, TmdbProxy.apiOptions().size());
        assertEquals(2, TmdbProxy.imageOptions().size());
        assertEquals(TmdbProxy.ITV666, TmdbProxy.valueForInput("itv666 API 代理", TmdbProxy.apiOptions()));
        assertEquals("itv666 图片代理", TmdbProxy.displayFor(TmdbProxy.ITV666, TmdbProxy.imageOptions()));
    }

    @Test
    public void recognizesOfficialApiAndImageHosts() {
        assertTrue(TmdbProxy.isOfficialApiHost("https://api.tmdb.org/3"));
        assertTrue(TmdbProxy.isOfficialApiHost("https://api.themoviedb.org"));
        assertTrue(TmdbProxy.isOfficialImageHost("https://image.tmdb.org/t/p/w342"));
        assertFalse(TmdbProxy.isOfficialApiHost("https://mirror.example.com"));
        assertFalse(TmdbProxy.isOfficialImageHost("https://mirror.example.com"));
    }
}
