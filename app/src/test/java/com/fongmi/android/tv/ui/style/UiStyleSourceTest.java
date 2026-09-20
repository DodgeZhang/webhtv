package com.fongmi.android.tv.ui.style;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UiStyleSourceTest {

    @Test
    public void sharedComponentStylesExistAndOnlyConsumeTokens() throws Exception {
        String styles = Files.readString(Path.of("src/main/res/values/webhtv_styles.xml"), StandardCharsets.UTF_8);
        String[] required = {
                "Theme.WebHTV", "ThemeOverlay.WebHTV.Dialog", "Widget.WebHTV.Button.Filled",
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
}
