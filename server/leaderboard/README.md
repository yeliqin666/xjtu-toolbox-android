# 小游戏全服榜服务端

分数类小游戏（合成西交大、GPA 2048、方块三玩法、跳一跳）的全服榜。App 端代码在
`app/src/main/java/com/xjtu/toolbox/game/board/`。

**零依赖**：只用 Node 24 内置的 `node:http` 和 `node:sqlite`，没有 npm 包，部署就是拷两个文件。

## 为什么是独立服务

这是一个**公开、无鉴权的写接口**——谁都能 POST 一个分数上来。同一台机器上的资料站后端
握着 OSS 密钥、阿里云 IMM 凭据和用户 JWT，两者共用进程或数据库都不划算：榜单这边被人打穿，
不该连带资料站。所以它独立进程、独立端口、独立 SQLite 文件。

## 接口

服务监听 `127.0.0.1:4100`，只接受本机（nginx）转发。路径前缀 `/games` 可带可不带。

### `GET /games/board?game=<id>&uid=<uid>&limit=<n>`

```json
{
  "game": "2048",
  "total": 137,
  "rows": [
    { "rank": 1, "name": "张三", "score": 9999 }
  ],
  "me": { "rank": 12, "name": "匿名", "score": 800 }
}
```

`rows` 按分数降序、同分先上榜的在前；只回前 `limit` 名（上限 100）。
`me` 是请求里那个 `uid` 自己的名次——即使排在百名开外也给，榜里放不下但玩家想知道。
不在榜上就是 `null`。

### `POST /games/score`

```json
{ "game": "2048", "uid": "a1b2c3d4e5f6", "name": "张三", "score": 800 }
```

按 `(game, uid)` 去重：同一个人同一款游戏**只有一行**，分数只增不减，名字以最后一次为准。
响应 `{ "ok": true, "best": 800, "rank": 12, "improved": true }`。

`name` 传空、传空白、传控制字符、或者就传 `"匿名"`，一律落成 `匿名`；超 12 个字截断。

### `GET /games/health`

`{ "ok": true, "games": [...], "limit": 100 }`

## 游戏 id

和客户端 `game/board/Leaderboard.kt` 的 `boardTitle()` 一一对应，加游戏时两边一起加：

| id | 榜名 |
|---|---|
| `merge` | 合成西交大 |
| `2048` | GPA 2048 |
| `hop` | 跳一跳 |
| `blocks_marathon` | 方块 · 马拉松 |
| `blocks_ultra` | 方块 · 冲分 |
| `blocks_rise` | 方块 · 无尽抬升 |

棋类不做排行榜，不在这个列表里。

## 关于作弊

**分数不做校验，防不住。** 整局游戏跑在客户端，任何人都能伪造一个分数传上来。这是明知的取舍
（见 App 侧 `LeaderboardApi` 的注释）——榜单是信任制的，只做「别把荒唐值写进库」的粗筛
（分数 0 ~ 10000000、名字长度、每 IP 每分钟 30 次提交）。

发现脏数据手工清：

```bash
sqlite3 /opt/xjtu-games/data.db "delete from scores where game='2048' and uid='<uid>';"
```

## 昵称审核

昵称是**玩家自由输入、又会公示给别人看**的字段，所以有三道拦截，按成本从低到高：

**第一道：本地词表（归一化后比对）。** 光做子串匹配挡不住花写，所以先把名字压成「骨架」再比：
NFKC 折叠全角与兼容字形、去掉零宽字符和控制符、转小写、再去掉所有空白/标点/符号/下划线。
`傻*逼`、`傻 逼`、`傻　逼`、`傻` + 零宽 + `逼`、`傻Ｂ`（全角 B）在骨架里都是 `傻b`，一条就够。

内置词表只有一小撮起手词（中文脏话 + 常见英文脏话 + 冒充官方身份的词）。真正的词表放同目录的
`banned.txt`，一行一个，`#` 开头是注释，改完重启生效。这个文件已 gitignore——公开一份完整词表，
等于给绕过它的人一张清单。**放服务端而不是打进 APK 是有意的**：改词表只要重启，不用等发版。

**第二道：阿里云内容安全「用户昵称检测_专业版」。** 见下面「接入内容安全」。
这一道**不覆盖冒充官方**——实测「管理员」返回的是"未检测出风险"——所以第一道不能省。

**第三道：昵称独占。** 同一款游戏里一个名字只归一个人（不同游戏之间可以重名，那是两块榜）。
匿名不参与独占。实现在 `handleScore`：审核之后、写入之前做一次检查，中间没有 `await`；
另有一条**部分唯一索引** `ON scores(game, name) WHERE name <> '匿名'` 兜住并发窗口，
撞上索引也会被翻译成同样的 `409 name_taken`。

**第四道（兜底）：人工清理。** 词表永远补不全（谐音、拆字、新梗）：

