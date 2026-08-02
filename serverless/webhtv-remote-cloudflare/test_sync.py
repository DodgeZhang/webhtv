#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WebHTV 观影记录同步测试脚本 — 适配 webhtv-remote-cloudflare (Durable Object + SQLite 后端)

与旧版 KV 后端 (webhtv-playback-sync-cloudflare) 的关键差异：
  1. 认证：X-WebHTV-Token (必填) + X-WebHTV-Config-Key (必填)
     token 由用户自行生成 (openssl rand -hex 32)，不写入 Worker 环境变量
  2. API 统一端点：/api/playback/sync
     - GET  → 按游标拉取增量进度和删除墓碑
     - POST → 写入进度 (playback.progress) 或删除墓碑 (playback.deleted)
  3. 状态端点：GET /api/playback/sync/status
  4. 删除方式：POST /api/playback/sync 发送 {event:"playback.deleted", scope:"item|site|all", ...}
     而非旧版的 DELETE /api/playback/records
  5. 分页：基于单调游标 since/nextSince，而非 maxItems
  6. 存储引擎：Durable Object 内置 SQLite，无需 KV 绑定

使用方式:
  python test_sync.py --url https://your-worker.workers.dev --token YOUR_TOKEN --config-key YOUR_CONFIG_KEY
  python test_sync.py --url ... --token ... --config-key ... --gui    # 启动 GUI
