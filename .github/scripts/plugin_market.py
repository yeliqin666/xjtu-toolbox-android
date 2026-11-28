#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把插件包收进在线市场索引（dzhz-server 的 plugin-market.json）。

整个流程只用标准库，签名校验借 openssl（runner 自带，和 sync-pkxdt-market.yml 一脉相承）：

    fetch_package(url)          下载（只认 https、挡内网地址、限制大小）
    inspect_package(payload)    解包、验签、逐条核对 files[] 清单
    update_market(...)          读索引 → 比对版本 → 改写 → 提交

改写索引走「走位替换」：只重写目标插件那一条和根级 tags，其余缩进、空行保持原样，
免得每次更新都把整份 json 刷一遍。提交用 Git Data API，索引和钥匙钉表一次提交。
"""

from __future__ import annotations

import base64
import binascii
import copy
import hashlib
import io
import ipaddress
import json
import re
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Optional

BEIJING = timezone(timedelta(hours=8))

MAX_ZIP_BYTES = 32 * 1024 * 1024
MAX_UNPACKED_BYTES = 32 * 1024 * 1024
MAX_ENTRIES = 64
MAX_MANIFEST_BYTES = 256 * 1024
MAX_REDIRECTS = 5
FETCH_TIMEOUT = 30

MARKET_SCHEMA = 1
KEYS_SCHEMA = 1
SUPPORTED_TYPES = ("dex", "web")
DEX_API_VERSION = 2

SEMVER_RE = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
PLUGIN_ID_RE = re.compile(r"^[a-z0-9][a-z0-9.-]{1,63}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
ORIGIN_RE = re.compile(r"^https?://[A-Za-z0-9.-]+(?::\d{1,5})?$")
URL_RE = re.compile(r"https?://[^\s<>()\[\]\"'`]+")
HEADING_RE = re.compile(r"^(#{1,6})[ \t]*(.+?)[ \t]*$", re.MULTILINE)
LINK_HEADINGS = ("插件直链", "插件下载直链", "下载直链", "插件下载地址")


class MarketError(Exception):
    """能直接回帖给用户的错误。"""


class Conflict(Exception):
    """提交时分支被别人推进了，值得重读重试。"""


@dataclass
class Package:
    manifest: dict[str, Any]
    size: int
    sha256: str
    key_sha256: str
    files: list[dict[str, Any]] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


@dataclass
class Plan:
    entry: dict[str, Any]
    raw: str
    keys: dict[str, str]
    keys_changed: bool
    changes: list[str]
    new_tags: list[str]
    key_recorded: bool
    unchanged: bool
    message: str


@dataclass
class UpdateResult:
    entry: dict[str, Any]
    package: Package
    previous: Optional[dict[str, Any]]
    changes: list[str]
    new_tags: list[str]
    key_recorded: bool
    unchanged: bool
    dry_run: bool
    commit_url: Optional[str]


def log(message: str) -> None:
    print(message, flush=True)


# --------------------------------------------------------------------------
# 链接与文本
# --------------------------------------------------------------------------


def clean_links(text: str) -> list[str]:
    """挑出文本里的 https 链接，zip / Release 直链排前面。"""
    found: list[str] = []
    for raw in URL_RE.findall(text):
        url = raw.rstrip(".,;:!?，。；：！？）】、>")
        if not url.lower().startswith("https://"):
            continue
        if url not in found:
            found.append(url)
    preferred = [u for u in found if u.split("?")[0].lower().endswith(".zip") or "/releases/download/" in u]
    return preferred + [u for u in found if u not in preferred]


def _normalize_headings(body: str) -> str:
    """把「**插件直链**」这种整行加粗的小标题也当成标题。"""
    names = "|".join(re.escape(name) for name in LINK_HEADINGS)
    return re.sub(rf"(?m)^[ \t]*\*\*({names})\*\*[ \t]*$", r"## \1", body or "")


def section_links(body: str) -> list[str]:
    """取「## 插件直链」这类小标题下面的链接，找不到返回空列表。"""
    body = _normalize_headings(body)
    headings = list(HEADING_RE.finditer(body or ""))
    for index, heading in enumerate(headings):
        title = heading.group(2).strip().strip("*`_ ").rstrip("：:").strip()
        if title not in LINK_HEADINGS:
            continue
        start = heading.end()
        end = headings[index + 1].start() if index + 1 < len(headings) else len(body)
        links = clean_links(body[start:end])
        if links:
            return links
    # 退一步：「插件直链：https://…」这种同行的，或者「插件直链：」后面跟一行的
    lines = body.splitlines()
    for index, line in enumerate(lines):
        stripped = line.strip().strip("*`_ ")
        for name in LINK_HEADINGS:
            if not stripped.startswith(name):
                continue
            links = clean_links(stripped[len(name):].lstrip("：: "))
            if links:
                return links
            for following in lines[index + 1:]:
                if not following.strip():
                    break
                links = clean_links(following)
                if links:
                    return links
    return []


def extract_links(text: str) -> list[str]:
    return clean_links(text or "")


def human_size(size: int) -> str:
    if size < 1024:
        return f"{size} B"
    if size < 1024 * 1024:
        return f"{size / 1024:.1f} KB"
    return f"{size / 1024 / 1024:.2f} MB"


