# 岱宗盒子公告

这个孤儿分支只放 `bulletin.json`。改这里不会触发主仓库的发版构建。

App 打开时先拉 Gitee `announce` 上的这份文件，失败再走页面 raw，再失败走 GitHub。

不要把这个分支 merge 进 `main`，也不要把仓库默认分支改成 `announce`。

`level`：`info` / `warn` / `update` / `critical` / `force_update`。

`targetVersion`：当前 versionName >= 它就不展示。旧版会把 `4.72` 和 `4.7.3` 比错，强制更新请同时写 `targetVersionCode`。

`targetVersionCode`：当前 versionCode >= 它就不展示。内部版本号单调递增，比名字可靠。

`forceBelow`：低于它时把 `update` 升级成强制更新。
