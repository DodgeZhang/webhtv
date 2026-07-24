"""
WebHTV Playback Sync - TV 端请求诊断工具 (GUI 版本)
基于 tkinter 的可点击 GUI，模拟 TV 端发送 webhook 请求并打印详细日志

打包为 EXE:
    pip install pyinstaller
    pyinstaller --onefile --windowed test_webhook_gui.py
"""

import json
import os
import sys
import threading
import time
import uuid
import socket
import ssl
from datetime import datetime
from urllib.parse import urlparse

import tkinter as tk
from tkinter import ttk, messagebox, scrolledtext

try:
    import requests as req_lib
    HAS_REQUESTS = True
except ImportError:
    HAS_REQUESTS = False

DEFAULT_URL = "https://webhtvplaybacksync.dodge.ccwu.cc/api/playback/webhook"
TIMEOUT = 10

COLORS = {
    "INFO": "#5BA4CF",
    "OK": "#4EC9B0",
    "WARN": "#E5C07B",
    "ERR": "#F07070",
    "REQ": "#C678DD",
    "RESP": "#61AFEF",
    "HEADER": "#98C379",
}


def _percent_encode_header_value(value):
    import urllib.parse
    return urllib.parse.quote(value, safe="-_.~*'()")


def _is_ascii_header_value(value):
    for ch in value:
        if ord(ch) < 0x20 or ord(ch) > 0x7E:
            return False
    return True


def _prepare_headers(headers):
    result = {}
    encoding_headers = {}
    for k, v in headers.items():
        if v is None:
            continue
        if isinstance(v, str) and not _is_ascii_header_value(v):
            result[k] = _percent_encode_header_value(v)
            encoding_headers[k + "-Encoding"] = "percent-utf-8"
        else:
            result[k] = str(v)
    for k, v in encoding_headers.items():
        result[k] = v
    return result


def build_test_record():
    ts = int(time.time() * 1000)
    return {
        "schema": "webhtv.playback.v1",
        "event": "playback.progress",
        "eventId": str(uuid.uuid4()),
        "timestamp": ts,
        "sessionId": f"test-session-{int(time.time())}",
        "dedupeKey": f"test-dedupe-{int(time.time())}",
        "cid": 1,
        "configKey": "test_config",
        "configName": "Test Interface",
        "historyKey": f"test_site/movie_{int(time.time())}/1",
        "siteKey": "test_site",
        "siteName": "Test Site",
        "vodId": f"movie_{int(time.time())}",
        "vodName": "The Great Test Movie",
        "vodPic": "",
        "flag": "4K",
        "episodeName": "EP01",
        "episodeUrl": "https://example.com/play.mp4",
        "episodeIndex": 1,
        "state": "playing",
        "positionMs": 123456,
        "durationMs": 5400000,
        "progress": 0.0228,
        "speed": 1.0,
        "completed": False,
        "appVersion": "1.0.0-test",
        "client": "tv",
        "clientKey": f"test-client-{uuid.uuid4().hex[:8]}"
    }


def http_request(method, url, headers=None, body=None, timeout=TIMEOUT):
    raw_hdrs = headers or {}
    hdrs = _prepare_headers(raw_hdrs)
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        hdrs.setdefault("Content-Type", "application/json; charset=utf-8")

    start = time.time()

    if HAS_REQUESTS:
        try:
            resp = req_lib.request(method, url, headers=hdrs, data=data, timeout=timeout)
            elapsed = (time.time() - start) * 1000
            return {
                "status": resp.status_code,
                "reason": resp.reason,
                "body": resp.text,
                "headers": dict(resp.headers),
                "elapsed_ms": elapsed,
                "error": None,
            }
        except req_lib.exceptions.Timeout:
            elapsed = (time.time() - start) * 1000
            return {"status": None, "error": f"Timeout ({elapsed:.0f}ms)"}
        except req_lib.exceptions.ConnectionError as e:
            elapsed = (time.time() - start) * 1000
            return {"status": None, "error": f"ConnectionError: {e}"}
        except Exception as e:
            elapsed = (time.time() - start) * 1000
            return {"status": None, "error": f"{type(e).__name__}: {e}"}
    else:
        from urllib.request import Request, urlopen
        from urllib.error import URLError, HTTPError

        req = Request(url, data=data, headers=hdrs, method=method)
        try:
            resp = urlopen(req, timeout=timeout)
            elapsed = (time.time() - start) * 1000
            resp_body = resp.read().decode("utf-8")
            return {
                "status": resp.status,
                "reason": resp.reason,
                "body": resp_body,
                "headers": dict(resp.getheaders()),
                "elapsed_ms": elapsed,
                "error": None,
            }
        except HTTPError as e:
            elapsed = (time.time() - start) * 1000
            resp_body = e.read().decode("utf-8", errors="replace")
            return {"status": e.code, "reason": e.reason, "body": resp_body, "headers": {}, "elapsed_ms": elapsed, "error": None}
        except URLError as e:
            elapsed = (time.time() - start) * 1000
            return {"status": None, "error": f"URLError: {e.reason}"}
        except Exception as e:
            elapsed = (time.time() - start) * 1000
            return {"status": None, "error": f"{type(e).__name__}: {e}"}


