package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PlaybackProgressDeleteInput {

    @SerializedName("historyKey")
    public String historyKey;
    @SerializedName("siteKey")
    public String siteKey;
    @SerializedName("vodId")
    public String vodId;
    @SerializedName("episodeName")
    public String episodeName;
    @SerializedName("mediaType")
    public String mediaType;
    @SerializedName("tmdbId")
    public int tmdbId;
    @SerializedName("seasonNumber")
    public int seasonNumber = -1;
    @SerializedName("scope")
    public String scope;
    @SerializedName("cid")
    public int cid;
    @SerializedName("configKey")
    public String configKey;
    @SerializedName("configUrl")
    public String configUrl;
    @SerializedName("confirm")
    public boolean confirm;

    public PlaybackProgressDeleteInput normalize() {
        historyKey = safe(historyKey);
        siteKey = safe(siteKey);
        vodId = safe(vodId);
        episodeName = safe(episodeName);
        mediaType = safe(mediaType).toLowerCase(Locale.ROOT);
        if (!"tv".equals(mediaType) && !"movie".equals(mediaType)) mediaType = "";
        if (seasonNumber < 0) seasonNumber = -1;
        configKey = PlaybackConfigIdentity.normalizeKey(configKey);
        configUrl = safe(configUrl);
        if (TextUtils.isEmpty(configKey) && !TextUtils.isEmpty(configUrl)) configKey = PlaybackConfigIdentity.keyForUrl(configUrl);
        scope = safe(scope).toLowerCase();
        return this;
    }

<<<<<<< HEAD
=======
    public PlaybackProgressDeleteInput copy() {
        PlaybackProgressDeleteInput input = new PlaybackProgressDeleteInput();
        input.historyKey = historyKey;
        input.siteKey = siteKey;
        input.vodId = vodId;
        input.episodeName = episodeName;
        input.mediaType = mediaType;
        input.tmdbId = tmdbId;
        input.seasonNumber = seasonNumber;
        input.scope = scope;
        input.cid = cid;
        input.configKey = configKey;
        input.configUrl = configUrl;
        input.confirm = confirm;
        input.action = action;
        input.event = event;
        input.deleted = deleted;
        input.deletedAt = deletedAt;
        return input;
    }

>>>>>>> upstream/dev
    public boolean isAllScope() {
        normalize();
        return "all".equals(scope);
    }

    public boolean isSiteScope() {
        normalize();
        return "site".equals(scope);
    }

<<<<<<< HEAD
=======
    public boolean isSeasonScope() {
        normalize();
        return "season".equals(scope) && "tv".equals(mediaType) && tmdbId > 0 && seasonNumber >= 0;
    }

    public boolean requestsSeasonScope() {
        normalize();
        return "season".equals(scope);
    }

    public boolean hasMalformedSeasonScope() {
        return requestsSeasonScope() && !isSeasonScope();
    }

    public boolean isDeleteOperation() {
        normalize();
        return deleted || "delete".equals(action) || "deleted".equals(action) || "remove".equals(action)
                || "removed".equals(action) || "playback.deleted".equals(event);
    }

>>>>>>> upstream/dev
    public static PlaybackProgressDeleteInput fromJson(JsonObject object) {
        PlaybackProgressDeleteInput input = App.gson().fromJson(object, PlaybackProgressDeleteInput.class);
        if (input == null) input = new PlaybackProgressDeleteInput();
        applyAliases(input, object);
        return input.normalize();
    }

    public static List<PlaybackProgressDeleteInput> listFromJson(String text) {
        if (TextUtils.isEmpty(text)) return Collections.emptyList();
        JsonElement element = JsonParser.parseString(text);
        if (element == null || element.isJsonNull()) return Collections.emptyList();
        JsonArray array = asArray(element);
        if (array == null) return element.isJsonObject() ? Collections.singletonList(fromJson(element.getAsJsonObject())) : Collections.emptyList();
        List<PlaybackProgressDeleteInput> inputs = new ArrayList<>();
        for (JsonElement item : array) if (item != null && item.isJsonObject()) inputs.add(fromJson(item.getAsJsonObject()));
        return inputs;
    }

    private static void applyAliases(PlaybackProgressDeleteInput input, JsonObject object) {
        input.historyKey = firstString(input.historyKey, object, "key");
        input.siteKey = firstString(input.siteKey, object, "site", "site_key");
        input.configKey = firstString(input.configKey, object, "config_key", "interfaceKey", "sourceConfigKey");
        input.configUrl = firstString(input.configUrl, object, "config_url", "interfaceUrl", "sourceConfigUrl");
        input.vodId = firstString(input.vodId, object, "vod_id", "videoId", "itemId");
        input.episodeName = firstString(input.episodeName, object, "episode", "episodeTitle", "vodRemarks", "remarks");
<<<<<<< HEAD
=======
        input.mediaType = firstString(input.mediaType, object, "media_type", "type");
        input.tmdbId = firstInt(input.tmdbId, object, "tmdb_id", "tmdb");
        input.seasonNumber = firstInt(input.seasonNumber, object, "season", "season_number", "tmdbSeasonNumber");
        input.action = firstString(input.action, object, "op", "operation");
        input.deletedAt = firstLong(input.deletedAt, object, "deleted_at", "timestamp", "updateTime", "updatedAt", "updated_at");
        if (object.has("deleted")) input.deleted = booleanValue(object.get("deleted"), input.deleted);
>>>>>>> upstream/dev
    }

    private static JsonArray asArray(JsonElement element) {
        if (element.isJsonArray()) return element.getAsJsonArray();
        if (!element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        for (String key : new String[]{"items", "records", "data", "list"}) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonArray()) return value.getAsJsonArray();
        }
        return null;
    }

