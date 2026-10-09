#!/usr/bin/env python3
"""同源反代：既发 :web 的静态产物，又把 /api/* 转发到 campus-api（127.0.0.1:3099）。

这是 Web 端**真实部署形状**的最小可运行版本（生产上这一段由 nginx 提供）：
浏览器直连 127.0.0.1:3099 是跨源请求，campus-api 刻意不给零鉴权端点发 CORS 头，
所以「页面与数据同源」不是优化，是 Web 端拿得到数据的前提。

用法：python3 web/tools/serve-same-origin.py [port] [dist_dir]
"""
import http.server
import socketserver
import sys
import urllib.error
import urllib.request
from functools import partial
from pathlib import Path

API = "http://127.0.0.1:3099"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8123
DIST = sys.argv[2] if len(sys.argv) > 2 else str(
    Path(__file__).resolve().parents[1] / "build/dist/wasmJs/productionExecutable"
)


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


if __name__ == "__main__":
    handler = partial(Handler, directory=DIST)
    with Server(("127.0.0.1", PORT), handler) as httpd:
        print(f"serving {DIST} on http://127.0.0.1:{PORT}  (/api/* -> {API})", flush=True)
        httpd.serve_forever()
