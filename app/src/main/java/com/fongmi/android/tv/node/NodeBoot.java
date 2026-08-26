package com.fongmi.android.tv.node;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 生成 Node 的入口脚本。
 *
 * <p>CatPawOpen 的 bundle 引用两个宿主全局：{@code catServerFactory}（fastify 的
 * serverFactory）和 {@code catDartServerPort()}（宿主 HTTP 端口，bundle 用它把
 * {@code /msg} 请求 POST 回宿主）。宿主是 Dart 的 CatVodApp，我们这边等价实现即可：
 * serverFactory 直接交回 Node 自己的 http.createServer，端口指向本机 Nano 服务。
 */
final class NodeBoot {

    private NodeBoot() {
    }

    static File write(Context context, File bundle, File config, int hostPort, int listenPort) throws IOException {
        File script = new File(NodeBundle.dir(context), "boot.js");
        try (FileOutputStream out = new FileOutputStream(script)) {
            out.write(source(bundle, config, hostPort, listenPort).getBytes("UTF-8"));
        }
        return script;
    }

    private static String escape(File file) {
        return file.getAbsolutePath().replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String source(File bundle, File config, int hostPort, int listenPort) {
        String path = escape(bundle);
        String cfg = escape(config);
        String portEscaped = escape(new File(bundle.getParentFile(), "port"));
        File data = new File(bundle.getParentFile(), "data");
        data.mkdirs();
        String dataEscaped = escape(data);
        return "'use strict';\n"
                + "const http = require('http');\n"
                // fastify 的 serverFactory 契约：拿到 (handler, opts) 返回一个 http.Server
                // fastify 的 serverFactory 只该用 handler；第二个参数是 fastify 自己的
                // 选项对象，塞给 http.createServer 会让 listen 建不起来
                + "globalThis.catServerFactory = (handler) => http.createServer(handler);\n"
                // bundle 会 POST 到 http://127.0.0.1:<port>/msg 回调宿主
                + "globalThis.catDartServerPort = () => " + hostPort + ";\n"
                // 这个 bundle 的 listen 默认 `port: process.env.DEV_HTTP_PORT || 0`，即随机端口，
                // 且没有 EADDRINUSE 重试。所以由 Java 侧先探到空闲端口再从这里指定；
                // 实际绑定结果仍以下面落盘的端口为准，避免任何假设。
                + (listenPort > 0 ? "process.env.DEV_HTTP_PORT = '" + listenPort + "';\n" : "")
                // NODE_PATH 是 bundle 约定的数据根目录：db.json 和多线程代理的 vod_cache 都落在这儿。
                // 不设会回退到进程 CWD，代理拿不到可靠的缓存目录、返回 200 但 0 字节。
                + "process.env.NODE_PATH = '" + dataEscaped + "';\n"

                + "process.on('uncaughtException', (e) => console.error('uncaught', e));\n"
                + "process.on('unhandledRejection', (e) => console.error('unhandled', e));\n"
                + "(async () => {\n"
                + "  try {\n"
                + "    const mod = require('" + path + "');\n"
                + "    const start = mod.start || (mod.default && mod.default.start);\n"
                + "    if (typeof start !== 'function') throw new Error('bundle has no start()');\n"
                // 配置必须传真实内容：各站点从 server.config.<key> 取参数，空对象会让它们抛 undefined
                + "    let conf = {};\n"
                + "    try {\n"
                + "      const raw = require('" + cfg + "');\n"
                + "      conf = raw && raw.default ? raw.default : (raw || {});\n"
                + "      console.log('config keys: ' + Object.keys(conf).length);\n"
                + "    } catch (e) { console.error('config load failed', e.message); }\n"
                + "    await start(conf);\n"
                // listen 失败时事件循环会空掉、node::Start 直接返回，进程死得无声无息。
                // 留一个心跳既能撑住循环，也能把绑定结果打出来便于定位。
                + "    setInterval(() => {}, 60000);\n"
                // bundle 里 listen 没指定端口时由系统分配，端口是随机的，
                // 所以启动后把实际端口落盘，Java 侧读它来拼地址。
                + "    const publish = () => {\n"
                + "      try {\n"
                + "        const handles = process._getActiveHandles ? process._getActiveHandles() : [];\n"
                + "        for (const h of handles) {\n"
                + "          if (h && typeof h.address === 'function' && h.constructor && h.constructor.name === 'Server') {\n"
                + "            const a = h.address();\n"
                + "            if (a && a.port) {\n"
                + "              require('fs').writeFileSync('" + portEscaped + "', String(a.port));\n"
                + "              console.log('cat bundle listening on ' + a.port);\n"
                + "              return true;\n"
                + "            }\n"
                + "          }\n"
                + "        }\n"
                + "      } catch (e) { console.error('publish port failed', e.message); }\n"
                + "      return false;\n"
                + "    };\n"
                + "    let tries = 0;\n"
                + "    const timer = setInterval(() => { if (publish() || ++tries > 60) clearInterval(timer); }, 500);\n"
                + "    console.log('cat bundle started on " + listenPort + "');\n"
                + "  } catch (e) {\n"
                + "    console.error('cat bundle failed', e && e.stack ? e.stack : e);\n"
                + "  }\n"
                + "})();\n";
    }
}
