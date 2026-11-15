#!/usr/bin/env python3
"""Keep only the newest N preview APK versions on OSS.

Preview APKs live under version-specific directories (``6.0.1-beta.3``, legacy
``6.0.0-dev.384``), so the GitHub pre-release retention is mirrored here: keep the
newest N directories and delete the rest. Only ListObjects/DeleteObject are used —
the CI key has no version-level permissions, and the bucket lifecycle rule expires
the noncurrent versions a plain delete leaves behind.
"""
import argparse
import os
import re

import oss2

DEFAULT_PREFIX = "xjtu-toolbox/apk/preview/"
VERSION_SHAPE = re.compile(r"^(\d+(?:\.\d+)*)-(beta|dev)\.(\d+)$")
# 同一核心版本内，迁移后的 -beta.N 比遗留的 -dev.N 新。
SCHEME_ORDER = {"dev": 0, "beta": 1}
BATCH_SIZE = 1000


def sort_key(version):
    """Version directory -> ordering key; None when the name is not a preview version."""
    match = VERSION_SHAPE.match(version)
    if not match:
        return None
    core, scheme, number = match.groups()
    return tuple(int(part) for part in core.split(".")), SCHEME_ORDER[scheme], int(number)


def group_by_version(bucket, prefix):
    """Map preview version directory -> (ordering key, object keys)."""
    found = {}
    for obj in oss2.ObjectIterator(bucket, prefix=prefix):
        version, sep, _ = obj.key[len(prefix):].partition("/")
        if not sep or not version:
            continue
        key = sort_key(version)
        if key is None:
            print(f"skip unrecognized preview version: {version}")
            continue
        found.setdefault(version, (key, []))[1].append(obj.key)
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keep", type=int, default=5, help="number of newest preview versions to keep")
    parser.add_argument("--prefix", default=DEFAULT_PREFIX)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.keep < 0:
        parser.error("--keep must be >= 0")

    endpoint = os.environ["OSS_ENDPOINT"]
    if not endpoint.startswith(("http://", "https://")):
        endpoint = "https://" + endpoint
    bucket = oss2.Bucket(
        oss2.Auth(os.environ["OSS_ACCESS_KEY_ID"], os.environ["OSS_ACCESS_KEY_SECRET"]),
        endpoint,
        os.environ["OSS_BUCKET"],
    )

    found = group_by_version(bucket, args.prefix)
    ordered = sorted(found, key=lambda v: found[v][0], reverse=True)
    stale = ordered[args.keep:]
    print(f"preview versions: {len(found)}, keeping: {len(found) - len(stale)}, pruning: {len(stale)}")

    keys = []
    for version in stale:
        print(f"{'would prune' if args.dry_run else 'prune'} {version}")
        keys.extend(found[version][1])
    if args.dry_run or not keys:
        return 0

    try:
        for start in range(0, len(keys), BATCH_SIZE):
            bucket.batch_delete_objects(keys[start:start + BATCH_SIZE])
    except oss2.exceptions.AccessDenied as e:
        # 清理失败不影响已完成的发布，只提示去 RAM 给 CI 密钥补 oss:DeleteObject
        print(f"::warning::OSS 拒绝删除旧预览包（{e.details.get('Code')}），需给 CI 密钥补 oss:DeleteObject 权限")
        return 0
    print(f"deleted {len(keys)} objects")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
