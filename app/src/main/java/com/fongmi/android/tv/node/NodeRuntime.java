package com.fongmi.android.tv.node;

import android.content.Context;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.CatSource;
import com.fongmi.android.tv.nodejs.NodeBridge;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.Proxy;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 猫源运行时：拉 libnode → 拉 bundle → 起 Node → bundle 在本机监听，App 当它的 HTTP 客户端。
 *
 * <p>{@code node::Start} 会阻塞到事件循环结束，所以整条链路跑在独立线程上；
 * Node 在一个进程里只能起一次，所以这里只允许启动一次。
 */
public final class NodeRuntime {

    /** 首选端口，被占用时往后找；bundle 本身不做 EADDRINUSE 重试，所以由这边探。 */
    private static final int PREFERRED_PORT = 9988;
    private static final int PORT_SCAN = 20;

    /**
     * 实际监听端口。即使指定了首选端口，也仍以引导脚本落盘的结果为准——
     * 这样无论 bundle 用了哪个端口都能正确找到。
     */
    private static volatile int port;

    private static final AtomicBoolean STARTING = new AtomicBoolean(false);
    /** node::Start 在一个进程里只能调一次，第二次会把已初始化的运行时搞坏。 */
    private static final AtomicBoolean LAUNCHED = new AtomicBoolean(false);
    private static volatile boolean running;
    private static int lastPercent = -2;
    /**
     * 已经跑起来的是哪个 bundle。
     *
     * <p>Node 不能在进程内重启，所以换到另一个猫源时必须如实拒绝——早先是直接把当前服务
     * 的地址回给调用方，于是新源静默拿到<b>旧源的配置</b>，界面上表现为「确定后没反应」，
     * 还会把旧源的站点当成新源的存进配置里。
     */
    private static volatile String servingUrl = "";

    public interface Callback {
        void onProgress(String message);

        void onReady(String baseUrl);

        void onError(String message);
    }

    private NodeRuntime() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static int port() {
        return port;
    }

    public static String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public static String configUrl() {
        return configUrl(port);
    }

    private static String configUrl(int value) {
        return "http://127.0.0.1:" + value + "/config";
    }

    /**
     * 启动运行时。一个进程只能真正启动一次——Node 不可重启。
     *
     * <p>再次以<b>同一个</b> bundle 调用会直接复用已就绪的服务；换成<b>另一个</b> bundle 则
     * 明确失败，并告知需要重启应用。不能沿用旧服务：那会让新源拿到旧源的配置。
     *
     * @param url 用户填的猫源地址（{@code .../index.js.md5}）
     */
    public static void start(Context context, String url, Callback callback) {
        if (TextUtils.isEmpty(url)) {
            post(callback, "未填写猫源地址");
            return;
        }
        // LAUNCHED 才是「Node 已经认定了某个 bundle」的真信号：即使服务还没就绪、或者起来后又退了，
        // 也没法在本进程里换成另一个 bundle。只看 running 会漏掉这些中间态。
        if (LAUNCHED.get() && !same(url)) {
            reject(context, callback);
            return;
        }
        if (running) {
            if (callback != null) callback.onReady(baseUrl());
            return;
        }
        if (!STARTING.compareAndSet(false, true)) {
            // 正在启动的可能是别的 bundle：那种情况下等下去也等不到自己的源，得如实说要重启
            if (same(url) || TextUtils.isEmpty(servingUrl)) post(callback, "正在启动中");
            else reject(context, callback);
            return;
        }
        servingUrl = NodeBundle.bundleUrl(url);
        new Thread(() -> run(context, url, callback), "node-runtime").start();
    }

    /** 是否就是当前这个 bundle。比的是去掉 {@code .md5} 后的 bundle 地址，避免两种写法被当成两个源。 */
    private static boolean same(String url) {
        String requested = NodeBundle.bundleUrl(url);
        return !TextUtils.isEmpty(requested) && requested.equals(servingUrl);
    }

    /** 换源要重启才生效：说清楚，别让用户对着「没反应」猜。 */
    private static void reject(Context context, Callback callback) {
        String message = context.getString(R.string.node_switch_restart);
        SpiderDebug.log("node", "reject switch: running=%s requested another bundle", servingUrl);
        NodeNotify.done(context, message);
        toast(message);
        post(callback, message);
    }

