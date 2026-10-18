package com.fongmi.android.tv.theme;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Java-built dialogs must not pin their foreground to a fixed colour.
 *
 * <p>The dialogs below draw onto {@code shape_shell_proxy_dialog}, whose fill is
 * {@code ?attr/colorSurfaceContainerHigh} - light in the day table
 * ({@code #E7E8EF}) and dark in the night table ({@code #2A2F34}). A hard-coded
 * foreground can therefore only be right in one of the two modes.
 *
 * <p>This is the Java-side twin of the layout guard in
 * {@link ThemeBaseWiringTest#darkGlassSheetsUseAConstantLightForeground}. Layouts pin
 * their colour in XML and can be checked by reading the file; these dialogs build their
 * views in code and assign the colour at runtime, so the check is on the assignment
 * source instead. The two guards cover the two ways a fixed colour gets in.
 *
 * <p>Measured on the day/night panel pair, the pinned literals these classes used were:
 * {@code #5F6368} 4.95:1 day but 2.23:1 night, {@code #202124} 13.18:1 day but 1.19:1
 * night. The semantic replacements resolve to 5.23:1 or better in <em>both</em> tables.
 */
public class DialogForegroundTokenTest {

    /** Java-built dialogs that paint onto the palette-following dialog panel. */
    private static final String[] DIALOG_SOURCES = {
            "DebugLogDialog.java",
            "MpvConfigDialog.java",
            "PlaybackPerformanceDialog.java",
            "ViewingRecordSyncDialog.java",
            "PlaybackRemoteSyncDialog.java",
            "PlaybackWebhookDialog.java",
    };

    /**
     * The viewing-record sync family: the top-level dialog and its two second-level managers.
     *
     * <p>They build their rows in code, so they are covered by the same foreground contract as
     * the dialogs above, plus a row/badge surface contract below.
     */
    private static final String[] VIEWING_RECORD_SYNC_SOURCES = {
            "ViewingRecordSyncDialog.java",
            "PlaybackRemoteSyncDialog.java",
            "PlaybackWebhookDialog.java",
    };

    /**
     * Fixed foregrounds that must not appear: the legacy Material greys and blues these
     * dialogs used to pin, plus the plain white/black literals that would equally break
     * the other mode.
     */
    private static final String[] FORBIDDEN_LITERALS = {
            "#5F6368", "#202124", "#3C4043", "#174EA6", "#1A73E8",
            "#8AB4F8", "#E8F0FE", "#C4C7C5", "#C5221F",
    };

    @Test
    public void javaBuiltDialogsDoNotPinAFixedForeground() throws Exception {
        for (String name : DIALOG_SOURCES) {
            String source = stripComments(read("src/main/java/com/fongmi/android/tv/ui/dialog/" + name));
            for (String literal : FORBIDDEN_LITERALS) {
                assertFalse(name + " pins the fixed foreground " + literal
                                + ", which cannot be legible in both the day and night palettes",
                        source.contains("\"" + literal + "\""));
            }
            assertFalse(name + " must not pin a foreground through Color.parseColor",
                    source.contains("Color.parseColor(\"#"));
        }
    }

    /**
     * The dialogs must take their foreground from the active semantic snapshot.
     *
     * <p>{@code Color.TRANSPARENT} is deliberately still allowed: it is not a foreground
     * and carries no palette assumption (window backgrounds, tab ripples).
     */
    @Test
    public void javaBuiltDialogsReadTheActiveTokens() throws Exception {
        for (String name : DIALOG_SOURCES) {
            String source = stripComments(read("src/main/java/com/fongmi/android/tv/ui/dialog/" + name));
            assertTrue(name + " must resolve its foreground from ThemeController.current()",
                    source.contains("ThemeController.current()"));
            assertTrue(name + " must import ThemeController",
                    source.contains("import com.fongmi.android.tv.theme.ThemeController;"));
        }
    }

    /**
     * The MPV action popup must keep its fill and its foreground in the same palette.
     *
     * <p>{@code MpvConfigDialog.actionItem} now paints its label with
     * {@code colorOnSurface} / {@code colorError}. The popup background it sits on was a
     * fixed white shape, so it had to migrate in the same change - otherwise night mode
     * would render a near-black label on a still-white popup. Both halves are pinned so a
     * later edit cannot move one without the other.
     */
    @Test
    public void mpvActionPopupFollowsTheSamePaletteAsItsForeground() throws Exception {
        String popup = stripComments(read("src/main/res/drawable/shape_mpv_action_menu.xml"));
        assertTrue("the popup fill must follow the palette",
                popup.contains("?attr/colorSurfaceContainerLowest"));
        assertTrue("the popup stroke must follow the palette",
                popup.contains("?attr/colorOutlineVariant"));
        assertFalse("the popup must not keep a fixed fill", popup.contains("#FFFFFF"));
        assertFalse("the popup must not keep a fixed stroke", popup.contains("#E1E5EA"));
    }

    /**
     * The viewing-record sync rows must take their card surface from the active palette.
     *
     * <p>These rows are built in code and were filled with a fixed light card
     * ({@code #F5F6F7}) on a fixed light hairline ({@code #DADCE0} / {@code #E2E5E8}), with the
     * row title pinned to {@code Color.BLACK} and the badge to {@code #FFFFFF}. None of those
     * values equals a {@code webhtv_color_*} role, so {@code ThemeBinder}'s exact-match rewrite
     * skipped them by design: under the night table the dialog panel went dark while every row
     * stayed a near-white card carrying black text, and the section labels/details stayed the
     * fixed {@code #5F6368} grey that measures 2.23:1 on that panel.
     *
     * <p>Measured after the change, the semantic pairs clear WCAG AA in both tables: row title
     * 15.43:1 day / 13.29:1 night, detail text 8.39:1 / 10.06:1, status colours 5.90:1 / 10.09:1,
     * and the suspended row's error text on its error container 5.14:1 / 5.51:1.
     */
    @Test
    public void viewingRecordSyncRowsUseTheActivePalette() throws Exception {
        for (String name : VIEWING_RECORD_SYNC_SOURCES) {
            String source = stripComments(read("src/main/java/com/fongmi/android/tv/ui/dialog/" + name));
            assertFalse(name + " must not pin a black or white literal, which cannot be legible in both palettes",
                    source.contains("Color.BLACK") || source.contains("Color.WHITE"));
            assertTrue(name + " must take its row card fill from the active palette",
                    source.contains("ThemeController.current().colorSurfaceContainerLow()"));
            assertTrue(name + " must take its row stroke from the active palette",
                    source.contains("ThemeController.current().colorOutlineVariant()"));
            assertTrue(name + " must take its row title from the active palette",
                    source.contains("ThemeController.current().colorOnSurface()"));
            assertTrue(name + " must take its detail text from the active palette",
                    source.contains("ThemeController.current().colorOnSurfaceVariant()"));
        }
    }

    /**
     * The badge chip and the suspended row keep their own semantic surfaces.
     *
     * <p>The badge was a fixed {@code #FFFFFF} fill, which under the night table drew a white
     * chip carrying the night success/error text. The suspended row was a fixed {@code #FFF7F7}
     * pink that only read as "attention" in the day table. Both are pinned here so a later edit
     * cannot move one half without the other.
     */
    @Test
    public void viewingRecordSyncBadgeAndSuspendedRowFollowThePalette() throws Exception {
        for (String name : new String[]{"PlaybackRemoteSyncDialog.java", "PlaybackWebhookDialog.java"}) {
            String source = stripComments(read("src/main/java/com/fongmi/android/tv/ui/dialog/" + name));
            assertTrue(name + " must take its badge fill from the active palette",
                    source.contains("ThemeController.current().colorSurfaceContainerLowest()"));
            assertTrue(name + " must take its status colour from the active palette",
                    source.contains("ThemeController.current().colorSuccess()"));
        }
        String webhook = stripComments(read("src/main/java/com/fongmi/android/tv/ui/dialog/PlaybackWebhookDialog.java"));
        assertTrue("the suspended webhook row must use the semantic error container",
                webhook.contains("ThemeController.current().colorErrorContainer()"));
    }

    private static String stripComments(String source) {
        String withoutXml = source.replaceAll("(?s)<!--.*?-->", "");
        return withoutXml
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private static String read(String path) throws Exception {
        Path root = Files.exists(Path.of("src")) ? Path.of("") : Path.of("app");
        return Files.readString(root.resolve(path), StandardCharsets.UTF_8);
    }
}
