# 岱宗盒子公告

这个孤儿分支只放 `bulletin.json`。改这里不会触发主仓库的发版构建。

App 打开时先拉 Gitee `announce` 上的这份文件，失败再走 GitHub。

```json
{
  "bulletins": [
    {
      "id": "2026-08-19-jwxt",
      "level": "info",
      "title": "标题",
      "body": "正文",
      "url": null,
      "startsAt": "2026-08-19T00:00:00+08:00",
      "endsAt": "2026-08-21T23:59:59+08:00",
      "minVersion": null,
      "maxVersion": null,
      "mustAck": false,
      "block": false
    }
  ]
}
```

`level`：`info` / `warn` / `critical` / `force_update`。

不要把这个分支 merge 进 `main`，也不要把仓库默认分支改成 `announce`。