# --------------------------------------------------------------------------
# 下载
# --------------------------------------------------------------------------


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def _host_is_public(host: str) -> bool:
    try:
        infos = socket.getaddrinfo(host, 443, proto=socket.IPPROTO_TCP)
    except socket.gaierror as error:
        raise MarketError(f"插件直链的域名解析不出来：{host}（{error}）") from None
    if not infos:
        raise MarketError(f"插件直链的域名解析不出来：{host}")
    for info in infos:
        address = str(info[4][0]).split("%", 1)[0]
        try:
            ip = ipaddress.ip_address(address)
        except ValueError:
            return False
        if not ip.is_global:
            return False
    return True


def fetch_bytes(url: str, limit: int) -> bytes:
    """只认 https 的公网地址；自己处理跳转，每一跳都重新检查。"""
    current = url
    for _ in range(MAX_REDIRECTS + 1):
        parts = urllib.parse.urlsplit(current)
        if parts.scheme != "https" or not parts.hostname:
            raise MarketError(f"插件直链必须是 https 地址：{current}")
        if not _host_is_public(parts.hostname):
            raise MarketError(f"插件直链指向内网 / 保留地址，拒绝下载：{parts.hostname}")
        request = urllib.request.Request(
            current, headers={"User-Agent": "dzhz-plugin-market-bot", "Accept": "application/zip,*/*"}
        )
        try:
            with _OPENER.open(request, timeout=FETCH_TIMEOUT) as response:
                payload = response.read(limit + 1)
            break
        except urllib.error.HTTPError as error:
            location = error.headers.get("Location") if error.code in (301, 302, 303, 307, 308) else None
            if location:
                current = urllib.parse.urljoin(current, location)
                continue
            raise MarketError(f"下载插件包失败（HTTP {error.code}）：{current}") from None
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise MarketError(f"下载插件包失败：{type(error).__name__}: {error}") from None
    else:
        raise MarketError("插件直链跳转次数太多，放弃")

    if len(payload) > limit:
        raise MarketError(f"插件包超过 {limit // (1024 * 1024)} MB 上限")
    return payload


# --------------------------------------------------------------------------
# 校验插件包
# --------------------------------------------------------------------------


def _safe_entry_name(name: str) -> bool:
    if not name or name.startswith("/") or "\\" in name or "\0" in name:
        return False
    return all(part not in ("", ".", "..") for part in name.split("/"))


def _require_text(value: Any, field_name: str, limit: int = 0) -> str:
    if not isinstance(value, str):
        raise MarketError(f"清单里的 `{field_name}` 必须是字符串")
    text = " ".join(value.split()) if field_name in ("name", "summary") else value.strip()
    if not text:
        raise MarketError(f"清单里的 `{field_name}` 是空的")
    if limit and len(text) > limit:
        raise MarketError(f"清单里的 `{field_name}` 太长（{len(text)} > {limit} 字）")
    return text


def _require_int(value: Any, field_name: str, minimum: int = 0) -> int:
    if type(value) is not int or value < minimum:
        raise MarketError(f"清单里的 `{field_name}` 必须是 ≥ {minimum} 的整数")
    return value


def _require_https(value: Any, field_name: str) -> str:
    text = _require_text(value, field_name)
    if not text.startswith("https://"):
        raise MarketError(f"清单里的 `{field_name}` 必须是 https 地址：{text}")
    return text


def _decode_base64(value: Any, field_name: str, limit: int) -> bytes:
    if not isinstance(value, str) or not value.strip():
        raise MarketError(f"`{field_name}` 是空的")
    try:
        data = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError):
        raise MarketError(f"`{field_name}` 不是合法的 Base64") from None
    if not 0 < len(data) <= limit:
        raise MarketError(f"`{field_name}` 长度不对（{len(data)} 字节）")
    return data


def _openssl(*args: str) -> str:
    try:
        result = subprocess.run(["openssl", *args], capture_output=True, text=True)
    except FileNotFoundError:
        raise MarketError("这台机器上没有 openssl，没法校验签名") from None
    if result.returncode != 0:
        raise MarketError("签名校验失败：dzhz.plugin.sig 和 dzhz.plugin.json 对不上，或者钥匙不是 P-256")
    return result.stdout


def verify_signature(content: bytes, signature: bytes, key_der: bytes) -> None:
    """ECDSA 验签，并且确认钥匙就是 P-256（宿主只认这个曲线）。"""
    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        (base / "key.der").write_bytes(key_der)
        (base / "sig").write_bytes(signature)
        (base / "manifest").write_bytes(content)
        info = _openssl("pkey", "-pubin", "-inform", "DER", "-in", str(base / "key.der"), "-text", "-noout")
        if "prime256v1" not in info and "P-256" not in info:
            raise MarketError("签名钥匙不是 ECDSA P-256，宿主不认")
        _openssl("pkey", "-pubin", "-inform", "DER", "-in", str(base / "key.der"),
                 "-pubout", "-out", str(base / "key.pem"))
        _openssl("dgst", "-sha256", "-verify", str(base / "key.pem"), "-signature",
                 str(base / "sig"), str(base / "manifest"))


