package com.fongmi.android.tv.ui.activity;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class FollowingUiSourceTest {

    @Test
    public void mobileBottomNavigationPlacesFollowingBetweenLiveAndSettings() throws Exception {
        String menu = read("app/src/mobile/res/menu/menu_nav.xml");
        String mobile = read("app/src/mobile/java/com/fongmi/android/tv/ui/activity/HomeActivity.java");

        assertTrue(menu.indexOf("@+id/live") < menu.indexOf("@+id/following"));
        assertTrue(menu.indexOf("@+id/following") < menu.indexOf("@+id/setting"));
        assertTrue(mobile.contains("FollowingSettings.isEnabled()"));
        assertTrue(mobile.contains("FollowingActivity.start(this"));
        assertTrue(mobile.contains("updateFollowingBadge()"));
    }

    @Test
    public void leanbackHomeButtonUsesStableIdEightAndDefaultOrder() throws Exception {
        String homeButton = read("app/src/leanback/java/com/fongmi/android/tv/bean/HomeButton.java");
        String func = read("app/src/leanback/java/com/fongmi/android/tv/bean/Func.java");
        String home = read("app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java");

        assertTrue(homeButton.contains("new HomeButton(8, R.string.home_following)"));
        assertTrue(homeButton.contains("ALL = \"0,1,2,3,8,4,5,6,7,9\""));
        assertTrue(homeButton.indexOf("ids.add(\"8\")") > homeButton.indexOf("ids.add(\"3\")"));
        assertTrue(func.contains("ic_home_following"));
        assertTrue(home.contains("FollowingActivity.start(this, null)"));
    }

    @Test
    public void followingScreenExposesOfficialSourceAndUserStatesSeparately() throws Exception {
        String adapter = read("app/src/main/java/com/fongmi/android/tv/ui/adapter/FollowingAdapter.java");
        assertTrue(adapter.contains("following_official"));
        assertTrue(adapter.contains("following_source"));
        assertTrue(adapter.contains("following_watched"));
        assertTrue(adapter.contains("following_unwatched"));
    }

    private static String read(String relative) throws Exception {
        Path path = Path.of(relative);
        if (!Files.exists(path) && relative.startsWith("app/")) path = Path.of(relative.substring(4));
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
