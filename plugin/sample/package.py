#!/usr/bin/env python3
"""把示例插件 APK 打成 .zip 插件包（dzhz.plugin.json + dzhz.plugin.sig）。

用法：
    ./package.py <sample apk> [-o sample-plugin.zip]

签名用 ECDSA P-256：私钥默认在 sample/signing/plugin-ec.pem，不存在就现生成一把
（演示用，别拿它签真插件）。清单里写公钥（SPKI DER 的 Base64），包根目录的
dzhz.plugin.sig 是清单**原始字节**的 SHA256withECDSA 签名。
"""
import argparse
import base64
import hashlib
import json
import pathlib
import subprocess
import sys
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
SIGNING_DIR = HERE / "signing"
DEFAULT_KEY = SIGNING_DIR / "plugin-ec.pem"

PAGES = [{"id": "home", "title": "示例页"}]

TOOLS = [
    {
        "name": "demo_course_count",
        "definition": {
            "type": "function",
            "function": {
                "name": "demo_course_count",
                "description": "示例插件：统计本周课表里的课程条数，并附上课表原文。",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "date": {"type": "string", "description": "yyyy-MM-dd，只统计该日；缺省本周。"}
                    },
                    "required": [],
                },
            },
        },
    }
]


def openssl(*args, stdin=None):
    return subprocess.run(
        ["openssl", *args], input=stdin, capture_output=True, check=True
    ).stdout


def ensure_key(path: pathlib.Path):
    if path.is_file():
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    openssl("ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(path))
    print(f"生成了新的签名私钥：{path}", file=sys.stderr)


def public_key_b64(key: pathlib.Path) -> str:
    der = openssl("ec", "-in", str(key), "-pubout", "-outform", "DER")
    return base64.b64encode(der).decode()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("-o", "--out", type=pathlib.Path, default=HERE / "sample-plugin.zip")
    parser.add_argument("-k", "--key", type=pathlib.Path, default=DEFAULT_KEY)
    parser.add_argument("--version", default="0.2.0")
    parser.add_argument("--version-code", type=int, default=6)
    args = parser.parse_args()

    apk_bytes = args.apk.read_bytes()
    apk_sha = hashlib.sha256(apk_bytes).hexdigest()

    ensure_key(args.key)

    manifest = {
        "manifestVersion": 1,
        "id": "top.dzhz.plugin.sample",
        "name": "示例插件",
        "summary": "把课程、考试、成绩、图书馆座位和空教室整理到一页，顺手给小岱加两个只读工具，不碰登录会话也不上传数据",
        "version": args.version,
        "versionCode": args.version_code,
        "minAppVersionCode": 101,
        "author": {"name": "Hz6826", "url": "https://github.com/Hz6826"},
        "projectUrl": "https://github.com/yeliqin666/xjtu-toolbox-android",
        "type": "dex",
        "apiVersion": 2,
        "dex": {
            "apk": "plugin.apk",
            "entryClass": "top.dzhz.plugin.sample.SamplePlugin",
            "pages": PAGES,
            "tools": TOOLS,
        },
        "files": [{"path": "plugin.apk", "sha256": apk_sha, "size": len(apk_bytes)}],
        "pubkey": public_key_b64(args.key),
    }

    manifest_bytes = json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
    signature = openssl("dgst", "-sha256", "-sign", str(args.key), stdin=manifest_bytes)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("dzhz.plugin.json", manifest_bytes)
        z.writestr("dzhz.plugin.sig", base64.b64encode(signature))
        z.writestr("plugin.apk", apk_bytes)

    print(f"{args.out}  ({args.out.stat().st_size} bytes)")
    print(f"  apk sha256 {apk_sha[:16]}…  size {len(apk_bytes)}")


if __name__ == "__main__":
    main()