def inspect_package(payload: bytes) -> Package:
    if not payload:
        raise MarketError("插件包是空的")
    if len(payload) > MAX_ZIP_BYTES:
        raise MarketError(f"插件包超过 {MAX_ZIP_BYTES // (1024 * 1024)} MB 上限")
    if not payload.startswith(b"PK"):
        raise MarketError("下载到的不是 zip 包（直链可能指向了网页）")

    try:
        bundle = zipfile.ZipFile(io.BytesIO(payload))
    except zipfile.BadZipFile:
        raise MarketError("zip 包损坏，解不开") from None

    with bundle:
        infos = bundle.infolist()
        names = [info.filename for info in infos]
        if len(infos) > MAX_ENTRIES:
            raise MarketError(f"包内条目太多（{len(infos)} > {MAX_ENTRIES}）")
        if len(set(names)) != len(names):
            raise MarketError("包内有重名条目")
        for info in infos:
            if not _safe_entry_name(info.filename):
                raise MarketError(f"包内条目路径不安全：{info.filename}")
            if info.is_dir():
                raise MarketError(f"包内不允许目录条目：{info.filename}")
        if sum(info.file_size for info in infos) > MAX_UNPACKED_BYTES:
            raise MarketError(f"解压后体积超过 {MAX_UNPACKED_BYTES // (1024 * 1024)} MB 上限")
        for required in ("dzhz.plugin.json", "dzhz.plugin.sig"):
            if required not in names:
                raise MarketError(f"包里缺少 {required}")

        content = bundle.read("dzhz.plugin.json")
        if len(content) > MAX_MANIFEST_BYTES:
            raise MarketError("dzhz.plugin.json 太大")
        try:
            manifest = json.loads(content)
        except json.JSONDecodeError:
            raise MarketError("dzhz.plugin.json 不是合法 JSON") from None
        if not isinstance(manifest, dict):
            raise MarketError("dzhz.plugin.json 的顶层必须是对象")

        manifest_version = manifest.get("manifestVersion", 1)
        if manifest_version != 1:
            raise MarketError(f"不认识的 manifestVersion：{manifest_version}")

        plugin_id = manifest.get("id")
        if not isinstance(plugin_id, str) or not PLUGIN_ID_RE.match(plugin_id):
            raise MarketError(f"插件 id 不合法：{plugin_id!r}（小写字母数字加点分，2~64 字符）")

        manifest["name"] = _require_text(manifest.get("name"), "name", 14)
        manifest["summary"] = _require_text(manifest.get("summary"), "summary", 200)

        plugin_type = manifest.get("type")
        if plugin_type not in SUPPORTED_TYPES:
            raise MarketError(f"插件类型只收 dex / web，这里是 {plugin_type!r}")

        version = manifest.get("version")
        if not isinstance(version, str) or not SEMVER_RE.match(version):
            raise MarketError(f"清单里的 `version` 不是三段版本号：{version!r}")
        manifest["version"] = version
        manifest["versionCode"] = _require_int(manifest.get("versionCode"), "versionCode", 1)
        manifest["minAppVersionCode"] = _require_int(manifest.get("minAppVersionCode"), "minAppVersionCode", 0)

        author = manifest.get("author")
        if not isinstance(author, dict):
            raise MarketError("清单里的 `author` 必须是 { name, url } 对象")
        author_name = _require_text(author.get("name"), "author.name", 40)
        author_url = author.get("url") or ""
        if author_url and not str(author_url).startswith("https://"):
            raise MarketError(f"清单里的 `author.url` 必须是 https 地址：{author_url}")
        manifest["author"] = {"name": author_name, "url": str(author_url)}

        project_url = manifest.get("projectUrl") or ""
        if project_url and not str(project_url).startswith("https://"):
            raise MarketError(f"清单里的 `projectUrl` 必须是 https 地址：{project_url}")
        manifest["projectUrl"] = str(project_url)

        warnings: list[str] = []
        if plugin_type == "dex":
            api_version = manifest.get("apiVersion")
            if api_version != DEX_API_VERSION:
                raise MarketError(f"dex 插件 apiVersion 必须是 {DEX_API_VERSION}，这里是 {api_version!r}")
            dex = manifest.get("dex")
            if not isinstance(dex, dict):
                raise MarketError("dex 插件缺少 `dex` 段")
            apk = dex.get("apk")
            if not isinstance(apk, str) or not apk.endswith(".apk"):
                raise MarketError(f"`dex.apk` 必须是一个 .apk 路径：{apk!r}")
            if not isinstance(dex.get("entryClass"), str) or not dex["entryClass"].strip():
                raise MarketError("`dex.entryClass` 是空的")
            if not isinstance(dex.get("pages"), list) or not dex["pages"]:
                raise MarketError("`dex.pages` 至少要有页")
            if not manifest["projectUrl"]:
                warnings.append("dex 插件没有填 `projectUrl`，索引里会退回用本讨论的链接——开源要求请自己把关")
        else:
            web = manifest.get("web")
            if not isinstance(web, dict):
                raise MarketError("web 插件缺少 `web` 段")
            start_url = _require_https(web.get("startUrl"), "web.startUrl")
            origins = web.get("origins")
            if not isinstance(origins, list) or not origins:
                raise MarketError("`web.origins` 不能是空的")
            for origin in origins:
                if not isinstance(origin, str) or not ORIGIN_RE.match(origin.rstrip("/")):
                    raise MarketError(f"`web.origins` 里有不合法的站点：{origin!r}")
            vpn = web.get("vpn", "off")
            if vpn not in ("required", "optional_on", "optional_off", "off"):
                raise MarketError(f"`web.vpn` 只能是 required / optional_on / optional_off / off，这里是 {vpn!r}")
            web["startUrl"] = start_url
            manifest["web"] = web

        key_der = _decode_base64(manifest.get("pubkey"), "pubkey", 1024)
        try:
            signature_text = bundle.read("dzhz.plugin.sig").decode("ascii")
        except UnicodeDecodeError:
            raise MarketError("dzhz.plugin.sig 不是 Base64 文本") from None
        signature = _decode_base64(signature_text, "dzhz.plugin.sig", 256)
        verify_signature(content, signature, key_der)

        raw_files = manifest.get("files")
        if not isinstance(raw_files, list):
            raise MarketError("清单里的 `files` 必须是数组")
        files: list[dict[str, Any]] = []
        seen: set[str] = set()
        for item in raw_files:
            if not isinstance(item, dict):
                raise MarketError("`files[]` 的每一条都必须是对象")
            path = item.get("path")
            if not isinstance(path, str) or not _safe_entry_name(path):
                raise MarketError(f"`files[]` 里有不合法的路径：{path!r}")
            if path in ("dzhz.plugin.json", "dzhz.plugin.sig"):
                raise MarketError(f"`files[]` 不该把 {path} 也列进去")
            if path in seen:
                raise MarketError(f"`files[]` 里有重复路径：{path}")
            seen.add(path)
            digest = item.get("sha256")
            if not isinstance(digest, str) or not SHA256_RE.match(digest):
                raise MarketError(f"`files[]` 里 {path} 的 sha256 不合法")
            size = item.get("size")
            if type(size) is not int or size < 0:
                raise MarketError(f"`files[]` 里 {path} 的 size 不合法")
            files.append({"path": path, "sha256": digest, "size": size})

        declared = {item["path"] for item in files}
        if declared | {"dzhz.plugin.json", "dzhz.plugin.sig"} != set(names):
            extra = sorted(set(names) - declared - {"dzhz.plugin.json", "dzhz.plugin.sig"})
            missing = sorted(declared - set(names))
            detail = "、".join((extra + missing)[:5])
            raise MarketError(f"包内文件和 `files[]` 清单对不上（{detail}）")
        for item in files:
            data = bundle.read(item["path"])
            if len(data) != item["size"] or hashlib.sha256(data).hexdigest() != item["sha256"]:
                raise MarketError(f"包内文件被改过或是打包时算错了：{item['path']}")
        if plugin_type == "dex" and manifest["dex"]["apk"] not in declared:
            raise MarketError(f"`dex.apk` 没有在 `files[]` 里声明：{manifest['dex']['apk']}")

    return Package(
        manifest=manifest,
        size=len(payload),
        sha256=hashlib.sha256(payload).hexdigest(),
        key_sha256=hashlib.sha256(key_der).hexdigest(),
        files=files,
        warnings=warnings,
    )


