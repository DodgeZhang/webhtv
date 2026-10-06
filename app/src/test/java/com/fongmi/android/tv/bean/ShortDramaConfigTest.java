package com.fongmi.android.tv.bean;

import com.google.gson.Gson;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShortDramaConfigTest {

    @Test
    public void explicitEmptyRulesDisableDefaults() {
        ShortDramaConfig config = new Gson().fromJson(
                "{\"configured\":true,\"enabledSites\":[],\"disabledSites\":[]}",
                ShortDramaConfig.class);
        config.sanitize();

        assertTrue(config.isConfigured());
        assertEquals("", config.getDisplayRules());
        assertFalse(config.isSiteEnabled("nodejs_short", "[短]短剧站"));
    }

    @Test
    public void missingConfigurationStillUsesDefaults() {
        ShortDramaConfig config = new ShortDramaConfig().sanitize();

        assertFalse(config.isConfigured());
        assertEquals(Arrays.asList("[短]", "短剧"), ShortDramaConfig.defaultRules());
        assertTrue(config.isSiteEnabled("nodejs_short", "[短]短剧站"));
    }

    @Test
    public void dialogDoesNotRecreateDefaultsAfterRemovingAllRules() throws Exception {
        String source = read("app/src/main/java/com/fongmi/android/tv/ui/dialog/ShortDramaSourceDialog.java");

        assertTrue(source.contains("tempEnabledRules = new ArrayList<>(config.isConfigured()"));
        assertTrue(source.contains("List<String> enabledRules = tempEnabledRules;"));
        assertTrue(source.contains("tempEnabledRules.addAll(ShortDramaConfig.defaultRules());"));
    }

    private static String read(String file) throws Exception {
        Path root = Files.exists(Path.of("app")) ? Path.of("") : Path.of("..");
        return Files.readString(root.resolve(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
