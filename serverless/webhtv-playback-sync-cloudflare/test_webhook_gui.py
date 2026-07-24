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
        self.test_results = {}
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
        self.test_results = {}

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

        # 综合日志分析与诊断报告
        self._analyze_results(token=token, total=len(tests), passed=passed, failed=failed,
                              blocked_by_waf=blocked_by_waf, interrupted=not self.running)

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

    # ===================== 日志分析与诊断报告 =====================

    def _analyze_results(self, token, total, passed, failed, blocked_by_waf, interrupted):
        """基于各测试收集的结构化结果，输出综合诊断报告与处理建议"""
        self._log("INFO", "")
        self._log("HEADER", "╔══════════════════════════════════════════════════════════╗")
        self._log("HEADER", "║           诊断报告与处理建议                              ║")
        self._log("HEADER", "╚══════════════════════════════════════════════════════════╝")
        self._log("INFO", "")

        # 1. 结果汇总表
        self._log("INFO", "【1】测试结果汇总")
        self._log("INFO", f"    总计: {total}  |  通过: {passed}  |  失败: {failed}  |  Token: {'已配置' if token else '未配置'}")
        if interrupted:
            self._log("WARN", "    ⚠ 测试被用户中断，结果可能不完整")
        self._log("INFO", "")

        # 2. 各项明细
        self._log("INFO", "【2】各项明细")
        detail_rows = self._build_detail_rows()
        for row in detail_rows:
            self._log("INFO", f"    {row}")
        self._log("INFO", "")

        # 3. 异常诊断与处理建议
        self._log("INFO", "【3】异常诊断与处理建议")
        suggestions = self._build_suggestions(token=token, blocked_by_waf=blocked_by_waf, interrupted=interrupted)
        if not suggestions:
            self._log("OK", "    🎉 未发现异常，所有测试均通过")
        else:
            for i, s in enumerate(suggestions, 1):
                self._log(s.get("level", "WARN"), f"    [{i}] {s['title']}")
                for tip in s.get("tips", []):
                    self._log("INFO", f"        - {tip}")
                self._log("INFO", "")
        self._log("INFO", "=" * 60)

    def _build_detail_rows(self):
        rows = []
        name_map = {
            "network": "网络层", "health": "健康检查", "capabilities": "能力查询",
            "cors": "CORS 预检", "no_token": "无Token访问", "webhook": "Webhook推送",
            "records": "记录查询", "batch": "批量推送",
        }
        for key in ["network", "health", "capabilities", "cors", "no_token", "webhook", "records", "batch"]:
            r = self.test_results.get(key)
            name = name_map.get(key, key)
            if r is None:
                rows.append(f"• {name:<10}: 跳过")
                continue
            status = r.get("status", "?")
            code = r.get("http_code")
            code_str = f"HTTP {code}" if code is not None else "—"
            mark = {"ok": "✅", "warn": "⚠️ ", "fail": "❌", "waf": "🛡️ "}.get(status, "?")
            extra = self._detail_extra(key, r)
            rows.append(f"• {name:<10}: {mark} {status.upper():<5} {code_str:<10} {extra}")
        return rows

    def _detail_extra(self, key, r):
        if key == "network":
            flags = []
            if r.get("dns_ok"): flags.append("DNS✓")
            else: flags.append("DNS✗")
            if r.get("tcp_ok"): flags.append("TCP✓")
            else: flags.append("TCP✗")
            if r.get("ssl_ok"): flags.append("SSL✓")
            else: flags.append("SSL✗")
            return " ".join(flags) + (f"  ({r.get('cert_info')})" if r.get("cert_info") else "")
        if key == "capabilities":
            return f"mode={r.get('server_mode','?')}, kv={r.get('kv_bound','?')}"
        if key == "cors":
            return f"ACAO={r.get('acao') or 'N/A'}"
        if key == "no_token":
            return "鉴权已启用" if r.get("auth_enabled") else ("鉴权未启用!" if r.get("auth_enabled") is False else "未知")
        if key == "webhook":
            et = r.get("error_type")
            return f"error={et}" if et and et != "ok" else "推送成功"
        if key == "records":
            total = r.get("total")
            if total is not None:
                return f"records={total}"
            et = r.get("error_type")
            return f"error={et}" if et else ""
        if key == "batch":
            applied = r.get("applied")
            if applied is not None:
                return f"applied={applied}"
            et = r.get("error_type")
            return f"error={et}" if et else ""
        return ""

    def _build_suggestions(self, token, blocked_by_waf, interrupted):
        suggestions = []
        r = self.test_results

        # 0. 用户中断
        if interrupted:
            suggestions.append({
                "level": "WARN",
                "title": "测试被用户中断",
                "tips": ["部分测试未执行，建议重新点击「开始诊断」完整运行一次以获得准确结论"],
            })

        # 1. WAF 拦截（最高优先级，根本原因）
        waf_keys = [k for k, v in r.items() if isinstance(v, dict) and v.get("status") == "waf"]
        if waf_keys:
            suggestions.append({
                "level": "ERR",
                "title": f"Cloudflare WAF 拦截了 {len(waf_keys)} 项请求 (Error 1010)",
                "tips": [
                    "根本原因: Cloudflare 安全策略在 Worker 之前拦截了请求，Worker 本身正常",
                    "1. Dashboard → Security → WAF → 关闭 Bot Fight Mode",
                    "2. WAF → Firewall Rules → 新建规则: URI Path contains \"/api/\" → Action: Allow",
                    "3. 检查 Security → 是否开启 'I'm Under Attack Mode' (应关闭)",
                    "4. WAF → Tools → 检查 User-Agent Block / IP Block / Country Block",
                    "Dashboard: https://dash.cloudflare.com/?to=/:account/waf",
                    "修复后重新运行本工具验证",
                ],
            })
            # WAF 是根因，其他失败都是连锁反应，提前返回
            return suggestions

        # 2. 网络层问题
        net = r.get("network")
        if net and net.get("status") == "fail":
            tips = []
            if not net.get("dns_ok"):
                tips.append("DNS 解析失败 → 检查本机 DNS / hosts / 域名是否正确，尝试更换 DNS (如 1.1.1.1 / 8.8.8.8)")
            if net.get("dns_ok") and not net.get("tcp_ok"):
                tips.append(f"TCP 连接失败 → 主机 {net.get('host')} :443 不通，检查本地防火墙/代理/出口策略")
            if net.get("tcp_ok") and not net.get("ssl_ok"):
                tips.append("SSL 握手失败 → 检查本机系统时间是否准确、根证书是否缺失、是否有中间人代理")
            suggestions.append({"level": "ERR", "title": "网络层诊断失败", "tips": tips})

        # 3. 健康检查失败
        health = r.get("health")
        if health and health.get("status") == "fail" and net and net.get("status") == "ok":
            code = health.get("http_code")
            suggestions.append({
                "level": "ERR",
                "title": f"健康检查失败 (HTTP {code})",
                "tips": [
                    "网络层正常但 /api/health 不可达 → Worker 部署/路由异常",
                    "1. Dashboard → Workers → 确认 Worker 已部署且路由 (Routes) 配置正确",
                    "2. 检查自定义域名是否已绑定到 Worker",
                    "3. 查看 Workers → Logs 实时日志是否有报错",
                ],
            })

        # 4. 能力查询：KV 未绑定
        cap = r.get("capabilities")
        if cap and cap.get("status") == "ok" and cap.get("kv_bound") is False:
            suggestions.append({
                "level": "ERR",
                "title": "KV 存储未绑定 (kvBound=false)",
                "tips": [
                    "Worker 缺少 KV 命名空间绑定，写入操作将返回 501",
                    "1. Dashboard → Workers → 选中 Worker → Settings → KV Namespace Bindings",
                    "2. 添加绑定: 变量名 PLAYBACK_KV → 选择已创建的 KV 命名空间",
                    "3. 或修改 wrangler.toml 中 kv_namespaces 配置后重新 wrangler deploy",
                ],
            })

        # 5. 鉴权相关
        no_token = r.get("no_token")
        if no_token and no_token.get("status") == "warn":
            suggestions.append({
                "level": "WARN",
                "title": "鉴权未启用: 无 Token 也能访问 /api/playback/records",
                "tips": [
                    "生产环境存在数据泄露风险，任何人都能读取/写入观影记录",
                    "1. Cloudflare Dashboard → Workers → Settings → Variables",
                    "2. 添加环境变量 ACCESS_TOKEN (强随机字符串)",
                    "3. App 端同步配置相同的 Token",
                    "4. 重新部署 Worker 使变量生效",
                ],
            })

        # 6. Webhook 推送失败细分
        wh = r.get("webhook")
        if wh and wh.get("status") == "fail":
            et = wh.get("error_type")
            if et == "missing_token":
                suggestions.append({
                    "level": "ERR",
                    "title": "Webhook 推送 401: 缺少 Token",
                    "tips": [
                        "服务端要求鉴权但本工具未提供 Token",
                        "1. 在本工具顶部 Token 输入框填入 ACCESS_TOKEN",
                        "2. 或通过环境变量 WEBHTV_TOKEN 设置",
                        "3. 确认 Token 与 Dashboard → Workers → Variables 中 ACCESS_TOKEN 一致",
                    ],
                })
            elif et == "invalid_token":
                suggestions.append({
                    "level": "ERR",
                    "title": "Webhook 推送 403: Token 无效",
                    "tips": [
                        "本工具/App 端配置的 Token 与服务端 ACCESS_TOKEN 不匹配",
                        "1. 复核 Dashboard → Workers → Variables 中 ACCESS_TOKEN 的精确值 (注意首尾空格)",
                        "2. App 端「播放同步配置」中的 Token 必须与之完全一致",
                        "3. 修改后需重新部署 Worker (环境变量变更不会自动生效)",
                    ],
                })
            elif et == "bad_request":
                suggestions.append({
                    "level": "ERR",
                    "title": f"Webhook 推送 400: 请求格式错误 ({wh.get('error_detail','')})",
                    "tips": [
                        "请求体不符合 schema 要求",
                        "1. 检查 build_test_record() 生成的字段是否完整",
                        "2. 对照 PlaybackRecord.java 的 schema: webhtv.playback.v1",
                        "3. 必填字段: positionMs/durationMs (毫秒)、siteKey、vodId、episodeName 等",
                    ],
                })
            elif et == "kv_not_configured":
                suggestions.append({
                    "level": "ERR",
                    "title": "Webhook 推送 501: KV 存储未配置",
                    "tips": [
                        "Worker 已部署但未绑定 KV 命名空间",
                        "1. Dashboard → Workers → Settings → KV Namespace Bindings",
                        "2. 绑定变量 PLAYBACK_KV 到已创建的 KV 命名空间",
                        "3. 或在 wrangler.toml 中配置 kv_namespaces 后重新 wrangler deploy",
                    ],
                })
            elif et in (
                "kv_put_limit_exceeded", "kv_get_limit_exceeded", "kv_list_limit_exceeded",
                "kv_delete_limit_exceeded", "kv_value_too_large", "kv_key_too_long",
                "kv_limit_exceeded", "worker_cpu_exceeded", "worker_request_limit_exceeded",
                "worker_subrequest_exceeded",
            ):
                sug = self._cf_quota_suggestion(et, wh.get("error_detail", ""), source="Webhook 推送")
                if sug:
                    suggestions.append(sug)
            elif et == "server_error":
                suggestions.append({
                    "level": "ERR",
                    "title": f"Webhook 推送 500: 服务端内部错误",
                    "tips": [
                        f"错误详情: {wh.get('error_detail','无')}",
                        "Worker 执行过程中抛出异常 (非配额超限)",
                        "1. Dashboard → Workers → Logs → 查看实时日志定位具体报错",
                        "2. 检查 Worker 代码中 KV 操作、JSON 解析等是否有异常未捕获",
                        "3. 若为 KV 相关错误，检查 KV 命名空间绑定是否正确",
                    ],
                })
            elif et == "network_error":
                suggestions.append({
                    "level": "ERR",
                    "title": f"Webhook 推送网络错误: {wh.get('error_detail','')}",
                    "tips": [
                        "请求未到达服务端",
                        "1. 检查 URL 是否正确 (应为 .../api/playback/webhook)",
                        "2. 检查本机网络/代理设置",
                        "3. 若 SSL 握手失败，检查系统时间与根证书",
                    ],
                })

        # 7. 记录查询失败
        rec = r.get("records")
        if rec and rec.get("status") == "fail":
            et = rec.get("error_type")
            if et == "missing_token":
                suggestions.append({
                    "level": "ERR",
                    "title": "记录查询 401: 缺少 Token",
                    "tips": ["请在顶部 Token 输入框填入正确的 ACCESS_TOKEN"],
                })
            elif et == "invalid_token":
                suggestions.append({
                    "level": "ERR",
                    "title": "记录查询 403: Token 无效",
                    "tips": ["Token 与服务端不匹配，请复核 ACCESS_TOKEN"],
                })
            elif et == "kv_limit_exceeded":
                sug = self._cf_quota_suggestion(et, rec.get("error_detail", ""), source="记录查询")
                if sug:
                    suggestions.append(sug)
            elif et in (
                "kv_put_limit_exceeded", "kv_get_limit_exceeded", "kv_list_limit_exceeded",
                "kv_delete_limit_exceeded", "kv_value_too_large", "kv_key_too_long",
                "worker_cpu_exceeded", "worker_request_limit_exceeded", "worker_subrequest_exceeded",
            ):
                sug = self._cf_quota_suggestion(et, rec.get("error_detail", ""), source="记录查询")
                if sug:
                    suggestions.append(sug)
            elif et == "server_error":
                suggestions.append({
                    "level": "ERR",
                    "title": f"记录查询 500: 服务端内部错误",
                    "tips": [
                        f"错误详情: {rec.get('error_detail','无')}",
                        "Dashboard → Workers → Logs 查看实时日志定位问题",
                    ],
                })

        # 8. 批量推送失败
        bat = r.get("batch")
        if bat and bat.get("status") == "fail":
            et = bat.get("error_type")
            if et in ("missing_token", "invalid_token"):
                suggestions.append({
                    "level": "ERR",
                    "title": f"批量推送失败: {et}",
                    "tips": ["与 Webhook 推送同源问题，请先解决上面的 Token 鉴权问题"],
                })
            elif et == "kv_limit_exceeded":
                sug = self._cf_quota_suggestion(et, bat.get("error_detail", ""), source="批量推送")
                if sug:
                    suggestions.append(sug)
            elif et in (
                "kv_put_limit_exceeded", "kv_get_limit_exceeded", "kv_list_limit_exceeded",
                "kv_delete_limit_exceeded", "kv_value_too_large", "kv_key_too_long",
                "worker_cpu_exceeded", "worker_request_limit_exceeded", "worker_subrequest_exceeded",
            ):
                sug = self._cf_quota_suggestion(et, bat.get("error_detail", ""), source="批量推送")
                if sug:
                    suggestions.append(sug)
            elif et == "server_error":
                suggestions.append({
                    "level": "ERR",
                    "title": "批量推送 500: 服务端内部错误",
                    "tips": [
                        f"错误详情: {bat.get('error_detail','无')}",
                        "Dashboard → Workers → Logs 查看实时日志",
                    ],
                })

        # 9. 一切正常但 KV 数据为空
        if (wh and wh.get("status") == "ok" and rec and rec.get("status") == "ok"
                and rec.get("total") == 0):
            suggestions.append({
                "level": "WARN",
                "title": "Webhook 推送成功但记录查询为空",
                "tips": [
                    "可能原因:",
                    "1. KV 写入存在最终一致性，建议等待 3~5 秒后重新查询",
                    "2. 本工具推送的 test 记录被去重 (dedupeKey) 跳过",
                    "3. TV 端尚未实际推送过真实记录",
                    "建议: 等待几秒后再次运行「记录查询」",
                ],
            })

        # 10. Token 未配置但鉴权已启用 → 提醒
        if not token and no_token and no_token.get("auth_enabled"):
            suggestions.append({
                "level": "WARN",
                "title": "服务端已启用鉴权但本工具未配置 Token",
                "tips": [
                    "请在顶部 Token 输入框填入 ACCESS_TOKEN 以执行完整的鉴权测试",
                ],
            })

        return suggestions

    # ===================== Cloudflare 配额错误分类与建议 =====================

    # 错误类型 → 中文显示标签
    _CF_ERROR_LABELS = {
        "kv_put_limit_exceeded": "KV 写入限额超限",
        "kv_get_limit_exceeded": "KV 读取限额超限",
        "kv_list_limit_exceeded": "KV 列表限额超限",
        "kv_delete_limit_exceeded": "KV 删除限额超限",
        "kv_value_too_large": "KV 值大小超限",
        "kv_key_too_long": "KV Key 长度超限",
        "kv_limit_exceeded": "KV 限额超限",
        "worker_cpu_exceeded": "Worker CPU 超限",
        "worker_request_limit_exceeded": "Worker 请求限额超限",
        "worker_subrequest_exceeded": "Worker 子请求超限",
        "server_error": "服务端错误",
    }

    def _cf_error_label(self, error_type):
        return self._CF_ERROR_LABELS.get(error_type, "服务端错误")

    def _classify_cf_error(self, body):
        """解析响应 body，识别 Cloudflare 免费额度超限错误。
        返回 (error_type, error_detail) 或 None。"""
        err_detail = ""
        try:
            data = json.loads(body or "")
            if isinstance(data, dict):
                err_detail = data.get("error", "") or data.get("message", "") or ""
        except Exception:
            err_detail = body or ""
        if not err_detail or not isinstance(err_detail, str):
            return None

        msg = err_detail.lower()

        # KV 相关配额错误 (按特异性优先级排序)
        if "kv put() limit" in msg or ("kv" in msg and "put" in msg and "limit exceeded" in msg):
            return ("kv_put_limit_exceeded", err_detail)
        if "kv get() limit" in msg or ("kv" in msg and "get" in msg and "limit exceeded" in msg):
            return ("kv_get_limit_exceeded", err_detail)
        if "kv list() limit" in msg or ("kv" in msg and "list" in msg and "limit exceeded" in msg):
            return ("kv_list_limit_exceeded", err_detail)
        if "kv delete() limit" in msg or ("kv" in msg and "delete" in msg and "limit exceeded" in msg):
            return ("kv_delete_limit_exceeded", err_detail)
        if ("kv" in msg and "value" in msg and ("length" in msg or "size" in msg or "exceeded" in msg)):
            return ("kv_value_too_large", err_detail)
        if ("kv" in msg and "key" in msg and ("length" in msg or "size" in msg or "exceeded" in msg)):
            return ("kv_key_too_long", err_detail)
        if "kv" in msg and "limit exceeded" in msg:
            return ("kv_limit_exceeded", err_detail)  # 通用 KV 限额

        # Worker 相关配额错误
        if "cpu" in msg and ("limit" in msg or "exceeded" in msg):
            return ("worker_cpu_exceeded", err_detail)
        if (("daily limit" in msg and "request" in msg)
                or "exceeded your daily limit" in msg
                or ("worker" in msg and "daily" in msg and "limit" in msg)):
            return ("worker_request_limit_exceeded", err_detail)
        if "subrequest" in msg and "limit" in msg:
            return ("worker_subrequest_exceeded", err_detail)

        return None

    def _cf_quota_suggestion(self, error_type, error_detail, source="Webhook 推送"):
        """生成 Cloudflare 配额超限的统一建议。返回 suggestion dict 或 None。"""
        if error_type == "server_error":
            return None  # server_error 不属于配额错误，由调用方单独处理

        label = self._cf_error_label(error_type)
        tips = [
            f"错误详情: {error_detail}",
            "",
            "Cloudflare 免费计划关键限额 (每日 UTC 0 点 / 北京时间 8:00 重置):",
            "  • Workers 请求: 100,000 次/天",
            "  • Workers CPU: 10ms/次 (Free), 50ms (Paid)",
            "  • KV 读取 (get): 100,000 次/天",
            "  • KV 写入 (put/delete): 1,000 次/天",
            "  • KV 列表 (list): 1,000 次/天",
            "  • KV 存储: 1 GB / 单值 25 MB / Key 512 字节",
            "",
        ]

        if error_type == "kv_put_limit_exceeded":
            tips += [
                "本次触发: KV 写入次数 (put) 超出 1,000 次/天限额",
                "",
                "解决方案 (按推荐顺序):",
                "  1. 【优化上报频率】确认 TV 端 PlaybackWebhookSender 防抖+批量配置合理",
                "     (建议最小上报间隔 >= 30 秒，优先使用批量接口 /api/playback/progress/batch)",
                "  2. 【改用 Workers D1】D1 免费额度: 写 10 万/天, 读 500 万/天",
                "     比 KV 更适合频繁写入的播放进度场景，且支持 SQL 查询",
                "  3. 【升级 Cloudflare 付费计划】Paid 版 KV 写入限额提升到 10 万次/天",
                "  4. 【等待次日重置】免费额度每日 UTC 0 点 (北京时间 8:00) 自动重置",
            ]
        elif error_type == "kv_get_limit_exceeded":
            tips += [
                "本次触发: KV 读取次数 (get) 超出 100,000 次/天限额",
                "  原因: TV 端轮询查询 /api/playback/records 过于频繁 (每次查询 = 1 次 KV get)",
                "",
                "解决方案:",
                "  1. 【降低查询频率】TV 端拉取观影记录的轮询间隔加大 (建议 >= 60 秒)",
                "  2. 【客户端缓存】TV 端缓存拉取结果，避免短时间内重复请求",
                "  3. 【改用 Workers D1】D1 免费读额度 500 万/天，远高于 KV",
                "  4. 【升级付费计划】Paid 版 KV 读取无限制",
                "  5. 【等待次日重置】UTC 0 点 (北京时间 8:00) 自动重置",
            ]
        elif error_type == "kv_list_limit_exceeded":
            tips += [
                "本次触发: KV 列表操作 (list) 超出 1,000 次/天限额",
                "  注意: 本 Worker 使用单 key 存储 (STORE_KEY=all_records)，正常不应触发 list",
                "  原因: 可能是 dashboard.js 或运维脚本调用了 list()",
                "",
                "解决方案:",
                "  1. 检查 dashboard.js 是否频繁调用 KV list()",
                "  2. 改用 Workers D1，list 操作可通过 SQL SELECT 替代",
                "  3. 升级付费计划，list 限额提升到 100 万次/天",
            ]
        elif error_type == "kv_delete_limit_exceeded":
            tips += [
                "本次触发: KV 删除操作 (delete) 超出 1,000 次/天限额",
                "  注意: delete 与 put 共享写入配额 (合计 1,000 次/天)",
                "",
                "解决方案: 同 KV 写入超限 — 优化清理频率或改用 D1",
            ]
        elif error_type == "kv_value_too_large":
            tips += [
                "本次触发: KV 单值大小超过 25 MB 上限",
                "  原因: STORE_KEY='all_records' 的 JSON 序列化后体积过大 (记录数过多或单条过大)",
                "",
                "解决方案:",
                "  1. 【减小 MAX_RECORDS】在 wrangler.toml 中将 MAX_RECORDS 调小 (如 1000)",
                "  2. 【缩短 RETENTION_DAYS】自动清理过期记录以减小体积",
                "  3. 【手动清理】通过 DELETE /api/playback/records?scope=all&confirm=true 清空",
                "  4. 【分片存储】改造 Worker，按 siteKey 分多个 KV key 存储",
                "  5. 【改用 D1】D1 单库 10 GB，远大于 KV 单值限制",
            ]
        elif error_type == "kv_key_too_long":
            tips += [
                "本次触发: KV Key 长度超过 512 字节",
                "  注意: 本 Worker 使用固定 STORE_KEY='all_records'，正常不应触发",
                "  原因: 可能是 Worker 代码被修改，使用了动态长 key",
                "",
                "解决方案: 检查 Worker 代码中 KV 操作的 key 来源",
            ]
        elif error_type == "kv_limit_exceeded":
            tips += [
                "本次触发: KV 操作超出每日限额 (具体类型未识别)",
                "解决方案: 同 KV 写入/读取超限 — 优化调用频率或改用 D1 / 升级付费",
            ]
        elif error_type == "worker_cpu_exceeded":
            tips += [
                "本次触发: Worker CPU 时间超过 10ms/次 (免费计划上限)",
                "  原因: 大量记录时 JSON.parse + filter + sort + JSON.stringify 耗时较高",
                "",
                "解决方案:",
                "  1. 【减小 MAX_RECORDS】减少单次处理的记录数 (推荐 <= 1000)",
                "  2. 【升级付费计划】Paid 版 CPU 限额 50ms/次",
                "  3. 【优化 Worker 代码】改用 KV 分片或 D1，避免全量加载",
                "  4. 【客户端分页】TV 端通过 maxItems 参数限制返回数量",
            ]
        elif error_type == "worker_request_limit_exceeded":
            tips += [
                "本次触发: Worker 请求次数超出 100,000 次/天限额",
                "  原因: TV 端轮询/上报过于频繁，或测试工具大量调用",
                "",
                "解决方案:",
                "  1. 【降低请求频率】TV 端上报间隔 >= 30 秒，查询间隔 >= 60 秒",
                "  2. 【使用批量接口】/api/playback/progress/batch 一次提交多条",
                "  3. 【客户端缓存】TV 端缓存查询结果",
                "  4. 【升级付费计划】Paid 版无每日请求限制",
                "  5. 【等待次日重置】UTC 0 点 (北京时间 8:00) 自动重置",
            ]
        elif error_type == "worker_subrequest_exceeded":
            tips += [
                "本次触发: Worker 单次请求子请求 (fetch) 数超过 50",
                "  注意: 本 Worker 当前不使用 fetch()，正常不应触发",
                "  原因: 可能是 Worker 代码被修改，添加了对外部 API 的调用",
                "",
                "解决方案: 检查 Worker 代码中是否有 fetch() 调用，考虑改为预存或客户端处理",
            ]
        else:
            return None

        tips += [
            "",
            "Dashboard 用量查看: https://dash.cloudflare.com/?to=/:account/workers",
            "Cloudflare 限额文档: https://developers.cloudflare.com/workers/platform/limits/",
        ]
        return {"level": "ERR", "title": f"{source} 500: {label}", "tips": tips}

    # ===================== 日志分析与诊断报告 END =====================

    def _test_network(self, base_url, token):
        self._log("INFO", "--- 网络层诊断 ---")
        self._set_result("network", "🌐 网络层: 运行中...", "pending")

        parsed = urlparse(base_url)
        host = parsed.hostname
        all_ok = True
        dns_ok = False
        tcp_ok = False
        ssl_ok = False
        dns_error = ""
        tcp_error = ""
        ssl_error = ""
        cert_info = ""

        try:
            self._log("INFO", f"DNS 解析: {host}")
            addrs = socket.getaddrinfo(host, 443, socket.AF_UNSPEC, socket.SOCK_STREAM)
            unique_ips = sorted(set(a[4][0] for a in addrs))
            for ip in unique_ips:
                self._log("OK", f"  {host} → {ip}")
            dns_ok = True
        except Exception as e:
            self._log("ERR", f"  DNS 解析失败: {e}")
            dns_error = str(e)
            all_ok = False

        try:
            self._log("INFO", f"TCP 连接: {host}:443")
            sock = socket.create_connection((host, 443), timeout=5)
            self._log("OK", "  TCP 连接成功")
            sock.close()
            tcp_ok = True
        except Exception as e:
            self._log("ERR", f"  TCP 连接失败: {e}")
            tcp_error = str(e)
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
                    cert_info = f"CN={subject.get('commonName', 'N/A')}, 过期={cert.get('notAfter', 'N/A')}"
            ssl_ok = True
        except Exception as e:
            self._log("ERR", f"  SSL 握手失败: {e}")
            ssl_error = str(e)
            all_ok = False

        self.test_results["network"] = {
            "status": "ok" if all_ok else "fail",
            "dns_ok": dns_ok, "tcp_ok": tcp_ok, "ssl_ok": ssl_ok,
            "dns_error": dns_error, "tcp_error": tcp_error, "ssl_error": ssl_error,
            "cert_info": cert_info, "host": host,
        }
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
            self.test_results["health"] = {"status": "waf", "http_code": resp.get("status")}
            self._set_result("health", "💓 健康检查: WAF 拦截", "fail")
            return "waf_blocked"

        ok = resp.get("status") == 200
        self.test_results["health"] = {"status": "ok" if ok else "fail", "http_code": resp.get("status")}
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
            self.test_results["capabilities"] = {"status": "waf", "http_code": resp.get("status")}
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
        self.test_results["capabilities"] = {
            "status": "ok" if ok else "fail",
            "http_code": resp.get("status"),
            "server_mode": mode, "kv_bound": kv,
        }
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
            self.test_results["cors"] = {"status": "waf", "http_code": resp.get("status")}
            self._set_result("cors", "🔒 CORS 预检: WAF 拦截", "fail")
            return "waf_blocked"

        ok = resp.get("status") == 204
        headers = resp.get("headers", {})
        acao = headers.get("Access-Control-Allow-Origin", "")
        acam = headers.get("Access-Control-Allow-Methods", "")
        self._log("INFO", f"  ACAO: {acao or 'N/A'}, ACAM: {acam or 'N/A'}")
        self.test_results["cors"] = {
            "status": "ok" if ok else "fail",
            "http_code": resp.get("status"),
            "acao": acao, "acam": acam,
        }
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
            self.test_results["no_token"] = {"status": "waf", "http_code": resp.get("status")}
            self._set_result("no_token", "🚫 无 Token: WAF 拦截", "fail")
            return "waf_blocked"

        status = resp.get("status")
        if status == 401:
            self._log("OK", "  鉴权已启用: 无 token 被正确拒绝 (401)")
            self.test_results["no_token"] = {"status": "ok", "http_code": 401, "auth_enabled": True}
            self._set_result("no_token", "🚫 无 Token: 鉴权正常 (401)", "ok")
            return True
        elif status == 200:
            self._log("WARN", "  鉴权未启用! 生产环境建议配置 ACCESS_TOKEN")
            self.test_results["no_token"] = {"status": "warn", "http_code": 200, "auth_enabled": False}
            self._set_result("no_token", "🚫 无 Token: 鉴权未启用 (200)", "warn")
            return True
        else:
            self.test_results["no_token"] = {"status": "fail", "http_code": status, "auth_enabled": None}
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
            self.test_results["webhook"] = {"status": "waf", "http_code": resp.get("status"), "error_type": "waf"}
            self._set_result("webhook", "📮 Webhook: WAF 拦截", "fail")
            return "waf_blocked"

        status = resp.get("status")
        body = resp.get("body", "")

        def _record_fail(err_type, detail):
            self.test_results["webhook"] = {
                "status": "fail", "http_code": status,
                "error_type": err_type, "error_detail": detail,
            }

        if status == 200:
            self._log("OK", "  Webhook 推送成功!")
            self.test_results["webhook"] = {"status": "ok", "http_code": 200, "error_type": None}
            self._set_result("webhook", "📮 Webhook: 推送成功 (200)", "ok")
            return True
        elif status == 401:
            self._log("ERR", "  缺少 token (401) — 请在 Dashboard 检查 ACCESS_TOKEN")
            _record_fail("missing_token", "缺少 token")
            self._set_result("webhook", "📮 Webhook: 缺少 Token (401)", "fail")
            return False
        elif status == 403:
            err_type = "invalid_token"
            err_detail = "Token 无效"
            try:
                data = json.loads(body)
                err_msg = data.get("error", "")
                if "Invalid token" in err_msg:
                    self._log("ERR", "  Token 无效 (403) — App 端 Token 与 Dashboard 不匹配")
                else:
                    err_type = "forbidden"
                    err_detail = err_msg or "403 Forbidden"
                    self._log("ERR", f"  403 Forbidden: {err_msg}")
            except Exception:
                err_type = "forbidden"
                err_detail = "403 Forbidden"
                self._log("ERR", "  403 Forbidden")
            _record_fail(err_type, err_detail)
            self._set_result("webhook", "📮 Webhook: Token 无效 (403)", "fail")
            return False
        elif status == 400:
            err_detail = "Bad Request"
            try:
                data = json.loads(body)
                err_detail = data.get("error", "")
                self._log("ERR", f"  请求格式错误: {err_detail}")
            except Exception:
                pass
            _record_fail("bad_request", err_detail)
            self._set_result("webhook", "📮 Webhook: 格式错误 (400)", "fail")
            return False
        elif status == 501:
            self._log("ERR", "  KV 存储未配置 (501) — 检查 KV namespace ID")
            _record_fail("kv_not_configured", "KV 存储未配置")
            self._set_result("webhook", "📮 Webhook: KV 未配置 (501)", "fail")
            return False
        elif status == 500:
            cf = self._classify_cf_error(body)
            if cf:
                et, ed = cf
                self._log("ERR", f"  Cloudflare 配额超限 (500): {ed}")
            else:
                et, ed = "server_error", (body or "")[:200] or "500 Internal Server Error"
                self._log("ERR", f"  服务端内部错误 (500): {ed}")
            _record_fail(et, ed)
            self._set_result("webhook", f"📮 Webhook: {self._cf_error_label(et)} (500)", "fail")
            return False
        elif status is None:
            err_detail = resp.get("error", "网络错误")
            self._log("ERR", f"  网络错误: {err_detail}")
            _record_fail("network_error", err_detail)
            self._set_result("webhook", "📮 Webhook: 网络错误", "fail")
            return False
        else:
            self._log("ERR", f"  未知错误: HTTP {status}")
            _record_fail("unknown", f"HTTP {status}")
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
            self.test_results["records"] = {"status": "waf", "http_code": resp.get("status")}
            self._set_result("records", "📋 记录查询: WAF 拦截", "fail")
            return "waf_blocked"

        status = resp.get("status")
        if status == 200:
            total = None
            try:
                data = json.loads(resp["body"])
                total = data.get("total", 0)
                self._log("OK", f"  查询成功: 共 {total} 条记录")
                self._set_result("records", f"📋 记录查询: {total} 条", "ok")
            except Exception:
                self._set_result("records", "📋 记录查询: 成功", "ok")
            self.test_results["records"] = {"status": "ok", "http_code": 200, "total": total}
            return True
        elif status == 401:
            self.test_results["records"] = {"status": "fail", "http_code": 401, "error_type": "missing_token"}
            self._set_result("records", "📋 记录查询: 缺少 Token (401)", "fail")
            return False
        elif status == 403:
            self.test_results["records"] = {"status": "fail", "http_code": 403, "error_type": "invalid_token"}
            self._set_result("records", "📋 记录查询: Token 无效 (403)", "fail")
            return False
        elif status == 500:
            cf = self._classify_cf_error(resp.get("body", ""))
            if cf:
                et, ed = cf
                self._log("ERR", f"  Cloudflare 配额超限 (500): {ed}")
            else:
                et, ed = "server_error", (resp.get("body", "") or "")[:200]
                self._log("ERR", f"  服务端内部错误 (500): {ed}")
            self.test_results["records"] = {"status": "fail", "http_code": 500, "error_type": et, "error_detail": ed}
            self._set_result("records", f"📋 记录查询: {self._cf_error_label(et)} (500)", "fail")
            return False
        else:
            self.test_results["records"] = {"status": "fail", "http_code": status, "error_type": "unknown"}
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
            self.test_results["batch"] = {"status": "waf", "http_code": resp.get("status")}
            self._set_result("batch", "📦 批量推送: WAF 拦截", "fail")
            return "waf_blocked"

        status = resp.get("status")
        if status == 200:
            applied = None
            try:
                data = json.loads(resp["body"])
                total = data.get("total", 0)
                applied = data.get("applied", 0)
                skipped = data.get("skipped", 0)
                failed_cnt = data.get("failed", 0)
                self._log("OK", f"  批量推送: 总计={total}, 应用={applied}, 跳过={skipped}, 失败={failed_cnt}")
                self._set_result("batch", f"📦 批量推送: 成功 (应用 {applied})", "ok")
            except Exception:
                self._set_result("batch", "📦 批量推送: 成功", "ok")
            self.test_results["batch"] = {"status": "ok", "http_code": 200, "applied": applied}
            return True
        elif status == 401:
            self.test_results["batch"] = {"status": "fail", "http_code": 401, "error_type": "missing_token"}
            self._set_result("batch", "📦 批量推送: 缺少 Token (401)", "fail")
            return False
        elif status == 403:
            self.test_results["batch"] = {"status": "fail", "http_code": 403, "error_type": "invalid_token"}
            self._set_result("batch", "📦 批量推送: Token 无效 (403)", "fail")
            return False
        elif status == 500:
            cf = self._classify_cf_error(resp.get("body", ""))
            if cf:
                et, ed = cf
                self._log("ERR", f"  Cloudflare 配额超限 (500): {ed}")
            else:
                et, ed = "server_error", (resp.get("body", "") or "")[:200]
                self._log("ERR", f"  服务端内部错误 (500): {ed}")
            self.test_results["batch"] = {"status": "fail", "http_code": 500, "error_type": et, "error_detail": ed}
            self._set_result("batch", f"📦 批量推送: {self._cf_error_label(et)} (500)", "fail")
            return False
        else:
            self.test_results["batch"] = {"status": "fail", "http_code": status, "error_type": "unknown"}
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