def fetch_package(url: str) -> Package:
    return inspect_package(fetch_bytes(url, MAX_ZIP_BYTES))


# --------------------------------------------------------------------------
# 索引改写
# --------------------------------------------------------------------------


def _skip(raw: str, index: int) -> int:
    while index < len(raw) and raw[index] in " \t\r\n,":
        index += 1
    return index


def _array_spans(raw: str, open_index: int) -> tuple[list[tuple[Any, int, int]], int, int]:
    """返回 ([(值, 起点, 终点)], 最后一个元素的终点, ']' 的位置)。"""
    decoder = json.JSONDecoder()
    items: list[tuple[Any, int, int]] = []
    cursor = _skip(raw, open_index + 1)
    last_end = cursor
    while cursor < len(raw) and raw[cursor] != "]":
        value, end = decoder.raw_decode(raw, cursor)
        items.append((value, cursor, end))
        last_end = end
        cursor = _skip(raw, end)
    return items, last_end, cursor


def _line_indent(raw: str, index: int) -> str:
    """index 所在行的行首缩进；拿不到就像 4 个空格。"""
    line_start = raw.rfind("\n", 0, index) + 1
    indent = raw[line_start:index]
    return indent if indent and not indent.strip() else "    "


def _replace_plugin(raw: str, entry: dict[str, Any]) -> str:
    marker = re.search(r'"plugins"\s*:\s*\[', raw)
    if marker is None:
        raise MarketError("索引里找不到 plugins 数组，格式可能变了")
    open_index = marker.end() - 1
    items, last_end, close = _array_spans(raw, open_index)
    for value, start, end in items:
        if isinstance(value, dict) and value.get("id") == entry["id"]:
            indent = _line_indent(raw, start)
            record = json.dumps(entry, ensure_ascii=False, indent=2).replace("\n", "\n" + indent)
            return raw[:start] + record + raw[end:]
    indent = _line_indent(raw, items[0][1]) if items else _line_indent(raw, close) + "  "
    record = json.dumps(entry, ensure_ascii=False, indent=2).replace("\n", "\n" + indent)
    if items:
        return raw[:last_end] + ",\n" + indent + record + raw[last_end:]
    return raw[:open_index + 1] + "\n" + indent + record + "\n" + _line_indent(raw, close) + raw[close:]


def _append_root_tags(raw: str, new_tags: list[str]) -> str:
    plugins = re.search(r'"plugins"\s*:\s*\[', raw)
    scope = raw[: plugins.start()] if plugins else raw
    marker = re.search(r'"tags"\s*:\s*\[', scope)
    if marker is None:
        raise MarketError("索引里找不到根级 `tags` 数组")
    open_index = marker.end() - 1
    items, last_end, close = _array_spans(raw, open_index)
    if items and "\n" in raw[open_index:close]:
        indent = _line_indent(raw, items[0][1])
        text = "".join(f",\n{indent}{json.dumps(tag, ensure_ascii=False)}" for tag in new_tags)
        return raw[:last_end] + text + raw[last_end:]
    text = ", ".join(json.dumps(tag, ensure_ascii=False) for tag in new_tags)
    if items:
        return raw[:last_end] + ", " + text + raw[last_end:]
    return raw[:open_index + 1] + text + raw[close:]


