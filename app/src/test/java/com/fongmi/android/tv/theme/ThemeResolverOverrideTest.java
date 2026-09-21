package com.fongmi.android.tv.theme;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ThemeResolverOverrideTest {

    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;

    @Test
    public void absentOrEmptyProfileKeepsTheFrozenPalette() {
        assertEquals(ThemeTokens.light(), ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.NONE, 0, 0, null, null, false));
        assertEquals(ThemeTokens.dark(), ThemeResolver.resolve(
                ThemeMode.DARK, ThemeSeed.NONE, 0, 0, ThemeProfile.defaultProfile(), null, true));
        assertTrue(ThemeResolver.lastDiagnostic().startsWith("default"));
    }

    @Test
    public void eachColorSlotOverridesOnlyItsOwnToken() {
        ThemeProfile profile = ThemeProfile.defaultProfile();
        ThemeProfile.SlotSet slots = profile.light;
        slots.primary = "#000000";
        slots.primaryContainer = "#000000";
        slots.secondaryContainer = "#000000";
        slots.focus = "#000000";
        slots.surface = "#FFFFFF";
        slots.surfaceContainer = "#F7F7F7";
        slots.surfaceContainerHigh = "#EFEFEF";
        slots.onSurface = "#000000";
        slots.onSurfaceVariant = "#000000";
        slots.outline = "#000000";
        slots.error = "#000000";
        slots.success = "#000000";
        slots.warning = "#000000";

        ThemeTokens base = ThemeTokens.light();
        ThemeTokens tokens = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.NONE, 0, 0, profile, null, false);
        tokens.requireContrast();

        assertEquals(BLACK, tokens.colorPrimary());
        assertEquals(BLACK, tokens.colorPrimaryContainer());
        assertEquals(BLACK, tokens.colorSecondaryContainer());
        assertEquals(BLACK, tokens.colorFocus());
        assertEquals(WHITE, tokens.colorSurface());
        assertEquals(0xFFF7F7F7, tokens.colorSurfaceContainer());
        assertEquals(0xFFEFEFEF, tokens.colorSurfaceContainerHigh());
        assertEquals(BLACK, tokens.colorOnSurface());
        assertEquals(BLACK, tokens.colorOnSurfaceVariant());
        assertEquals(BLACK, tokens.colorOutline());
        assertEquals(BLACK, tokens.colorError());
        assertEquals(BLACK, tokens.colorSuccess());
        assertEquals(BLACK, tokens.colorWarning());

        // Slots the user did not touch keep the frozen palette.
        assertEquals(base.colorTertiary(), tokens.colorTertiary());
        assertEquals(base.colorSecondary(), tokens.colorSecondary());
        assertEquals(base.colorErrorContainer(), tokens.colorErrorContainer());
        assertEquals(base.colorOutlineVariant(), tokens.colorOutlineVariant());
        assertEquals(base.colorInverseSurface(), tokens.colorInverseSurface());
    }

    @Test
    public void playerAndHealthTokensAreNeverReachableFromAProfile() {
        ThemeProfile profile = ThemeProfile.defaultProfile();
        ThemeProfile.SlotSet slots = profile.light;
        slots.primary = "#FF00AA";
        slots.surface = "#FAFAFA";
        slots.error = "#AA0000";
        slots.dialogOpacity = 0.75f;
        slots.scrimOpacity = 0.80f;
        slots.overlayOpacity = 0.55f;

        ThemeTokens base = ThemeTokens.light();
        ThemeTokens tokens = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.NONE, 0, 0, profile, null, false);
        assertEquals(base.colorPlayerControl(), tokens.colorPlayerControl());
        assertEquals(base.colorPlayerControlMuted(), tokens.colorPlayerControlMuted());
        assertEquals(base.colorPlayerControlActive(), tokens.colorPlayerControlActive());
        assertEquals(base.colorPlayerScrim(), tokens.colorPlayerScrim());
        assertEquals(base.colorHealthGood(), tokens.colorHealthGood());
        assertEquals(base.colorHealthWarn(), tokens.colorHealthWarn());
        assertEquals(base.colorHealthBad(), tokens.colorHealthBad());
        assertEquals(base.colorOverlayDark(), tokens.colorOverlayDark());
        assertEquals(base.focusScale(), tokens.focusScale(), 0.0001f);
    }

    @Test
    public void derivedOnColorsFollowBlackOrWhiteReadability() {
        ThemeProfile profile = ThemeProfile.defaultProfile();
        profile.light.primary = "#FFFFFF";
        ThemeTokens light = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.NONE, 0, 0, profile, null, false);
        assertEquals(BLACK, light.colorOnPrimary());
        assertTrue(ThemeContrast.ratio(light.colorOnPrimary(), light.colorPrimary()) >= 4.5);

        ThemeProfile darkProfile = ThemeProfile.defaultProfile();
        darkProfile.dark.primary = "#000000";
        ThemeTokens dark = ThemeResolver.resolve(
                ThemeMode.DARK, ThemeSeed.NONE, 0, 0, darkProfile, null, true);
        assertEquals(WHITE, dark.colorOnPrimary());
        assertTrue(ThemeContrast.ratio(dark.colorOnPrimary(), dark.colorPrimary()) >= 4.5);
    }

    @Test
    public void unreadableUserTextIsCorrectedInsteadOfRejected() {
        ThemeProfile profile = ThemeProfile.defaultProfile();
        profile.light.surface = "#FFFFFF";
        profile.light.onSurface = "#FEFEFE";
        profile.light.onSurfaceVariant = "#FDFDFD";
        profile.light.outline = "#FBFBFB";

        ThemeTokens tokens = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.NONE, 0, 0, profile, null, false);
        tokens.requireContrast();
        assertTrue(ThemeContrast.ratio(tokens.colorOnSurface(), tokens.colorSurface()) >= 4.5);
        assertTrue(ThemeContrast.ratio(tokens.colorOnSurfaceVariant(), tokens.colorSurface()) >= 4.5);
        assertTrue(ThemeContrast.ratio(tokens.colorOutline(), tokens.colorSurface()) >= 3.0);
        assertTrue(ThemeResolver.lastDiagnostic().contains("profile"));
    }

    @Test
    public void identicalSurfaceLayersReceiveAMinimalDistinction() {
        ThemeProfile profile = ThemeProfile.defaultProfile();
        profile.light.surface = "#F5F5F5";
        profile.light.surfaceContainer = "#F5F5F5";
        profile.light.surfaceContainerHigh = "#F5F5F5";

        ThemeTokens tokens = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.NONE, 0, 0, profile, null, false);
        assertNotEquals(tokens.colorSurface(), tokens.colorSurfaceContainer());
        assertNotEquals(tokens.colorSurfaceContainer(), tokens.colorSurfaceContainerHigh());
        tokens.requireContrast();
    }

    @Test
    public void lightAndDarkSlotsResolveIndependently() {
        ThemeProfile profile = ThemeProfile.defaultProfile();
        profile.light.primary = "#155DFC";
        profile.dark.primary = "#FFCC00";

        ThemeTokens light = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.NONE, 0, 0, profile, null, false);
        ThemeTokens dark = ThemeResolver.resolve(
                ThemeMode.DARK, ThemeSeed.NONE, 0, 0, profile, null, true);
        assertEquals(0xFF155DFC, light.colorPrimary());
        assertEquals(0xFFFFCC00, dark.colorPrimary());
        assertNotEquals(light.colorPrimary(), dark.colorPrimary());
    }

    @Test
    public void opacitySlotsAreClampedToTheValidatorRange() {
        ThemeProfile profile = ThemeProfile.defaultProfile();
        profile.light.scrimOpacity = 0.85f;
        profile.light.dialogOpacity = 0.80f;
        profile.light.overlayOpacity = 0.60f;

        ThemeTokens tokens = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.NONE, 0, 0, profile, null, false);
        assertEquals(217, (tokens.colorScrim() >>> 24) & 0xFF);
        assertEquals(0.80f, tokens.dialogOpacity(), 0.0001f);
        assertEquals(153, (tokens.colorOverlayLight() >>> 24) & 0xFF);
    }

    @Test
    public void profileLayersOnTopOfTheSeedInsteadOfReplacingIt() {
        ThemeProfile profile = ThemeProfile.defaultProfile();
        profile.light.focus = "#FF00AA";

        ThemeTokens seeded = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.EXPLICIT, 0xFF0B57D0, 0, profile, null, false);
        assertEquals(0xFFFF00AA, seeded.colorFocus());
        assertTrue(ThemeResolver.lastDiagnostic().contains("profile"));
        // The seed still owns every slot the profile did not override.
        ThemeTokens seedOnly = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.EXPLICIT, 0xFF0B57D0, 0, null, null, false);
        assertEquals(seedOnly.colorPrimary(), seeded.colorPrimary());
        assertEquals(seedOnly.colorSurface(), seeded.colorSurface());
    }

    @Test
    public void invalidProfileFallsBackToLastGoodThenToTheFrozenDefault() {
        ThemeProfile broken = ThemeProfile.defaultProfile();
        broken.light.dialogOpacity = 0.10f;

        ThemeProfile lastGood = ThemeProfile.defaultProfile();
        lastGood.light.primary = "#123456";

        ThemeTokens recovered = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.NONE, 0, 0, broken, lastGood, false);
        assertEquals(0xFF123456, recovered.colorPrimary());
        assertTrue(ThemeResolver.lastDiagnostic().startsWith("last-good"));

        ThemeTokens fallback = ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.NONE, 0, 0, broken, null, false);
        assertEquals(ThemeTokens.light(), fallback);
        assertTrue(ThemeResolver.lastDiagnostic().startsWith("fallback"));
    }

    @Test
    public void invalidSeedStillShortCircuitsToTheDefaultPalette() {
        assertEquals(ThemeTokens.light(), ThemeResolver.resolve(
                ThemeMode.LIGHT, ThemeSeed.EXPLICIT, 0x00123456, 0, null, null, false));
        assertTrue(ThemeResolver.lastDiagnostic().startsWith("fallback:invalid-seed"));
    }
}
