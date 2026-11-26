#!/usr/bin/env python3
"""Publish stable / preview APKs and manifests to Cloudflare R2 (dl.dzhz.top)."""
import argparse
import datetime
import hashlib
import json
import os
import re
import sys
import time

DEFAULT_PUBLIC_BASE = "https://dl.dzhz.top"
DEFAULT_OSS_BASE = "https://dzhz.zyxf.top/xjtu-toolbox"
DEFAULT_REPO = "yeliqin666/xjtu-toolbox-android"
CACHE_IMMUTABLE = "public, max-age=31536000, immutable"
CACHE_NO_CACHE = "no-cache"

PREVIEW_PREFIX = "apk/preview/"
VERSION_SHAPE = re.compile(r"^(\d+(?:\.\d+)*)-(beta|dev)\.(\d+)$")
SCHEME_ORDER = {"dev": 0, "beta": 1}


def require_r2_config():
    keys = ("R2_ACCOUNT_ID", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET")
    vals = {k: os.environ.get(k, "").strip() for k in keys}
    if not all(vals.values()):
        return None
    vals["R2_PUBLIC_BASE"] = (os.environ.get("R2_PUBLIC_BASE", "").strip() or DEFAULT_PUBLIC_BASE).rstrip("/")
    return vals


def build_client(cfg):
    import boto3
    from botocore.config import Config

    return boto3.client(
        "s3",
        endpoint_url=f"https://{cfg['R2_ACCOUNT_ID']}.r2.cloudflarestorage.com",
        aws_access_key_id=cfg["R2_ACCESS_KEY_ID"],
        aws_secret_access_key=cfg["R2_SECRET_ACCESS_KEY"],
        region_name="auto",
        config=Config(connect_timeout=30, read_timeout=60, retries={"max_attempts": 3}),
    )


def upload_file(client, bucket: str, src: str, key: str, cache_control: str, content_type: str) -> None:
    for attempt in range(1, 4):
        try:
            client.upload_file(
                src,
                bucket,
                key,
                ExtraArgs={"CacheControl": cache_control, "ContentType": content_type},
            )
            print(f"uploaded {src} -> r2://{bucket}/{key}")
            return
        except Exception as e:  # noqa: BLE001
            if attempt == 3:
                raise
            print(f"upload {key} attempt {attempt} failed: {e}; retrying", file=sys.stderr)
            time.sleep(5 * attempt)


def upload_json(client, bucket: str, data: dict, key: str, cache_control: str) -> bytes:
    payload = json.dumps(data, ensure_ascii=False, indent=2).encode("utf-8")
    for attempt in range(1, 4):
        try:
            client.put_object(
                Bucket=bucket,
                Key=key,
                Body=payload,
                CacheControl=cache_control,
                ContentType="application/json; charset=utf-8",
            )
            print(f"uploaded manifest -> r2://{bucket}/{key}")
            return payload
        except Exception as e:  # noqa: BLE001
            if attempt == 3:
                raise
            print(f"upload {key} attempt {attempt} failed: {e}; retrying", file=sys.stderr)
            time.sleep(5 * attempt)
    raise AssertionError("unreachable")


def apk_digest_and_size(path: str) -> tuple[str, int]:
    h = hashlib.sha256()
    size = 0
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
            size += len(chunk)
    return h.hexdigest(), size


def now_utc_iso() -> str:
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def verify_manifest(client, bucket: str, manifest_key: str, apk_key: str, version: str, sha256: str, size: int) -> None:
    remote = json.loads(client.get_object(Bucket=bucket, Key=manifest_key)["Body"].read())
    assert remote["versionName"] == version, remote
    assert remote["sha256"] == sha256, remote
    head = client.head_object(Bucket=bucket, Key=apk_key)
    assert head["ContentLength"] == size, (head["ContentLength"], size)
    print(f"R2 verified: {manifest_key} ({version}, {size} bytes)")


def publish_stable(client, cfg, args) -> None:
    bucket = cfg["R2_BUCKET"]
    base = cfg["R2_PUBLIC_BASE"]
    oss_base = (os.environ.get("OSS_PUBLIC_BASE", "").strip() or DEFAULT_OSS_BASE).rstrip("/")
    repo = os.environ.get("GITHUB_REPO", DEFAULT_REPO).strip() or DEFAULT_REPO
    version = args.version
    sha256, size = apk_digest_and_size(args.apk)
    notes = open(args.notes_file, encoding="utf-8").read()

    apk_key = f"apk/{version}/app-release.apk"
    latest_key = "apk/latest/app-release.apk"
    apk_mime = "application/vnd.android.package-archive"
    upload_file(client, bucket, args.apk, apk_key, CACHE_IMMUTABLE, apk_mime)
    upload_file(client, bucket, args.apk, latest_key, CACHE_NO_CACHE, apk_mime)

    manifest = {
        "schema": 1,
        "channel": "stable",
        "versionName": version,
        "versionCode": args.version_code,
        "apkUrl": f"{base}/{apk_key}",
        "mirrors": [
            f"{oss_base}/apk/{version}/app-release.apk",
            f"https://github.com/{repo}/releases/download/v{version}/app-release.apk",
            f"https://gitee.com/{repo}/releases/download/v{version}/app-release.apk",
        ],
        "sha256": sha256,
        "size": size,
        "notes": notes,
        "releaseUrl": f"https://github.com/{repo}/releases/tag/v{version}",
        "publishedAt": now_utc_iso(),
        "rollout": 100,
    }
    upload_json(client, bucket, manifest, f"history/stable-{version}.json", CACHE_IMMUTABLE)
    upload_json(client, bucket, manifest, "stable.json", CACHE_NO_CACHE)

    verify_manifest(client, bucket, "stable.json", apk_key, version, sha256, size)
    latest_head = client.head_object(Bucket=bucket, Key=latest_key)
    assert latest_head["ContentLength"] == size, (latest_head["ContentLength"], size)


def sort_key(version: str):
    match = VERSION_SHAPE.match(version)
    if not match:
        return None
    core, scheme, number = match.groups()
    return tuple(int(part) for part in core.split(".")), SCHEME_ORDER[scheme], int(number)


def prune_previews(client, bucket: str, keep: int) -> None:
    paginator = client.get_paginator("list_objects_v2")
    found: dict[str, tuple[tuple, list[str]]] = {}
    for page in paginator.paginate(Bucket=bucket, Prefix=PREVIEW_PREFIX):
        for obj in page.get("Contents", []):
            key = obj["Key"]
            version, sep, _ = key[len(PREVIEW_PREFIX):].partition("/")
            if not sep or not version:
                continue
            sk = sort_key(version)
            if sk is None:
                continue
            found.setdefault(version, (sk, []))[1].append(key)

    ordered = sorted(found, key=lambda v: found[v][0], reverse=True)
    stale = ordered[keep:]
    print(f"R2 preview versions: {len(found)}, keeping: {len(found) - len(stale)}, pruning: {len(stale)}")
    keys = [k for v in stale for k in found[v][1]]
    for start in range(0, len(keys), 1000):
        batch = [{"Key": k} for k in keys[start:start + 1000]]
        client.delete_objects(Bucket=bucket, Delete={"Objects": batch})


def publish_preview(client, cfg, args) -> None:
    bucket = cfg["R2_BUCKET"]
    base = cfg["R2_PUBLIC_BASE"]
    oss_base = (os.environ.get("OSS_PUBLIC_BASE", "").strip() or DEFAULT_OSS_BASE).rstrip("/")
    repo = os.environ.get("GITHUB_REPO", DEFAULT_REPO).strip() or DEFAULT_REPO
    version = args.version
    sha256, size = apk_digest_and_size(args.apk)

    apk_key = f"apk/preview/{version}/app-preview.apk"
    upload_file(client, bucket, args.apk, apk_key, CACHE_IMMUTABLE, "application/vnd.android.package-archive")

    manifest = {
        "schema": 1,
        "channel": "preview",
        "versionName": version,
        "versionCode": args.version_code,
        "apkUrl": f"{base}/{apk_key}",
        "mirrors": [
            f"{oss_base}/apk/preview/{version}/app-preview.apk",
            f"https://github.com/{repo}/releases/download/{args.tag}/xjtu-toolbox-{version}.apk",
        ],
        "sha256": sha256,
        "size": size,
        "notes": f"预览版 {version}（提交 {args.commit}）\n\n这是尚在测试的版本，仅推送给在设置中开启「接收预览版更新」的用户。",
        "releaseUrl": f"https://github.com/{repo}/releases/tag/{args.tag}",
        "publishedAt": now_utc_iso(),
        "rollout": 100,
    }
    upload_json(client, bucket, manifest, "preview.json", CACHE_NO_CACHE)
    verify_manifest(client, bucket, "preview.json", apk_key, version, sha256, size)
    prune_previews(client, bucket, args.keep)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_stable = sub.add_parser("stable")
    p_stable.add_argument("--apk", required=True)
    p_stable.add_argument("--version", required=True)
    p_stable.add_argument("--version-code", type=int, required=True)
    p_stable.add_argument("--notes-file", required=True)

    p_prev = sub.add_parser("preview")
    p_prev.add_argument("--apk", required=True)
    p_prev.add_argument("--version", required=True)
    p_prev.add_argument("--version-code", type=int, required=True)
    p_prev.add_argument("--tag", required=True)
    p_prev.add_argument("--commit", required=True)
    p_prev.add_argument("--keep", type=int, default=5)

    args = parser.parse_args()
    cfg = require_r2_config()
    if cfg is None:
        print("::notice::没配 R2_ACCOUNT_ID / R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY / R2_BUCKET，跳过 R2 发布")
        return 0

    client = build_client(cfg)
    if args.cmd == "stable":
        publish_stable(client, cfg, args)
    else:
        publish_preview(client, cfg, args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
