#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WebHTV 观影记录同步测试脚本 — 适配 webhtv-remote-cloudflare (Durable Object + SQLite 后端)

与旧版 KV 后端 (webhtv-playback-sync-cloudflare) 的关键差异：
  1. API 统一端点：/api/playback/sync
     - GET  → 按游标拉取增量进度和删除墓碑
     - POST → 写入进度 (playback.progress) 或删除墓碑 (playback.deleted)
  2. 状态端点：GET /api/playback/sync/status
  3. 删除方式：POST /api/playback/sync 发送 {event:"playback.deleted", scope:"item|site|all", ...}
     而非旧版的 DELETE /api/playback/records
  4. 分页：基于单调游标 since/nextSince，而非 maxItems
  5. 存储引擎：Durable Object 内置 SQLite，无需 KV 绑定

使用方式:
  # 直接运行（默认启动 GUI，弹出地址/Token/Config Key 输入框）
  python test_sync.py

  # CLI 模式（config-key 可直接填点播接口 URL，自动计算 SHA-256）
  python test_sync.py --url https://your-worker.workers.dev --token YOUR_TOKEN --config-key YOUR_CONFIG_KEY
  python test_sync.py --url ... --token ... --config-key https://example.com/config.json
"""

import argparse
import hashlib
import json
import os
import socket
import ssl
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime
from threading import Thread

# ============================================================
# 控制台编码兼容修复 (Windows 打包为 EXE 后尤其重要)
# ------------------------------------------------------------
# 现象: Windows 默认 GBK (代码页 936) 控制台, 打印中文/Emoji
#       (如 ✅❌⚠️) 会抛出 UnicodeEncodeError: 'gbk' codec ...
# 修复: 1) 设置 PYTHONUTF8=1 让 Python I/O 默认 UTF-8
#       2) 重定向 sys.stdout/stderr 为 UTF-8 编码 + errors='replace'
#       3) Win32 API 设置控制台输入/输出代码页为 65001 (UTF-8)
# ============================================================
def _fixup_console_encoding():
    if sys.platform != 'win32':
        return
    os.environ.setdefault('PYTHONUTF8', '1')
    # Python 3.7+ 支持 TextIOWrapper.reconfigure
    for stream_name in ('stdout', 'stderr'):
        try:
            stream = getattr(sys, stream_name, None)
            if stream is None or not hasattr(stream, 'reconfigure'):
                continue
            try:
                stream.reconfigure(encoding='utf-8', errors='replace')
            except Exception:
                # reconfigure 失败时 fallback: 重建 wrapper
                try:
                    import io
                    buf = getattr(stream, 'buffer', stream)
                    new_stream = io.TextIOWrapper(
                        buf, encoding='utf-8', errors='replace',
                        line_buffering=getattr(stream, 'line_buffering', True)
                    )
                    setattr(sys, stream_name, new_stream)
                except Exception:
                    pass
        except Exception:
            pass
    # Win32 控制台代码页 (仅真实控制台生效，IDE/管道无副作用)
    try:
        import ctypes
        _CP_UTF8 = 65001
        kernel32 = ctypes.windll.kernel32
        try:
            kernel32.SetConsoleOutputCP(_CP_UTF8)
        except Exception:
            pass
        try:
            kernel32.SetConsoleCP(_CP_UTF8)
        except Exception:
            pass
        # 启用 VT100 转义序列支持 (Windows 10 1607+)
        try:
            _STD_OUTPUT_HANDLE = -11
            _ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004
            hStdOut = kernel32.GetStdHandle(_STD_OUTPUT_HANDLE)
            mode = ctypes.c_ulong()
            if kernel32.GetConsoleMode(hStdOut, ctypes.byref(mode)):
                mode.value |= _ENABLE_VIRTUAL_TERMINAL_PROCESSING
                kernel32.SetConsoleMode(hStdOut, mode)
        except Exception:
            pass
    except Exception:
        pass
_fixup_console_encoding()
del _fixup_console_encoding

# ============================================================
# HTTP 请求工具
# ============================================================

def _is_ascii_header_value(value):
    try:
        str(value).encode('ascii')
        return True
    except (UnicodeEncodeError, UnicodeDecodeError):
        return False

def _percent_encode_header_value(value):
    return urllib.parse.quote(str(value), safe="-_.~*'()")

def _prepare_headers(headers):
    """模拟 TV 端 PlaybackHttpHeaders 的 percent-utf-8 编码逻辑"""
    result = {}
    for k, v in headers.items():
        if isinstance(v, str) and not _is_ascii_header_value(v):
            result[k] = _percent_encode_header_value(v)
            result[k + "-Encoding"] = "percent-utf-8"
        else:
            result[k] = str(v)
    return result

def http_request(url, method='GET', headers=None, body=None, timeout=15):
    """发送 HTTP 请求并返回 (status, response_headers, response_body)"""
    if headers is None:
        headers = {}
    # 设置浏览器 UA，避免被 Cloudflare Bot Fight Mode 拦截 (Error 1010)
    headers.setdefault('User-Agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36')
    prepared = _prepare_headers(headers)
    data = None
    if body is not None:
        if isinstance(body, (dict, list)):
            data = json.dumps(body, ensure_ascii=False).encode('utf-8')
            prepared.setdefault('Content-Type', 'application/json; charset=utf-8')
        elif isinstance(body, str):
            data = body.encode('utf-8')
        else:
            data = body
    req = urllib.request.Request(url, data=data, method=method, headers=prepared)
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
        status = resp.getcode()
        resp_headers = dict(resp.headers)
        resp_body = resp.read().decode('utf-8', errors='replace')
        return status, resp_headers, resp_body
    except urllib.error.HTTPError as e:
        resp_headers = dict(e.headers) if e.headers else {}
        resp_body = e.read().decode('utf-8', errors='replace') if e.fp else ''
        return e.code, resp_headers, resp_body
    except Exception as e:
        return None, {}, str(e)


# ============================================================
# 日志工具
# ============================================================

# 日志输出回调（GUI 模式下设为写入 Text 控件的函数；CLI 模式下为 None）
_LOG_SINK = None
_STOP_FLAG = False

def log(level, msg):
    ts = datetime.now().strftime('%H:%M:%S.') + f'{int(datetime.now().microsecond / 1000):03d}'
    icons = {'INFO': 'INFO', 'OK': '  OK', 'ERR': ' ERR', 'WARN': 'WARN', 'DBG': ' DBG'}
    icon = icons.get(level, level)
    line = f'[{ts}] [{icon:>6}] {msg}'
    if _LOG_SINK:
        _LOG_SINK(level, line)
    else:
        print(line)

def log_separator(title=''):
    line = '=' * 70
    if _LOG_SINK:
        _LOG_SINK('SEP', '')
        _LOG_SINK('SEP', line)
        if title:
            _LOG_SINK('SEP', f'  {title}')
            _LOG_SINK('SEP', line)
    else:
        print()
        print(line)
        if title:
            print(f'  {title}')
            print(line)

def is_stopped():
    return _STOP_FLAG


# ============================================================
# 测试用例
# ============================================================

class SyncTester:
    """观影记录同步测试器 — 适配 Durable Object 后端"""

    def __init__(self, base_url, token, config_key):
        self.base_url = base_url.rstrip('/')
        self.token = token
        self.config_key = config_key
        self.results = {}

    def _headers(self, extra=None):
        h = {
            'X-WebHTV-Config-Key': self.config_key,
        }
        # Token 留空时不发送 X-WebHTV-Token 头（无 Token 模式）
        if self.token:
            h['X-WebHTV-Token'] = self.token
        if extra:
            h.update(extra)
        return h

    def _url(self, path):
        return self.base_url + path

    # --- 测试 0: 网络连通性 ---
    def test_network(self):
        log_separator('测试 0/8: 网络连通性诊断')
        parsed = urllib.parse.urlparse(self.base_url)
        host = parsed.hostname
        port = parsed.port or (443 if parsed.scheme == 'https' else 80)
        dns_ok = tcp_ok = ssl_ok = False
        cert_info = ''

        log('INFO', f'解析主机: {host}')
        try:
            addrs = socket.getaddrinfo(host, port, socket.AF_INET)
            ip = addrs[0][4][0]
            dns_ok = True
            log('OK', f'DNS 解析成功: {host} -> {ip}')
        except Exception as e:
            log('ERR', f'DNS 解析失败: {e}')
            self.results['network'] = {'status': 'fail', 'error': f'DNS: {e}'}
            return False

        log('INFO', f'TCP 连接: {host}:{port}')
        try:
            sock = socket.create_connection((host, port), timeout=10)
            tcp_ok = True
            log('OK', f'TCP 连接成功')
            if parsed.scheme == 'https':
                ctx = ssl.create_default_context()
                ssock = ctx.wrap_socket(sock, server_hostname=host)
                cert = ssock.getpeercert()
                ssl_ok = True
                subject = dict(x[0] for x in cert.get('subject', []))
                issuer = dict(x[0] for x in cert.get('issuer', []))
                cert_info = f'CN={subject.get("commonName", "?")}'
                not_after = cert.get('notAfter', '')
                log('OK', f'SSL 握手成功: {cert_info}, 到期={not_after}')
                ssock.close()
            else:
                ssl_ok = True
            sock.close()
        except Exception as e:
            log('ERR', f'连接失败: {e}')
            sock.close()

        ok = dns_ok and tcp_ok and ssl_ok
        self.results['network'] = {
            'status': 'ok' if ok else 'fail',
            'dns_ok': dns_ok, 'tcp_ok': tcp_ok, 'ssl_ok': ssl_ok,
            'cert_info': cert_info, 'host': host
        }
        return ok

    # --- 测试 1: 健康检查 ---
    def test_health(self):
        log_separator('测试 1/8: 健康检查 GET /api/health')
        status, headers, body = http_request(self._url('/api/health'), 'GET', timeout=10)
        log('INFO', f'HTTP {status}')
        log('DBG', f'响应: {body[:500]}')
        if status == 200:
            try:
                data = json.loads(body)
                if data.get('ok'):
                    log('OK', '健康检查通过')
                    self.results['health'] = {'status': 'ok', 'http_code': 200}
                    return True
            except Exception:
                pass
        log('ERR', f'健康检查失败: HTTP {status}')
        self.results['health'] = {'status': 'fail', 'http_code': status}
        return False

    # --- 测试 2: 服务器能力 ---
    def test_capabilities(self):
        log_separator('测试 2/8: 服务器能力 GET /api/server/capabilities')
        status, headers, body = http_request(self._url('/api/server/capabilities'), 'GET', timeout=10)
        log('INFO', f'HTTP {status}')
        log('DBG', f'响应: {body[:800]}')
        if status == 200:
            try:
                data = json.loads(body)
                caps = data.get('capabilities', {})
                playback_sync = caps.get('playbackSync', False)
                log('OK', f"serverMode={data.get('serverMode')}, relayMode={data.get('relayMode')}")
                if playback_sync:
                    log('OK', 'playbackSync=true (PLAYBACK_DO 已配置)')
                else:
                    log('WARN', 'playbackSync=false (PLAYBACK_DO 未配置，观影记录同步不可用)')
                self.results['capabilities'] = {
                    'status': 'ok', 'http_code': 200,
                    'playback_sync': playback_sync,
                    'server_mode': data.get('serverMode'),
                    'relay_mode': data.get('relayMode')
                }
                return True
            except Exception as e:
                log('ERR', f'JSON 解析失败: {e}')
        self.results['capabilities'] = {'status': 'fail', 'http_code': status}
        return False

    # --- 测试 3: 同步状态查询 ---
    def test_status(self):
        log_separator('测试 3/8: 同步状态 GET /api/playback/sync/status')
        status, headers, body = http_request(
            self._url('/api/playback/sync/status'), 'GET',
            headers=self._headers(), timeout=10
        )
        log('INFO', f'HTTP {status}')
        log('DBG', f'响应: {body[:800]}')
        if status == 200:
            try:
                data = json.loads(body)
                log('OK', f"items={data.get('items')}, tombstones={data.get('tombstones')}, "
                         f"nextSince={data.get('nextSince')}, retentionDays={data.get('retentionDays')}")
                self.results['status'] = {
                    'status': 'ok', 'http_code': 200,
                    'items': data.get('items', 0),
                    'tombstones': data.get('tombstones', 0),
                    'next_since': data.get('nextSince'),
                    'retention_days': data.get('retentionDays')
                }
                return True
            except Exception as e:
                log('ERR', f'JSON 解析失败: {e}')
        elif status == 401:
            log('ERR', 'Token 缺失或无效')
        elif status == 400:
            log('ERR', 'Config-Key 缺失或无效')
        self.results['status'] = {'status': 'fail', 'http_code': status}
        return False

    # --- 测试 4: 写入进度 (Webhook 上报) ---
    def test_push_progress(self):
        log_separator('测试 4/8: 写入进度 POST /api/playback/sync (playback.progress)')
        event_id = f'test-progress-{int(time.time())}'
        now_ms = int(time.time() * 1000)
        payload = {
            'schema': 'webhtv.playback.v1',
            'event': 'playback.progress',
            'eventId': event_id,
            'timestamp': now_ms,
            'configKey': self.config_key,
            'historyKey': f'test-site@@@test-vod@@@1',
            'siteKey': 'test-site',
            'vodId': 'test-vod',
            'vodName': '测试影片',
            'episodeName': '第1集',
            'positionMs': 120000,
            'durationMs': 600000,
            'speed': 1.0
        }
        headers = self._headers({
            'Content-Type': 'application/json; charset=utf-8',
            'X-WebHTV-Webhook-Id': event_id,
            'Idempotency-Key': event_id,
        })
        log('INFO', f'写入测试进度: eventId={event_id}')
        status, resp_headers, body = http_request(
            self._url('/api/playback/sync'), 'POST',
            headers=headers, body=payload, timeout=15
        )
        log('INFO', f'HTTP {status}')
        log('DBG', f'响应: {body[:800]}')
        if status == 200:
            try:
                data = json.loads(body)
                applied = data.get('applied', 0)
                skipped = data.get('skipped', 0)
                results = data.get('results', [])
                log('OK', f'写入成功: applied={applied}, skipped={skipped}')
                if results:
                    r = results[0]
                    log('DBG', f"action={r.get('action')}, sequence={r.get('sequence')}")
                self.results['push_progress'] = {
                    'status': 'ok', 'http_code': 200,
                    'event_id': event_id, 'applied': applied, 'skipped': skipped
                }
                return True
            except Exception as e:
                log('ERR', f'JSON 解析失败: {e}')
        elif status == 400:
            log('ERR', f'请求格式错误: {body[:200]}')
        elif status == 401:
            log('ERR', 'Token 缺失或无效')
        self.results['push_progress'] = {'status': 'fail', 'http_code': status}
        return False

    # --- 测试 4b: 直播流写入 (durationMs=0，如虎牙 .flv 直播) ---
    def test_push_live(self):
        log_separator('测试 4b: 直播流写入 (durationMs=0，模拟虎牙 .flv 直播)')
        event_id = f'test-live-{int(time.time())}'
        now_ms = int(time.time() * 1000)
        # 模拟直播流：durationMs=0，positionMs>0（当前播放位置）
        # 直播 URL 作为 episodeUrl，虎牙房间号作为 vodId
        payload = {
            'schema': 'webhtv.playback.v1',
            'event': 'playback.progress',
            'eventId': event_id,
            'timestamp': now_ms,
            'configKey': self.config_key,
            'historyKey': 'huya@@@2367547387@@@0',
            'siteKey': 'huya',
            'vodId': '2367547387',
            'vodName': '虎牙直播间测试',
            'episodeName': '原画',
            'episodeUrl': 'https://al.flv.huya.com/src/2367547387-2367547387-10168538598895255552-4735218230-10057-A-0-1-imgplus.flv?codec=264&ctype=huya_pc_exe&wsSecret=abc&wsTime=def',
            'positionMs': 30000,
            'durationMs': 0,
            'speed': 1.0
        }
        headers = self._headers({
            'Content-Type': 'application/json; charset=utf-8',
            'X-WebHTV-Webhook-Id': event_id,
            'Idempotency-Key': event_id,
        })
        log('INFO', f'写入直播流进度: eventId={event_id}, durationMs=0')
        status, resp_headers, body = http_request(
            self._url('/api/playback/sync'), 'POST',
            headers=headers, body=payload, timeout=15
        )
        log('INFO', f'HTTP {status}')
        log('DBG', f'响应: {body[:800]}')
        if status == 200:
            try:
                data = json.loads(body)
                applied = data.get('applied', 0)
                log('OK', f'直播流写入成功: applied={applied}')
                self.results['push_live'] = {
                    'status': 'ok', 'http_code': 200,
                    'event_id': event_id, 'applied': applied
                }
                return True
            except Exception as e:
                log('ERR', f'JSON 解析失败: {e}')
        elif status == 400:
            log('ERR', f'请求格式错误 (直播流未通过校验): {body[:200]}')
            log('WARN', '服务端可能未更新支持 durationMs=0 的直播流模式，请重新部署 Worker')
        elif status == 401:
            log('ERR', 'Token 缺失或无效')
        self.results['push_live'] = {'status': 'fail', 'http_code': status}
        return False

    # --- 测试 4c: 小说阅读进度写入 (mediaType=novel, kind=1) ---
    # 验证 v4 schema 新增的 mediaType 字段被服务端持久化，
    # 且与同名视频记录不互相覆盖 (同名不同类型由 dedupSameTitle 按 media_type 隔离)。
    def test_push_novel_progress(self):
        log_separator('测试 4c: 小说阅读进度写入 (mediaType=novel, kind=1)')
        event_id = f'test-novel-{int(time.time())}'
        now_ms = int(time.time() * 1000)
        # positionMs/durationMs 复用为「章节内锚点序号 / 锚点总数」(协议沿用历史字段名，单位非毫秒)
        payload = {
            'schema': 'webhtv.playback.v1',
            'event': 'playback.progress',
            'eventId': event_id,
            'timestamp': now_ms,
            'configKey': self.config_key,
            'historyKey': 'novel-site@@@novel-book@@@1',
            'siteKey': 'novel-site',
            'vodId': 'novel-book',
            'vodName': '测试小说',
            'vodPic': 'https://example.com/novel-cover.jpg',
            'episodeName': '第1章 序章',
            'episodeUrl': 'https://example.com/novel/chapter-1',
            'mediaType': 'novel',
            'positionMs': 5,
            'durationMs': 20,
            'completed': False,
            'speed': 1.0
        }
        headers = self._headers({
            'Content-Type': 'application/json; charset=utf-8',
            'X-WebHTV-Webhook-Id': event_id,
            'Idempotency-Key': event_id,
        })
        log('INFO', f'写入小说阅读进度: eventId={event_id}, mediaType=novel, 锚点 5/20')
        status, resp_headers, body = http_request(
            self._url('/api/playback/sync'), 'POST',
            headers=headers, body=payload, timeout=15
        )
        log('INFO', f'HTTP {status}')
        log('DBG', f'响应: {body[:800]}')
        if status == 200:
            try:
                data = json.loads(body)
                applied = data.get('applied', 0)
                results = data.get('results', [])
                media_type_back = ''
                if results:
                    media_type_back = results[0].get('mediaType', '')
                log('OK', f'小说阅读进度写入成功: applied={applied}, mediaType={media_type_back}')
                self.results['push_novel_progress'] = {
                    'status': 'ok', 'http_code': 200,
                    'event_id': event_id, 'applied': applied,
                    'media_type': media_type_back
                }
                return True
            except Exception as e:
                log('ERR', f'JSON 解析失败: {e}')
        elif status == 400:
            log('ERR', f'请求格式错误: {body[:200]}')
            log('WARN', '服务端可能未更新支持 mediaType 字段 (v4 schema)，请重新部署 Worker')
        elif status == 401:
            log('ERR', 'Token 缺失或无效')
        self.results['push_novel_progress'] = {'status': 'fail', 'http_code': status}
        return False

    # --- 测试 4d: 漫画阅读进度写入 (mediaType=comic, kind=2) ---
    # 验证 kind=2 数字别名被服务端归一化为 comic，并写入 media_type 列。
    def test_push_comic_progress(self):
        log_separator('测试 4d: 漫画阅读进度写入 (kind=2 → mediaType=comic)')
        event_id = f'test-comic-{int(time.time())}'
        now_ms = int(time.time() * 1000)
        # 故意使用数字 kind 而非 mediaType，验证服务端 alias 归一化
        payload = {
            'schema': 'webhtv.playback.v1',
            'event': 'playback.progress',
            'eventId': event_id,
            'timestamp': now_ms,
            'configKey': self.config_key,
            'historyKey': 'comic-site@@@comic-manga@@@1',
            'siteKey': 'comic-site',
            'vodId': 'comic-manga',
            'vodName': '测试漫画',
            'vodPic': 'https://example.com/comic-cover.jpg',
            'episodeName': '第1话',
            'episodeUrl': 'https://example.com/comic/episode-1',
            'kind': 2,
            'positionMs': 3,
            'durationMs': 24,
            'completed': False,
            'speed': 1.0
        }
        headers = self._headers({
            'Content-Type': 'application/json; charset=utf-8',
            'X-WebHTV-Webhook-Id': event_id,
            'Idempotency-Key': event_id,
        })
        log('INFO', f'写入漫画阅读进度: eventId={event_id}, kind=2 (期望归一化为 comic), 页码 3/24')
        status, resp_headers, body = http_request(
            self._url('/api/playback/sync'), 'POST',
            headers=headers, body=payload, timeout=15
        )
        log('INFO', f'HTTP {status}')
        log('DBG', f'响应: {body[:800]}')
        if status == 200:
            try:
                data = json.loads(body)
                applied = data.get('applied', 0)
                results = data.get('results', [])
                media_type_back = ''
                if results:
                    media_type_back = results[0].get('mediaType', '')
                if media_type_back == 'comic':
                    log('OK', f'漫画阅读进度写入成功: applied={applied}, mediaType={media_type_back} (kind=2 已归一化)')
                else:
                    log('WARN', f'写入返回但 mediaType={media_type_back!r} (期望 comic)')
                self.results['push_comic_progress'] = {
                    'status': 'ok', 'http_code': 200,
                    'event_id': event_id, 'applied': applied,
                    'media_type': media_type_back
                }
                return True
            except Exception as e:
                log('ERR', f'JSON 解析失败: {e}')
        elif status == 400:
            log('ERR', f'请求格式错误: {body[:200]}')
        elif status == 401:
            log('ERR', 'Token 缺失或无效')
        self.results['push_comic_progress'] = {'status': 'fail', 'http_code': status}
        return False

    # --- 测试 5: 拉取增量记录 ---
    def test_pull(self):
        log_separator('测试 5/8: 拉取增量 GET /api/playback/sync?since=0')
        headers = self._headers({
            'X-WebHTV-Since': '0',
            'X-WebHTV-Limit': '100',
        })
        status, resp_headers, body = http_request(
            self._url('/api/playback/sync?since=0&limit=100'), 'GET',
            headers=headers, timeout=15
        )
        log('INFO', f'HTTP {status}')
        log('DBG', f'响应: {body[:1000]}')
        if status == 200:
            try:
                data = json.loads(body)
                changes = data.get('changes', [])
                upserts = [c for c in changes if c.get('action') != 'delete']
                deletes = [c for c in changes if c.get('action') == 'delete']
                log('OK', f'拉取成功: changes={len(changes)}, upserts={len(upserts)}, '
                         f"deletes={len(deletes)}, nextSince={data.get('nextSince')}, "
                         f"hasMore={data.get('hasMore')}")
                if upserts:
                    latest = upserts[-1]
                    log('DBG', f"最新记录: {latest.get('vodName', '?')} - "
                             f"{latest.get('episodeName', '?')} "
                             f"({latest.get('positionMs', 0)}ms/{latest.get('durationMs', 0)}ms)")
                self.results['pull'] = {
                    'status': 'ok', 'http_code': 200,
                    'changes': len(changes),
                    'upserts': len(upserts),
                    'deletes': len(deletes),
                    'next_since': data.get('nextSince'),
                    'has_more': data.get('hasMore')
                }
                return True
            except Exception as e:
                log('ERR', f'JSON 解析失败: {e}')
        self.results['pull'] = {'status': 'fail', 'http_code': status}
        return False

    # --- 测试 6: 删除墓碑 ---
    def test_delete(self):
        log_separator('测试 6/8: 删除墓碑 POST /api/playback/sync (playback.deleted)')
        event_id = f'test-delete-{int(time.time())}'
        now_ms = int(time.time() * 1000)
        payload = {
            'event': 'playback.deleted',
            'eventId': event_id,
            'scope': 'item',
            'historyKey': 'test-site@@@test-vod@@@1',
            'siteKey': 'test-site',
            'vodId': 'test-vod',
            'deletedAt': now_ms,
            'configKey': self.config_key
        }
        headers = self._headers({
            'Content-Type': 'application/json; charset=utf-8',
            'X-WebHTV-Webhook-Id': event_id,
        })
        log('INFO', f'发送删除墓碑: eventId={event_id}, scope=item')
        status, resp_headers, body = http_request(
            self._url('/api/playback/sync'), 'POST',
            headers=headers, body=payload, timeout=15
        )
        log('INFO', f'HTTP {status}')
        log('DBG', f'响应: {body[:800]}')
        if status == 200:
            try:
                data = json.loads(body)
                results = data.get('results', [])
                if results:
                    r = results[0]
                    log('OK', f"删除成功: action={r.get('action')}, affected={r.get('affected', 0)}, "
                             f"sequence={r.get('sequence')}")
                self.results['delete'] = {
                    'status': 'ok', 'http_code': 200,
                    'event_id': event_id
                }
                return True
            except Exception as e:
                log('ERR', f'JSON 解析失败: {e}')
        elif status == 400:
            log('ERR', f'删除请求格式错误: {body[:200]}')
        self.results['delete'] = {'status': 'fail', 'http_code': status}
        return False

    # --- 测试 7: 批量写入 ---
    def test_batch(self):
        log_separator('测试 7/8: 批量写入 POST /api/playback/sync (数组)')
        now_ms = int(time.time() * 1000)
        items = []
        for i in range(3):
            items.append({
                'event': 'playback.progress',
                'eventId': f'test-batch-{now_ms}-{i}',
                'timestamp': now_ms + i * 1000,
                'configKey': self.config_key,
                'historyKey': f'test-site@@@test-vod-batch@@@{i+1}',
                'siteKey': 'test-site',
                'vodId': f'test-vod-batch-{i+1}',
                'vodName': f'批量测试影片{i+1}',
                'episodeName': f'第{i+1}集',
                'positionMs': 60000 * (i + 1),
                'durationMs': 600000,
            })
        headers = self._headers({'Content-Type': 'application/json; charset=utf-8'})
        log('INFO', f'批量写入 {len(items)} 条记录')
        status, resp_headers, body = http_request(
            self._url('/api/playback/sync'), 'POST',
            headers=headers, body=items, timeout=15
        )
        log('INFO', f'HTTP {status}')
        log('DBG', f'响应: {body[:800]}')
        if status == 200:
            try:
                data = json.loads(body)
                log('OK', f"批量写入成功: received={data.get('received')}, "
                         f"applied={data.get('applied')}, skipped={data.get('skipped')}")
                self.results['batch'] = {
                    'status': 'ok', 'http_code': 200,
                    'received': data.get('received', 0),
                    'applied': data.get('applied', 0)
                }
                return True
            except Exception as e:
                log('ERR', f'JSON 解析失败: {e}')
        self.results['batch'] = {'status': 'fail', 'http_code': status}
        return False

    # --- 测试 8: 认证验证 ---
    def test_auth(self):
        log_separator('测试 8/8: 认证验证 (缺少 Token / Config-Key)')
        # 不带 Token（无 Token 模式合法，应返回 200 或服务端响应数据）
        log('INFO', '测试 1: 不带 X-WebHTV-Token（无 Token 模式）')
        status, _, body = http_request(
            self._url('/api/playback/sync/status'), 'GET',
            headers={'X-WebHTV-Config-Key': self.config_key}, timeout=10
        )
        log('DBG', f'HTTP {status}: {body[:200]}')
        # 无 Token 模式现在是合法的：路由到 user-no-token 命名空间
        # 因此应返回 200（空状态或有数据的 status）
        no_token_ok = (status == 200)

        # 不带 Config-Key（Config-Key 仍然是强制必填）
        log('INFO', '测试 2: 不带 X-WebHTV-Config-Key')
        status2, _, body2 = http_request(
            self._url('/api/playback/sync/status'), 'GET',
            headers={'X-WebHTV-Token': self.token}, timeout=10
        )
        log('DBG', f'HTTP {status2}: {body2[:200]}')
        no_configkey_ok = (status2 == 400)

        # 错误 Token：路由到独立命名空间但仍应返回 200
        log('INFO', '测试 3: 错误的 Token')
        status3, _, body3 = http_request(
            self._url('/api/playback/sync/status'), 'GET',
            headers={'X-WebHTV-Token': 'wrong-token', 'X-WebHTV-Config-Key': self.config_key},
            timeout=10
        )
        log('DBG', f'HTTP {status3}: {body3[:200]}')
        # 错误 Token 现在是合法命名空间 (user-sha256(wrong-token))，应返回 200
        wrong_token_ok = (status3 == 200)

        if no_token_ok and no_configkey_ok and wrong_token_ok:
            log('OK', '认证机制验证通过: 缺 Token→200(公共命名空间), 缺 Config-Key→400(必填校验), 错误 Token→独立命名空间(200)')
        else:
            log('WARN', f'认证验证部分异常: no_token={no_token_ok} (期望 200), no_configkey={no_configkey_ok} (期望 400), '
                       f'wrong_token={wrong_token_ok} (期望 200)')

        self.results['auth'] = {
            'status': 'ok' if (no_token_ok and no_configkey_ok) else 'warn',
            'no_token_allowed_public_ns': no_token_ok,
            'no_configkey_rejected': no_configkey_ok,
            'wrong_token_isolated_ns': wrong_token_ok
        }
        return True

    # --- 运行全部测试 ---
    def run_all(self):
        log_separator('WebHTV 观影记录同步测试 (Durable Object 后端)')
        log('INFO', f'目标: {self.base_url}')
        if self.token:
            log('INFO', f'Token: {self.token[:8]}...{self.token[-4:] if len(self.token) > 12 else "***"}')
        else:
            log('INFO', 'Token: (空 · 无 Token 模式 · 公共命名空间 user-no-token)')
        log('INFO', f'Config-Key: {self.config_key}')
        log('INFO', f'时间: {datetime.now().strftime("%Y-%m-%d %H:%M:%S")}')

        tests = [
            self.test_network,
            self.test_health,
            self.test_capabilities,
            self.test_status,
            self.test_push_progress,
            self.test_push_live,
            self.test_push_novel_progress,
            self.test_push_comic_progress,
            self.test_pull,
            self.test_delete,
            self.test_batch,
        ]

        passed = 0
        failed = 0
        for test in tests:
            if is_stopped():
                log('WARN', '测试已被用户停止')
                break
            try:
                if test():
                    passed += 1
                else:
                    failed += 1
            except Exception as e:
                log('ERR', f'测试异常: {e}')
                failed += 1

        # 认证测试单独运行（不阻断）
        if not is_stopped():
            try:
                self.test_auth()
                passed += 1
            except Exception:
                failed += 1

        self._print_report(passed, failed)
        return failed == 0

    def _print_report(self, passed, failed):
        log_separator('测试结果汇总')
        total = passed + failed
        log('INFO', f'总计: {total}  |  通过: {passed}  |  失败: {failed}')

        log_separator('各项明细')
        labels = {
            'network': '🌐 网络层',
            'health': '🏥 健康检查',
            'capabilities': '⚙️  服务器能力',
            'status': '📊 同步状态',
            'push_progress': '📮 写入进度',
            'push_live': '📺 直播流写入',
            'push_novel_progress': '📖 小说阅读进度',
            'push_comic_progress': '🎨 漫画阅读进度',
            'pull': '📥 拉取增量',
            'delete': '🗑️  删除墓碑',
            'batch': '📦 批量写入',
            'auth': '🔐 认证验证',
        }
        for key, label in labels.items():
            r = self.results.get(key, {})
            status = r.get('status', 'skip')
            if status == 'ok':
                icon = '✅'
                detail = self._detail_ok(key, r)
            elif status == 'warn':
                icon = '⚠️ '
                detail = '部分异常'
            elif status == 'fail':
                icon = '❌'
                detail = f"HTTP {r.get('http_code', '?')}"
            else:
                icon = '⏭️ '
                detail = '跳过'
            print(f'  {icon} {label:<14} {detail}')

        log_separator('异常诊断与处理建议')
        suggestions = self._build_suggestions()
        if not suggestions:
            print('  🎉 全部测试通过，无需处理。')
        else:
            for i, sug in enumerate(suggestions, 1):
                print(f'\n  [{i}] {sug["title"]}')
                for tip in sug.get('tips', []):
                    print(f'      {tip}')

    def _detail_ok(self, key, r):
        if key == 'network':
            parts = []
            if r.get('dns_ok'): parts.append('DNS✓')
            if r.get('tcp_ok'): parts.append('TCP✓')
            if r.get('ssl_ok'): parts.append('SSL✓')
            return ' '.join(parts) + (f'  ({r.get("cert_info", "")})' if r.get('cert_info') else '')
        if key == 'health':
            return 'HTTP 200'
        if key == 'capabilities':
            ps = '✓' if r.get('playback_sync') else '✗'
            return f"{r.get('server_mode', '?')}, playbackSync={ps}"
        if key == 'status':
            return f"items={r.get('items', 0)}, tombstones={r.get('tombstones', 0)}"
        if key == 'push_progress':
            return f"applied={r.get('applied', 0)}, skipped={r.get('skipped', 0)}"
        if key == 'push_live':
            return f"durationMs=0, applied={r.get('applied', 0)}"
        if key == 'push_novel_progress':
            return f"mediaType={r.get('media_type', '?')}, applied={r.get('applied', 0)}"
        if key == 'push_comic_progress':
            return f"mediaType={r.get('media_type', '?')}, applied={r.get('applied', 0)}"
        if key == 'pull':
            return f"changes={r.get('changes', 0)}, upserts={r.get('upserts', 0)}"
        if key == 'delete':
            return f"eventId={r.get('event_id', '?')[:20]}"
        if key == 'batch':
            return f"received={r.get('received', 0)}, applied={r.get('applied', 0)}"
        if key == 'auth':
            return '认证拦截正常'
        return 'OK'

    def _build_suggestions(self):
        suggestions = []

        # 网络层失败
        net = self.results.get('network', {})
        if net.get('status') == 'fail':
            tips = ['网络连接失败，无法到达 Worker 服务']
            if not net.get('dns_ok'):
                tips += ['DNS 解析失败:', '  1. 检查 Worker 域名拼写是否正确',
                         '  2. 尝试更换 DNS 服务器 (8.8.8.8 / 1.1.1.1)',
                         '  3. 确认 Worker 已成功部署到 Cloudflare']
            elif not net.get('tcp_ok'):
                tips += ['TCP 连接失败:', '  1. 检查本地防火墙是否拦截出站 443 端口',
                         '  2. 尝试使用代理或 VPN',
                         '  3. 确认网络环境是否限制访问 Cloudflare']
            elif not net.get('ssl_ok'):
                tips += ['SSL 握手失败:', '  1. 检查系统时间是否正确 (证书验证依赖时间)',
                         '  2. 更新系统根证书', '  3. 确认 Worker 已配置自定义域名或使用 *.workers.dev']
            suggestions.append({'title': '🌐 网络层连接失败', 'tips': tips})

        # 能力检查 — PLAYBACK_DO 未配置
        caps = self.results.get('capabilities', {})
        if caps.get('status') == 'ok' and not caps.get('playback_sync'):
            suggestions.append({
                'title': '⚙️  PLAYBACK_DO 未配置',
                'tips': [
                    '服务器 capabilities.playbackSync=false，观影记录同步不可用',
                    '原因: wrangler.toml 中缺少 PLAYBACK_DO Durable Object 绑定',
                    '解决方案:',
                    '  1. 在 wrangler.toml 中添加:',
                    '     [[durable_objects.bindings]]',
                    '     name = "PLAYBACK_DO"',
                    '     class_name = "WebHTVPlaybackSyncDO"',
                    '  2. 添加迁移:',
                    '     [[migrations]]',
                    '     tag = "v2"',
                    '     new_sqlite_classes = ["WebHTVPlaybackSyncDO"]',
                    '  3. 重新部署: npm run deploy',
                ]
            })

        # 直播流写入失败（durationMs=0 被拒绝）
        live = self.results.get('push_live', {})
        if live.get('status') == 'fail':
            suggestions.append({
                'title': '📺 直播流写入失败 (durationMs=0 被拒绝)',
                'tips': [
                    '服务端返回 HTTP 400，说明未支持直播流的 durationMs=0 场景',
                    '原因: 旧版 playback-sync.js 强制要求 durationMs > 0',
                    '解决方案: 重新部署包含直播流支持的 Worker',
                    '  1. 确认 src/playback-sync.js 中包含 isLiveStream 逻辑',
                    '  2. 重新部署: npm run deploy',
                    '  3. 部署后再次运行测试验证',
                ]
            })

        # 小说/漫画阅读进度写入失败 (mediaType 未识别 / v4 schema 未部署)
        novel = self.results.get('push_novel_progress', {})
        comic = self.results.get('push_comic_progress', {})
        if novel.get('status') == 'fail' or comic.get('status') == 'fail':
            tips = [
                '服务端未支持小说/漫画阅读记录同步 (v4 schema mediaType 字段)',
                '原因: 已部署的 playback-sync.js 版本过旧，缺少 normalizeMediaType / migrateV4MediaType',
                '解决方案:',
                '  1. 确认 src/playback-sync.js 中包含 MEDIA_TYPE_ALIASES / MEDIA_TYPE_KIND_ALIASES',
                '  2. 确认 wrangler.toml 已追加 v4 migration tag (new_sqlite_classes = [])',
                '  3. 重新部署: npm run deploy',
                '  4. 部署后再次运行测试，确认 byType 字段返回 novel/comic 计数',
            ]
            if novel.get('status') == 'fail':
                tips.append(f"  小说写入 HTTP {novel.get('http_code', '?')}")
            if comic.get('status') == 'fail':
                tips.append(f"  漫画写入 HTTP {comic.get('http_code', '?')}")
            suggestions.append({'title': '📖 小说/漫画阅读进度写入失败', 'tips': tips})

        # 小说/漫画已写入但 mediaType 未归一化 (写入成功但返回 video 而非 novel/comic)
        if novel.get('status') == 'ok' and novel.get('media_type') != 'novel':
            suggestions.append({
                'title': '📖 小说 mediaType 未正确归一化',
                'tips': ['写入返回 mediaType=' + str(novel.get('media_type')) + ' (期望 novel)',
                         '服务端 normalizeMediaType 未识别 mediaType 字段，请确认 playback-sync.js 已更新到 v4 版本']
            })
        if comic.get('status') == 'ok' and comic.get('media_type') != 'comic':
            suggestions.append({
                'title': '🎨 漫画 mediaType 未正确归一化 (kind=2 未转 comic)',
                'tips': ['写入返回 mediaType=' + str(comic.get('media_type')) + ' (期望 comic)',
                         '服务端 MEDIA_TYPE_KIND_ALIASES 未生效，请确认 playback-sync.js 已更新到 v4 版本']
            })

        # 认证问题
        auth = self.results.get('auth', {})
        if auth.get('status') in ('fail', 'warn'):
            if not auth.get('no_token_allowed_public_ns'):
                suggestions.append({
                    'title': '🔐 无 Token 模式未正常工作 (缺 Token 未返回 200)',
                    'tips': ['服务端 playback-sync.js 可能不是最新版本',
                             '重新部署 Worker: npm run deploy',
                             '或者服务端启用了严格的 Token 校验（旧版行为）']
                })
            if not auth.get('no_configkey_rejected'):
                suggestions.append({
                    'title': '🔐 缺少 Config-Key 时未返回 400',
                    'tips': ['服务端未正确拦截无 Config-Key 请求，检查 Worker 是否为最新版本']
                })

        # 写入失败
        push = self.results.get('push_progress', {})
        if push.get('status') == 'fail':
            code = push.get('http_code')
            if code == 400:
                suggestions.append({
                    'title': '📮 写入进度 400: 请求格式错误',
                    'tips': ['检查请求体是否包含必填字段: siteKey, vodId, vodName, episodeName, positionMs, durationMs',
                             '确认 configKey 与 X-WebHTV-Config-Key 头一致']
                })
            elif code == 401:
                suggestions.append({
                    'title': '📮 写入进度 401: Token 无效',
                    'tips': ['X-WebHTV-Token 不正确', '确认 token 与 App 中填写的完全一致']
                })
            elif code == 413:
                suggestions.append({
                    'title': '📮 写入进度 413: 请求体过大',
                    'tips': ['请求体超过 128 KiB 上限', '减少单次批量写入的记录数 (上限 100 条)']
                })

        # 拉取失败
        pull = self.results.get('pull', {})
        if pull.get('status') == 'fail':
            suggestions.append({
                'title': '📥 拉取增量失败',
                'tips': ['检查 Token 和 Config-Key 是否正确', '确认 PLAYBACK_DO 已配置']
            })

        # 删除失败
        dele = self.results.get('delete', {})
        if dele.get('status') == 'fail':
            code = dele.get('http_code')
            if code == 400:
                suggestions.append({
                    'title': '🗑️  删除墓碑 400: 请求格式错误',
                    'tips': ['删除请求必须包含: scope (item/site/all), deletedAt 或 timestamp',
                             'scope=item 时需要 historyKey 或 siteKey+vodId',
                             'scope=all 必须显式指定，不能省略']
                })

        return suggestions


# ============================================================
# GUI
# ============================================================

CONFIG_FILE = os.path.join(os.path.expanduser('~'), '.webhtv-sync-test', 'config.json')

def _compute_config_key(url):
    """SHA-256 计算点播接口 URL 的 configKey（与 App 端 PlaybackConfigIdentity.keyForUrl 一致）"""
    trimmed = (url or '').strip()
    if not trimmed:
        return ''
    return hashlib.sha256(trimmed.encode('utf-8')).hexdigest()

def _is_sha256_hex(value):
    return len(value) == 64 and all(c in '0123456789abcdef' for c in value.lower())

def _load_config():
    try:
        with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
            return json.load(f)
    except Exception:
        return {}

def _save_config(config):
    try:
        os.makedirs(os.path.dirname(CONFIG_FILE), exist_ok=True)
        with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
            json.dump(config, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def run_gui():
    """启动 GUI 界面"""
    global _LOG_SINK, _STOP_FLAG
    try:
        import tkinter as tk
        from tkinter import ttk, scrolledtext, filedialog, messagebox
    except ImportError:
        print('GUI 需要 tkinter，请使用命令行模式运行')
        return

    # ---- 颜色配置 ----
    BG = '#1e1e2e'
    BG_ENTRY = '#2a2a3e'
    FG = '#e4e6f4'
    FG_MUTED = '#9aa0c3'
    COLOR_MAP = {
        'OK': '#34d399',
        'ERR': '#f87171',
        'WARN': '#fbbf24',
        'INFO': FG,
        'DBG': '#6b7094',
        'SEP': '#4a5070',
    }
    RESULT_ICON = {'ok': '✅', 'fail': '❌', 'warn': '⚠️', 'skip': '⏭️'}

    # ---- 测试项定义 ----
    TEST_DEFS = [
        ('network', '🌐 网络连通性'),
        ('health', '🏥 健康检查'),
        ('capabilities', '⚙️ 服务器能力'),
        ('status', '📊 同步状态'),
        ('push_progress', '📮 写入进度'),
        ('push_novel_progress', '📖 小说阅读进度'),
        ('push_comic_progress', '🎨 漫画阅读进度'),
        ('pull', '📥 拉取增量'),
        ('delete', '🗑️ 删除墓碑'),
        ('batch', '📦 批量写入'),
        ('auth', '🔐 认证验证'),
    ]

    root = tk.Tk()
    root.title('WebHTV 观影记录同步测试 (DO 后端)')
    root.geometry('980x780')
    root.minsize(800, 600)
    root.configure(bg=BG)

    # ---- 样式 ----
    style = ttk.Style()
    try:
        style.theme_use('clam')
    except Exception:
        pass
    style.configure('TFrame', background=BG)
    style.configure('TLabel', background=BG, foreground=FG, font=('Microsoft YaHei UI', 10))
    style.configure('TLabelframe', background=BG, foreground=FG_MUTED, font=('Microsoft YaHei UI', 10, 'bold'))
    style.configure('TLabelframe.Label', background=BG, foreground=FG_MUTED)
    style.configure('TButton', font=('Microsoft YaHei UI', 10))
    style.configure('TCheckbutton', background=BG, foreground=FG, font=('Microsoft YaHei UI', 9))
    style.configure('Horizontal.TProgressbar', thickness=8)

    saved = _load_config()

    # ---- 顶部配置区 ----
    config_frame = ttk.LabelFrame(root, text='  连接配置  ', padding=12)
    config_frame.pack(fill='x', padx=12, pady=(10, 6))

    ttk.Label(config_frame, text='Worker URL:').grid(row=0, column=0, sticky='w', padx=(0, 8), pady=3)
    url_var = tk.StringVar(value=saved.get('url', ''))
    ttk.Entry(config_frame, textvariable=url_var, width=55).grid(row=0, column=1, columnspan=3, sticky='ew', pady=3)

    ttk.Label(config_frame, text='Token (可选):').grid(row=1, column=0, sticky='w', padx=(0, 8), pady=3)
    token_var = tk.StringVar(value=saved.get('token', ''))
    token_entry = ttk.Entry(config_frame, textvariable=token_var, width=55, show='*')
    token_entry.grid(row=1, column=1, columnspan=3, sticky='ew', pady=3)

    def toggle_token_visibility():
        token_entry.config(show='' if show_token_var.get() else '*')
    show_token_var = tk.BooleanVar(value=False)
    ttk.Checkbutton(config_frame, text='显示', variable=show_token_var, command=toggle_token_visibility).grid(row=1, column=4, padx=4)

    ttk.Label(config_frame, text='Config Key:').grid(row=2, column=0, sticky='w', padx=(0, 8), pady=3)
    configkey_var = tk.StringVar(value=saved.get('config_key', ''))
    configkey_entry = ttk.Entry(config_frame, textvariable=configkey_var, width=55)
    configkey_entry.grid(row=2, column=1, columnspan=3, sticky='ew', pady=3)

    # URL→Key 计算按钮
    def compute_key():
        raw = configkey_var.get().strip()
        if not raw:
            messagebox.showinfo('提示', '请在 Config Key 输入框中填入点播接口 URL')
            return
        if _is_sha256_hex(raw):
            messagebox.showinfo('提示', f'输入已经是 configKey:\n{raw}')
            return
        key = _compute_config_key(raw)
        configkey_var.set(key)
        messagebox.showinfo('计算成功', f'点播接口 URL 的 SHA-256:\n{key}')

    ttk.Button(config_frame, text='URL→SHA256', command=compute_key).grid(row=2, column=4, padx=4, pady=3)

    ttk.Label(config_frame, text='（输入点播接口URL点"URL→SHA256"自动计算，或直接粘贴64位configKey）',
              foreground=FG_MUTED, font=('Microsoft YaHei UI', 8)).grid(row=3, column=1, columnspan=4, sticky='w', pady=(0, 2))

    ttk.Label(config_frame, text='（Token 留空 = 无 Token 模式，使用公共命名空间 user-no-token，与其他无 Token 用户共享数据）',
              foreground='#fbbf24', font=('Microsoft YaHei UI', 8)).grid(row=4, column=1, columnspan=4, sticky='w', pady=(0, 2))

    save_cfg_var = tk.BooleanVar(value=True)
    ttk.Checkbutton(config_frame, text='保存配置到本地', variable=save_cfg_var).grid(row=3, column=0, sticky='w')

    config_frame.columnconfigure(1, weight=1)

    # ---- 测试选择区 ----
    test_frame = ttk.LabelFrame(root, text='  测试项目（勾选要运行的测试）  ', padding=10)
    test_frame.pack(fill='x', padx=12, pady=6)

    test_vars = {}
    for i, (key, label) in enumerate(TEST_DEFS):
        var = tk.BooleanVar(value=True)
        test_vars[key] = var
        r, c = divmod(i, 3)
        ttk.Checkbutton(test_frame, text=label, variable=var).grid(row=r, column=c, sticky='w', padx=8, pady=2)
    for c in range(3):
        test_frame.columnconfigure(c, weight=1, uniform='test')

    def select_all_tests():
        for v in test_vars.values(): v.set(True)
    def deselect_all_tests():
        for v in test_vars.values(): v.set(False)
    ttk.Button(test_frame, text='全选', command=select_all_tests).grid(row=3, column=0, sticky='w', padx=8, pady=(4, 0))
    ttk.Button(test_frame, text='全不选', command=deselect_all_tests).grid(row=3, column=1, sticky='w', padx=8, pady=(4, 0))

    # ---- 操作按钮区 ----
    btn_frame = ttk.Frame(root)
    btn_frame.pack(fill='x', padx=12, pady=4)

    progress_var = tk.DoubleVar(value=0)
    progress_label_var = tk.StringVar(value='就绪')

    def on_start():
        url = url_var.get().strip()
        token = token_var.get().strip()
        raw_ck = configkey_var.get().strip()

        if not url:
            messagebox.showwarning('提示', '请填写 Worker URL')
            return
        if not raw_ck:
            messagebox.showwarning('提示', '请填写 Config Key 或点播接口 URL')
            return
        # Token 留空时进入公共命名空间（无 Token 模式）
        if not token:
            if not messagebox.askyesno(
                '无 Token 模式确认',
                '未填写 Token，将使用公共命名空间 user-no-token（与其他无 Token 用户共享数据，无隔离）。\n\n是否继续？'
            ):
                return

        # 智能识别：如果是 URL 则自动计算
        if _is_sha256_hex(raw_ck):
            config_key = raw_ck.lower()
        elif raw_ck.startswith('http'):
            config_key = _compute_config_key(raw_ck)
            if not config_key:
                messagebox.showerror('错误', 'Config Key 计算失败')
                return
            configkey_var.set(config_key)
        else:
            config_key = raw_ck.lower()

        # 保存配置
        if save_cfg_var.get():
            _save_config({'url': url, 'token': token, 'config_key': config_key})

        # 确定要运行的测试
        selected = [k for k, v in test_vars.items() if v.get()]
        if not selected:
            messagebox.showwarning('提示', '请至少选择一个测试项')
            return

        # 清空日志和结果
        log_text.config(state='normal')
        log_text.delete('1.0', 'end')
        for widget in result_inner.winfo_children():
            widget.destroy()
        global _STOP_FLAG
        _STOP_FLAG = False
        start_btn.config(state='disabled')
        stop_btn.config(state='normal')
        progress_var.set(0)
        progress_label_var.set('准备中...')

        def worker():
            global _LOG_SINK
            tester = SyncTester(url, token, config_key)
            tester.results = {}

            # 构建 selected 测试方法映射
            method_map = {
                'network': tester.test_network,
                'health': tester.test_health,
                'capabilities': tester.test_capabilities,
                'status': tester.test_status,
                'push_progress': tester.test_push_progress,
                'push_novel_progress': tester.test_push_novel_progress,
                'push_comic_progress': tester.test_push_comic_progress,
                'pull': tester.test_pull,
                'delete': tester.test_delete,
                'batch': tester.test_batch,
                'auth': tester.test_auth,
            }

            log_separator('WebHTV 观影记录同步测试 (Durable Object 后端)')
            log('INFO', f'目标: {url}')
            masked = token[:8] + '...' + token[-4:] if len(token) > 12 else '***'
            log('INFO', f'Token: {masked}')
            log('INFO', f'Config-Key: {config_key}')
            log('INFO', f'时间: {datetime.now().strftime("%Y-%m-%d %H:%M:%S")}')
            log('INFO', f'选中测试: {len(selected)} 项')

            passed = 0
            failed = 0
            total = len(selected)
            for idx, key in enumerate(selected):
                if _STOP_FLAG:
                    log('WARN', '测试已被用户停止')
                    break
                label = dict(TEST_DEFS).get(key, key)
                progress_label_var.set(f'{idx+1}/{total}  {label}')
                root.after(0, lambda l=label, k=key: update_result_row(k, l, 'running', ''))
                try:
                    ok = method_map[key]()
                    if ok:
                        passed += 1
                    else:
                        failed += 1
                except Exception as e:
                    log('ERR', f'{label} 测试异常: {e}')
                    failed += 1
                r = tester.results.get(key, {})
                status = r.get('status', 'fail')
                detail = tester._detail_ok(key, r) if status == 'ok' else f"HTTP {r.get('http_code', '?')}"
                root.after(0, lambda k=key, l=label, s=status, d=detail: update_result_row(k, l, s, d))
                progress_var.set((idx + 1) / total * 100)

            if not _STOP_FLAG:
                progress_label_var.set(f'完成: {passed} 通过, {failed} 失败')
                log_separator('测试结果汇总')
                log('INFO', f'总计: {total}  |  通过: {passed}  |  失败: {failed}')
                log_separator('异常诊断与处理建议')
                suggestions = tester._build_suggestions()
                if not suggestions:
                    log('OK', '🎉 全部测试通过，无需处理。')
                else:
                    for i, sug in enumerate(suggestions, 1):
                        log('WARN', f'[{i}] {sug["title"]}')
                        for tip in sug.get('tips', []):
                            log('INFO', f'      {tip}')
            else:
                progress_label_var.set(f'已停止: {passed} 通过, {failed} 失败')

            root.after(0, lambda: (start_btn.config(state='normal'), stop_btn.config(state='disabled')))

        Thread(target=worker, daemon=True).start()

    def on_stop():
        global _STOP_FLAG
        _STOP_FLAG = True
        log('WARN', '正在停止测试...')
        stop_btn.config(state='disabled')

    def on_clear():
        log_text.config(state='normal')
        log_text.delete('1.0', 'end')
        for widget in result_inner.winfo_children():
            widget.destroy()
        progress_var.set(0)
        progress_label_var.set('就绪')

    def on_export():
        content = log_text.get('1.0', 'end')
        if not content.strip():
            messagebox.showinfo('提示', '日志为空')
            return
        path = filedialog.asksaveasfilename(
            defaultextension='.log',
            filetypes=[('日志文件', '*.log'), ('文本文件', '*.txt'), ('所有文件', '*.*')],
            initialfile=f'webhtv-sync-test-{datetime.now().strftime("%Y%m%d-%H%M%S")}.log'
        )
        if path:
            try:
                with open(path, 'w', encoding='utf-8') as f:
                    f.write(content)
                messagebox.showinfo('成功', f'日志已保存到:\n{path}')
            except Exception as e:
                messagebox.showerror('错误', f'保存失败: {e}')

    start_btn = ttk.Button(btn_frame, text='▶ 开始测试', command=on_start)
    start_btn.pack(side='left', padx=(0, 6))
    stop_btn = ttk.Button(btn_frame, text='⏹ 停止', command=on_stop, state='disabled')
    stop_btn.pack(side='left', padx=6)
    ttk.Button(btn_frame, text='🗑 清空', command=on_clear).pack(side='left', padx=6)
    ttk.Button(btn_frame, text='💾 导出日志', command=on_export).pack(side='left', padx=6)

    ttk.Label(btn_frame, textvariable=progress_label_var, foreground=FG_MUTED).pack(side='right', padx=4)

    # ---- 进度条 ----
    ttk.Progressbar(root, variable=progress_var, maximum=100, mode='determinate').pack(fill='x', padx=12, pady=2)

    # ---- 主体区域：左日志 + 右结果 ----
    body_frame = ttk.Frame(root)
    body_frame.pack(fill='both', expand=True, padx=12, pady=6)

    # 日志区
    log_frame = ttk.LabelFrame(body_frame, text='  日志  ', padding=4)
    log_frame.pack(side='left', fill='both', expand=True, padx=(0, 4))
    log_text = scrolledtext.ScrolledText(log_frame, wrap='word', font=('Consolas', 9),
                                          bg=BG_ENTRY, fg=FG, insertbackground=FG,
                                          selectbackground='#3a3a5e', borderwidth=0,
                                          padx=8, pady=6)
    log_text.pack(fill='both', expand=True)
    for tag, color in COLOR_MAP.items():
        log_text.tag_config(tag, foreground=color)

    # 结果区
    result_frame = ttk.LabelFrame(body_frame, text='  测试结果  ', padding=8)
    result_frame.pack(side='right', fill='y', padx=(4, 0))
    result_inner = ttk.Frame(result_frame)
    result_inner.pack(fill='both', expand=True)

    result_rows = {}

    def update_result_row(key, label, status, detail):
        if key in result_rows:
            for w in result_rows[key]:
                w.destroy()
        row_frame = ttk.Frame(result_inner)
        row_frame.pack(fill='x', pady=1)
        icon = {'ok': '✅', 'fail': '❌', 'warn': '⚠️', 'skip': '⏭️', 'running': '⏳'}.get(status, '❓')
        color = {'ok': '#34d399', 'fail': '#f87171', 'warn': '#fbbf24', 'running': '#60a5fa'}.get(status, FG)
        lbl_icon = tk.Label(row_frame, text=icon, bg=BG, fg=color, font=('Microsoft YaHei UI', 10), width=2)
        lbl_icon.pack(side='left')
        lbl_text = tk.Label(row_frame, text=label, bg=BG, fg=FG, font=('Microsoft YaHei UI', 9), anchor='w', width=14)
        lbl_text.pack(side='left')
        lbl_detail = tk.Label(row_frame, text=detail, bg=BG, fg=FG_MUTED, font=('Microsoft YaHei UI', 8), anchor='w')
        lbl_detail.pack(side='left', fill='x', expand=True)
        result_rows[key] = [row_frame, lbl_icon, lbl_text, lbl_detail]

    # ---- 日志回调 ----
    def gui_log_sink(level, line):
        log_text.config(state='normal')
        if level == 'SEP' and not line:
            log_text.insert('end', '\n')
        else:
            tag = level if level in COLOR_MAP else 'INFO'
            log_text.insert('end', line + '\n', tag)
        log_text.see('end')
        log_text.config(state='disabled')
        root.update_idletasks()

    _LOG_SINK = gui_log_sink

    # ---- 底部状态栏 ----
    status_bar = ttk.Frame(root)
    status_bar.pack(fill='x', padx=12, pady=(0, 8))
    ttk.Label(status_bar, text=f'配置文件: {CONFIG_FILE}', foreground=FG_MUTED,
              font=('Microsoft YaHei UI', 8)).pack(side='left')

    root.mainloop()
    _LOG_SINK = None


# ============================================================
# 主入口
# ============================================================

def _can_run_gui():
    """检测当前环境是否支持 tkinter GUI。
    Code Runner 的嵌入式输出面板不支持 tkinter，会导致 Tk() 崩溃。
    通过以下特征判断：
      1. sys.stdout 不是 TTY（Code Runner 的输出面板不是真正的终端）
      2. 环境变量中存在 Code Runner 特征
      3. 尝试创建隐藏的 Tk 窗口，如果失败则降级
    """
    # Code Runner 环境变量特征
    code_runner_vars = ['VSCODE_OUTPUT', 'CODE_RUNNER', 'RUN_CODE']
    for var in code_runner_vars:
        if os.environ.get(var):
            return False

    # 非 Windows 环境下检查 DISPLAY
    if sys.platform != 'win32' and not os.environ.get('DISPLAY'):
        return False

    # 尝试创建隐藏 Tk 窗口验证
    try:
        import tkinter as tk
        root = tk.Tk()
        root.withdraw()
        root.update_idletasks()
        root.destroy()
        return True
    except Exception:
        return False


def _interactive_cli_mode():
    """交互式 CLI 模式：在终端中逐项询问配置，然后运行测试"""
    print('╔══════════════════════════════════════════════════════════╗')
    print('║     WebHTV 观影记录同步测试 (交互式 CLI 模式)            ║')
    print('╚══════════════════════════════════════════════════════════╝')
    print()
    print('提示：GUI 不可用，已自动降级为交互式命令行模式。')
    print('      如需 GUI，请使用 "Run Python File" 按钮或在终端中运行。')
    print()

    # 读取已保存的配置作为默认值
    saved = _load_config()
    default_url = saved.get('url', 'https://webhtv-remote-cloudflare.<subdomain>.workers.dev')
    default_token = saved.get('token', '')
    default_config_key = saved.get('config_key', '')

    # 1. Worker URL
    url = input(f'  Worker URL [{default_url}]: ').strip() or default_url
    if not url.startswith('http'):
        print('  ✗ URL 必须以 http:// 或 https:// 开头')
        sys.exit(1)

    # 2. Token（可选，留空使用公共命名空间）
    token = input(f'  Token (可选，留空=无Token模式) [{"*" * len(default_token) if default_token else ""}]: ').strip()
    if not token:
        print('  → 未填写 Token，将使用公共命名空间 user-no-token（与其他无 Token 用户共享数据）')
        token = ''

    # 3. Config Key
    config_key = input(f'  Config Key (或点播接口 URL) [{default_config_key}]: ').strip()
    if not config_key:
        print('  ✗ Config Key 不能为空')
        sys.exit(1)

    # 如果输入的是 URL，自动计算 SHA-256
    if not _is_sha256_hex(config_key) and config_key.startswith('http'):
        original = config_key
        config_key = _compute_config_key(config_key)
        print(f'  → 已从 URL 计算 configKey: {config_key}')

    # 保存配置
    try:
        _save_config({'url': url, 'token': token, 'config_key': config_key})
    except Exception:
        pass

    print()
    print(f'  URL:       {url}')
    if token:
        print(f'  Token:     {token[:8]}{"*" * (len(token) - 8) if len(token) > 8 else ""}')
    else:
        print(f'  Token:     (空 · 无 Token 模式 · 公共命名空间)')
    print(f'  ConfigKey: {config_key}')
    print()

    # 确认
    confirm = input('  开始测试？[Y/n]: ').strip().lower()
    if confirm in ('n', 'no'):
        print('  已取消。')
        return

    print()
    tester = SyncTester(url, token, config_key.lower())
    ok = tester.run_all()
    sys.exit(0 if ok else 1)


def main():
    parser = argparse.ArgumentParser(description='WebHTV 观影记录同步测试 (Durable Object 后端)')
    parser.add_argument('--url', help='Worker URL, 例如 https://your-worker.workers.dev')
    parser.add_argument('--token', default='', help='访问令牌 (X-WebHTV-Token)，可选；留空使用公共命名空间 user-no-token')
    parser.add_argument('--config-key', help='点播接口标识 (X-WebHTV-Config-Key) 或点播接口 URL')
    parser.add_argument('--cli', action='store_true', help='强制使用命令行模式（默认无参数时启动 GUI）')
    args = parser.parse_args()

    # 无任何参数时：尝试 GUI，不可用时降级为交互式 CLI
    if not args.cli and not args.url and not args.token and not args.config_key:
        if _can_run_gui():
            run_gui()
        else:
            _interactive_cli_mode()
        return

    # 指定了 --cli 或任何连接参数时走命令行模式（Token 可选）
    if not args.url or not args.config_key:
        parser.error('CLI 模式需要 --url, --config-key 参数（--token 可选，留空使用公共命名空间；无参数运行将启动 GUI）')

    config_key = args.config_key
    if not _is_sha256_hex(config_key) and config_key.startswith('http'):
        config_key = _compute_config_key(config_key)
        print(f'已从 URL 计算 configKey: {config_key}')

    tester = SyncTester(args.url, args.token, config_key.lower())
    ok = tester.run_all()
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
