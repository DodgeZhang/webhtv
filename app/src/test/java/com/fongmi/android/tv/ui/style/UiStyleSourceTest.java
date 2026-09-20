package com.fongmi.android.tv.ui.style;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UiStyleSourceTest {

    @Test
    public void sharedComponentStylesExistAndOnlyConsumeTokens() throws Exception {
        String styles = Files.readString(Path.of("src/main/res/values/webhtv_styles.xml"), StandardCharsets.UTF_8);
        String[] required = {
                "Theme.WebHTV", "Theme.WebHTV.Dialog", "ThemeOverlay.WebHTV.Dialog", "Widget.WebHTV.Button.Filled",
                "Widget.WebHTV.Button.Tonal", "Widget.WebHTV.Button.Outlined", "Widget.WebHTV.Button.Text",
                "Widget.WebHTV.Input", "Widget.WebHTV.ListItem", "Widget.WebHTV.Card",
                "Widget.WebHTV.Dialog", "Widget.WebHTV.BottomSheet", "Widget.WebHTV.PlayerControl"
        };
        for (String name : required) assertTrue(name, styles.contains("name=\"" + name + "\""));
        assertFalse(styles.matches("(?s).*#[0-9A-Fa-f]{6,8}.*"));
    }

    @Test
    public void selectorsCoverInteractionStates() throws Exception {
        Path directory = Path.of("src/main/res/color");
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(value -> value.getFileName().toString().startsWith("webhtv_selector_")).toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                assertTrue(path + " missing disabled", source.contains("state_enabled=\"false\""));
                assertTrue(path + " missing pressed", source.contains("state_pressed=\"true\""));
                assertTrue(path + " missing focused", source.contains("state_focused=\"true\""));
                assertTrue(path + " missing selected/activated", source.contains("state_selected=\"true\"") || source.contains("state_activated=\"true\""));
            }
        }
    }

    @Test
    public void legacyDialogNamesRemainAliasesOnly() throws Exception {
        String styles = Files.readString(Path.of("src/main/res/values/styles.xml"), StandardCharsets.UTF_8);
        assertTrue(styles.contains("<style name=\"Theme.WebHTV.LightDialog\" parent=\"Theme.WebHTV.Dialog\" />"));
        assertTrue(styles.contains("<style name=\"ThemeOverlay.WebHTV.LightDialog\" parent=\"ThemeOverlay.WebHTV.Dialog\" />"));
        for (Path path : javaSources()) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            assertFalse(path + " still uses a legacy dialog theme", source.contains("R.style.Theme_WebHTV_LightDialog"));
            assertFalse(path + " still uses a legacy dialog overlay", source.contains("R.style.ThemeOverlay_WebHTV_LightDialog"));
        }
    }

    @Test
    public void dialogAndSettingLayoutsUseSemanticColors() throws Exception {
        for (Path path : dialogAndSettingLayouts()) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            assertFalse(path + " contains a raw color", source.matches("(?s).*#[0-9A-Fa-f]{6,8}.*"));
            assertFalse(path + " contains a fixed business color", source.matches("(?s).*@color/(white|black|white_[0-9]+|black_[0-9]+|grey_[0-9]+|text|yellow)\\b.*"));
            assertFalse(path + " still uses a legacy widget style", source.contains("@style/Widget.WebHTV.LightDialog"));
        }
    }

    private static Path[] javaSources() throws Exception {
        try (Stream<Path> paths = Stream.of(Path.of("src/main/java"), Path.of("src/mobile/java"), Path.of("src/leanback/java"))) {
            return paths.flatMap(UiStyleSourceTest::walk)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toArray(Path[]::new);
        }
    }

    private static Path[] dialogAndSettingLayouts() throws Exception {
        return Stream.of(Path.of("src/main/res/layout"), Path.of("src/mobile/res/layout"), Path.of("src/leanback/res/layout"))
                .flatMap(UiStyleSourceTest::walk)
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith("dialog_") || name.startsWith("fragment_setting") || name.startsWith("activity_setting");
                })
                .toArray(Path[]::new);
    }

    private static Stream<Path> walk(Path root) {
        try {
            return Files.walk(root);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