"""

import argparse
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

def log(level, msg):
    ts = datetime.now().strftime('%H:%M:%S.') + f'{int(datetime.now().microsecond / 1000):03d}'
    icons = {'INFO': 'INFO', 'OK': '  OK', 'ERR': ' ERR', 'WARN': 'WARN', 'DBG': ' DBG'}
    print(f'[{ts}] [{icons.get(level, level):>6}] {msg}')

def log_separator(title=''):
    print()
    print('=' * 70)
    if title:
        print(f'  {title}')
        print('=' * 70)


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
            'X-WebHTV-Token': self.token,
            'X-WebHTV-Config-Key': self.config_key,
        }
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
        # 不带 Token
        log('INFO', '测试 1: 不带 X-WebHTV-Token')
        status, _, body = http_request(
            self._url('/api/playback/sync/status'), 'GET',
            headers={'X-WebHTV-Config-Key': self.config_key}, timeout=10
        )
        log('DBG', f'HTTP {status}: {body[:200]}')
        no_token_ok = (status == 401)

        # 不带 Config-Key
        log('INFO', '测试 2: 不带 X-WebHTV-Config-Key')
        status2, _, body2 = http_request(
            self._url('/api/playback/sync/status'), 'GET',
            headers={'X-WebHTV-Token': self.token}, timeout=10
        )
        log('DBG', f'HTTP {status2}: {body2[:200]}')
        no_configkey_ok = (status2 == 400)

        # 错误 Token
        log('INFO', '测试 3: 错误的 Token')
        status3, _, body3 = http_request(
            self._url('/api/playback/sync/status'), 'GET',
            headers={'X-WebHTV-Token': 'wrong-token', 'X-WebHTV-Config-Key': self.config_key},
            timeout=10
        )
        log('DBG', f'HTTP {status3}: {body3[:200]}')
        wrong_token_ok = (status3 in (401, 400))

        if no_token_ok and no_configkey_ok and wrong_token_ok:
            log('OK', '认证机制验证通过: 缺 Token→401, 缺 Config-Key→400, 错误 Token→拒绝')
        else:
            log('WARN', f'认证验证部分异常: no_token={no_token_ok}, no_configkey={no_configkey_ok}, '
                       f'wrong_token={wrong_token_ok}')

        self.results['auth'] = {
            'status': 'ok' if (no_token_ok and no_configkey_ok) else 'warn',
            'no_token_rejected': no_token_ok,
            'no_configkey_rejected': no_configkey_ok,
            'wrong_token_rejected': wrong_token_ok
        }
        return True

    # --- 运行全部测试 ---
    def run_all(self):
        log_separator('WebHTV 观影记录同步测试 (Durable Object 后端)')
        log('INFO', f'目标: {self.base_url}')
        log('INFO', f'Token: {self.token[:8]}...{self.token[-4:] if len(self.token) > 12 else "***"}')
        log('INFO', f'Config-Key: {self.config_key}')
        log('INFO', f'时间: {datetime.now().strftime("%Y-%m-%d %H:%M:%S")}')

        tests = [
            self.test_network,
            self.test_health,
            self.test_capabilities,
            self.test_status,
            self.test_push_progress,
            self.test_pull,
            self.test_delete,
            self.test_batch,
        ]

        passed = 0
        failed = 0
        for test in tests:
            try:
                if test():
                    passed += 1
                else:
                    failed += 1
            except Exception as e:
                log('ERR', f'测试异常: {e}')
                failed += 1

        # 认证测试单独运行（不阻断）
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

        # 认证问题
        auth = self.results.get('auth', {})
        if auth.get('status') in ('fail', 'warn'):
            if not auth.get('no_token_rejected'):
                suggestions.append({
                    'title': '🔐 缺少 Token 时未返回 401',
                    'tips': ['服务端未正确拦截无 Token 请求，检查 Worker 是否正常部署']
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
# GUI (可选)
# ============================================================

def run_gui(tester):
    """启动简单的 GUI 界面 (使用 tkinter)"""
    try:
        import tkinter as tk
        from tkinter import ttk, scrolledtext, messagebox
    except ImportError:
        log('ERR', 'GUI 需要 tkinter，请使用命令行模式运行')
        return

    root = tk.Tk()
    root.title('WebHTV 观影记录同步测试 (DO 后端)')
    root.geometry('900x700')

    # 配置区
    config_frame = ttk.LabelFrame(root, text='配置', padding=10)
    config_frame.pack(fill='x', padx=10, pady=5)

    ttk.Label(config_frame, text='Worker URL:').grid(row=0, column=0, sticky='w', padx=5)
    url_entry = ttk.Entry(config_frame, width=50)
    url_entry.grid(row=0, column=1, padx=5, pady=3)

    ttk.Label(config_frame, text='Token:').grid(row=1, column=0, sticky='w', padx=5)
    token_entry = ttk.Entry(config_frame, width=50, show='*')
    token_entry.grid(row=1, column=1, padx=5, pady=3)

    ttk.Label(config_frame, text='Config-Key:').grid(row=2, column=0, sticky='w', padx=5)
    configkey_entry = ttk.Entry(config_frame, width=50)
    configkey_entry.grid(row=2, column=1, padx=5, pady=3)

    # 日志区
    log_frame = ttk.LabelFrame(root, text='日志', padding=5)
    log_frame.pack(fill='both', expand=True, padx=10, pady=5)
    log_text = scrolledtext.ScrolledText(log_frame, wrap='word', font=('Consolas', 10))
    log_text.pack(fill='both', expand=True)

    def gui_log(level, msg):
        ts = datetime.now().strftime('%H:%M:%S.')
        ts += f'{int(datetime.now().microsecond / 1000):03d}'
        log_text.insert('end', f'[{ts}] [{level:>6}] {msg}\n')
        log_text.see('end')

    def run_tests():
        url = url_entry.get().strip()
        token = token_entry.get().strip()
        ck = configkey_entry.get().strip().lower()
        if not url or not token or not ck:
            messagebox.showwarning('提示', '请填写所有配置项')
            return
        log_text.delete('1.0', 'end')
        run_btn.config(state='disabled')
        root.update()

        # 重写 log 函数
        import builtins
        original_print = builtins.print

        def capture_print(*args, **kwargs):
            text = ' '.join(str(a) for a in args)
            log_text.insert('end', text + '\n')
            log_text.see('end')
            root.update()

        builtins.print = capture_print
        try:
            t = SyncTester(url, token, ck)
            t.run_all()
        except Exception as e:
            gui_log('ERR', f'测试异常: {e}')
        finally:
            builtins.print = original_print
            run_btn.config(state='normal')

    btn_frame = ttk.Frame(root)
    btn_frame.pack(fill='x', padx=10, pady=5)
    run_btn = ttk.Button(btn_frame, text='▶ 开始测试', command=run_tests)
    run_btn.pack(side='left', padx=5)

    root.mainloop()


# ============================================================
# 主入口
# ============================================================

def main():
    parser = argparse.ArgumentParser(description='WebHTV 观影记录同步测试 (Durable Object 后端)')
    parser.add_argument('--url', required=True, help='Worker URL, 例如 https://your-worker.workers.dev')
    parser.add_argument('--token', required=True, help='访问令牌 (X-WebHTV-Token)')
    parser.add_argument('--config-key', required=True, help='点播接口标识 (X-WebHTV-Config-Key)')
    parser.add_argument('--gui', action='store_true', help='启动 GUI 界面')
    args = parser.parse_args()

    tester = SyncTester(args.url, args.token, args.config_key.lower())

    if args.gui:
        run_gui(tester)
    else:
        ok = tester.run_all()
        sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
