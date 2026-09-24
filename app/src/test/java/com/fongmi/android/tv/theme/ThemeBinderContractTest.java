package com.fongmi.android.tv.theme;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
            int content = source.indexOf("View content = getBinding().getRoot();");
            int setContent = source.indexOf("setContentView(content);", content);
            int first = source.indexOf("ThemeController.bindTheme(content);", setContent);
            int second = source.indexOf("ThemeController.bindTheme(content);", first + 1);
            assertTrue(flavour + " must resolve one inflated binding as the content view", content > 0);
            assertTrue(flavour + " must bind after setContentView", setContent > content && first > setContent);
            assertTrue(flavour + " must re-bind after initView", second > first);
            assertTrue(flavour + " must bind before initEvent",
                    source.indexOf("initEvent();", second) > second);
        }
    }

    @Test
    public void presetSeedsMustResolveToTheirOwnPaletteInsteadOfFallingBack() {
        for (int seed : new int[]{0xFF0B57D0, 0xFF00897B, 0xFF146C2E, 0xFFFB8C00, 0xFFB3261E, 0xFF8E24AA}) {
            String hex = "#" + Integer.toHexString(seed).toUpperCase(java.util.Locale.ROOT);
            ThemeTokens tokens = ThemeResolver.resolve(
                    ThemeMode.LIGHT, ThemeSeed.EXPLICIT, seed, 0, null, null, false);
            String diagnostic = ThemeResolver.lastDiagnostic();
            assertFalse("seed " + hex + " silently fell back: " + diagnostic,
                    diagnostic.startsWith("fallback"));
            assertNotEquals("seed " + hex + " resolved to the frozen palette",
                    ThemeTokens.light().colorPrimary(), tokens.colorPrimary());
        }
    }

    @Test
    public void backgroundChannelCoversMaterialShapeAndInsetWrappers() throws Exception {
        String source = read("src/main/java/com/fongmi/android/tv/theme/ThemeBinder.java");
        assertTrue("must unwrap Material's inset window background", source.contains("instanceof InsetDrawable inset"));
        assertTrue("must reopen the wrapped drawable", source.contains("bindDrawable(inset.getDrawable()"));
        assertTrue("must cover Material shape panels", source.contains("instanceof MaterialShapeDrawable shape"));
        assertTrue(source.contains("shape.getFillColor()"));
        assertTrue(source.contains("shape.setFillColor(color)"));
        // Still no reflection or hidden API on the widening path.
        assertFalse(source.contains("setAccessible"));
        assertFalse(source.contains("getDeclaredField"));
    }

    @Test
    public void followingPageOptsIntoTheSharedAppearanceContract() throws Exception {
        String source = read("src/main/java/com/fongmi/android/tv/ui/activity/FollowingActivity.java");
        int apply = source.indexOf("ThemeController.applyFromPreferences(this);");
        int setContent = source.indexOf("setContentView(binding.getRoot());");
        int first = source.indexOf("ThemeController.bindTheme(binding.getRoot());");
        int initView = source.indexOf("initView();");
        int second = source.indexOf("ThemeController.bindTheme(binding.getRoot());", first + 1);
        assertTrue("must resolve tokens before the first content view", apply > 0 && apply < setContent);
        assertTrue("must bind right after setContentView", first > setContent);
        assertTrue("must re-bind after initView", second > first && initView > first && second > initView);
    }

    /**
     * Every Activity that hosts native views must participate in the theme contract.
     * Activities outside BaseActivity are the exception and are pinned here so a new
     * one cannot silently ship with a statically coloured tree.
     */
    @Test
    public void nativeActivitiesOutsideBaseActivityAreExplicitlyAccountedFor() throws Exception {
        Path root = Files.exists(Path.of("src")) ? Path.of("") : Path.of("app");
        Set<String> exempt = Set.of(
                "LabActivity", "LabDetailActivity", "LabOutputActivity", "LabTerminalActivity",
                "CatWebActivity", "GameWebActivity", "WebReaderActivity");
        java.util.List<String> unbound = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(root.resolve("src/main/java/com/fongmi/android/tv"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (!source.contains("extends AppCompatActivity")) continue;
                String name = path.getFileName().toString().replace(".java", "");
                if (exempt.contains(name)) continue;
                if (!source.contains("ThemeController.bindTheme(")) unbound.add(name);
            }
        }
        assertTrue("these activities host native views but never bind the theme: " + unbound, unbound.isEmpty());
    }

    @Test
    public void binderRewritesTheMaterialButtonAndBackgroundTintChannels() throws Exception {
        String source = read("src/main/java/com/fongmi/android/tv/theme/ThemeBinder.java");
        assertTrue(source.contains("instanceof MaterialButton button"));
        assertTrue(source.contains("view.getBackgroundTintList()"));
        assertTrue(source.contains("view.setBackgroundTintList(tint)"));
        assertTrue(source.contains("view.setStrokeColor(stroke)"));
        assertTrue(source.contains("view.setIconTint(icon)"));
        // The visible contract behind this: native pages fill their primary
        // buttons through app:backgroundTint, not through a GradientDrawable.
        String following = read("src/main/res/layout/activity_following.xml");
        assertTrue(following.contains("app:backgroundTint=\"?attr/colorPrimary\""));
    }

    @Test
    public void alertDialogFamiliesBindThroughTheSharedDialogChannel() throws Exception {
        String light = read("src/main/java/com/fongmi/android/tv/ui/dialog/LightDialog.java");
        assertEquals("both LightDialog entry points must bind",
                2, light.split("ThemeController\\.bindDialog\\(dialog\\)", -1).length - 1);
        assertFalse("the dialog title must not be a hard-coded hex colour",
                light.contains("Color.parseColor(\"#202124\")"));
        assertTrue(light.contains("titleView.setTextColor(ThemeController.current().colorOnSurface())"));

        String base = read("src/main/java/com/fongmi/android/tv/ui/dialog/BaseAlertDialog.java");
        assertTrue(base.contains("ThemeController.bindDialog(dialog)"));
        assertFalse("MaterialAlertDialogBuilder owns the dialog OnShowListener",
                base.contains("setOnShowListener"));

        // The channels the binder rewrites must really resolve through the
        // semantic tokens, otherwise the exact-match guard could never fire.
        for (String name : new String[]{"dialog_primary_button_bg", "dialog_primary_button_text",
                "dialog_outlined_button_bg", "dialog_outlined_button_text", "dialog_outlined_button_stroke"}) {
            String selector = read("src/main/res/color/" + name + ".xml");
            assertTrue(name + " must resolve through webhtv tokens",
                    selector.contains("@color/webhtv_color_"));
        }
    }

    @Test
    public void seedDerivedTokensStayMappableFromTheFrozenBaseline() {
        ThemeTokens baseline = ThemeTokens.light();
        ThemeTokens active = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.EXPLICIT, 0xFFB3261E, 0, null, null, false);
        assertNotEquals("an explicit seed must really move the palette",
                baseline.colorPrimary(), active.colorPrimary());
        // The seed derives focus from primary, so the shared-baseline ambiguity
        // resolves and the view colour can be rewritten.
        assertEquals(Integer.valueOf(active.colorPrimary()),
                ThemeColorIndex.of(baseline).replacementFor(baseline.colorPrimary(), active));
        assertEquals(Integer.valueOf(active.colorSurface()),
                ThemeColorIndex.of(baseline).replacementFor(baseline.colorSurface(), active));
    }

    @Test
    public void aSeedDerivedBaselineWouldSilentlyDisableTheBinder() {
        ThemeTokens staticPalette = ThemeTokens.light();
        ThemeTokens active = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.EXPLICIT, 0xFFB3261E, 0, null, null, false);
        ThemeTokens seedBaseline = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.EXPLICIT, 0xFFB3261E, 0, null, null, false);
        // Regression guard for the shipped defect: when the baseline was resolved
        // through the legacy theme_color seed it matched no inflated view colour,
        // so the binder bound nothing outside the legacy site dialog.
        assertNull(ThemeColorIndex.of(seedBaseline)
                .replacementFor(staticPalette.colorPrimary(), active));
        assertNull(ThemeColorIndex.of(seedBaseline)
                .replacementFor(staticPalette.colorSurface(), active));
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
