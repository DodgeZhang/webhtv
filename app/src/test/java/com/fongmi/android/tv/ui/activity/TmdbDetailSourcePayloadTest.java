package com.fongmi.android.tv.ui.activity;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class TmdbDetailSourcePayloadTest {

    @Test
    public void sourcePayloadIsAppliedBeforeAnyTmdbRequest() throws Exception {
        String body = loadContentBody();
        int source = body.indexOf("Result result = SiteApi.detailContent(key, id);");
        int parse = body.indexOf("TmdbSourcePayloadParser.parse(finalVod.getTmdb())");
        int apply = body.indexOf("applyLoaded(finalVod, initialBundle, new ArrayList<>(), finalError, false);");
        int plan = body.indexOf("TmdbSourceCapabilityPlanner.plan(initialBundle, sourcePayload");
        int zeroRequest = body.indexOf("if (!plan.hasInitialNetworkGaps()) return;");
        int request = body.indexOf("tmdbService.detailForSource(initialBundle.item(), sourcePayload.getSeasonNumber(), tmdbConfig, plan.missing())");

        assertTrue("source detail must be fetched before payload parsing", source >= 0 && parse > source);
        assertTrue("source bundle must be applied before the capability plan", apply > parse && plan > apply);
        assertTrue("complete initial capabilities must return without a TMDB request", zeroRequest > plan && request > zeroRequest);
    }

    @Test
    public void partialPayloadUsesIdentityRequestAndFillOnlyMerge() throws Exception {
        String body = loadContentBody();
        int request = body.indexOf("tmdbService.detailForSource(initialBundle.item(), sourcePayload.getSeasonNumber(), tmdbConfig, plan.missing())");
        int adapt = body.indexOf("TmdbSourceAdapter.fromNetwork(initialBundle.item(), detail, tmdbConfig)");
        int merge = body.indexOf("TmdbSourceMerger.fillOnly(initialBundle, sourcePayload, networkBundle)");
        int apply = body.indexOf("applyTmdbResultNow(new TmdbLoadResult(mergedBundle, List.of()));", merge);

        assertTrue("missing capabilities must request by the embedded TMDB identity", request >= 0 && adapt > request);
        assertTrue("network data must be merged fill-only and then applied", merge > adapt && apply > merge);
    }

    @Test
    public void legacyMatchingPathStartsOnlyAfterSourcePayloadDecision() throws Exception {
        String body = loadContentBody();
        int sourcePayload = body.indexOf("if (sourceBundle != null)");
        int legacyFuture = body.indexOf("Future<TmdbLoadResult> tmdbFuture = tmdbConfig.isReady() && tmdbAllowed");

        assertTrue("legacy title matching must not be scheduled before source/payload parsing", sourcePayload > 0 && legacyFuture > sourcePayload);
    }

    private static String loadContentBody() throws Exception {
        Path sourcePath = findMainJavaPath().resolve(Path.of("com", "fongmi", "android", "tv", "ui", "activity", "TmdbDetailActivity.java"));
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
        int start = source.indexOf("private void loadContent(@Nullable TmdbBundle reusableBundle)");
        int end = source.indexOf("private boolean shouldLoadInitialStandaloneTmdbDetailInSinglePass", start);
        assertTrue(sourcePath + " is missing the loadContent method", start >= 0 && end > start);
        return source.substring(start, end);
    }

    private static Path findMainJavaPath() {
        Path root = Files.exists(Path.of("src", "main")) ? Path.of(".") : Path.of("app");
        return root.resolve(Path.of("src", "main", "java"));
    }
}
