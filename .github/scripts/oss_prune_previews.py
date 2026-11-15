#!/usr/bin/env python3
"""Keep only the newest N preview APK versions on OSS.

Preview APKs are stored under version-specific keys, so the bucket lifecycle rule
for noncurrent versions cannot clean them. This mirrors the GitHub pre-release
retention: keep the newest N version directories and delete older directories with
all their object versions and delete markers.

Version directory names are either the current scheme ``6.0.1-beta.3`` (beta number
restarts at 1 for every core version) or the legacy ``6.0.0-dev.384`` (CI run number,
kept only as history). Both are recognized so the migration leftovers get pruned too;
ordering is by core version first, then by scheme (``beta`` beats ``dev``) and number.
"""
import argparse
import os
import re

import oss2
from oss2.models import BatchDeleteObjectVersion, BatchDeleteObjectVersionList

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


def find_preview_versions(bucket, prefix):
    """Map preview version directory -> ordering key; skip unrecognized names."""
    found = {}
    for obj in oss2.ObjectIterator(bucket, prefix=prefix):
        version, sep, _ = obj.key[len(prefix):].partition("/")
        if not sep or not version:
            continue
        key = sort_key(version)
        if key is None:
            print(f"skip unrecognized preview version: {version}")
            continue
        found[version] = key
    return found


def prune_versions(found, keep):
    """Return version directories to delete, newest `keep` excluded."""
    ordered = sorted(found.items(), key=lambda item: item[1], reverse=True)
    return [version for version, _ in ordered[keep:]]


def list_all_versions(bucket, prefix):
    """List every object version and delete marker under prefix."""
    items = []
    key_marker = ""
    versionid_marker = ""
    while True:
        page = bucket.list_object_versions(
            prefix=prefix,
            key_marker=key_marker,
            versionid_marker=versionid_marker,
            max_keys=BATCH_SIZE,
        )
        for version in page.versions:
            items.append(BatchDeleteObjectVersion(version.key, version.versionid))
        for marker in page.delete_marker:
            items.append(BatchDeleteObjectVersion(marker.key, marker.versionid))
        if not page.is_truncated:
            return items
        key_marker = page.next_key_marker
        versionid_marker = page.next_versionid_marker


def delete_versions(bucket, items):
    for start in range(0, len(items), BATCH_SIZE):
        batch = BatchDeleteObjectVersionList(items[start:start + BATCH_SIZE])
        bucket.delete_object_versions(batch)


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

    found = find_preview_versions(bucket, args.prefix)
    stale = prune_versions(found, args.keep)
    kept = min(args.keep, len(found))
    print(f"preview versions: {len(found)}, keeping: {kept}, pruning: {len(stale)}")
    for version in stale:
        print(f"{'would prune' if args.dry_run else 'prune'} {version}")

    if args.dry_run:
        return 0

    deleted = 0
    for version in stale:
        items = list_all_versions(bucket, args.prefix + version + "/")
        if items:
            delete_versions(bucket, items)
            deleted += len(items)
    print(f"deleted {deleted} object versions/delete markers")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
