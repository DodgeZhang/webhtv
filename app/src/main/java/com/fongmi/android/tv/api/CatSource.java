package com.fongmi.android.tv.api;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.node.NodeRuntime;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 把「猫源」（CatPawOpen 一类的 CatVod T4 服务端）返回的配置整形成标准 TVBox 配置。
 *
 * <p>两处差异：配置顶层是裸站点数组而不是带 {@code sites} 字段的对象；站点 {@code api}
 * 是服务端上的相对路径（如 {@code /video/douban}）而不是绝对地址。凭据不用管——
 * {@code AuthInterceptor} 已经会把 URL 里的 userinfo 转成认证头并按 host 记住。
 */
public class CatSource {

    /** 猫源地址指向 Node bundle 本身（如 {@code .../index.js.md5}），不是可直接解析的配置。 */
    public static boolean isBundle(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String value = url.trim().toLowerCase();
        return value.endsWith(".js.md5") || value.endsWith("/index.js");
    }

    /**
     * 把 bundle 跑起来，返回本机可读的配置地址。阻塞到服务就绪——调用方本身在后台线程。
     */
    public static String serve(String url) throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> error = new java.util.concurrent.atomic.AtomicReference<>();
        NodeRuntime.start(App.get(), url, new NodeRuntime.Callback() {
            @Override
            public void onProgress(String message) {
                SpiderDebug.log("cat-source", "%s", message);
            }

            @Override
            public void onReady(String baseUrl) {
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                error.set(message);
                latch.countDown();
            }
        });
        latch.await();
        if (error.get() != null) throw new Exception("猫源启动失败: " + error.get());
        return NodeRuntime.configUrl();
    }

    /** 本机跑的 bundle 按媒体类型分组，点播站点在这个键下面。 */
    private static final String VIDEO = "video";

    /**
     * @throws IllegalArgumentException 配置既不是站点数组也不是对象时——服务端返回空响应、
     *                                  HTML 错误页或纯文本都会走到这里，明确报错比抛 NPE 好定位。
     */
    public static JsonObject normalize(String url, JsonElement root) {
        if (root == null || root.isJsonNull()) throw new IllegalArgumentException("配置为空");
        if (!root.isJsonArray() && !root.isJsonObject()) throw new IllegalArgumentException("配置格式不是 JSON 对象或数组");
        JsonObject object = root.isJsonArray() ? wrap(root.getAsJsonArray()) : lift(root.getAsJsonObject());
        rebase(object, base(url));
        return object;
    }

    private static JsonObject wrap(JsonArray sites) {
        JsonObject object = new JsonObject();
        object.add("sites", sites);
        return object;
    }

    /**
     * 猫源有两种 config 形态：远端服务给的是扁平站点数组；本机跑 bundle 时是
     * {@code {video:{sites:[...]}, read:{...}, comic:{...}, ...}}。后者把 video.sites 提上来，
     * 其余分组（小说/漫画/音乐/网盘）当前不接入点播列表。
     */
    private static JsonObject lift(JsonObject object) {
        if (object.has("sites") || !object.has(VIDEO)) return object;
        JsonElement video = object.get(VIDEO);
        if (!video.isJsonObject()) return object;
        JsonElement sites = video.getAsJsonObject().get("sites");
        if (sites == null || !sites.isJsonArray()) return object;
        JsonObject out = new JsonObject();
        out.add("sites", sites);
        return out;
    }

    /** 相对 api 单独存在没有意义，所以对任何配置都补基址，不只猫源。 */
    private static void rebase(JsonObject object, String base) {
        if (TextUtils.isEmpty(base) || !object.has("sites")) return;
        JsonElement sites = object.get("sites");
        if (!sites.isJsonArray()) return;
        for (JsonElement element : sites.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject site = element.getAsJsonObject();
            String api = string(site, "api");
            if (api.startsWith("/")) site.addProperty("api", base + api);
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) return "";
        return element.getAsString();
    }

    /** {@code scheme://userinfo@host:port}——保留 userinfo，免得每次请求都先吃一个 401。 */
    private static String base(String url) {
        if (TextUtils.isEmpty(url)) return "";
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        String authority = uri.getEncodedAuthority();
        if (TextUtils.isEmpty(scheme) || TextUtils.isEmpty(authority)) return "";
        return scheme + "://" + authority;
    }
}
