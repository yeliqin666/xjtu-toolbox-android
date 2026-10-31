#!/usr/bin/env python3
"""Upload a local file to the release OSS bucket."""
import os
import sys

import oss2


def main() -> int:
    if len(sys.argv) not in (3, 4):
        print(
            "usage: oss_upload.py <local-file> <object-key> [cache-control]",
            file=sys.stderr,
        )
        return 2

    src, key = sys.argv[1], sys.argv[2]
    # 固定 key（如 apk/latest/app-release.apk）每次发版都覆盖同一个对象，
    # 不显式给 Cache-Control 的话 CDN 可能继续下发上一版安装包。
    headers = {"Cache-Control": sys.argv[3]} if len(sys.argv) == 4 else None
    endpoint = os.environ["OSS_ENDPOINT"]
    if not endpoint.startswith(("http://", "https://")):
        endpoint = "https://" + endpoint

    bucket = oss2.Bucket(
        oss2.Auth(os.environ["OSS_ACCESS_KEY_ID"], os.environ["OSS_ACCESS_KEY_SECRET"]),
        endpoint,
        os.environ["OSS_BUCKET"],
    )
    bucket.put_object_from_file(key, src, headers=headers)
    print(f"uploaded {src} -> oss://{os.environ['OSS_BUCKET']}/{key}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