<<<<<<< HEAD
=======
    private static JsonObject unwrapSingle(JsonObject object) {
        for (String key : new String[]{"data", "record", "item"}) {
            JsonElement value = object.get(key);
            if (value == null || !value.isJsonObject()) continue;
            JsonObject target = value.getAsJsonObject().deepCopy();
            inherit(target, object, "action", "op", "operation", "event", "deleted", "scope", "confirm",
                    "deletedAt", "timestamp", "updatedAt", "cid", "configKey", "configUrl",
                    "mediaType", "tmdbId", "seasonNumber");
            return target;
        }
        return object;
    }

    private static void inherit(JsonObject target, JsonObject source, String... keys) {
        for (String key : keys) {
            if (target.has(key) || !source.has(key)) continue;
            JsonElement value = source.get(key);
            if (value == null || value.isJsonArray() || value.isJsonObject()) continue;
            target.add(key, value);
        }
    }

>>>>>>> upstream/dev
    private static String firstString(String current, JsonObject object, String... keys) {
        if (!TextUtils.isEmpty(current)) return current;
        for (String key : keys) {
            try {
                JsonElement value = object.get(key);
                if (value != null && !value.isJsonNull()) return value.getAsString();
            } catch (Exception ignored) {
            }
        }
        return current;
    }

<<<<<<< HEAD
=======
    private static long firstLong(long current, JsonObject object, String... keys) {
        if (current > 0) return current;
        for (String key : keys) {
            try {
                JsonElement value = object.get(key);
                if (value != null && !value.isJsonNull()) return value.getAsLong();
            } catch (Exception ignored) {
            }
        }
        return current;
    }

    private static int firstInt(int current, JsonObject object, String... keys) {
        if (current > 0) return current;
        for (String key : keys) {
            try {
                JsonElement value = object.get(key);
                if (value != null && !value.isJsonNull()) return value.getAsInt();
            } catch (Exception ignored) {
            }
        }
        return current;
    }

    private static boolean booleanValue(JsonElement value, boolean fallback) {
        try {
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return fallback;
            if (value.getAsJsonPrimitive().isNumber()) return value.getAsInt() != 0;
            String text = value.getAsString().trim();
            return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String part(String key, int index) {
        try {
            String[] parts = safe(key).split(AppDatabase.SYMBOL);
            return parts.length > index ? parts[index] : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String fallback(String value, String fallback) {
        return empty(value) ? safe(fallback) : safe(value);
    }

>>>>>>> upstream/dev
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