def _stamp(raw: str) -> str:
    now = datetime.now(BEIJING).isoformat(timespec="seconds")
    updated, count = re.subn(
        r'("updatedAt"\s*:\s*")[^"]*(")', lambda match: match.group(1) + now + match.group(2), raw, count=1
    )
    if count != 1:
        raise MarketError("索引里没有 `updatedAt` 字段")
    return updated


def _load_keys(raw: Optional[str], path: str) -> dict[str, str]:
    if raw is None or not raw.strip():
        return {}
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        raise MarketError(f"{path} 不是合法 JSON") from None
    if not isinstance(data, dict) or data.get("schema") != KEYS_SCHEMA:
        raise MarketError(f"{path} 的 schema 不是 {KEYS_SCHEMA}")
    plugins = data.get("plugins")
    if not isinstance(plugins, dict):
        raise MarketError(f"{path} 里缺少 `plugins` 对象")
    return {str(key): str(value) for key, value in plugins.items()}


def _dump_keys(keys: dict[str, str]) -> str:
    body = {"schema": KEYS_SCHEMA, "plugins": dict(sorted(keys.items()))}
    return json.dumps(body, ensure_ascii=False, indent=2) + "\n"


def plan_update(
        raw: str,
        index: dict[str, Any],
        keys: dict[str, str],
        package: Package,
        download_url: str,
        project_url_fallback: str,
        tags: Optional[list[str]],
        featured: Optional[bool],
) -> Plan:
    manifest = package.manifest
    plugin_id = manifest["id"]
    plugins = index.get("plugins")
    if not isinstance(plugins, list):
        raise MarketError("索引里缺少 `plugins` 数组")
    matches = [item for item in plugins if isinstance(item, dict) and item.get("id") == plugin_id]
    if len(matches) > 1:
        raise MarketError(f"索引里有 {len(matches)} 条重复的 `{plugin_id}`，请先手工清理")
    previous = matches[0] if matches else None

    changes: list[str] = []
    new_tags: list[str] = []
    root_tags = list(index.get("tags") or [])

    pinned = keys.get(plugin_id)
    if pinned is not None and pinned != package.key_sha256:
        raise MarketError(
            f"`{plugin_id}` 的签名钥匙变了：索引里钉的是 `{pinned[:16]}…`，这个包是 `{package.key_sha256[:16]}…`。"
            f"确认是作者换了钥匙的话，先去 dzhz-server 的钥匙钉表里手工改掉"
        )
    key_recorded = pinned is None
    new_keys = dict(keys)
    if key_recorded:
        new_keys[plugin_id] = package.key_sha256

    if previous is not None:
        old_type = previous.get("type")
        if old_type != manifest["type"]:
            raise MarketError(f"`{plugin_id}` 已经以 {old_type} 型收录，不能改成 {manifest['type']} 型")
        old_code = previous.get("versionCode")
        old_sha = str(previous.get("sha256") or "").removeprefix("sha256:")
        if type(old_code) is not int:
            raise MarketError(f"索引里 `{plugin_id}` 的 versionCode 不是整数，请先手工修")
        if manifest["versionCode"] < old_code:
            raise MarketError(
                f"版本回退：包里是 versionCode {manifest['versionCode']}，索引里已经是 {old_code}"
            )
        if manifest["versionCode"] == old_code:
            if old_sha != package.sha256 or previous.get("version") != manifest["version"]:
                raise MarketError(
                    f"versionCode {old_code} 已经在索引里了，但包内容不一样——请提升 versionCode 再发"
                )
            entry = dict(previous)
            if tags is not None:
                entry["tags"] = list(tags)
            if featured is not None:
                entry["featured"] = featured
            return Plan(
                entry=entry,
                raw=raw,
                keys=new_keys,
                keys_changed=key_recorded,
                changes=[],
                new_tags=[],
                key_recorded=key_recorded,
                unchanged=True,
                message=f"Update online market: {plugin_id} v{manifest['version']}",
            )

        entry = dict(previous)
        updates = {
            "name": manifest["name"],
            "summary": manifest["summary"],
            "type": manifest["type"],
            "version": manifest["version"],
            "versionCode": manifest["versionCode"],
            "minAppVersionCode": manifest["minAppVersionCode"],
            "author": manifest["author"]["name"],
            "authorUrl": manifest["author"]["url"],
            "download": download_url,
            "sha256": package.sha256,
            "size": package.size,
        }
        for key, value in updates.items():
            old = entry.get(key)
            if old != value:
                changes.append(f"`{key}`：{_short(old)} → {_short(value)}")
            entry[key] = value
        entry.setdefault("mirrors", [])
    else:
        entry = {
            "id": plugin_id,
            "name": manifest["name"],
            "summary": manifest["summary"],
            "type": manifest["type"],
            "version": manifest["version"],
            "versionCode": manifest["versionCode"],
            "minAppVersionCode": manifest["minAppVersionCode"],
            "author": manifest["author"]["name"],
            "authorUrl": manifest["author"]["url"],
            "projectUrl": manifest["projectUrl"] or project_url_fallback,
            "download": download_url,
            "mirrors": [],
            "sha256": package.sha256,
            "size": package.size,
            "featured": bool(featured) if featured is not None else False,
            "tags": list(tags or []),
            "iconName": "",
        }

    if tags is not None:
        entry["tags"] = list(tags)
    if featured is not None:
        entry["featured"] = bool(featured)
    if previous is not None and tags is not None:
        changes.append(f"`tags`：{_short(previous.get('tags'))} → {_short(tags)}")
    if previous is not None and featured is not None and previous.get("featured") != bool(featured):
        changes.append(f"`featured`：{_short(previous.get('featured'))} → {_short(bool(featured))}")

    for tag in entry.get("tags") or []:
        if tag not in root_tags:
            root_tags.append(tag)
            new_tags.append(tag)

    new_raw = _replace_plugin(raw, entry)
    if new_tags:
        new_raw = _append_root_tags(new_raw, new_tags)
    new_raw = _stamp(new_raw)

    checked = json.loads(new_raw)
    checked.pop("updatedAt", None)
    expected = copy.deepcopy(index)
    expected.pop("updatedAt", None)
    if previous is None:
        expected["plugins"] = list(expected["plugins"]) + [entry]
    else:
        expected["plugins"] = [entry if item.get("id") == plugin_id else item for item in expected["plugins"]]
    expected["tags"] = root_tags
    if checked != expected:
        raise MarketError("改写后的索引和预期不一致，已放弃提交（格式可能被人改过）")

    return Plan(
        entry=entry,
        raw=new_raw,
        keys=new_keys,
        keys_changed=key_recorded,
        changes=changes,
        new_tags=new_tags,
        key_recorded=key_recorded,
        unchanged=False,
        message=f"Update online market: {plugin_id} v{manifest['version']}",
    )


