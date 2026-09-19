package com.fongmi.android.tv.following;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FollowingSourceProbeTest {

    @Test
    public void preferredFlagIsUsedWhenItHasPlayableEpisodes() {
        Vod vod = vod(
                Flag.create("preferred", "1$one#2$two"),
                Flag.create("fallback", "1$one#2$two#3$three"));

        assertEquals("preferred", FollowingSourceProbe.chooseFlag(vod, "preferred").getFlag());
    }

    @Test
    public void emptyPreferredFlagFallsBackToLargestPlayableFlag() {
        Vod vod = vod(
                Flag.create("preferred", ""),
                Flag.create("small", "1$one"),
                Flag.create("large", "1$one#2$two#3$three"));

        assertEquals("large", FollowingSourceProbe.chooseFlag(vod, "preferred").getFlag());
    }

    @Test
    public void noPlayableEpisodesReturnsNull() {
        assertNull(FollowingSourceProbe.chooseFlag(vod(Flag.create("empty", "")), ""));
    }

    @Test
    public void episodeNumberPrefersTmdbMapping() {
        Episode episode = Episode.create("第10集", "url");
        episode.setTmdbEpisode(new com.fongmi.android.tv.bean.TmdbEpisode(3, "", "", "", "", 0, 0));

        assertEquals(3, FollowingSourceProbe.episodeNumber(episode));
    }

    private static Vod vod(Flag... flags) {
        Vod vod = new Vod();
        vod.setFlags(List.of(flags));
        return vod;
    }
}