```bash
sqlite3 /opt/xjtu-games/data.db "select game, uid, name, score from scores where name like '%关键词%';"
sqlite3 /opt/xjtu-games/data.db "delete from scores where uid = '<uid>' and game = '<game>';"
```

命中时接口返回 `code: "name_rejected"`（不合适）或 `code: "name_taken"`（被占用），
客户端据此**把询问弹窗留在原地**让玩家换个名字，而不是当成网络故障。本机记住的名字若被判不合适，
客户端会把它忘掉、下次上榜重新问——否则那个人以后每次上榜都会静静地失败。

一个已知取舍：**子串匹配一定会有误伤**（经典的 Scunthorpe 问题）。所以内置词表刻意不收太短的词——
收一个 `ass` 就会把 `class`、`grass` 一起拦掉。往 `banned.txt` 加词时同理：越短越要谨慎。

### 接入内容安全

调的是 **`TextModerationPlus`**（Version `2022-03-02`）+ ServiceCode **`nickname_detection_pro`**。

> ⚠️ **别用 `TextModeration`**。那个接口不认 `_pro` 结尾的服务码，会返回
> `service is invalid`；`nickname_detection`（非专业版）才是它的。两者**响应结构也不一样**：
> `TextModeration` 的 `Data` 是 JSON 字符串（含 `labels`），`TextModerationPlus` 的 `Data` 是对象
> （含 `Result[]` 和 `RiskLevel`）。代码里两种都兜了。

> ⚠️ endpoint 用 **`green-cip.cn-shanghai.aliyuncs.com`**。cn-beijing 也能连通但返回
> `service is invalid`；cn-hangzhou 直接 `ServiceUnavailable`。

**凭据**：需要一个能调内容安全的 AccessKey。做法是建个 **RAM 子用户**，只挂内容安全的权限，
然后把 AK 写到服务端（**别用主账号的 AK**）：

```bash
# 在服务器上执行（或从控制台的「云助手」跑），把两个占位符换成自己的
install -d -m 755 /opt/xjtu-games
printf '%s\n' 'accessKeyId=你的AK' 'accessKeySecret=你的SK' > /opt/xjtu-games/aliyun.env
chown root:www /opt/xjtu-games/aliyun.env
chmod 640 /opt/xjtu-games/aliyun.env
systemctl restart xjtu-games
curl -s http://127.0.0.1:4100/games/health; echo   # moderation 字段应当变成 nickname_detection_pro
```

也可以改用环境变量 `GREEN_ACCESS_KEY_ID` / `GREEN_ACCESS_KEY_SECRET`（优先级更高），
或在 systemd 单元里加 `EnvironmentFile`。

> ⚠️ **RAM 授权用的是 `yundun-greenweb` 命名空间，不是 `green`。** 自定义策略写成
> `green:TextModerationPlus` 会得到 `Code=408 No permissions.`（而签名是对的，所以别去怀疑签名）。
> 正确写法是 `yundun-greenweb:TextModerationPlus`；系统策略 `AliyunYundunGreenWebFullAccess`
> 展开就是 `yundun-greenweb:*` + `secllm:*` + `sasti:*`。策略改完**要等几分钟才传播到位**，
> 立刻测可能还是 408。排权限问题时把 `AccessDeniedDetail` 打出来，里面有服务端认定的 action。

**没配凭据时第二道是空操作**，服务照常跑，只跑本地词表和独占检查——所以可以先跑起来再补 AK。
`/games/health` 的 `moderation` 字段会显示 `off` 或服务码。

**审核没跑成的时候不放行新名字。** 超时、鉴权错、响应看不懂都算"没跑成"：先重试一次，
仍失败就返回 `unavailable`。这时按两种情况分道走——

- **新名字**（这个名字还没有出现在该 uid 的榜上行里）：返回 `503` + `code: "moderation_unavailable"`，
  玩家看到「名字要过一遍审核，稍后再试一次」。**绝不放进来**：昵称是公开字段，涉政内容不能靠
  "恰好云挂了"混上榜单。
- **已审过的老名字**（自己榜上已有这个名字）：照常更新分数。它早就过过审，没理由因为阿里云抖一下
  就把老玩家挡在榜外。
- **匿名**根本不进这道（没有名字可审）。

判断"是不是老名字"用的是 `scores(game, uid, name)` 这条查询——`uid` 加上同名，说明这个名字
本来就是这个人榜上正在用的。

每次放行/拦下/没跑成都会写日志（`journalctl -u xjtu-games`），回头能核对放过和误伤。

### 涉政类目实测

`nickname_detection_pro` 的涉政覆盖是**开着的，而且比预期严格**——连单独一个领导人名字都会被拦
（返回 `political_figure` + `political_a/涉政专项升级保障` 这种专项标签）。实测到的标签：

