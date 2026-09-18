package com.fongmi.android.tv.subtitle.source;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

public class SubtitleSourceEnvironmentTest {

    @Test
    public void resolvesWhitelistedPlaceholderWithoutMutatingManifestParams() {
        JsonObject params = object("{\"token\":\"${ASSRT_TOKEN}\",\"literal\":\"keep\"}");
        JsonObject environment = object("{\"ASSRT_TOKEN\":\"secret\"}");

        JsonObject resolved = SubtitleSourceEnvironment.resolve(params, environment);

        assertEquals("secret", resolved.get("token").getAsString());
        assertEquals("keep", resolved.get("literal").getAsString());
        assertEquals("${ASSRT_TOKEN}", params.get("token").getAsString());
    }

    @Test
    public void replacesMissingPlaceholderWithEmptyStringAndIgnoresInvalidNames() {
        JsonObject params = object("{\"missing\":\"${MISSING_TOKEN}\",\"invalid\":\"${lowercase}\"}");

        JsonObject resolved = SubtitleSourceEnvironment.resolve(params, new JsonObject());

        assertEquals("", resolved.get("missing").getAsString());
        assertEquals("${lowercase}", resolved.get("invalid").getAsString());
    }

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