    private static void run(Context context, String url, Callback callback) {
        NodeDialog dialog = null;
        try {
            // 只有首次要下几十 MB 才值得弹窗；后续启动只有几秒，弹窗反而干扰
            if (!NodeLib.installed(context)) {
                dialog = NodeDialog.create();
                dialog.show();
            }
            final NodeDialog ui = dialog;
            step(context, ui, callback, context.getString(R.string.node_prepare), -1);
            String downloading = context.getString(R.string.node_downloading);
            String error = NodeLib.ensure(context, (done, total) -> {
                int percent = total > 0 ? (int) (done * 100 / total) : -1;
                if (ui != null) ui.progress(downloading, done, total);
                notifyOnly(context, percent >= 0
                        ? String.format("%s %d%%（%s/%s）", downloading, percent, size(done), size(total))
                        : downloading + " " + size(done), percent);
            });
            if (error != null) {
                fail(context, ui, callback, "Node 运行时不可用: " + error);
                return;
            }
            step(context, ui, callback, context.getString(R.string.node_bundle), -1);
            error = NodeBundle.ensure(context, url);
            if (error != null) {
                fail(context, ui, callback, error);
                return;
            }
            File bundle = NodeBundle.file(context);
            File portFile = new File(NodeBundle.dir(context), "port");
            // 只有真要启动 Node 时才清端口：已启动过的话没人再写这个文件，
            // 删掉会让 waitReady 空等到超时（而服务其实可能已经就绪）。
            if (!LAUNCHED.get()) portFile.delete();
            int preferred = freePort();
            File script = NodeBoot.write(context, bundle, NodeBundle.config(context), Proxy.getPort(), preferred);
            String starting = context.getString(R.string.node_starting);
            step(context, ui, callback, preferred > 0 ? starting + "（" + preferred + "）" : starting, -1);
            if (LAUNCHED.compareAndSet(false, true)) {
                new Thread(() -> {
                    int code = NodeBridge.start(script, "--max-old-space-size=256");
                    running = false;
                    SpiderDebug.log("node", "node exited code=%s", code);
                }, "node-main").start();
            } else {
                // 已经起过一次：不能重来，只能等它自己就绪或如实报错
                SpiderDebug.log("node", "node already launched, skip second start");
            }
            if (waitReady(context, ui, portFile, callback)) {
                running = true;
                String ready = context.getString(R.string.node_ready);
                if (ui != null) ui.dismiss();
                NodeNotify.done(context, ready + "，端口 " + port);
                toast(ready);
                if (callback != null) callback.onReady(baseUrl());
            } else {
                fail(context, ui, callback, "服务未在预期时间内就绪");
            }
        } catch (Throwable e) {
            SpiderDebug.log("node", e);
            fail(context, dialog, callback, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            STARTING.set(false);
        }
    }

    /**
     * 先等引导脚本把候选端口落盘，再逐个探 /config，认准返回猫源配置的那个。
     *
     * <p>候选可能有多个：魔改 bundle 会额外起自己的 HTTP 服务（如内置弹幕服务器）。那些
     * 服务对 {@code /config} 返回 401 信封或欢迎页，都是非空响应——只判空会认错端口，
     * 配置里 sites 解析为空，表现为「订阅无效」且无任何报错。所以按配置形状判定。
     *
     * <p>每轮都重读端口文件：附带服务可能比猫源晚绑定，候选集会随之变化。
     */
    private static boolean waitReady(Context context, NodeDialog dialog, File portFile, Callback callback) {
        String announced = "";
        for (int i = 0; i < 90; i++) {
            try {
                Thread.sleep(500);
                List<Integer> candidates = readPorts(portFile);
                if (candidates.isEmpty()) continue;
                String text = candidates.toString();
                if (!text.equals(announced)) {
                    announced = text;
                    step(context, dialog, callback, "服务已监听 " + join(candidates) + "，装配站点 ...", -1);
                }
                for (int candidate : candidates) {
                    if (!CatSource.isConfig(OkHttp.string(configUrl(candidate)))) continue;
                    port = candidate;
                    return true;
                }
            } catch (InterruptedException e) {
                return false;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static String join(List<Integer> ports) {
        StringBuilder builder = new StringBuilder();
        for (int value : ports) builder.append(builder.length() == 0 ? "" : "/").append(value);
        return builder.toString();
    }

    /**
     * 从首选端口往后找一个能绑上的。探测用的 socket 立刻关闭，与 Node 真正 bind 之间
     * 存在极小的竞争窗口——所以最终仍以落盘的实际端口为准，探测只是让端口尽量可预期。
     * 全部占用时返回 0，让 bundle 自己取随机端口。
     */
    private static int freePort() {
        for (int candidate = PREFERRED_PORT; candidate < PREFERRED_PORT + PORT_SCAN; candidate++) {
            try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new java.net.InetSocketAddress("127.0.0.1", candidate));
                return candidate;
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /** 引导脚本落盘的候选端口，逗号分隔，猫源那个（我们指定的）在最前。兼容只有单个端口的旧格式。 */
    private static List<Integer> readPorts(File file) {
        List<Integer> ports = new ArrayList<>();
        if (!file.exists()) return ports;
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[128];
            int len = in.read(buf);
            if (len <= 0) return ports;
            for (String part : new String(buf, 0, len).trim().split(",")) {
                try {
                    int value = Integer.parseInt(part.trim());
                    if (value > 0 && !ports.contains(value)) ports.add(value);
                } catch (NumberFormatException ignored) {
                }
            }
            return ports;
        } catch (Exception ignored) {
            return ports;
        }
    }

    /** 阶段性进度：对话框、通知栏、回调三处同步。 */
    private static void step(Context context, NodeDialog dialog, Callback callback, String message, int percent) {
        if (dialog != null) dialog.status(message);
        notifyOnly(context, message, percent);
        if (callback != null) callback.onProgress(message);
    }

    /** 只更新通知栏——下载阶段回调很密，对话框那份由调用方单独刷。 */
    private static void notifyOnly(Context context, String message, int percent) {
        if (percent < 0 || percent != lastPercent) {
            lastPercent = percent;
            NodeNotify.progress(context, message, percent);
        }
        SpiderDebug.log("node", "%s", message);
    }

    private static void fail(Context context, NodeDialog dialog, Callback callback, String message) {
        SpiderDebug.log("node", "start failed: %s", message);
        if (dialog != null) dialog.dismiss();
        NodeNotify.done(context, "猫源启动失败：" + message);
        toast("猫源启动失败：" + message);
        if (callback != null) callback.onError(message);
    }

    private static void post(Callback callback, String message) {
        if (callback != null) callback.onError(message);
    }

    private static void toast(String message) {
        App.post(() -> Notify.show(message));
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1048576) return String.format(Locale.US, "%.0fKB", bytes / 1024f);
        return String.format(Locale.US, "%.1fMB", bytes / 1048576f);
    }
}
