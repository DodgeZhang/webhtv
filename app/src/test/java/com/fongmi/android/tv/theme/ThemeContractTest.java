package com.fongmi.android.tv.theme;

import org.junit.Test;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ThemeContractTest {

    private static final Pattern COLOR = Pattern.compile("<color name=\"(webhtv_color_[a-z0-9_]+)\">#([0-9A-Fa-f]{6,8})</color>");

    @Test
    public void resourceMappingMatchesTheImmutableRecordForEveryColor() throws Exception {
        assertResourceMapping(ThemeTokens.light(), Path.of("src/main/res/values/webhtv_tokens.xml"));
        assertResourceMapping(ThemeTokens.dark(), Path.of("src/main/res/values-night/webhtv_tokens.xml"));
    }

    @Test
    public void allContrastPairsAreFrozenAndPass() {
        ThemeTokens.light().requireContrast();
        ThemeTokens.dark().requireContrast();
        assertEquals(6.386, ThemeContrast.ratio(ThemeTokens.light().colorOnPrimary(), ThemeTokens.light().colorPrimary()), 0.002);
        assertEquals(4.284, ThemeContrast.ratio(ThemeTokens.light().colorOutline(), ThemeTokens.light().colorSurface()), 0.002);
        assertEquals(14.724, ThemeContrast.ratio(ThemeTokens.dark().colorPlayerControlActive(), 0xFF000000), 0.002);
    }

    @Test
    public void playerControlsAreIsolatedFromGeneralFocusAndPrimary() {
        for (ThemeTokens tokens : new ThemeTokens[]{ThemeTokens.light(), ThemeTokens.dark()}) {
            assertNotEquals(tokens.colorPrimary(), tokens.colorPlayerControlActive());
            assertNotEquals(tokens.colorFocus(), tokens.colorPlayerControlActive());
        }
    }

    @Test
    public void allSemanticAttrsAreDeclared() throws Exception {
        String attrs = Files.readString(Path.of("src/main/res/values/webhtv_attrs.xml"), StandardCharsets.UTF_8);
        for (RecordComponent component : ThemeTokens.class.getRecordComponents()) {
            if (!component.getType().equals(int.class)) continue;
            assertTrue(component.getName(), attrs.contains("name=\"" + attrName(component.getName()) + "\""));
        }
        assertFalse(attrs.contains("format=\"string\""));
    }

    private static void assertResourceMapping(ThemeTokens tokens, Path path) throws Exception {
        Map<String, Integer> colors = colors(path);
        assertEquals(49, colors.size());
        for (RecordComponent component : ThemeTokens.class.getRecordComponents()) {
            if (!component.getType().equals(int.class)) continue;
            String name = component.getName();
            String resource = "webhtv_color_" + snakeCase(name.substring("color".length()));
            Integer actual = colors.get(resource);
            assertTrue(path + " missing " + resource, actual != null);
            assertEquals(resource, component.getAccessor().invoke(tokens), actual);
        }
    }

    private static Map<String, Integer> colors(Path path) throws Exception {
        Map<String, Integer> colors = new LinkedHashMap<>();
        Matcher matcher = COLOR.matcher(Files.readString(path, StandardCharsets.UTF_8));
        while (matcher.find()) {
            long value = Long.parseLong(matcher.group(2), 16);
            colors.put(matcher.group(1), matcher.group(2).length() == 6 ? (int) (0xFF000000L | value) : (int) value);
        }
        return colors;
    }

    private static String attrName(String getter) {
        return "webhtv" + Character.toUpperCase(getter.charAt(0)) + getter.substring(1);
    }

    private static String snakeCase(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.US);
    }
}