class TestApp:
    def __init__(self, root):
        self.root = root
        self.root.title("WebHTV Playback Sync - TV 端请求诊断工具")
        self.root.geometry("1060x780")
        self.root.configure(bg="#1a1b26")

        self._build_ui()
        self._build_style()

        self.results = {}
        self.running = False

    def _build_style(self):
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("TFrame", background="#1a1b26")
        style.configure("TLabelframe", background="#1a1b26", foreground="#a9b1d6",
                         font=("Segoe UI", 9, "bold"))
        style.configure("TLabelframe.Label", background="#1a1b26", foreground="#a9b1d6")
        style.configure("TLabel", background="#1a1b26", foreground="#c0caf5",
                         font=("Segoe UI", 9))
        style.configure("Status.TLabel", background="#1a1b26", foreground="#565f89",
                         font=("Segoe UI", 8))
        style.configure("TButton", font=("Segoe UI", 9), padding=6)
        style.configure("Accent.TButton", font=("Segoe UI", 10, "bold"), padding=8)
        style.configure("TEntry", fieldbackground="#24283b", foreground="#c0caf5")
        style.configure("TCheckbutton", background="#1a1b26", foreground="#c0caf5",
                         font=("Segoe UI", 9))
        style.configure("TCombobox", fieldbackground="#24283b", foreground="#c0caf5",
                         background="#24283b")

        style.configure("Green.TLabel", foreground="#4EC9B0")
        style.configure("Red.TLabel", foreground="#F07070")
        style.configure("Yellow.TLabel", foreground="#E5C07B")
        style.configure("Blue.TLabel", foreground="#5BA4CF")

        self.root.option_add("*TCombobox*Listbox.background", "#24283b")
        self.root.option_add("*TCombobox*Listbox.foreground", "#c0caf5")
        self.root.option_add("*TCombobox*Listbox.selectBackground", "#3b4261")
        self.root.option_add("*TCombobox*Listbox.selectForeground", "#c0caf5")

    def _build_ui(self):
        pad = {"padx": 10, "pady": 6}

        main_frame = ttk.Frame(self.root)
        main_frame.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)

        top_frame = ttk.Frame(main_frame)
        top_frame.pack(fill=tk.X, pady=(0, 8))

        url_label = ttk.Label(top_frame, text="Webhook URL:", width=12)
        url_label.pack(side=tk.LEFT, padx=(0, 4))
        self.url_var = tk.StringVar(value=DEFAULT_URL)
        url_entry = ttk.Entry(top_frame, textvariable=self.url_var, font=("Consolas", 10))
        url_entry.pack(side=tk.LEFT, fill=tk.X, expand=True)

        token_label = ttk.Label(top_frame, text="Token:", width=8)
        token_label.pack(side=tk.LEFT, padx=(10, 4))
        self.token_var = tk.StringVar(value=os.environ.get("WEBHTV_TOKEN", ""))
        token_entry = ttk.Entry(top_frame, textvariable=self.token_var, show="●", width=30)
        token_entry.pack(side=tk.LEFT)

        self.show_token_var = tk.BooleanVar(value=False)
        show_btn = ttk.Checkbutton(top_frame, text="显示", variable=self.show_token_var,
                                    command=self._toggle_token)
        show_btn.pack(side=tk.LEFT, padx=(4, 0))

        config_frame = ttk.LabelFrame(main_frame, text="测试配置")
        config_frame.pack(fill=tk.X, pady=(0, 8))

        tests_frame = ttk.Frame(config_frame)
        tests_frame.pack(fill=tk.X, padx=8, pady=6)

        self.chk_network = tk.BooleanVar(value=True)
        self.chk_health = tk.BooleanVar(value=True)
        self.chk_capabilities = tk.BooleanVar(value=True)
        self.chk_cors = tk.BooleanVar(value=True)
        self.chk_no_token = tk.BooleanVar(value=True)
        self.chk_webhook = tk.BooleanVar(value=True)
        self.chk_records = tk.BooleanVar(value=True)
        self.chk_batch = tk.BooleanVar(value=False)

        chks = [
            ("网络层诊断 (DNS/TCP/SSL)", self.chk_network),
            ("健康检查", self.chk_health),
            ("能力查询", self.chk_capabilities),
            ("CORS 预检", self.chk_cors),
            ("无 Token 访问", self.chk_no_token),
            ("Webhook 推送", self.chk_webhook),
            ("记录查询", self.chk_records),
            ("批量推送", self.chk_batch),
        ]

        for i, (text, var) in enumerate(chks):
            w, r = divmod(i, 4)
            chk = ttk.Checkbutton(tests_frame, text=text, variable=var)
            chk.grid(row=r, column=w, sticky=tk.W, padx=8, pady=2)

        action_frame = ttk.Frame(config_frame)
        action_frame.pack(fill=tk.X, padx=8, pady=(0, 6))

        self.run_btn = ttk.Button(action_frame, text="▶ 开始诊断", style="Accent.TButton",
                                   command=self._on_run)
        self.run_btn.pack(side=tk.LEFT)

        self.stop_btn = ttk.Button(action_frame, text="■ 停止", command=self._on_stop,
                                    state=tk.DISABLED)
        self.stop_btn.pack(side=tk.LEFT, padx=(6, 0))

        ttk.Button(action_frame, text="清空日志", command=self._on_clear).pack(side=tk.RIGHT)
        ttk.Button(action_frame, text="导出日志", command=self._on_export).pack(side=tk.RIGHT, padx=(0, 6))
        ttk.Button(action_frame, text="保存配置", command=self._on_save_config).pack(side=tk.RIGHT, padx=(0, 6))

        result_frame = ttk.LabelFrame(main_frame, text="测试结果")
        result_frame.pack(fill=tk.X, pady=(0, 8))

        self.result_labels = {}
        result_items = [
            ("network", "🌐 网络层"),
            ("health", "💓 健康检查"),
            ("capabilities", "⚙️ 能力查询"),
            ("cors", "🔒 CORS 预检"),
            ("no_token", "🚫 无 Token"),
            ("webhook", "📮 Webhook"),
            ("records", "📋 记录查询"),
            ("batch", "📦 批量推送"),
        ]
        for i, (key, text) in enumerate(result_items):
            lbl = ttk.Label(result_frame, text=f"{text}: 待测", width=22)
            lbl.grid(row=0, column=i, padx=4, pady=4, sticky=tk.W)
            self.result_labels[key] = lbl

        for i in range(4):
            result_frame.columnconfigure(i, weight=1)

        log_frame = ttk.LabelFrame(main_frame, text="详细日志")
        log_frame.pack(fill=tk.BOTH, expand=True, pady=(0, 8))

        self.log_text = scrolledtext.ScrolledText(
            log_frame, bg="#181825", fg="#a9b1d6",
            font=("Consolas", 9), insertbackground="#a9b1d6",
            selectbackground="#3b4261", borderwidth=0,
            highlightthickness=0, padx=8, pady=8
        )
        self.log_text.pack(fill=tk.BOTH, expand=True, padx=4, pady=4)

        self.log_text.tag_configure("INFO", foreground=COLORS["INFO"])
        self.log_text.tag_configure("OK", foreground=COLORS["OK"])
        self.log_text.tag_configure("WARN", foreground=COLORS["WARN"])
        self.log_text.tag_configure("ERR", foreground=COLORS["ERR"])
        self.log_text.tag_configure("REQ", foreground=COLORS["REQ"])
        self.log_text.tag_configure("RESP", foreground=COLORS["RESP"])
        self.log_text.tag_configure("HEADER", foreground=COLORS["HEADER"])
        self.log_text.tag_configure("TIME", foreground="#565f89")
        self.log_text.tag_configure("BOLD", foreground="#c0caf5", font=("Consolas", 9, "bold"))

        bottom_frame = ttk.Frame(main_frame)
        bottom_frame.pack(fill=tk.X)

        self.status_var = tk.StringVar(value="就绪")
        status_lbl = ttk.Label(bottom_frame, textvariable=self.status_var, style="Status.TLabel")
        status_lbl.pack(side=tk.LEFT)

        self.progress = ttk.Progressbar(bottom_frame, mode="determinate", length=200)
        self.progress.pack(side=tk.RIGHT)

        self._log("INFO", f"已加载: requests={HAS_REQUESTS}")
        self._log("INFO", f"默认 URL: {DEFAULT_URL}")
        self._log("INFO", "就绪 — 点击 '开始诊断' 开始测试")

    def _toggle_token(self):
        if self.show_token_var.get():
            self.token_var.set(self.token_var.get())
            for widget in self.root.winfo_children():
                self._set_token_show(widget, "")
        else:
            for widget in self.root.winfo_children():
                self._set_token_show(widget, "●")

    def _set_token_show(self, widget, show):
        if isinstance(widget, ttk.Entry) and str(widget.cget("textvariable")) == str(self.token_var):
            widget.configure(show=show)
        for child in widget.winfo_children():
            self._set_token_show(child, show)

    def _log(self, level, msg):
        ts = datetime.now().strftime("%H:%M:%S.") + f"{datetime.now().microsecond // 1000:03d}"
        prefix = {"INFO": "INFO", "OK": "  OK", "WARN": "WARN", "ERR": " ERR",
                   "REQ": "  →  ", "RESP": "  ←  ", "HEADER": "HEAD"}.get(level, level)
        line = f"[{ts}] [{prefix}] {msg}\n"

        self.log_text.insert(tk.END, f"[{ts}] ", "TIME")
        self.log_text.insert(tk.END, f"[{prefix}] ", level)
        self.log_text.insert(tk.END, msg + "\n")
        self.log_text.see(tk.END)

        self.root.update_idletasks()

    def _log_req(self, method, url, headers, body):
        self._log("REQ", f"{method} {url}")
        for k, v in headers.items():
            self._log("HEADER", f"  {k}: {v}")
        if body:
            body_str = json.dumps(body, ensure_ascii=False)
            self._log("REQ", f"  Body ({len(body_str)} chars): {body_str[:500]}")

    def _log_resp(self, resp):
        status = resp.get("status")
        elapsed = resp.get("elapsed_ms", 0)
        body = resp.get("body", "")
        headers = resp.get("headers", {})
        error = resp.get("error")

        if error:
            self._log("ERR", f"→ Error ({elapsed:.0f}ms): {error}")
        else:
            self._log("RESP", f"→ {status} ({elapsed:.0f}ms)")
        for k, v in list(headers.items())[:8]:
            self._log("HEADER", f"  {k}: {v}")
        if body:
            self._log("RESP", f"  Body: {body[:500]}")
            try:
                json.loads(body)
                self._log("OK", f"  JSON 解析成功")
            except Exception:
                pass

    def _set_result(self, key, text, status):
        lbl = self.result_labels.get(key)
        if not lbl:
            return
        style_map = {
            "ok": "Green.TLabel",
            "fail": "Red.TLabel",
            "warn": "Yellow.TLabel",
            "pending": "Blue.TLabel",
        }
        lbl.configure(text=text, style=style_map.get(status, "TLabel"))

    def _set_progress(self, value, maximum=None):
        if maximum is not None:
            self.progress.configure(maximum=maximum)
        self.progress["value"] = value

    def _set_status(self, text):
        self.status_var.set(text)

    def _on_run(self):
        if self.running:
            return
        self.running = True
        self.run_btn.configure(state=tk.DISABLED)
        self.stop_btn.configure(state=tk.NORMAL)
        self._on_clear()

        for key in self.result_labels:
            self._set_result(key, f"{self.result_labels[key].cget('text').split(':')[0]}: 运行中...", "pending")

        thread = threading.Thread(target=self._run_tests, daemon=True)
        thread.start()

    def _on_stop(self):
        self.running = False
        self._log("WARN", "用户请求停止...")

    def _on_clear(self):
        self.log_text.delete("1.0", tk.END)
        for key, lbl in self.result_labels.items():
            orig_text = lbl.cget("text").split(":")[0]
            self._set_result(key, f"{orig_text}: 待测", "pending")

    def _on_export(self):
        from tkinter import filedialog
        filepath = filedialog.asksaveasfilename(
            defaultextension=".log",
            filetypes=[("Log files", "*.log"), ("Text files", "*.txt"), ("All files", "*.*")],
            initialfile=f"webhtv-test-{datetime.now().strftime('%Y%m%d-%H%M%S')}.log"
        )
        if filepath:
            content = self.log_text.get("1.0", tk.END)
            with open(filepath, "w", encoding="utf-8") as f:
                f.write(content)
            self._log("OK", f"日志已导出: {filepath}")

    def _on_save_config(self):
        try:
            config = {
                "url": self.url_var.get(),
                "token": self.token_var.get(),
            }
            config_dir = os.path.join(os.path.expanduser("~"), ".webhtv-test")
            os.makedirs(config_dir, exist_ok=True)
            config_path = os.path.join(config_dir, "config.json")
            with open(config_path, "w", encoding="utf-8") as f:
                json.dump(config, f, ensure_ascii=False, indent=2)
            self._log("OK", f"配置已保存: {config_path}")
        except Exception as e:
            self._log("ERR", f"保存配置失败: {e}")

    def _run_tests(self):
        base_url = self.url_var.get().strip()
        token = self.token_var.get().strip()
        has_token = bool(token)

        self._log("INFO", "=" * 50)
        self._log("INFO", "WebHTV Playback Sync - TV 端请求诊断")
        self._log("INFO", f"目标 URL: {base_url}")
        self._log("INFO", f"Token: {'已配置' if has_token else '未配置'}")
        self._log("INFO", f"HTTP 客户端: {'requests' if HAS_REQUESTS else 'urllib'}")
        self._log("INFO", "=" * 50)

        tests = []
        if self.chk_network.get():
            tests.append(("network", self._test_network))
        if self.chk_health.get():
            tests.append(("health", self._test_health))
        if self.chk_capabilities.get():
            tests.append(("capabilities", self._test_capabilities))
        if self.chk_cors.get():
            tests.append(("cors", self._test_cors))
        if self.chk_no_token.get():
            tests.append(("no_token", self._test_no_token))
        if self.chk_webhook.get():
            tests.append(("webhook", self._test_webhook))
        if self.chk_records.get():
            tests.append(("records", self._test_records))
        if self.chk_batch.get():
            tests.append(("batch", self._test_batch))

        self._set_progress(0, len(tests))
        self._set_status(f"开始诊断，共 {len(tests)} 项测试...")

        passed = 0
        failed = 0
        blocked_by_waf = False

        for i, (key, test_fn) in enumerate(tests):
            if not self.running:
                self._log("WARN", "测试已被用户中断")
                break

            self._set_progress(i + 0.3, len(tests))
            self._set_status(f"正在运行: {key} ({i + 1}/{len(tests)})")

            try:
                result = test_fn(base_url, token)
                if result == "waf_blocked":
                    blocked_by_waf = True
                    failed += 1
                elif result:
                    passed += 1
                else:
                    failed += 1
            except Exception as e:
                self._log("ERR", f"测试 '{key}' 异常: {e}")
                failed += 1

            self._set_progress(i + 1, len(tests))

        self._set_progress(len(tests), len(tests))

        self._log("INFO", "=" * 50)
        self._log("INFO", f"诊断完成: 通过 {passed}, 失败 {failed}, 共 {len(tests)}")
        self._log("INFO", "=" * 50)

        if blocked_by_waf:
            self._show_waf_diagnosis()
        elif failed == 0:
            self._log("OK", "🎉 所有测试通过！")
        else:
            self._log("WARN", "部分测试失败，请检查上方日志")

        self._set_status(f"诊断完成: 通过 {passed}, 失败 {failed}")
        self.running = False
        self.run_btn.configure(state=tk.NORMAL)
        self.stop_btn.configure(state=tk.DISABLED)

    def _check_waf(self, status, body):
        if status == 403 and body and "error code: 1010" in body.lower():
            self._log("ERR", "⚠️  检测到 Cloudflare WAF 拦截 (Error 1010)")
            return True
        return False

    def _show_waf_diagnosis(self):
        self._log("ERR", "=" * 50)
        self._log("ERR", "  Cloudflare WAF 拦截了请求 (Error 1010)")
        self._log("ERR", "  Worker 本身正常，但 WAF 在 Worker 之前拦截")
        self._log("ERR", "=" * 50)
        self._log("WARN", "修复方案:")
        self._log("WARN", "  1. Dashboard → Security → WAF → 关闭 Bot Fight Mode")
        self._log("WARN", "  2. WAF → Firewall Rules → 添加 Allow 规则:")
        self._log("WARN", "     Expression: http.request.uri.path contains \"/api/\"")
        self._log("WARN", "  3. 检查 'I'm Under Attack' 模式是否开启")
        self._log("WARN", "  4. 检查 Country/IP Block 是否封锁了 TV 出口")
        self._log("WARN", "  Dashboard: https://dash.cloudflare.com/?to=/:account/waf")

    def _test_network(self, base_url, token):
        self._log("INFO", "--- 网络层诊断 ---")
        self._set_result("network", "🌐 网络层: 运行中...", "pending")

        parsed = urlparse(base_url)
        host = parsed.hostname
        all_ok = True

        try:
            self._log("INFO", f"DNS 解析: {host}")
            addrs = socket.getaddrinfo(host, 443, socket.AF_UNSPEC, socket.SOCK_STREAM)
            unique_ips = sorted(set(a[4][0] for a in addrs))
            for ip in unique_ips:
                self._log("OK", f"  {host} → {ip}")
        except Exception as e:
            self._log("ERR", f"  DNS 解析失败: {e}")
            all_ok = False

        try:
            self._log("INFO", f"TCP 连接: {host}:443")
            sock = socket.create_connection((host, 443), timeout=5)
            self._log("OK", "  TCP 连接成功")
            sock.close()
        except Exception as e:
            self._log("ERR", f"  TCP 连接失败: {e}")
            all_ok = False

        try:
            self._log("INFO", "SSL 握手测试")
            ctx = ssl.create_default_context()
            with socket.create_connection((host, 443), timeout=5) as sock:
                with ctx.wrap_socket(sock, server_hostname=host) as ssock:
                    cert = ssock.getpeercert()
                    subject = dict(x[0] for x in cert.get("subject", []))
                    self._log("OK", f"  SSL 证书: {subject.get('commonName', 'N/A')}")
                    self._log("OK", f"  过期时间: {cert.get('notAfter', 'N/A')}")
        except Exception as e:
            self._log("ERR", f"  SSL 握手失败: {e}")
            all_ok = False

        self._set_result("network", f"🌐 网络层: {'通过' if all_ok else '失败'}",
                          "ok" if all_ok else "fail")
        return all_ok

    def _test_health(self, base_url, token):
        self._log("INFO", "--- 健康检查 ---")
        self._set_result("health", "💓 健康检查: 运行中...", "pending")
        url = base_url.replace("/api/playback/webhook", "/api/health")
        self._log_req("GET", url, {}, None)
        resp = http_request("GET", url)
        self._log_resp(resp)

        if self._check_waf(resp.get("status"), resp.get("body", "")):
            self._set_result("health", "💓 健康检查: WAF 拦截", "fail")
            return "waf_blocked"

        ok = resp.get("status") == 200
        self._set_result("health", f"💓 健康检查: {'通过' if ok else '失败 (HTTP ' + str(resp.get('status')) + ')'}",
                          "ok" if ok else "fail")
        return ok

    def _test_capabilities(self, base_url, token):
        self._log("INFO", "--- 能力查询 ---")
        self._set_result("capabilities", "⚙️ 能力查询: 运行中...", "pending")
        url = base_url.replace("/api/playback/webhook", "/api/server/capabilities")
        self._log_req("GET", url, {}, None)
        resp = http_request("GET", url)
        self._log_resp(resp)

        if self._check_waf(resp.get("status"), resp.get("body", "")):
            self._set_result("capabilities", "⚙️ 能力查询: WAF 拦截", "fail")
            return "waf_blocked"

        ok = resp.get("status") == 200
        mode = "?"
        kv = "?"
        if ok:
            try:
                data = json.loads(resp["body"])
                mode = data.get("serverMode", "?")
                kv = data.get("kvBound", "?")
            except Exception:
                pass
        self._set_result("capabilities",
                          f"⚙️ 能力查询: {'通过' if ok else '失败'} (mode={mode}, kv={kv})",
                          "ok" if ok else "fail")
        return ok

    def _test_cors(self, base_url, token):
        self._log("INFO", "--- CORS 预检 ---")
        self._set_result("cors", "🔒 CORS 预检: 运行中...", "pending")
        self._log_req("OPTIONS", base_url, {}, None)
        resp = http_request("OPTIONS", base_url)
        self._log_resp(resp)

        if self._check_waf(resp.get("status"), resp.get("body", "")):
            self._set_result("cors", "🔒 CORS 预检: WAF 拦截", "fail")
            return "waf_blocked"

        ok = resp.get("status") == 204
        headers = resp.get("headers", {})
        acao = headers.get("Access-Control-Allow-Origin", "")
        acam = headers.get("Access-Control-Allow-Methods", "")
        self._log("INFO", f"  ACAO: {acao or 'N/A'}, ACAM: {acam or 'N/A'}")
        self._set_result("cors", f"🔒 CORS 预检: {'通过' if ok else '失败'}", "ok" if ok else "fail")
        return ok

    def _test_no_token(self, base_url, token):
        self._log("INFO", "--- 无 Token 访问 ---")
        self._set_result("no_token", "🚫 无 Token: 运行中...", "pending")
        url = base_url.replace("/api/playback/webhook", "/api/playback/records")
        self._log_req("GET", url, {}, None)
        resp = http_request("GET", url)
        self._log_resp(resp)

        if self._check_waf(resp.get("status"), resp.get("body", "")):
            self._set_result("no_token", "🚫 无 Token: WAF 拦截", "fail")
            return "waf_blocked"

        status = resp.get("status")
        if status == 401:
            self._log("OK", "  鉴权已启用: 无 token 被正确拒绝 (401)")
            self._set_result("no_token", "🚫 无 Token: 鉴权正常 (401)", "ok")
            return True
        elif status == 200:
            self._log("WARN", "  鉴权未启用! 生产环境建议配置 ACCESS_TOKEN")
            self._set_result("no_token", "🚫 无 Token: 鉴权未启用 (200)", "warn")
            return True
        else:
            self._set_result("no_token", f"🚫 无 Token: 异常 (HTTP {status})", "fail")
            return False

    def _test_webhook(self, base_url, token):
        self._log("INFO", "--- Webhook 推送 ---")
        self._set_result("webhook", "📮 Webhook: 运行中...", "pending")

        record = build_test_record()
        ts = int(time.time())

        headers = {
            "X-WebHTV-Timestamp": str(ts),
            "X-WebHTV-Webhook-Id": record["eventId"],
            "Idempotency-Key": record["eventId"],
            "X-WebHTV-Dedupe-Key": record["dedupeKey"],
            "X-WebHTV-Config-Key": record["configKey"],
            "X-WebHTV-Config-Name": record["configName"],
            "User-Agent": "WebHTV-Test-Script/1.0",
        }
        if token:
            headers["X-WebHTV-Token"] = token

        self._log_req("POST", base_url, headers, record)
        resp = http_request("POST", base_url, headers=headers, body=record)
        self._log_resp(resp)

        if self._check_waf(resp.get("status"), resp.get("body", "")):
            self._set_result("webhook", "📮 Webhook: WAF 拦截", "fail")
            return "waf_blocked"

        status = resp.get("status")
        body = resp.get("body", "")

        if status == 200:
            self._log("OK", "  Webhook 推送成功!")
            self._set_result("webhook", "📮 Webhook: 推送成功 (200)", "ok")
            return True
        elif status == 401:
            self._log("ERR", "  缺少 token (401) — 请在 Dashboard 检查 ACCESS_TOKEN")
            self._set_result("webhook", "📮 Webhook: 缺少 Token (401)", "fail")
            return False
        elif status == 403:
            try:
                data = json.loads(body)
                if "Invalid token" in data.get("error", ""):
                    self._log("ERR", "  Token 无效 (403) — App 端 Token 与 Dashboard 不匹配")
                else:
                    self._log("ERR", f"  403 Forbidden: {data.get('error', '')}")
            except Exception:
                self._log("ERR", "  403 Forbidden")
            self._set_result("webhook", "📮 Webhook: Token 无效 (403)", "fail")
            return False
        elif status == 400:
            try:
                data = json.loads(body)
                self._log("ERR", f"  请求格式错误: {data.get('error', '')}")
            except Exception:
                pass
            self._set_result("webhook", "📮 Webhook: 格式错误 (400)", "fail")
            return False
        elif status == 501:
            self._log("ERR", "  KV 存储未配置 (501) — 检查 KV namespace ID")
            self._set_result("webhook", "📮 Webhook: KV 未配置 (501)", "fail")
            return False
        else:
            self._log("ERR", f"  未知错误: HTTP {status}")
            self._set_result("webhook", f"📮 Webhook: HTTP {status}", "fail")
            return False

    def _test_records(self, base_url, token):
        self._log("INFO", "--- 记录查询 ---")
        self._set_result("records", "📋 记录查询: 运行中...", "pending")
        url = base_url.replace("/api/playback/webhook", "/api/playback/records")

        headers = {}
        if token:
            headers["X-WebHTV-Token"] = token

        self._log_req("GET", url, headers, None)
        resp = http_request("GET", url, headers=headers)
        self._log_resp(resp)

        if self._check_waf(resp.get("status"), resp.get("body", "")):
            self._set_result("records", "📋 记录查询: WAF 拦截", "fail")
            return "waf_blocked"

        status = resp.get("status")
        if status == 200:
            try:
                data = json.loads(resp["body"])
                total = data.get("total", 0)
                self._log("OK", f"  查询成功: 共 {total} 条记录")
                self._set_result("records", f"📋 记录查询: {total} 条", "ok")
            except Exception:
                self._set_result("records", "📋 记录查询: 成功", "ok")
            return True
        elif status == 401:
            self._set_result("records", "📋 记录查询: 缺少 Token (401)", "fail")
            return False
        else:
            self._set_result("records", f"📋 记录查询: HTTP {status}", "fail")
            return False

    def _test_batch(self, base_url, token):
        self._log("INFO", "--- 批量推送 ---")
        self._set_result("batch", "📦 批量推送: 运行中...", "pending")

        url = base_url.replace("/api/playback/webhook", "/api/playback/progress/batch")
        ts = int(time.time())
        record1 = build_test_record()
        record2 = build_test_record()

        headers = {
            "X-WebHTV-Timestamp": str(ts),
            "User-Agent": "WebHTV-Test-Script/1.0",
        }
        if token:
            headers["X-WebHTV-Token"] = token

        body = {"items": [record1, record2]}
        self._log_req("POST", url, headers, body)
        resp = http_request("POST", url, headers=headers, body=body)
        self._log_resp(resp)

        if self._check_waf(resp.get("status"), resp.get("body", "")):
            self._set_result("batch", "📦 批量推送: WAF 拦截", "fail")
            return "waf_blocked"

        status = resp.get("status")
        if status == 200:
            try:
                data = json.loads(resp["body"])
                total = data.get("total", 0)
                applied = data.get("applied", 0)
                skipped = data.get("skipped", 0)
                failed = data.get("failed", 0)
                self._log("OK", f"  批量推送: 总计={total}, 应用={applied}, 跳过={skipped}, 失败={failed}")
                self._set_result("batch", f"📦 批量推送: 成功 (应用 {applied})", "ok")
            except Exception:
                self._set_result("batch", "📦 批量推送: 成功", "ok")
            return True
        elif status == 401:
            self._set_result("batch", "📦 批量推送: 缺少 Token (401)", "fail")
            return False
        else:
            self._set_result("batch", f"📦 批量推送: HTTP {status}", "fail")
            return False


def main():
    root = tk.Tk()

    try:
        root.iconbitmap(default="")
    except Exception:
        pass

    app = TestApp(root)

    try:
        config_dir = os.path.join(os.path.expanduser("~"), ".webhtv-test")
        config_path = os.path.join(config_dir, "config.json")
        if os.path.exists(config_path):
            with open(config_path, "r", encoding="utf-8") as f:
                config = json.load(f)
            if config.get("url"):
                app.url_var.set(config["url"])
            if config.get("token"):
                app.token_var.set(config["token"])
                app._log("INFO", f"已加载保存的配置")
    except Exception:
        pass

    root.mainloop()


if __name__ == "__main__":
    main()