def _short(value: Any, limit: int = 40) -> str:
    if value is None:
        return "（无）"
    if isinstance(value, bool):
        text = "true" if value else "false"
    elif isinstance(value, list):
        text = "、".join(str(item) for item in value) or "（空）"
    elif isinstance(value, str) and len(value) == 64 and SHA256_RE.match(value):
        text = value[:12] + "…"
    else:
        text = str(value)
    text = text.replace("`", "'")
    return text if len(text) <= limit else text[:limit] + "…"


# --------------------------------------------------------------------------
# 读写仓库
# --------------------------------------------------------------------------


RestCall = Callable[[str, str, Optional[dict[str, Any]]], tuple[int, dict[str, Any]]]


def _read_contents(rest: RestCall, repo: str, path: str, branch: str) -> Optional[str]:
    status, data = rest("GET", f"/repos/{repo}/contents/{path}?ref={urllib.parse.quote(branch)}", None)
    if status == 404:
        return None
    if status != 200:
        detail = (data or {}).get("message") or ""
        raise MarketError(f"读取 {repo}/{path} 失败（HTTP {status}）{detail}")
    if data.get("encoding") != "base64" or not data.get("content"):
        raise MarketError(f"{repo}/{path} 太大或不是普通文件，读不出来")
    return base64.b64decode(data["content"]).decode("utf-8")


