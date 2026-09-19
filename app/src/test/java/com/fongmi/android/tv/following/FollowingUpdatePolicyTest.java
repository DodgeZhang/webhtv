package com.fongmi.android.tv.following;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FollowingUpdatePolicyTest {

    @Test
    public void firstAddDoesNotNotifyButLaterEpisodeDoes() {
        Following item = following(0);
        FollowingUpdatePolicy.initializeNew(item, 0, 100);
        assertFalse(FollowingUpdatePolicy.shouldNotify(item));

        FollowingMetadataSnapshot snapshot = snapshot(1, 1);
        item.notifyEnabled = true;
        assertTrue(FollowingUpdatePolicy.applyMetadata(item, snapshot, 200));
        assertTrue(FollowingUpdatePolicy.shouldNotify(item));
    }

    @Test
    public void readAndNotifiedWatermarksSuppressDuplicates() {
        Following item = following(8);
        item.notifyEnabled = true;
        FollowingUpdatePolicy.initializeNew(item, 8, 100);
        item.watchedEpisode = 5;
        FollowingUpdatePolicy.refreshDerived(item, 0);
        assertEquals(3, item.unwatchedCount);
        assertFalse(item.hasUpdate);
        assertFalse(FollowingUpdatePolicy.shouldNotify(item));

        FollowingUpdatePolicy.applyMetadata(item, snapshot(9, 9), 200);
        assertTrue(FollowingUpdatePolicy.shouldNotify(item));
        FollowingUpdatePolicy.markNotified(item, 201);
        assertFalse(FollowingUpdatePolicy.shouldNotify(item));
        FollowingUpdatePolicy.markRead(item, 202);
        assertFalse(item.hasUpdate);
        assertEquals(4, item.unwatchedCount);
    }

    private static Following following(int released) {
        Following item = new Following();
        item.identityKey = "tmdb:tv:1:s1";
        item.trackedSeason = 1;
        item.latestReleasedSeason = 1;
        item.latestReleasedEpisode = released;
        item.seasonReleasedEpisodes = released;
        return item;
    }

    private static FollowingMetadataSnapshot snapshot(int season, int episode) {
        FollowingMetadataSnapshot snapshot = new FollowingMetadataSnapshot();
        snapshot.source = "tmdb";
        snapshot.status = FollowingMetadataSnapshot.RETURNING;
        snapshot.latestReleasedSeason = season;
        snapshot.latestReleasedEpisode = episode;
        snapshot.seasonReleasedEpisodes = episode;
        snapshot.fetchedAt = 200;
        return snapshot;
    }
}