| 样例 | 结果 |
|---|---|
| 违禁组织名 | 拦截 `political_n/疑似敏感政治内容` |
| 敏感事件名 | 拦截 `political_n` + `political_figure` |
| 单独一个领导人名 | 拦截 `political_figure` + `political_a/涉政专项升级保障` |
| 低俗口头语 / 辱骂（中英文、含全角变体） | 拦截 `inappropriate_oral` + `inappropriate_profanity` |
| 正常昵称（含中英混排） | 放行 |

代码对**任何非 `nonLabel` 的标签一律拒绝**，不按标签枚举——阿里云以后新增标签也自动生效。

还有个本地词表做不到的地方值得记一笔：阿里云返回的命中词是**展开过变体的**，例如送「法&轮功」进去，
回来命中词里有 `法輪功`、`法轮&功`、`法&轮功` 等等。**繁体字和插入符号它都会展开**。
本地词表的骨架归一化只能去标点，不做繁简转换，所以涉政这类"变体对抗决定成败"的类目
**不要往 `banned.txt` 里塞**，交给云。

**成本**：只对**非匿名**且**过了本地词表**的名字调用，同一个名字有内存缓存（2000 条，命中与否都缓存），
再加每 IP 每分钟 30 次提交的限流与"同一 uid 同一游戏只有一行"的去重——正常用量下调用量远小于上榜次数。
"没跑成"的结果**不缓存**，下次还得真查。

## 部署

目标机：一台 Linux 服务器（本文的参考环境是 Ubuntu 22.04 + Node 24）。
下面用 `$HOST` 代替具体地址——**别把服务器的 IP 写进本仓库**：域名解析一下就能拿到 IP，
所以写 IP 并不能"隐藏"什么，但它会把「这个仓库」和「这台机器」在公开文档里明确绑到一起。
用域名或你自己的笔记即可。

```bash
HOST=zyxf.top                  # 换成本机实际可达的地址（域名即可）

# 1. 建目录、拷代码（本地执行）
ssh root@"$HOST" 'mkdir -p /opt/xjtu-games'
scp index.js package.json root@"$HOST":/opt/xjtu-games/

# 2. 让运行用户拥有工作目录（DB 就落在里面）
ssh root@"$HOST" 'chown -R www:www /opt/xjtu-games'

# 3. 装 systemd 单元
scp deploy/xjtu-games.service root@"$HOST":/etc/systemd/system/
ssh root@"$HOST" 'systemctl daemon-reload && systemctl enable --now xjtu-games'

# 4. 把 deploy/nginx-games.conf 的内容加进你站点 nginx 的 443 server 块，
#    然后检查并重载
ssh root@"$HOST" 'nginx -t && systemctl reload nginx'

# 5. 验证
curl -s https://zyxf.top/games/health
curl -s 'https://zyxf.top/games/board?game=2048&uid=testuid12345&limit=10'
```

升级只改 `index.js` 的话：`scp index.js root@"$HOST":/opt/xjtu-games/ && ssh root@"$HOST" 'systemctl restart xjtu-games'`。

### 回滚

```bash
systemctl disable --now xjtu-games
# 从站点的 nginx 配置里删掉 location ^~ /games/ 那段，nginx -t && systemctl reload nginx
```

App 侧把 `LeaderboardApi.HOST` 置空字符串，整块排行榜功能就不再出现（入口按钮都会隐藏）。

## 本地跑测试

```bash
cd server/leaderboard
node --test test/smoke.test.js
```

起一个真进程、走真 HTTP，覆盖排序、去重、名次、入参校验。不需要任何测试依赖。

## 数据

一个 SQLite 文件，WAL 模式：

```sql
CREATE TABLE scores (
  game TEXT NOT NULL, uid TEXT NOT NULL, name TEXT NOT NULL,
  score INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
  PRIMARY KEY (game, uid)
);
```

`uid` 是设备级匿名 ID（`ANDROID_ID` 的哈希，见 `FeedbackStore.anonId`），不含学号账号。
**一台设备一份**：换设备就是新的一行，同一个人在两台设备上会有两条记录。

> **为什么没做成「一个账户一行」。** 那需要用学号派生一个稳定 ID，但学号是公开可枚举的
> （校区分段 + 年份 + 院系 + 序号），而 App 是公开的、盐就在源码里——拿到榜单库的人把候选
> 挨个算一遍就能反查出每行是谁，**换什么哈希都一样**。实测：完整学号在一张消费级显卡上是
> **秒级**，加 30 万次迭代也只抬到「天级」，挡得住顺手而为，挡不住铁了心的人。姓名同理，
> 而且姓名还会变（ID 跟着变，反而破坏「一行」）。要让「按账户」同时反推不出来，输入必须
> 掺进一个**秘密**，而秘密只能靠数据迁移（备份带着走）或第三方登录跨设备——对一个小游戏
> 排行榜都不划算。所以定成设备级：**宁可少一点唯一性，也不引入任何可反查的标识**。
>
> 想隐藏身份仍然可以：**昵称留空即匿名**，榜上显示什么由玩家自己决定。

整个库里就这些——**没有时间线、没有历史成绩、没有其他任何用户数据**。
