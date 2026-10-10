#!/usr/bin/env python3
"""同源反代：既发 :web 的静态产物，又把 /api/* 转发到一个后端。

这是 Web 端**真实部署形状**的最小可运行版本（生产上这一段由 nginx 提供）：
浏览器直连后端（campus-api / serve 的 :server）是跨源请求，它们刻意不给零鉴权端点发 CORS 头，
所以「页面与数据同源」不是优化，是 Web 端拿得到数据的前提。

## 两个后端（--backend）

| 后端 | 端口 | 谁在答 |
|---|---|---|
| campus-api（默认，现状） | `3099` | 只读的旧形状（`code == 0` 成功、非 0 是业务码） |
| serve 模式的 :server | `8123` | `docs/api-contract.md` §4/§5 的新契约（`code == HTTP 状态码`），**同时托管 :web 产物** |

⚠️ `--backend 8123` 这一档只在这个脚本当**反代**时有意义（例如 :server 换了端口、或你想把
产物目录指到别处）。serve 模式的**正常用法**根本不用它：`:server` 自己就托管 `:web` 的产物
（`--dist`），直接 `./gradlew :server:run` 就够了。

## 用法

    python3 web/tools/serve-same-origin.py [--port 8123] [--dist DIR] [--backend 3099]
    python3 web/tools/serve-same-origin.py [port] [dist_dir]        # 旧写法，仍然可用

`--port` / `--dist` 的默认值与旧写法一字不差（端口 8123、产物目录 `web/build/dist/wasmJs/productionExecutable`）：
它现在的行为就是「同源反代到 campus-api」，`--backend` 只多给一个开关，不改现状。
"""
import argparse
import http.server
import socketserver
import sys
import urllib.error
import urllib.request
from functools import partial
from pathlib import Path

# 默认后端：campus-api（现状）。serve 模式传 --backend 8123。
DEFAULT_BACKEND = 3099
DEFAULT_PORT = 8123
DEFAULT_DIST = Path(__file__).resolve().parents[1] / "build/dist/wasmJs/productionExecutable"


class Handler(http.server.SimpleHTTPRequestHandler):
    def _proxy(self):
        body = None
        if "Content-Length" in self.headers:
            body = self.rfile.read(int(self.headers["Content-Length"]))
        req = urllib.request.Request(API + self.path, data=body, method=self.command)
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                data = resp.read()
                self.send_response(resp.status)
                for k, v in resp.headers.items():
                    if k.lower() not in ("transfer-encoding", "connection"):
                        self.send_header(k, v)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
        except urllib.error.HTTPError as e:  # 上游 4xx/5xx 也照原样转发
            data = e.read()
            self.send_response(e.code)
            for k, v in e.headers.items():
                if k.lower() not in ("transfer-encoding", "connection", "content-length"):
                    self.send_header(k, v)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception as e:  # noqa: BLE001 - 探针要看到失败原文
            msg = f"proxy error: {e}".encode()
            self.send_response(502)
            self.send_header("Content-Length", str(len(msg)))
            self.end_headers()
            self.wfile.write(msg)

    def do_GET(self):  # noqa: N802
        if self.path.startswith("/api/"):
            return self._proxy()
        return super().do_GET()

    def do_POST(self):  # noqa: N802
        if self.path.startswith("/api/"):
            return self._proxy()
        self.send_error(405)

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def parse_args(argv):
    """位置参数（旧写法）与 `--port/--dist/--backend` 两种都收，位置参数优先。"""
    parser = argparse.ArgumentParser(add_help=True, description="serve :web 产物 + 同源反代 /api/*")
    parser.add_argument("port_pos", nargs="?", type=int, help="（旧写法）监听端口，默认 %d" % DEFAULT_PORT)
    parser.add_argument("dist_pos", nargs="?", help="（旧写法）静态产物目录")
    parser.add_argument("--port", type=int, default=None, help="监听端口，默认 %d" % DEFAULT_PORT)
    parser.add_argument("--dist", default=None, help="静态产物目录，默认 %s" % DEFAULT_DIST)
    parser.add_argument(
        "--backend",
        type=int,
        default=DEFAULT_BACKEND,
        help="要反代的后端端口，默认 %d（campus-api）；serve 模式的 :server 是 8123" % DEFAULT_BACKEND,
    )
    parser.add_argument("--backend-host", default="127.0.0.1", help="后端主机，默认 127.0.0.1")
    args = parser.parse_args(argv)

    port = args.port_pos if args.port_pos is not None else (args.port if args.port is not None else DEFAULT_PORT)
    dist = args.dist_pos if args.dist_pos is not None else (args.dist if args.dist is not None else str(DEFAULT_DIST))
    if not 0 < port < 65536:
        parser.error("端口要落在 1..65535，给的是 %r" % port)
    if not 0 < args.backend < 65536:
        parser.error("--backend 要落在 1..65535，给的是 %r" % args.backend)
    return port, dist, "http://%s:%d" % (args.backend_host, args.backend)


if __name__ == "__main__":
    PORT, DIST, API = parse_args(sys.argv[1:])
    handler = partial(Handler, directory=DIST)
    with Server(("127.0.0.1", PORT), handler) as httpd:
        print(f"serving {DIST} on http://127.0.0.1:{PORT}  (/api/* -> {API})", flush=True)
        if not Path(DIST).is_dir():
            print(f"⚠️ {DIST} 不存在：先跑 ./gradlew :web:wasmJsBrowserDistribution（约 18 分钟）", flush=True)
        httpd.serve_forever()
