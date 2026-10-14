package com.fongmi.android.tv.ui.dialog;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SiteDialogLayoutTest {

    @Test
    public void siteDialogUsesCenteredPanelInsteadOfFullScreen() throws Exception {
        Path sourcePath = findLeanbackJavaPath().resolve(Path.of("com", "fongmi", "android", "tv", "ui", "dialog", "SiteDialog.java"));
        String source = new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);

        assertTrue("Site dialog window must be a centered panel, not a full-screen sheet",
                source.contains("params.gravity = Gravity.CENTER"));
        assertTrue("Site dialog window height must wrap its content",
                source.contains("params.height = WindowManager.LayoutParams.WRAP_CONTENT"));
        assertTrue("Site dialog window size must be applied through setLayout on TV devices",
                source.contains("window.setLayout(params.width, params.height);"));
        assertFalse("Site dialog must not fill the screen width",
                source.contains("params.width = WindowManager.LayoutParams.MATCH_PARENT"));
        assertFalse("Site dialog must not fill the screen height",
                source.contains("params.height = WindowManager.LayoutParams.MATCH_PARENT"));
    }

    @Test
    public void siteDialogLeavesSafeMarginsAndCapsItsWidth() throws Exception {
        Path sourcePath = findLeanbackJavaPath().resolve(Path.of("com", "fongmi", "android", "tv", "ui", "dialog", "SiteDialog.java"));
        String source = new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);

        assertTrue("panel width must be a fraction of the screen so it never spans the full width",
                source.contains("screen * PANEL_WIDTH_RATIO"));
        assertTrue("panel width must be capped so 4K TVs do not stretch a single row",
                source.contains("PANEL_MAX_WIDTH_DP"));
        assertTrue("panel height must be capped as a fraction of the available height",
                source.contains("PANEL_MAX_HEIGHT_RATIO"));
    }

    @Test
    public void siteGridHeightSnapsToWholeRowsSoTheLastRowIsNotClipped() throws Exception {
        Path sourcePath = findLeanbackJavaPath().resolve(Path.of("com", "fongmi", "android", "tv", "ui", "dialog", "SiteDialog.java"));
        String source = new String(Files.readAllBytes(sourcePath), StandardCharsets.UTF_8);

        assertTrue("grid height must be derived from whole rows",
                source.contains("private int gridHeight(int count)"));
        assertTrue("grid height must be clamped to the panel's height budget",
                source.contains("gridBudgetHeight()"));
        assertFalse("the grid must no longer be stretched to fill the panel",
                source.contains("params.height = ViewGroup.LayoutParams.MATCH_PARENT;\n        binding.recycler.setLayoutParams(params);"));
    }

    private static Path findLeanbackJavaPath() {
        Path moduleRelative = Path.of("src", "leanback", "java");
        if (Files.exists(moduleRelative)) return moduleRelative;
        return Path.of("app", "src", "leanback", "java");
    }
}
