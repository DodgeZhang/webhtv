package com.fongmi.android.tv.bean;

import org.junit.Test;



import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 同一 TMDB 集存在多个版本（同名但不同 URL 的源站条目）时的版本身份判定。
 * <p>
 * 场景来源：炫彩详情模式点击选集卡片跳到播放页后，用户点击“第二版本”却播放/选中“第一版本”。
 * 根因是 `matchesPlayback`/`matches` 在双方都绑定 TMDB 季集号时直接以“集号相同”返回同集，
 * 版本差异（URL）根本没参与比较。
 */
public class EpisodeVersionIdentityTest {

    private static Episode version(String name, String url, int season, int number) {
        Episode episode = Episode.create(name, url);
        episode.setTmdbEpisode(new TmdbEpisode(number, "", "", "", "", 0, 0, 0, season));
        return episode;
    }

    @Test
    public void sameTmdbEpisodeWithDifferentUrlsIsADifferentVersion() {
        Episode first = version("正片", "url-v1", 1, 3);
        Episode second = version("正片", "url-v2", 1, 3);

        assertFalse(second.matchesPlayback(first));
        assertFalse(first.matchesPlayback(second));
        assertFalse(second.matches(first));
        assertFalse(first.matches(second));
    }

    @Test
    public void sameTmdbEpisodeAndSameUrlIsTheSameVersion() {
        Episode first = version("正片", "url-v1", 1, 3);
        Episode again = version("正片", "url-v1", 1, 3);

        assertTrue(again.matchesPlayback(first));
        assertTrue(again.matches(first));
    }

    @Test
    public void unknownUrlOnOneSideMustNotFallBackToTheFirstVersion() {
        Episode known = version("正片", "url-v2", 1, 3);
        Episode unknown = version("正片", "", 1, 3);

        assertFalse(known.matchesPlayback(unknown));
        assertFalse(unknown.matchesPlayback(known));
    }

    @Test
    public void sameTmdbEpisodeWithoutAnyVersionInfoStaysTheSameEpisode() {
        Episode first = version("", "", 1, 3);
        Episode second = version("", "", 1, 3);

        assertTrue(second.matchesPlayback(first));
    }

    @Test
    public void sameTmdbEpisodeUsesSourceNameWhenNoUrlIsAvailable() {
        Episode first = version("[1080P] 第3集", "", 1, 3);
        Episode sameName = version("[1080P] 第3集", "", 1, 3);
        Episode otherName = version("[4K] 第3集", "", 1, 3);

        assertTrue(sameName.matchesPlayback(first));
        assertFalse(otherName.matchesPlayback(first));
    }

    @Test
    public void sameTmdbEpisodeUsesSourceDescriptionAsVersionEvidence() {
        Episode first = Episode.create("[720P] 第3集", "1080P", "");
        first.setTmdbEpisode(new TmdbEpisode(3, "", "", "", "", 0, 0, 0, 1));
        Episode second = Episode.create("[720P] 第3集", "4K", "");
        second.setTmdbEpisode(new TmdbEpisode(3, "", "", "", "", 0, 0, 0, 1));

        assertFalse(second.matchesPlayback(first));
    }

    @Test
    public void differentTmdbEpisodesStillRejectEachOther() {
        Episode third = version("正片", "url-3", 1, 3);
        Episode fourth = version("正片", "url-4", 1, 4);

        assertFalse(fourth.matchesPlayback(third));
    }

    @Test
    public void differentTmdbSeasonWithSameNumberStillRejects() {
        Episode firstSeason = version("正片", "url-s1e3", 1, 3);
        Episode secondSeason = version("正片", "url-s2e3", 2, 3);

        assertFalse(secondSeason.matchesPlayback(firstSeason));
    }

    @Test
    public void legacyUnboundEpisodesKeepTolerantUrlFallback() {
        Episode saved = Episode.create("第9集", "old-url");
        Episode refreshed = Episode.create("第9集", "new-url");

        assertTrue(refreshed.matchesPlayback(saved));
    }

    @Test
    public void findWithUrlSelectsTheRequestedVersionAmongDuplicates() {
        Flag flag = new Flag("线路1");
        Episode first = Episode.create("正片", "url-v1");
        Episode second = Episode.create("正片", "url-v2");
        flag.getEpisodes().add(first);
        flag.getEpisodes().add(second);

        assertSame(second, flag.find(Episode.create("正片", "url-v2"), true));
        assertSame(first, flag.find(Episode.create("正片", "url-v1"), true));
    }

    @Test
    public void findWithUrlStillFallsBackToEpisodeNumberWhenUrlIsUnknown() {
        Flag flag = new Flag("线路1");
        Episode first = Episode.create("第1集", "url-v1");
        Episode second = Episode.create("第2集", "url-v2");
        flag.getEpisodes().add(first);
        flag.getEpisodes().add(second);

        // 定位请求不带 URL 时，仍按集名/集号解析，不返回 null。
        assertSame(second, flag.find(Episode.create("第2集", ""), true));
    }

    @Test
    public void findWithoutUrlKeepsStrictMissSemantics() {
        Flag flag = new Flag("线路1");
        flag.getEpisodes().add(Episode.create("第1集", "url-v1"));
        flag.getEpisodes().add(Episode.create("第2集", "url-v2"));

        // 多集线路才存在「严格未命中」语义：单集线路的 find(Episode, true) 会无条件返回该集。
        assertNull(flag.find(Episode.create("另一个名字", ""), true));
    }

    @Test
    public void unknownSeasonKeepsTolerantTmdbNumberMatch() {
        // 季号一侧未知时不得做版本消歧，否则跨源续播（同集号、换源、换 URL）会被误判为不同集。
        Episode known = version("第3集", "url-v2", -1, 3);
        Episode other = version("源站第三集", "url-v9", 2, 3);

        assertTrue(other.matchesPlayback(known));
        assertTrue(known.matchesPlayback(other));
    }

    @Test
    public void matchesPlaybackTreatsHistoryRebuiltFromVersionAsSameVersion() {
        // 播放页把“用户点到的版本”写进 History（remarks + episodeUrl），
        // 再次比较时应认出同一版本，而不是同集的第一个版本。
        Episode second = version("正片", "url-v2", 1, 3);
        History history = new History();
        history.setVodRemarks(second.getName());
        history.setEpisodeUrl(second.getUrl());
        history.setTmdbEpisodePosition(second);

        Episode rebuilt = history.getEpisode();
        assertTrue(rebuilt.matchesPlayback(second));
        assertFalse(rebuilt.matchesPlayback(version("正片", "url-v1", 1, 3)));
    }

    @Test
    public void flagFindPrefersRequestedVariantOverTmdbNumberShortcut() {
        Flag flag = new Flag("线路1");
        Episode first = version("正片", "url-v1", 1, 3);
        Episode second = version("正片", "url-v2", 1, 3);
        flag.getEpisodes().add(first);
        flag.getEpisodes().add(second);

        Episode resolved = flag.find(version("正片", "url-v2", 1, 3), true);
        assertSame(second, resolved);
    }
}
