package com.fongmi.android.tv.theme;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ThemeBinderContractTest {

    @Test
    public void explicitRoleTagsAreParsedCaseInsensitivelyAndBounded() {
        assertEquals(ThemeRole.PRIMARY, ThemeRole.fromTag("webhtv:primary"));
        assertEquals(ThemeRole.PRIMARY, ThemeRole.fromTag(" webhtv:PRIMARY "));
        assertEquals(ThemeRole.SURFACE_CONTAINER_HIGH, ThemeRole.fromTag("webhtv:surface_container_high"));
        assertNull(ThemeRole.fromTag("webhtv:"));
        assertNull(ThemeRole.fromTag("webhtv:playerControl"));
        assertNull(ThemeRole.fromTag("something-else"));
        assertNull(ThemeRole.fromTag(null));
        assertNull(ThemeRole.fromTag(42));
    }

    @Test
    public void rolesExposeOnlyUserSlotsAndTheirDerivedForegrounds() {
        int userSlots = 0;
        for (ThemeRole role : ThemeRole.values()) if (role.isUserSlot()) userSlots++;
        assertEquals(13, userSlots);
        for (ThemeRole role : ThemeRole.values()) {
            assertTrue(role.name(), role.name().matches("[A-Z_]+"));
        }
        // Player, media, health and brand roles must not be representable.
        assertNull(ThemeRole.fromTag("webhtv:player_control"));
        assertNull(ThemeRole.fromTag("webhtv:health_good"));
    }

    @Test
    public void indexResolvesUniqueBaselineColorsOnly() {
        ThemeTokens baseline = ThemeTokens.light();
        ThemeColorIndex index = ThemeColorIndex.of(baseline);
        assertFalse(index.isEmpty());
        assertEquals(ThemeRole.ERROR, index.uniqueRoleFor(baseline.colorError()));
        assertEquals(ThemeRole.SURFACE, index.uniqueRoleFor(baseline.colorSurface()));
        // The light palette deliberately shares one blue between primary and focus,
        // so that value is ambiguous and the binder requires both to agree.
        assertTrue(index.rolesFor(baseline.colorFocus()).contains(ThemeRole.PRIMARY));
        assertTrue(index.rolesFor(baseline.colorFocus()).contains(ThemeRole.FOCUS));
        assertNull(index.uniqueRoleFor(0xFF123456));
        assertNull(ThemeColorIndex.of(null).uniqueRoleFor(0xFF123456));
        assertTrue(ThemeColorIndex.of(null).isEmpty());
    }

    @Test
    public void sharedBaselineColorsAreReportedAsAmbiguous() {
        ThemeColorIndex index = ThemeColorIndex.of(ThemeTokens.light());
        Set<ThemeRole> roles = index.rolesFor(0xFFFFFFFF);
        assertTrue("white must be shared by several foreground roles", roles.size() > 1);
        assertNull(index.uniqueRoleFor(0xFFFFFFFF));
    }

    @Test
    public void replacementIsReturnedOnlyWhenEverySharingRoleAgrees() {
        ThemeTokens baseline = ThemeTokens.light();
        ThemeColorIndex index = ThemeColorIndex.of(baseline);

        ThemeProfile outlineProfile = ThemeProfile.defaultProfile();
        outlineProfile.light.outline = "#000000";
        ThemeTokens outlineActive = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.NONE, 0, 0, outlineProfile, null, false);
        assertEquals(Integer.valueOf(0xFF000000), index.replacementFor(baseline.colorOutline(), outlineActive));

        // White is shared by onPrimary/onSecondary/onTertiary; overriding only the
        // primary makes those roles disagree, so the binder must keep the static color.
        ThemeProfile primaryProfile = ThemeProfile.defaultProfile();
        primaryProfile.light.primary = "#FFFFFF";
        ThemeTokens primaryActive = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.NONE, 0, 0, primaryProfile, null, false);
        assertNull(index.replacementFor(baseline.colorOnPrimary(), primaryActive));
    }

    @Test
    public void unknownAndUnchangedColorsAreNeverRewritten() {
        ThemeTokens baseline = ThemeTokens.light();
        ThemeColorIndex index = ThemeColorIndex.of(baseline);
        assertNull(index.replacementFor(0xFF123456, ThemeTokens.dark()));
        assertNull(index.replacementFor(baseline.colorPrimary(), baseline));
        assertNull(index.replacementFor(baseline.colorPrimary(), null));
    }

    @Test
    public void binderIsControlledMainThreadOnlyAndPlayerSafe() throws Exception {
        String source = read("src/main/java/com/fongmi/android/tv/theme/ThemeBinder.java");
        assertTrue(source.contains("Looper.myLooper() != Looper.getMainLooper()"));
        assertTrue(source.contains("baseline.equals(active)"));
        assertTrue(source.contains("webhtv:ignore"));
        assertTrue(source.contains("addOnChildAttachStateChangeListener"));
        assertTrue(source.contains("isStateful()"));
        assertFalse("no Resources/AssetManager reflection", source.contains("AssetManager"));
        assertFalse("no reflection bypass", source.contains("setAccessible"));
        assertFalse("no style application bypass", source.contains("applyStyle"));
        // Class#getRecordComponents does not exist on Android API < 33 and crashed
        // the app on API 28; the signature must stay reflection-free.
        assertFalse("no Java-16 record reflection on the runtime path",
                source.contains("getRecordComponents()") || source.contains("getDeclaredConstructor("));
        assertTrue(source.contains("tokens.hashCode()"));
        assertTrue(source.contains("WeakHashMap"));
    }

    @Test
    public void everyActivityBindsTheThemeTreeTwice() throws Exception {
        for (String flavour : new String[]{"mobile", "leanback"}) {
            String source = read("src/" + flavour + "/java/com/fongmi/android/tv/ui/base/BaseActivity.java");
            int first = source.indexOf("ThemeController.bindTheme(getBinding().getRoot());");
            int second = source.indexOf("ThemeController.bindTheme(getBinding().getRoot());", first + 1);
            assertTrue(flavour + " must bind after setContentView", first > 0);
            assertTrue(flavour + " must re-bind after initView", second > first);
            assertTrue(flavour + " must bind before initEvent",
                    source.indexOf("initEvent();", second) > second);
        }
    }

    @Test
    public void bottomSheetsBindThroughTheSharedDialogChannel() throws Exception {
        String sheet = read("src/main/java/com/fongmi/android/tv/ui/dialog/BaseBottomSheetDialog.java");
        assertTrue(sheet.contains("ThemeController.bindDialog(dialog)"));
        assertTrue(sheet.contains("bindDialogTheme();"));
        String controller = read("src/main/java/com/fongmi/android/tv/theme/ThemeController.java");
        assertTrue(controller.contains("public static void bindTheme(View root)"));
        assertTrue(controller.contains("public static void bindDialog(Dialog dialog)"));
        assertTrue(controller.contains("public static boolean hasProfileOverrides()"));
        assertTrue(controller.contains("ThemeProfileStore.load()"));
    }

    private static String read(String path) throws Exception {
        Path root = Files.exists(Path.of("src")) ? Path.of("") : Path.of("app");
        return Files.readString(root.resolve(path), StandardCharsets.UTF_8);
    }
}
