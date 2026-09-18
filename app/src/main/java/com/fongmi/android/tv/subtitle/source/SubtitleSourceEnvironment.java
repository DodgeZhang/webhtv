package com.fongmi.android.tv.subtitle.source;

import com.fongmi.android.tv.setting.Setting;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SubtitleSourceEnvironment {

    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([A-Z][A-Z0-9_]*)}$");

    private SubtitleSourceEnvironment() {
    }

    public static JsonObject resolve(JsonObject params) {
        return resolve(params, load());
    }

    static JsonObject resolve(JsonObject params, JsonObject environment) {
        JsonObject result = params == null ? new JsonObject() : params.deepCopy();
        environment = environment == null ? new JsonObject() : environment;
        for (Map.Entry<String, JsonElement> entry : result.entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) continue;
            Matcher matcher = PLACEHOLDER.matcher(entry.getValue().getAsString());
            if (!matcher.matches()) continue;
            JsonElement value = environment.get(matcher.group(1));
            entry.setValue(value == null || value.isJsonNull() ? new com.google.gson.JsonPrimitive("") : value.deepCopy());
        }
        return result;
    }

    public static String resolveToken(String name) {
        JsonElement value = load().get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static JsonObject load() {
        String json = Setting.getSubtitleSourceEnvironment();
        if (json.isEmpty()) return new JsonObject();
        try {
            JsonElement element = JsonParser.parseString(json);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }
}
