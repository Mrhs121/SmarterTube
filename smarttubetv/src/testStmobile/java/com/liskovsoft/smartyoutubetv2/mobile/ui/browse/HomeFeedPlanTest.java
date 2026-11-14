package com.liskovsoft.smartyoutubetv2.mobile.ui.browse;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure JVM tests for {@link HomeFeedAdapter} recommended feed candidate checks, Shorts filtering,
 * and incremental vertical pagination detection.
 */
public class HomeFeedPlanTest {

    private static Video makeVideo(String id, boolean isShorts, boolean isChannel) {
        Video v = new Video();
        v.videoId = isChannel ? null : id;
        v.channelId = isChannel ? "ch_" + id : null;
        v.title = "Title " + id;
        v.isShorts = isShorts;
        return v;
    }

    @Test
    public void isRecommendedCandidate_rejectsShortsOnlyAndChannelOnlyGroups() {
        List<Video> shortsOnly = Arrays.asList(
                makeVideo("s1", true, false),
                makeVideo("s2", true, false));
        List<Video> channelsOnly = Arrays.asList(
                makeVideo("c1", false, true),
                makeVideo("c2", false, true));
        List<Video> recommended = Arrays.asList(
                makeVideo("v1", false, false),
                makeVideo("v2", false, false));

        assertFalse(HomeFeedAdapter.isRecommendedCandidate(Collections.emptyList()));
        assertFalse(HomeFeedAdapter.isRecommendedCandidate(shortsOnly));
        assertFalse(HomeFeedAdapter.isRecommendedCandidate(channelsOnly));
        assertTrue(HomeFeedAdapter.isRecommendedCandidate(recommended));
    }

    @Test
    public void filterRecommended_excludesShortsItems() {
        List<Video> mixed = Arrays.asList(
                makeVideo("v1", false, false),
                makeVideo("s1", true, false),
                makeVideo("v2", false, false));

        List<Video> filtered = HomeFeedAdapter.filterRecommended(mixed);
        assertEquals(2, filtered.size());
        assertEquals("v1", filtered.get(0).videoId);
        assertEquals("v2", filtered.get(1).videoId);
    }

    @Test
    public void isPureTailAppend_detectsIncrementalPagination() {
        List<Video> page1 = Arrays.asList(
                makeVideo("v0", false, false),
                makeVideo("v1", false, false),
                makeVideo("v2", false, false));

        List<Video> page2 = new ArrayList<>(page1);
        page2.add(makeVideo("v3", false, false));
        page2.add(makeVideo("v4", false, false));

        assertTrue(HomeFeedAdapter.isPureTailAppend(page1, page2));
        assertFalse(HomeFeedAdapter.isPureTailAppend(Collections.emptyList(), page1));

        List<Video> replaced = Arrays.asList(
                makeVideo("x0", false, false),
                makeVideo("v1", false, false),
                makeVideo("v2", false, false),
                makeVideo("v3", false, false));
        assertFalse(HomeFeedAdapter.isPureTailAppend(page1, replaced));
    }
}
