#!/usr/bin/env python3
"""发版后刷新 ESA 边缘缓存（dzhz.zyxf.top 前面的阿里云边缘加速）。

    esa_purge.py <对象路径>...   # 相对 xjtu-toolbox/，例如 apk/latest/app-release.apk

stable.json / preview.json 在 ESA 规则里不缓存；要刷的是「会被覆盖写、又落在长缓存规则里」的
固定路径：apk/latest/app-release.apk（30 天），以及实测会被缓存的 bulletin.json（announce.yml 传完就刷）。
ESA 不认源站 Cache-Control，覆盖写之后不刷就要等
缓存过期（#519）。没配 ESA_SITE_ID / ESA_ACCESS_KEY_ID / ESA_ACCESS_KEY_SECRET 时跳过；
刷新失败只报警告，不让发版失败。
"""
import os
import sys

BASE = os.environ.get("ESA_PURGE_BASE") or "https://dzhz.zyxf.top/xjtu-toolbox"
ENDPOINT = os.environ.get("ESA_ENDPOINT") or "esa.cn-hangzhou.aliyuncs.com"


def main() -> int:
    paths = sys.argv[1:]
    if not paths:
        print("usage: esa_purge.py <path>...", file=sys.stderr)
        return 2
    site, ak, sk = (os.environ.get(k, "").strip() for k in ("ESA_SITE_ID", "ESA_ACCESS_KEY_ID", "ESA_ACCESS_KEY_SECRET"))
    if not (site and ak and sk):
        print("::notice::没配 ESA_SITE_ID / ESA_ACCESS_KEY_ID / ESA_ACCESS_KEY_SECRET，跳过 ESA 缓存刷新")
        return 0
    urls = [f"{BASE.rstrip('/')}/{p.lstrip('/')}" for p in paths]
    try:
        from alibabacloud_esa20240910 import models
        from alibabacloud_esa20240910.client import Client
        from alibabacloud_tea_openapi import models as api

        client = Client(api.Config(access_key_id=ak, access_key_secret=sk, endpoint=ENDPOINT))
        request = models.PurgeCachesRequest(
            site_id=int(site),
            type="file",
            content=models.PurgeCachesRequestContent(files=urls),
        )
        response = client.purge_caches(request)
        print(f"ESA 缓存刷新已提交（任务 {getattr(response.body, 'task_id', '?')}）：{', '.join(urls)}")
    except Exception as e:  # noqa: BLE001 —— 刷新失败不拦发版，只是要等缓存过期
        print(f"::warning::ESA 缓存刷新失败，清单要等边缘缓存过期才生效：{e}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
