package com.fongmi.android.tv.utils;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpiderCrashBreadcrumbTest {

    @Test
    public void formatsSiteApiJarFingerprintAndSourceKind() {
        String text = SpiderCrashBreadcrumb.format("csp_PianDan", "csp_PianDan", "e351de1cedb1d8de5398cbf95f93f319", "http://host/spring.jar");

        assertEquals("site=csp_PianDan api=csp_PianDan jar=e351de1cedb1d8de5398cbf95f93f319 src=http", text);
    }

    @Test
    public void omitsEmptyFieldsInsteadOfLeavingDanglingSeparators() {
        assertEquals("jar=abc123", SpiderCrashBreadcrumb.format("", "", "abc123", ""));
        assertEquals("", SpiderCrashBreadcrumb.format("", "", "", ""));
    }

    @Test
    public void flattensControlCharactersSoTheCrashPageKeepsOneLinePerField() {
        String text = SpiderCrashBreadcrumb.format("key\nwith\tnewline", "api", "jar", "");

        assertFalse(text.contains("\n"));
        assertFalse(text.contains("\t"));
        assertEquals("site=key_with_newline api=api jar=jar", text);
    }

    @Test
    public void capsLengthSoAHostileConfigCannotBlowUpTheCrashPage() {
        String text = SpiderCrashBreadcrumb.format("k".repeat(4000), "a".repeat(4000), "j".repeat(4000), "http://x");

        assertTrue(text.length() <= 512);
    }

    @Test
    public void roundTripsThroughDiskAndReturnsEmptyForMissingFile() throws Exception {
        File dir = Files.createTempDirectory("spider-breadcrumb").toFile();
        File target = new File(dir, "spider-last.txt");
        Files.write(target.toPath(), "site=abc api=csp_X jar=deadbeef src=http".getBytes(StandardCharsets.UTF_8));

        assertEquals("site=abc api=csp_X jar=deadbeef src=http", SpiderCrashBreadcrumb.read(dir));
        assertEquals("", SpiderCrashBreadcrumb.read(Files.createTempDirectory("spider-empty").toFile()));
        assertEquals("", SpiderCrashBreadcrumb.read(null));
    }

    @Test
    public void rejectsAnOversizedFileRatherThanShowingGarbage() throws Exception {
        File dir = Files.createTempDirectory("spider-oversized").toFile();
        Files.write(new File(dir, "spider-last.txt").toPath(), "x".repeat(4096).getBytes(StandardCharsets.UTF_8));

        assertEquals("", SpiderCrashBreadcrumb.read(dir));
    }

    @Test
    public void treatsADirectoryAsUnreadableInsteadOfThrowing() throws Exception {
        File dir = Files.createTempDirectory("spider-directory").toFile();
        assertTrue(new File(dir, "spider-last.txt").mkdirs());

        assertEquals("", SpiderCrashBreadcrumb.read(dir));
    }
}