def push_commit(rest: RestCall, repo: str, branch: str, files: dict[str, str], message: str) -> str:
    """用 Git Data API 一次提交多个文件，返回 commit 链接。"""

    def call(method: str, path: str, body: Optional[dict[str, Any]], ok: tuple[int, ...]) -> dict[str, Any]:
        status, data = rest(method, path, body)
        if status not in ok:
            detail = (data or {}).get("message") or ""
            raise MarketError(f"{method} {path} 失败（HTTP {status}）{detail}")
        return data

    ref = call("GET", f"/repos/{repo}/git/ref/heads/{branch}", None, (200,))
    head = ref["object"]["sha"]
    base_tree = call("GET", f"/repos/{repo}/git/commits/{head}", None, (200,))["tree"]["sha"]

    entries = []
    for path, text in files.items():
        blob = call("POST", f"/repos/{repo}/git/blobs", {"content": text, "encoding": "utf-8"}, (201,))
        entries.append({"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]})
    tree = call("POST", f"/repos/{repo}/git/trees", {"base_tree": base_tree, "tree": entries}, (201,))

    author = {
        "name": "github-actions[bot]",
        "email": "41898282+github-actions[bot]@users.noreply.github.com",
    }
    created = call(
        "POST",
        f"/repos/{repo}/git/commits",
        {"message": message, "tree": tree["sha"], "parents": [head], "author": author, "committer": author},
        (201,),
    )
    status, data = rest(
        "PATCH", f"/repos/{repo}/git/refs/heads/{branch}", {"sha": created["sha"], "force": False}
    )
    if status in (409, 422):
        raise Conflict(str((data or {}).get("message") or f"HTTP {status}"))
    if status != 200:
        detail = (data or {}).get("message") or ""
        raise MarketError(f"更新 {repo} 的 {branch} 失败（HTTP {status}）{detail}")
    return created.get("html_url") or f"https://github.com/{repo}/commit/{created['sha']}"


def update_market(
        *,
        rest: RestCall,
        repo: str,
        branch: str,
        index_path: str,
        keys_path: str,
        package: Package,
        download_url: str,
        project_url_fallback: str,
        tags: Optional[list[str]],
        featured: Optional[bool],
        dry_run: bool,
        retries: int = 3,
) -> UpdateResult:
    last_error: Optional[str] = None
    for attempt in range(1, retries + 1):
        raw = _read_contents(rest, repo, index_path, branch)
        if raw is None:
            raise MarketError(f"{repo} 的 {index_path} 不存在")
        try:
            index = json.loads(raw)
        except json.JSONDecodeError:
            raise MarketError(f"{repo} 的 {index_path} 不是合法 JSON") from None
        if not isinstance(index, dict) or index.get("schema") != MARKET_SCHEMA:
            raise MarketError(f"{index_path} 的 schema 不是 {MARKET_SCHEMA}")

        keys = _load_keys(_read_contents(rest, repo, keys_path, branch), keys_path)
        plan = plan_update(
            raw, index, keys, package, download_url, project_url_fallback, tags, featured
        )
        previous = next(
            (item for item in index.get("plugins") or [] if item.get("id") == package.manifest["id"]), None
        )

        # 版本没变但第一次记钥匙时也只提交钉表；dry-run 什么都不写。
        needs_commit = not plan.unchanged or plan.keys_changed
        if dry_run or not needs_commit:
            return UpdateResult(
                entry=plan.entry,
                package=package,
                previous=previous,
                changes=plan.changes,
                new_tags=plan.new_tags,
                key_recorded=plan.key_recorded,
                unchanged=plan.unchanged,
                dry_run=dry_run,
                commit_url=None,
            )

        files: dict[str, str] = {}
        if not plan.unchanged:
            files[index_path] = plan.raw
        if plan.keys_changed:
            files[keys_path] = _dump_keys(plan.keys)
        try:
            commit_url = push_commit(rest, repo, branch, files, plan.message)
        except Conflict as error:
            last_error = str(error)
            log(f"第 {attempt} 次提交和别人撞了，重读索引再来：{error}")
            time.sleep(1 + attempt)
            continue

        return UpdateResult(
            entry=plan.entry,
            package=package,
            previous=previous,
            changes=plan.changes,
            new_tags=plan.new_tags,
            key_recorded=plan.key_recorded,
            unchanged=plan.unchanged,
            dry_run=False,
            commit_url=commit_url,
        )

    raise MarketError(f"连续 {retries} 次被别人的提交抢先，稍后再试一次（{last_error}）")


# --------------------------------------------------------------------------
# 自检
# --------------------------------------------------------------------------


def _sample_index() -> str:
    return json.dumps(
        {
            "schema": 1,
            "updatedAt": "2026-11-28T21:42:15+08:00",
            "tags": ["示例", "模板"],
            "plugins": [
                {
                    "id": "top.dzhz.plugin.template",
                    "name": "插件模板",
                    "summary": "模板",
                    "type": "dex",
                    "version": "1.0.1",
                    "versionCode": 13,
                    "minAppVersionCode": 101,
                    "author": "Hz6826",
                    "authorUrl": "https://github.com/Hz6826",
                    "projectUrl": "https://github.com/Hz6826/dzhz-plugin-template",
                    "download": "https://example.com/old.zip",
                    "mirrors": [],
                    "sha256": "0" * 64,
                    "size": 1,
                    "featured": True,
                    "tags": ["示例", "模板"],
                    "iconName": "",
                }
            ],
        },
        ensure_ascii=False,
        indent=2,
    )


def _sample_package(version: str = "1.0.2", code: int = 14, sha: str = "1" * 64) -> Package:
    manifest = {
        "manifestVersion": 1,
        "id": "top.dzhz.plugin.template",
        "name": "插件模板",
        "summary": "模板",
        "version": version,
        "versionCode": code,
        "minAppVersionCode": 101,
        "type": "dex",
        "projectUrl": "https://github.com/Hz6826/dzhz-plugin-template",
        "author": {"name": "Hz6826", "url": "https://github.com/Hz6826"},
        "dex": {"apk": "plugin.apk", "entryClass": "x.Y", "pages": [{"id": "a"}]},
        "files": [{"path": "plugin.apk", "sha256": "2" * 64, "size": 10}],
        "pubkey": "x",
    }
    return Package(manifest=manifest, size=1234, sha256=sha, key_sha256="3" * 64)


def self_test() -> None:
    body = (
        "## v1.2.2 更新\n\n- 修了点东西\n\n## 插件直链\n\n"
        "https://github.com/a/b/releases/download/v1.2.2/plugin.zip\n"
    )
    assert section_links(body) == ["https://github.com/a/b/releases/download/v1.2.2/plugin.zip"]
    assert section_links("**插件直链**\nhttps://e.com/p.zip") == ["https://e.com/p.zip"]
    assert section_links("插件直链：\nhttps://e.com/p.zip") == ["https://e.com/p.zip"]
    assert section_links("普通文字\nhttps://e.com/p.zip") == []
    assert clean_links("看这个 https://e.com/a.zip。") == ["https://e.com/a.zip"]
    assert clean_links("http://e.com/a.zip") == []
    assert clean_links("说明 https://e.com/page 和 https://e.com/a.zip")[0] == "https://e.com/a.zip"
    assert human_size(1024) == "1.0 KB"

    # 路径校验
    for bad in ("/abs", "a\\b", "../x", "a/./b", "a//b", ""):
        assert not _safe_entry_name(bad), bad
    for good in ("plugin.apk", "assets/icon.png"):
        assert _safe_entry_name(good), good

    # 新版本：改写、追加标签、更新 updatedAt，其余条目原样
    raw = _sample_index()
    index = json.loads(raw)
    package = _sample_package()
    plan = plan_update(raw, index, {}, package, "https://e.com/new.zip", "https://github.com/o/r/discussions/1",
                       ["模板", "新标签"], True)
    checked = json.loads(plan.raw)
    assert checked["plugins"][0]["versionCode"] == 14
    assert checked["plugins"][0]["featured"] is True
    assert checked["plugins"][0]["tags"] == ["模板", "新标签"]
    assert checked["tags"] == ["示例", "模板", "新标签"]
    assert checked["updatedAt"] != index["updatedAt"]
    assert plan.new_tags == ["新标签"] and plan.key_recorded
    assert plan.keys["top.dzhz.plugin.template"] == "3" * 64
    assert '\n    "新标签"\n  ]' in plan.raw

    # 同 versionCode 同内容：不动
    same = plan_update(raw, index, {package.manifest["id"]: package.key_sha256},
                       _sample_package(version="1.0.1", code=13, sha="0" * 64),
                       "https://e.com/old.zip", "", None, None)
    assert same.unchanged and same.raw == raw

    # 同 versionCode 不同内容：拒绝
    try:
        plan_update(raw, index, {}, _sample_package(version="1.0.1", code=13, sha="9" * 64),
                    "https://e.com/x.zip", "", None, None)
    except MarketError:
        pass
    else:
        raise AssertionError("同 versionCode 不同内容应当拒绝")

    # 版本回退：拒绝
    try:
        plan_update(raw, index, {}, _sample_package(code=12), "https://e.com/x.zip", "", None, None)
    except MarketError:
        pass
    else:
        raise AssertionError("版本回退应当拒绝")

    # 换钥匙：拒绝
    try:
        plan_update(raw, index, {"top.dzhz.plugin.template": "f" * 64},
                    package, "https://e.com/x.zip", "", None, None)
    except MarketError:
        pass
    else:
        raise AssertionError("换了签名钥匙应当拒绝")

    # 新插件：追加在数组末尾，tags 为给定值
    other = Package(
        manifest={
            "manifestVersion": 1,
            "id": "hz6826.example",
            "name": "例子",
            "summary": "例子插件",
            "version": "0.1.0",
            "versionCode": 1,
            "minAppVersionCode": 101,
            "type": "web",
            "projectUrl": "",
            "author": {"name": "Hz6826", "url": ""},
            "web": {"startUrl": "https://e.com/", "origins": ["https://e.com"], "vpn": "off"},
            "files": [],
            "pubkey": "x",
        },
        size=10,
        sha256="4" * 64,
        key_sha256="5" * 64,
    )
    plan = plan_update(raw, index, {}, other, "https://e.com/new.zip",
                       "https://github.com/xjtu-dzhz/dzhz/discussions/1", None, None)
    checked = json.loads(plan.raw)
    assert len(checked["plugins"]) == 2
    assert checked["plugins"][1]["projectUrl"].endswith("/discussions/1")
    assert checked["plugins"][1]["tags"] == [] and checked["plugins"][1]["featured"] is False
    assert plan.new_tags == []

    # 空 tags 数组也能追加
    empty = json.dumps({"schema": 1, "updatedAt": "x", "tags": [], "plugins": []}, ensure_ascii=False, indent=2)
    plan = plan_update(empty, json.loads(empty), {}, other, "https://e.com/new.zip", "https://e.com/d",
                       ["学习资料"], None)
    assert json.loads(plan.raw)["tags"] == ["学习资料"]
    assert len(json.loads(plan.raw)["plugins"]) == 1

    # 端到端：假 REST 走一遍「读索引 → 改写 → Git Data API 提交」
    state: dict[str, Any] = {"index": _sample_index(), "keys": None, "head": "h1"}
    blobs: dict[str, str] = {}

    def fake_rest(method: str, path: str, body: Optional[dict[str, Any]] = None):
        if method == "GET" and path.startswith("/repos/o/r/contents/"):
            name = path.split("/contents/", 1)[1].split("?", 1)[0]
            text = state["index"] if name == "index.json" else state["keys"]
            if text is None:
                return 404, {"message": "Not Found"}
            return 200, {"encoding": "base64", "content": base64.b64encode(text.encode()).decode()}
        if method == "GET" and path == "/repos/o/r/git/ref/heads/main":
            return 200, {"object": {"sha": state["head"]}}
        if method == "GET" and path == f"/repos/o/r/git/commits/{state['head']}":
            return 200, {"tree": {"sha": "t0"}}
        if method == "POST" and path.endswith("/git/blobs"):
            sha = hashlib.sha256(body["content"].encode()).hexdigest()
            blobs[sha] = body["content"]
            return 201, {"sha": sha}
        if method == "POST" and path.endswith("/git/trees"):
            assert body["base_tree"] == "t0"
            return 201, {"sha": "t1"}
        if method == "POST" and path.endswith("/git/commits"):
            assert body["tree"] == "t1" and body["parents"] == ["h1"]
            return 201, {"sha": "c1", "html_url": "https://github.com/o/r/commit/c1"}
        if method == "PATCH" and path == "/repos/o/r/git/refs/heads/main":
            return 200, {"object": {"sha": body["sha"]}}
        return 500, {"message": f"unexpected {method} {path}"}

    result = update_market(
        rest=fake_rest, repo="o/r", branch="main", index_path="index.json", keys_path="keys.json",
        package=_sample_package(), download_url="https://e.com/new.zip",
        project_url_fallback="https://e.com/d", tags=None, featured=None, dry_run=False,
    )
    assert result.commit_url == "https://github.com/o/r/commit/c1" and not result.unchanged
    written = list(blobs.values())
    assert any('"versionCode": 14' in text for text in written)
    assert any("top.dzhz.plugin.template" in text and "3" * 64 in text for text in written)

    # dry-run 不写
    state["keys"] = None
    result = update_market(
        rest=fake_rest, repo="o/r", branch="main", index_path="index.json", keys_path="keys.json",
        package=_sample_package(), download_url="https://e.com/new.zip",
        project_url_fallback="https://e.com/d", tags=None, featured=None, dry_run=True,
    )
    assert result.dry_run and result.commit_url is None
