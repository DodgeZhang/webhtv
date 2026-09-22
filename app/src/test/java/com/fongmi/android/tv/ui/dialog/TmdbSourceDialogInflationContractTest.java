package com.fongmi.android.tv.ui.dialog;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TmdbSourceDialogInflationContractTest {

    @Test
    public void materialButtonLabelsAreAppliedAfterInflation() throws Exception {
        String layout = read(sourcePath().resolve(Path.of("..", "..", "main", "res", "layout", "dialog_tmdb_source.xml")));
        String source = read(sourcePath().resolve(Path.of("com", "fongmi", "android", "tv", "ui", "dialog", "TmdbSourceDialog.java")));

        assertFalse("TMDB dialog buttons must not resolve text from binary XML on API 25",
                buttonBlock(layout, "add").contains("android:text=")
                        || buttonBlock(layout, "addDisabled").contains("android:text=")
                        || buttonBlock(layout, "manage").contains("android:text=")
                        || buttonBlock(layout, "resetDefault").contains("android:text="));
        assertTrue(source.contains("addBtn.setText(R.string.dialog_tmdb_add)"));
        assertTrue(source.contains("addDisabledBtn.setText(R.string.dialog_tmdb_add)"));
        assertTrue(source.contains("manageBtn.setText(R.string.dialog_tmdb_site_manage)"));
        assertTrue(source.contains("resetBtn.setText(R.string.dialog_tmdb_reset_default)"));
    }

    @Test
    public void proxySelectorDoesNotNestDuplicateHintLabels() throws Exception {
        String layout = read(sourcePath().resolve(Path.of("..", "..", "main", "res", "layout", "dialog_tmdb_source.xml")));
        int label = layout.indexOf("@string/dialog_tmdb_proxy_host_label");
        int input = layout.indexOf("@+id/proxyHostInput");
        assertTrue(label >= 0 && input > label);
        String section = layout.substring(label, Math.min(layout.length(), input + 900));
        assertTrue(section.contains("MaterialAutoCompleteTextView"));
        assertTrue(section.contains("TextInputLayout"));
        assertFalse("proxy selector must not duplicate the external label as a hint", section.contains("android:hint="));
    }

    private static String buttonBlock(String layout, String id) {
        String marker = "android:id=\"@+id/" + id + "\"";
        int idStart = layout.indexOf(marker);
        if (idStart < 0) return "";
        int start = layout.lastIndexOf("<com.google.android.material.button.MaterialButton", idStart);
        int end = layout.indexOf("/>", idStart);
        return start >= 0 && end >= 0 ? layout.substring(start, end) : "";
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path sourcePath() {
        Path moduleRelative = Path.of("src", "main", "java");
        if (Files.exists(moduleRelative)) return moduleRelative;
        return Path.of("app", "src", "main", "java");
    }
}
