#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""飞书反馈表 → 崩溃日报（issue）+ 反馈日报（讨论），讨论里的 /reply、/skip 回写飞书回复表。

    python3 feedback_digest.py digest            # 每日：发日报，再扫一遍漏处理的命令
    python3 feedback_digest.py scan              # 只扫命令
    python3 feedback_digest.py comment ISSUE ID  # 旧的反馈日报 issue 里的评论
    讨论里的 /reply、/skip 由 discussion_commands.py 接收，调这里的 run_discussion_commands。

日报
* 从上一期的水位往后拉（见 watermark），按工单号去重，停跑多久都能补齐。
* 崩溃一条 issue，按 /toissue 规范打 Bug / P0 / Awaiting Assignment；只收核心版本 ≥ CRASH_MIN_VERSION，按异常签名聚合。
* 反馈一条讨论（「反馈日报」分类）：正文放统计、版本评价表、用法；每条反馈一层楼，楼里有工单号标记。
  「投票·」开头的是公告投票，不进日报。联系方式一律不发。
* 审核（MODERATION）：off 不审；mark（默认）用 Qwen3Guard 打标签，Unsafe / Controversial 照发但折叠；
  block 时这两档不展示正文，只列在帖子里。

命令（只认 OWNER / MEMBER / COLLABORATOR）
* 在某层反馈楼里回复：/reply [私密] + 下一行起写回复（第一行「问题：…」可改公开问答的问题）；/skip 不回复。
  顶层评论里要写工单号；/skip all 跳过本期剩下全部。/toissue 由 discussion_commands.py 处理。
* /reply 往回复表追加一条（工单号、回复、回复时间、公开、问题），公开默认勾上。
* 处理状态记在帖子正文的隐藏标记里（pending / done），不查飞书。结果（写了什么 / 为什么失败）
  在那串楼里显式回一条，和 /toissue 一致；反馈楼标 🎉 / 👀。都处理完关闭讨论。
"""

from __future__ import annotations

import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
    except (AttributeError, ValueError):
        pass

BEIJING = timezone(timedelta(hours=8))
FEISHU = "https://open.feishu.cn/open-apis"
GITHUB = "https://api.github.com"
BOT = "github-actions[bot]"

LABEL_FEEDBACK = "feedback"
LABEL_CRASH = "crash"
LABEL_DONE = "已处理"
LABEL_COLORS = {LABEL_FEEDBACK: "0e8a16", LABEL_CRASH: "b60205", LABEL_DONE: "c5def5"}
# 崩溃日报直接按 /toissue 的规范打标签（名字与 discussion_commands.py 一致）
CRASH_LABELS = [LABEL_CRASH, "🐛 Type:Bug", "🚨 Priority: P0 - Critical", "📥 State:Awaiting Assignment"]
GRAPHQL = "https://api.github.com/graphql"
# 每条反馈是讨论里的一层楼，楼里带这个标记，回复它时据此认工单
MARK_ITEM = re.compile(r"<!-- feedback-ticket:(T[A-Z0-9]{8,16}) -->")

CATEGORIES = ["有bug", "待改进", "新点子", "其他"]
# 旧版 App 提交的类别名，归到新名下
CATEGORY_ALIASES = {"功能坏了": "有bug", "有点难用": "待改进", "想要新功能": "新点子"}
# 与 App 端 FeedbackScreen 的 DIAGNOSTICS_SEPARATOR 一致
DIAGNOSTICS_SEPARATOR = "———— 诊断信息 ————"
RATING_PREFIX = "版本评价"
VOTE_PREFIX = "投票"
CRASH = "崩溃"
EMPTY_NOTES = {"", "（未填写）", "(未填写)"}

TICKET_RE = re.compile(r"T[A-Z0-9]{8,16}")
MARK_TICKETS = re.compile(r"<!-- feedback-digest kind=(\w+) tickets=([A-Z0-9,]*)(?: until=(\d+))? -->")
OVERLAP_MS = 3600_000  # 水位往回多看 1 小时，防时钟偏差；重叠部分靠工单号去重
MARK_PENDING = re.compile(r"<!-- feedback-pending=([A-Z0-9,]*) -->")
MARK_DONE = re.compile(r"<!-- feedback-done=([A-Z0-9,]*) -->")
MARK_FAILED = "<!-- feedback-cmd-failed:{} -->"
COMMAND_RE = re.compile(r"^[ \t]*/(reply|skip)\b[ \t]*(.*)$", re.IGNORECASE)
QUESTION_RE = re.compile(r"^\s*问题\s*[:：]\s*(.+)$")
PRIVATE_WORDS = {"私密", "不公开", "否", "private", "no", "false", "0"}
TRUSTED = {"OWNER", "MEMBER", "COLLABORATOR"}

USAGE_TEXT = """在那条反馈的楼里**回复**（不用写工单号）：

```
/reply [私密]
问题：公开问答里显示的问题（可省，默认用反馈原文）
回复正文，可多行
```

- 默认进应用内「大家在问」；加 `私密`（或 `不公开`、`private`）则只有提问的人能看到
- 不回复：`/skip`；转 issue：`/toissue bug 1`
- 在顶层评论里也行，但要写工单号：`/reply T工单号`、`/skip T工单号 …`；`/skip all` 跳过本期剩下全部
- 处理完 bot 在楼里回一条结果，那条反馈标 🎉（已回复）/ 👀（跳过）；都处理完自动关闭"""
USAGE = f"<details open><summary>用法</summary>\n\n{USAGE_TEXT}\n\n</details>"

# 回复表里要手动建的可选列；没建也能写，只是少这一项
OPTIONAL_REPLY_FIELDS = {"回复人"}

ITEM_LIMIT = 1500
BODY_BUDGET = 60000
QUESTION_LIMIT = 200


def log(msg: str) -> None:
    print(msg, flush=True)


def env(name: str, default: Optional[str] = None) -> str:
    v = os.environ.get(name, "").strip()
    if v:
        return v
    if default is None:
        print(f"::error::缺环境变量 {name}", file=sys.stderr, flush=True)
        raise SystemExit(1)
    return default


DRY_RUN = os.environ.get("DRY_RUN", "").strip().lower() in {"1", "true", "yes"}


# ---------------------------------------------------------------- HTTP

def http(method: str, url: str, body: Any = None, headers: Optional[dict] = None) -> Any:
    data = json.dumps(body).encode() if body is not None else None
    h = {"Content-Type": "application/json; charset=utf-8", **(headers or {})}
    for attempt in range(3):
        req = urllib.request.Request(url, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                raw = r.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            raw = e.read() or b"{}"
            if e.code >= 500 and attempt < 2:
                time.sleep(2 * (attempt + 1))
                continue
            try:
                payload = json.loads(raw)
            except ValueError:
                payload = {"message": raw[:200].decode(errors="replace")}
            payload["_status"] = e.code
            return payload
        except urllib.error.URLError:
            if attempt < 2:
                time.sleep(2 * (attempt + 1))
                continue
            raise
    return {}


# ---------------------------------------------------------------- 飞书

class Feishu:
    def __init__(self) -> None:
        self.base = env("FEEDBACK_BASE_TOKEN")
        self.submit_table = env("FEEDBACK_TABLE_SUBMIT")
        self.reply_table = env("FEEDBACK_TABLE_REPLY")
        r = http("POST", f"{FEISHU}/auth/v3/tenant_access_token/internal",
                 {"app_id": env("FEEDBACK_APP_ID"), "app_secret": env("FEEDBACK_APP_SECRET")})
        if r.get("code") != 0:
            raise RuntimeError(f"飞书取 token 失败：{r.get('code')} {r.get('msg')}")
        self.auth = {"Authorization": "Bearer " + r["tenant_access_token"]}

    def search(self, table: str, flt: Optional[dict] = None) -> list[dict]:
        rows: list[dict] = []
        token = None
        for _ in range(50):
            url = f"{FEISHU}/bitable/v1/apps/{self.base}/tables/{table}/records/search?page_size=500"
            if token:
                url += "&page_token=" + urllib.parse.quote(token)
            body: dict = {"automatic_fields": True}
            if flt:
                body["filter"] = flt
            r = http("POST", url, body, self.auth)
            if r.get("code") != 0:
                raise RuntimeError(f"飞书检索失败：{r.get('code')} {r.get('msg')}")
            d = r.get("data") or {}
            rows += d.get("items") or []
            if not d.get("has_more"):
                break
            token = d.get("page_token")
        return rows

    def submission(self, ticket: str) -> Optional[dict]:
        flt = {"conjunction": "and",
               "conditions": [{"field_name": "工单号", "operator": "is", "value": [ticket]}]}
        rows = self.search(self.submit_table, flt)
        return rows[0]["fields"] if rows else None

    def add_reply(self, fields: dict) -> None:
        url = f"{FEISHU}/bitable/v1/apps/{self.base}/tables/{self.reply_table}/records"
        r = http("POST", url, {"fields": fields}, self.auth)
        # 可选列（「回复人」）还没建时飞书报字段不存在：去掉可选列再写一次，回复本身不能因此失败
        if r.get("code") != 0 and any(k in fields for k in OPTIONAL_REPLY_FIELDS):
            log(f"::warning::回复表写入失败（{r.get('code')} {r.get('msg')}），去掉可选列重试")
            r = http("POST", url, {"fields": {k: v for k, v in fields.items() if k not in OPTIONAL_REPLY_FIELDS}},
                     self.auth)
        if r.get("code") != 0:
            raise RuntimeError(f"写回复表失败：{r.get('code')} {r.get('msg')}")


def text(v: Any) -> str:
    if v is None:
        return ""
    if isinstance(v, list):
        return "".join(x.get("text", "") if isinstance(x, dict) else str(x) for x in v)
    if isinstance(v, dict):
        return str(v.get("text") or v.get("name") or "")
    return str(v)


@dataclass
class Record:
    ticket: str
    category: str
    content: str
    version: str
    device: str
    system: str
    created: datetime

    @property
    def main(self) -> str:
        """去掉 App 附的诊断信息之后的正文。"""
        return self.content.split(DIAGNOSTICS_SEPARATOR, 1)[0].strip()

    @property
    def diagnostics(self) -> str:
        """App 附的诊断信息；去掉里面给 Markdown 用的折叠标签和代码围栏，日报自己包一层。"""
        if DIAGNOSTICS_SEPARATOR not in self.content:
            return ""
        raw = self.content.split(DIAGNOSTICS_SEPARATOR, 1)[1]
        keep = [ln for ln in raw.splitlines()
                if not re.match(r"\s*(</?details>|<summary>.*</summary>|<details><summary>.*|```)", ln)]
        return "\n".join(keep).strip()

    @staticmethod
    def of(row: dict) -> Optional["Record"]:
        f = row.get("fields") or {}
        m = TICKET_RE.search(text(f.get("工单号")))
        if not m:
            return None
        ms = row.get("created_time") or 0
        return Record(
            ticket=m.group(0),
            category=CATEGORY_ALIASES.get(text(f.get("类型")).strip(), text(f.get("类型")).strip()),
            content=text(f.get("内容")).strip(),
            version=text(f.get("版本")).strip(),
            device=text(f.get("机型")).strip(),
            system=text(f.get("系统")).strip(),
            created=datetime.fromtimestamp(int(ms) / 1000, BEIJING),
        )


# ---------------------------------------------------------------- GitHub

class GitHub:
    def __init__(self) -> None:
        self.repo = env("GITHUB_REPOSITORY")
        self.auth = {"Authorization": "Bearer " + env("GITHUB_TOKEN"),
                     "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28"}

    def call(self, method: str, path: str, body: Any = None) -> Any:
        r = http(method, f"{GITHUB}/repos/{self.repo}{path}", body, self.auth)
        if isinstance(r, dict) and r.get("_status"):
            raise RuntimeError(f"GitHub {method} {path} → {r['_status']} {r.get('message')}")
        return r

    def pages(self, path: str) -> list[dict]:
        out: list[dict] = []
        sep = "&" if "?" in path else "?"
        for page in range(1, 20):
            got = self.call("GET", f"{path}{sep}per_page=100&page={page}")
            out += got
            if len(got) < 100:
                break
        return out

    def write(self, method: str, path: str, body: Any = None) -> Any:
        if DRY_RUN:
            log(f"[dry-run] {method} {path} {json.dumps(body, ensure_ascii=False)[:300] if body else ''}")
            return {"number": 0, "html_url": "(dry-run)"}
        return self.call(method, path, body)

    def ensure_labels(self) -> None:
        for name, color in LABEL_COLORS.items():
            try:
                self.call("GET", f"/labels/{urllib.parse.quote(name)}")
            except RuntimeError:
                self.write("POST", "/labels", {"name": name, "color": color})

    def rocketed(self, comment_id: int) -> bool:
        got = self.call("GET", f"/issues/comments/{comment_id}/reactions?content=rocket&per_page=100")
        return any((x.get("user") or {}).get("login") == BOT for x in got)

    # ---- 讨论区（GraphQL）

    def graphql(self, query: str, **variables: Any) -> dict:
        r = http("POST", GRAPHQL, {"query": query, "variables": variables}, self.auth)
        if r.get("errors") or r.get("_status"):
            raise RuntimeError(f"GitHub GraphQL 失败：{json.dumps(r.get('errors') or r.get('message'), ensure_ascii=False)[:300]}")
        return r["data"]

    def gwrite(self, query: str, **variables: Any) -> dict:
        if DRY_RUN:
            log(f"[dry-run] GraphQL {query.split('(')[0].split()[-1]} "
                f"{json.dumps(variables, ensure_ascii=False)[:300]}")
            return {}
        return self.graphql(query, **variables)

    def owner_name(self) -> tuple[str, str]:
        owner, name = self.repo.split("/", 1)
        return owner, name

    def feedback_category(self) -> tuple[str, str]:
        """(仓库 node id, 反馈日报分类 id)；分类要先在网页上建好（API 建不了）。"""
        owner, name = self.owner_name()
        want = env("FEEDBACK_DISCUSSION_CATEGORY", "反馈日报")
        d = self.graphql("""query($o:String!,$n:String!){repository(owner:$o,name:$n){
            id discussionCategories(first:50){nodes{id name}}}}""", o=owner, n=name)["repository"]
        for c in d["discussionCategories"]["nodes"]:
            if c["name"] == want:
                return d["id"], c["id"]
        raise RuntimeError(f"讨论区没有「{want}」分类，请先在仓库 Discussions 里新建（格式选 Announcement）")

    def feedback_discussions(self, category_id: str, limit: int = 50, open_only: bool = False) -> list[dict]:
        """最近的反馈日报讨论，新的在前。"""
        owner, name = self.owner_name()
        states = "[OPEN]" if open_only else "[OPEN, CLOSED]"
        d = self.graphql(f"""query($o:String!,$n:String!,$c:ID!,$k:Int!){{repository(owner:$o,name:$n){{
            discussions(first:$k, categoryId:$c, states:{states}, orderBy:{{field:CREATED_AT, direction:DESC}}){{
              nodes{{id number url body closed createdAt}}}}}}}}""", o=owner, n=name, c=category_id, k=limit)
        return d["repository"]["discussions"]["nodes"]


_EDGE_L = r"(?<![0-9A-Za-z_.])"
_EDGE_R = r"(?![0-9A-Za-z_.])"
_PII = [
    ("身份证号", re.compile(_EDGE_L + r"\d{17}[0-9Xx]" + _EDGE_R)),
    ("手机号", re.compile(_EDGE_L + r"(?:\+?86[- ]?)?1[3-9]\d{9}" + _EDGE_R)),
    ("邮箱", re.compile(_EDGE_L + r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}" + _EDGE_R)),
]


def _id_card_ok(s: str) -> bool:
    y, m, d = int(s[6:10]), int(s[10:12]), int(s[12:14])
    if not (1900 <= y <= 2100 and 1 <= m <= 12 and 1 <= d <= 31):
        return False
    w = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
    return "10X98765432"[sum(int(s[i]) * w[i] for i in range(17)) % 11] == s[17].upper()


def scrub_pii(s: str) -> str:
    """发 issue 前再打一次码（前端规则的同款，宁漏勿错）：崩溃日志和旧版应用都不经过前端检查。"""
    for label, rx in _PII:
        s = rx.sub(lambda m: f"[{label}]" if label != "身份证号" or _id_card_ok(m.group(0)) else m.group(0), s)
    return s


def mention_safe(s: str) -> str:
    """不让反馈正文里的 @某人、#编号 去通知别人或乱链，也不让 HTML 标签生效。"""
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = re.sub(r"@(?=[A-Za-z0-9])", "@​", s)
    return re.sub(r"(?<![\w&])#(?=\d)", "#​", s)


def quote(s: str, limit: int = ITEM_LIMIT) -> str:
    s = s.strip()
    if len(s) > limit:
        s = s[:limit] + "…（截断）"
    return "\n".join("> " + line if line.strip() else ">" for line in mention_safe(scrub_pii(s)).splitlines())


def one_line(s: str, limit: int = 120) -> str:
    s = " ".join(s.split())
    return s[:limit] + "…" if len(s) > limit else s


def ids(xs) -> str:
    return ",".join(sorted(set(xs)))


_DOTS = {"red": "🔴", "orange": "🟠", "brightgreen": "🟢", "blue": "🔵", "informational": "🔵", "lightgrey": "⚪"}


def badge(label: str, message: str, color: str) -> str:
    """徽章用 <kbd> + 色点：外链图片要走 camo.githubusercontent.com，国内常打不开。"""
    return f"<kbd>{_DOTS.get(color, '⚪')} {label} {message}</kbd>"


CATEGORY_ICONS = {"有bug": "🐞", "待改进": "🔧", "新点子": "💡", "其他": "💬", RATING_PREFIX: "⭐"}
VERDICT_COLORS = {"Unsafe": "red", "Controversial": "orange"}
RATING_COLORS = {"好用": "brightgreen", "有问题": "orange"}


# ---------------------------------------------------------------- 版本

def core_version(v: str) -> Optional[tuple[int, int, int]]:
    m = re.match(r"\s*v?(\d+)\.(\d+)\.(\d+)", v)
    return tuple(int(x) for x in m.groups()) if m else None  # type: ignore[return-value]


def short_version(v: str) -> str:
    """「6.0.3 (97)」→「6.0.3」；预览版保留 -dev.N。"""
    m = re.match(r"\s*(\S+)", v)
    return m.group(1) if m else (v or "未知")


# ---------------------------------------------------------------- 崩溃签名

_HEADER = re.compile(r"^(time|build|preview|thread)=")
_EXC = re.compile(r"^(?:Caused by:\s*)?([A-Za-z_$][\w$]*(?:\.[\w$<>]+)+)(?::\s*(.*))?$")
_APP_FRAME = re.compile(r"^\s*at (com\.xjtu\.toolbox\.[\w$.]+)\(")


def crash_signature(content: str) -> str:
    lines = [ln.rstrip() for ln in content.splitlines() if not _HEADER.match(ln)]
    exc = ""
    msg = ""
    for ln in lines:
        m = _EXC.match(ln.strip())
        if m and not ln.lstrip().startswith("at "):
            if not exc or ln.lstrip().startswith("Caused by"):
                exc, msg = m.group(1), m.group(2) or ""
    # 优先取最深一层 Caused by 下面的第一个本应用帧
    cause = max((i for i, ln in enumerate(lines) if ln.lstrip().startswith("Caused by")), default=0)
    frame = next((m.group(1) for ln in lines[cause:] + lines if (m := _APP_FRAME.match(ln))), "")
    if not exc:
        exc = one_line(lines[0] if lines else "（无堆栈）", 80)
    if frame:
        return f"{exc} @ {frame}"
    norm = re.sub(r"@[0-9a-f]+|0x[0-9a-f]+|\d+", "#", msg, flags=re.IGNORECASE)
    return f"{exc}: {one_line(norm, 80)}" if norm else exc


# ---------------------------------------------------------------- 日报

def watermark(gh: GitHub, discussions: list[dict], fallback_ms: int) -> int:
    """上次收录到哪一刻（毫秒）：最近几期日报（崩溃 issue、反馈讨论，以及旧的反馈 issue）里最大的 until。
    until 只写在一次运行最后发出的那期里，前一期发了、后一期失败时水位不动，下次照样补。
    一期都找不到（首次运行、旧日报删了）就退回 [fallback_ms]。"""
    bodies = [d["body"] for d in discussions[:10]] + [
        issue.get("body") or ""
        for label in (LABEL_CRASH, LABEL_FEEDBACK)
        for issue in gh.call("GET", f"/issues?labels={label}&state=all&sort=created&direction=desc&per_page=10")]
    got = [int(m.group(3)) for b in bodies for m in MARK_TICKETS.finditer(b) if m.group(3)]
    return max(got) if got else fallback_ms


def seen_tickets(gh: GitHub, discussions: list[dict], since_ms: int) -> set[str]:
    """水位之后（往前留 3 天余量）发过的日报里已经收录的工单。"""
    since_dt = datetime.fromtimestamp(since_ms / 1000 - 3 * 86400, timezone.utc)
    since = since_dt.strftime("%Y-%m-%dT%H:%M:%SZ")
    bodies = [d["body"] for d in discussions
              if datetime.fromisoformat(d["createdAt"].replace("Z", "+00:00")) >= since_dt]
    for label in (LABEL_FEEDBACK, LABEL_CRASH):
        bodies += [issue.get("body") or "" for issue in gh.pages(f"/issues?labels={label}&state=all&since={since}")]
    return {t for b in bodies for m in MARK_TICKETS.finditer(b) for t in m.group(2).split(",") if t}


def fetch_since(fs: Feishu, since_ms: int) -> list[Record]:
    """表里有「创建时间」列就让飞书按日期先筛一遍（粒度到天，往前多留一天），没有就整表拉；精确过滤在调用方。"""
    day_before = since_ms - 86400_000
    flt = {"conjunction": "and", "conditions": [
        {"field_name": "创建时间", "operator": "isGreater", "value": ["ExactDate", str(day_before)]}]}
    try:
        rows = fs.search(fs.submit_table, flt)
        log(f"按「创建时间」增量拉取：{len(rows)} 条")
    except RuntimeError as e:
        log(f"表里没有可筛选的「创建时间」列，整表拉取（{e}）")
        rows = fs.search(fs.submit_table)
    return [r for r in (Record.of(x) for x in rows) if r]


def moderate(records: list[Record], mode: str) -> dict[str, tuple[str, list[str]]]:
    if mode == "off" or not records:
        return {}
    try:
        import feedback_guard
    except ImportError:
        log("::warning::没装审核依赖，本次不审核")
        return {}
    out: dict[str, tuple[str, list[str]]] = {}
    for r in records:
        if r.main in EMPTY_NOTES:
            continue
        got = feedback_guard.classify(r.main)
        if got:
            out[r.ticket] = got
    return out


def render_item(r: Record, verdict: Optional[tuple[str, list[str]]], mode: str) -> tuple[str, bool]:
    """返回 (markdown, 是否展示了正文)。一条 = 一行头信息 + 引用正文；疑似违规的整条收进一个折叠行。"""
    def esc(s: str) -> str:
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    if r.category.startswith(RATING_PREFIX):
        tier = r.category.split("·")[-1].strip()
        parts = [f"{CATEGORY_ICONS[RATING_PREFIX]} {_DOTS.get(RATING_COLORS.get(tier, ''), '⚪')} {esc(tier)}"]
    else:
        parts = [f"{CATEGORY_ICONS.get(r.category, '📝')} <b>{esc(r.category or '未分类')}</b>"]
    parts.append(f"<code>{r.ticket}</code>")
    parts += [esc(x) for x in [short_version(r.version), r.device, r.system.split(" · ")[0]] if x]
    parts.append(r.created.strftime("%m-%d %H:%M"))
    head = " · ".join(parts)
    body = quote(r.main)
    diag = r.diagnostics
    if diag:
        body += ("\n\n<details><summary>诊断信息</summary>\n\n```\n"
                 + scrub_pii(diag[:4000]).replace("```", "ˋˋˋ") + "\n```\n\n</details>")
    flagged = verdict is not None and verdict[0] != "Safe"
    if not flagged:
        return f"{head}\n{body}", True
    tag = f"{_DOTS.get(VERDICT_COLORS.get(verdict[0], ''), '⚪')} <b>疑似 {verdict[0]}</b>" + (
        f"（{esc(', '.join(verdict[1]))}）" if verdict[1] else "")
    if mode == "block":
        return f"{head} · {tag} · 正文未展示", False
    return f"<details><summary>{head} · {tag}</summary>\n\n{body}\n\n</details>", True


def until_mark(until: Optional[int]) -> str:
    return f" until={until}" if until else ""


@dataclass
class FeedbackDigest:
    body: str
    summary: str
    # (工单号, 楼层 markdown)：每条一层楼，按类别排好
    items: list[tuple[str, str]]


def build_feedback(records: list[Record], verdicts: dict, mode: str, window: str,
                   until: Optional[int] = None, extra: list[str] = ()) -> Optional[FeedbackDigest]:
    """帖子正文放统计、版本评价表和用法，每条反馈单独一层楼。
    [extra]：只记进去重标记、不展示的工单（只有旧版本崩溃、没单独发崩溃日报时）。"""
    if not records:
        return None
    ratings = [r for r in records if r.category.startswith(RATING_PREFIX)]
    others = [r for r in records if r not in ratings]
    parts: list[str] = []
    items: list[tuple[str, str]] = []
    hidden: list[str] = []

    def add_item(r: Record) -> None:
        md, shown = render_item(r, verdicts.get(r.ticket), mode)
        if shown:
            items.append((r.ticket, md))
        else:
            hidden.append(md)

    if ratings:
        counts: dict[str, Counter] = defaultdict(Counter)
        for r in ratings:
            counts[short_version(r.version)][r.category.split("·")[-1].strip() or "?"] += 1
        tiers = sorted({k for c in counts.values() for k in c})
        rows = ["| 版本 | " + " | ".join(tiers) + " |", "|---" * (len(tiers) + 1) + "|"]
        for ver in sorted(counts, key=lambda v: core_version(v) or (0, 0, 0), reverse=True):
            rows.append(f"| {ver} | " + " | ".join(str(counts[ver][t]) for t in tiers) + " |")
        parts.append(f"### {CATEGORY_ICONS[RATING_PREFIX]} 版本评价（{len(ratings)}）\n\n" + "\n".join(rows))
    for cat in CATEGORIES + sorted({r.category for r in others} - set(CATEGORIES)):
        for r in others:
            if r.category == cat:
                add_item(r)
    for r in ratings:
        if r.main not in EMPTY_NOTES:
            add_item(r)
    if hidden:
        parts.append("### 未展示\n\n" + "\n\n".join(hidden))

    flagged = sum(1 for t, v in verdicts.items() if v[0] != "Safe")
    audit = {"off": "未开启审核", "mark": "审核只标记不拦截", "block": "审核未通过的不展示正文"}[mode]
    counts_line = " ".join(
        [badge("新收", str(len(records)), "informational")]
        + [badge(c, str(n), "blue") for c, n in Counter(
            RATING_PREFIX if r.category.startswith(RATING_PREFIX) else (r.category or "未分类") for r in records
        ).most_common()]
        + ([badge("疑似违规", str(flagged), "orange")] if flagged and mode != "off" else [])
    )
    intro = (f"{counts_line}\n\n<sub>{window} · {audit}</sub>\n\n"
             "> [!TIP]\n> 每条反馈是下面的一层楼，在那层楼里**回复**：\n"
             "> - `/reply`（可加 `私密`），下一行起写回复；第一行写 `问题：…` 可改应用内「大家在问」里的问题\n"
             "> - `/skip`：不回复\n"
             "> - `/toissue bug 1`：转成 issue（转完仍需 `/reply` 或 `/skip`）\n"
             "> \n> 在本帖顶层评论 `/skip all` 跳过剩下全部。都处理完本帖自动关闭。")
    tickets = [r.ticket for r in records] + list(extra)
    marks = (f"<!-- feedback-digest kind=feedback tickets={ids(tickets)}{until_mark(until)} -->\n"
             f"<!-- feedback-pending={ids(t for t, _ in items)} -->\n<!-- feedback-done= -->")
    body = marks + "\n\n" + intro + ("\n\n" + "\n\n".join(parts) if parts else "") + "\n"
    return FeedbackDigest(body, f"{len(records)} 条", items)


def build_crash(records: list[Record], min_ver: tuple[int, int, int], window: str,
                until: Optional[int] = None) -> Optional[tuple[str, str]]:
    fresh = [r for r in records if (core_version(r.version) or (0, 0, 0)) >= min_ver]
    if not fresh:
        return None
    old = len(records) - len(fresh)
    groups: dict[str, list[Record]] = defaultdict(list)
    for r in fresh:
        groups[scrub_pii(crash_signature(r.content))].append(r)
    parts: list[str] = []
    used = 0
    for i, (sig, rs) in enumerate(sorted(groups.items(), key=lambda kv: -len(kv[1])), 1):
        vers = Counter(short_version(r.version) for r in rs)
        devs = Counter(r.device for r in rs if r.device)
        sample = max(rs, key=lambda r: r.created)
        stack = scrub_pii(sample.content[:3000]).replace("```", "ˋˋˋ")
        md = (f"### {i}. `{one_line(sig, 160).replace('`', 'ˋ')}`\n"
              f"🔴 **{len(rs)} 次** · {'、'.join(f'{v} ×{n}' for v, n in vers.most_common(4))}"
              f" · {'、'.join(f'{d} ×{n}' for d, n in devs.most_common(3))}\n\n"
              f"<details><summary>样例 <code>{sample.ticket}</code> · {sample.created:%m-%d %H:%M}</summary>\n\n"
              f"```\n{stack}\n```\n\n</details>")
        if used + len(md) > BODY_BUDGET:
            parts.append(f"另有 {len(groups) - i + 1} 种因篇幅未展开。")
            break
        parts.append(md)
        used += len(md)
    ver_s = ".".join(map(str, min_ver))
    intro = " ".join([badge("崩溃", str(len(fresh)), "red"), badge("种类", str(len(groups)), "orange"),
                      badge("版本门槛", f"≥{ver_s}", "lightgrey")])
    intro += f"\n\n<sub>{window}" + (f" · 另有 {old} 条 {ver_s} 之前版本的崩溃未列出" if old else "") + "</sub>"
    marks = f"<!-- feedback-digest kind=crash tickets={ids(r.ticket for r in records)}{until_mark(until)} -->"
    body = marks + "\n\n" + intro + "\n\n" + "\n\n".join(parts) + "\n"
    return body, f"{len(fresh)} 条 / {len(groups)} 种"


def digest() -> None:
    gh = GitHub()
    fs = Feishu()
    days = int(env("WINDOW_DAYS", "7"))
    mode = env("MODERATION", "mark").lower()
    if mode not in {"off", "mark", "block"}:
        mode = "mark"
    min_ver = core_version(env("CRASH_MIN_VERSION", "5.1.1")) or (5, 1, 1)
    now = datetime.now(BEIJING)
    repo_id, category_id = gh.feedback_category()
    discussions = gh.feedback_discussions(category_id)
    since_ms = watermark(gh, discussions, int((now - timedelta(days=days)).timestamp() * 1000)) - OVERLAP_MS
    since = datetime.fromtimestamp(since_ms / 1000, BEIJING)

    seen = seen_tickets(gh, discussions, since_ms)
    records = fetch_since(fs, since_ms)
    fresh = [r for r in records if r.created >= since and r.ticket not in seen
             and not r.category.startswith(VOTE_PREFIX)]
    fresh.sort(key=lambda r: r.created)
    log(f"水位 {since:%m-%d %H:%M}，之后未收录 {len(fresh)} 条")
    if not fresh:
        return
    until = int(fresh[-1].created.timestamp() * 1000)
    start = fresh[0].created
    window = f"{start:%m-%d %H:%M} ～ {now:%m-%d %H:%M}（北京时间）"
    crashes = [r for r in fresh if r.category == CRASH]
    feedback = [r for r in fresh if r.category != CRASH]

    gh.ensure_labels()
    # 手动补跑时同一天可能有两期，标题带上时间区分
    manual = os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
    date = now.strftime("%Y-%m-%d %H:%M" if manual else "%Y-%m-%d")
    # 水位只写进本次最后发的那期：先发的成功、后发的失败时水位不动，下次补发
    built = build_crash(crashes, min_ver, window, None if feedback else until)
    if built:
        body, summary = built
        issue = gh.write("POST", "/issues", {"title": f"[崩溃日报] {date}（{summary}）",
                                             "body": body, "labels": CRASH_LABELS})
        log(f"崩溃日报：{issue.get('html_url')}")
    # 只有旧版本崩溃时不单发崩溃日报，工单号记进反馈日报的去重标记
    extra = [] if built else [r.ticket for r in crashes]
    verdicts = moderate(feedback, mode)
    digest_ = build_feedback(feedback, verdicts, mode, window, until, extra)
    if digest_:
        post_feedback(gh, repo_id, category_id, f"[反馈日报] {date}（{digest_.summary}）", digest_)


def post_feedback(gh: GitHub, repo_id: str, category_id: str, title: str, d: FeedbackDigest) -> None:
    """发帖，再一条反馈一层楼。有楼没发出去就把它从去重标记里摘掉、不写水位，下次补发。"""
    got = gh.gwrite("""mutation($r:ID!,$c:ID!,$t:String!,$b:String!){
        createDiscussion(input:{repositoryId:$r,categoryId:$c,title:$t,body:$b}){discussion{id url}}}""",
                    r=repo_id, c=category_id, t=title, b=d.body)
    disc = (got.get("createDiscussion") or {}).get("discussion") or {"id": "", "url": "(dry-run)"}
    log(f"反馈日报：{disc['url']}")
    failed: list[str] = []
    for ticket, md in d.items:
        try:
            gh.gwrite("""mutation($d:ID!,$b:String!){addDiscussionComment(input:{discussionId:$d,body:$b}){comment{id}}}""",
                      d=disc["id"], b=f"<!-- feedback-ticket:{ticket} -->\n{md}")
        except RuntimeError as e:
            log(f"::warning::{ticket} 没发出去：{e}")
            failed.append(ticket)
        if not DRY_RUN:
            time.sleep(1)  # 发内容有频率限制
        else:
            log(md + "\n")
    if failed and not DRY_RUN:
        body = d.body
        for rx, group in ((MARK_TICKETS, 2), (MARK_PENDING, 1)):
            m = rx.search(body)
            kept = ids(t for t in m.group(group).split(",") if t and t not in failed)
            body = body[:m.start(group)] + kept + body[m.end(group):]
        body = re.sub(r" until=\d+", "", body)
        gh.gwrite("""mutation($d:ID!,$b:String!){updateDiscussion(input:{discussionId:$d,body:$b}){discussion{id}}}""",
                  d=disc["id"], b=body)
        raise RuntimeError(f"{len(failed)} 条没发出去，已从标记里摘掉，下次补发")


# ---------------------------------------------------------------- 命令

@dataclass
class Command:
    verb: str
    tickets: list[str]
    public: bool = True
    question: str = ""
    reply: str = ""
    skip_all: bool = False


def parse_commands(body: str) -> list[Command]:
    cmds: list[Command] = []
    cur: Optional[Command] = None
    buf: list[str] = []
    fenced = False

    def flush() -> None:
        if cur is None:
            return
        lines = list(buf)
        while lines and not lines[0].strip():
            lines.pop(0)
        if cur.verb == "reply" and lines:
            m = QUESTION_RE.match(lines[0])
            if m:
                cur.question = m.group(1).strip()
                lines.pop(0)
        cur.reply = "\n".join(lines).strip()
        cmds.append(cur)

    for line in body.splitlines():
        if line.strip().startswith("```"):
            fenced = not fenced
        m = None if fenced or line.lstrip().startswith(">") else COMMAND_RE.match(line)
        if m:
            flush()
            buf = []
            args = m.group(2).split()
            tickets = [a.upper() for a in args if TICKET_RE.fullmatch(a.upper())]
            rest = [a for a in args if not TICKET_RE.fullmatch(a.upper())]
            cur = Command(verb=m.group(1).lower(), tickets=tickets)
            if cur.verb == "reply":
                cur.public = not any(a.lower() in PRIVATE_WORDS for a in rest)
            else:
                cur.skip_all = any(a.lower() in {"all", "全部", "其余"} for a in rest)
        elif cur is not None:
            buf.append(line)
    flush()
    return cmds


def issue_marks(body: str) -> tuple[set[str], set[str], set[str]]:
    def grab(rx: re.Pattern, group: int = 1) -> set[str]:
        m = rx.search(body)
        return {t for t in m.group(group).split(",") if t} if m else set()
    return grab(MARK_TICKETS, 2), grab(MARK_PENDING), grab(MARK_DONE)


@dataclass
class Outcome:
    fs: Optional[Feishu]
    newly: set[str]
    errors: list[str]
    replied: set[str] = field(default_factory=set)
    # 成功的每一项一行，讨论里显式回给操作者看
    notes: list[str] = field(default_factory=list)


def apply_commands(fs: Optional[Feishu], cmds: list[Command], tickets: set[str], pending: set[str],
                   default: Optional[str], where: str, author: str = "") -> Outcome:
    """执行一条评论里的命令。[default]：没写工单号时用的工单（讨论里回复的那层楼）；
    [author]：评论人的 GitHub 用户名，写进「回复人」，App 里显示是谁回的。"""
    errors: list[str] = []
    newly: set[str] = set()
    notes: list[str] = []
    for c in cmds:
        if c.verb == "skip" and c.skip_all:
            newly |= pending
            notes.append(f"已跳过本期剩下的 {len(pending)} 条")
            continue
        if not c.tickets and default:
            c.tickets = [default]
        if not c.tickets:
            errors.append(f"`/{c.verb}` 不知道是哪条反馈：在那条反馈下回复，或写上工单号")
            continue
        unknown = [t for t in c.tickets if t not in tickets]
        if unknown:
            errors.append(f"{'、'.join(unknown)} 不在这期日报里")
            continue
        if c.verb == "skip":
            newly.update(c.tickets)
            notes.append("已跳过 " + "、".join(f"`{t}`" for t in c.tickets))
            continue
        if len(c.tickets) != 1:
            errors.append("`/reply` 一次只回一个工单")
            continue
        if not c.reply:
            errors.append(f"`{c.tickets[0]}` 的回复正文是空的")
            continue
        t = c.tickets[0]
        try:
            fs = fs or Feishu()
            question = c.question
            if not question:
                f = fs.submission(t) or {}
                question = one_line(text(f.get("内容")).split(DIAGNOSTICS_SEPARATOR, 1)[0], QUESTION_LIMIT)
            fields = {"工单号": t, "回复": c.reply,
                      # 精确到秒：同一工单可重复回复，App 按这个字符串取最新
                      "回复时间": datetime.now(BEIJING).strftime("%Y-%m-%d %H:%M:%S"),
                      "公开": c.public, "问题": question}
            if author:
                fields["回复人"] = author
            if DRY_RUN:
                log(f"[dry-run] 回复表 += {json.dumps(fields, ensure_ascii=False)}")
            else:
                fs.add_reply(fields)
            newly.add(t)
            notes.append(f"`{t}` 已写入飞书 · "
                         + ("公开，问题显示为「" + one_line(question, 60).replace("`", "'") + "」" if c.public
                            else "私密，只有提问的人能看到"))
            log(f"{where} {t} 已写回复（公开={c.public}）")
        except Exception as e:  # noqa: BLE001
            errors.append(f"`{t}` 写入飞书失败：{e}")
    replied = {c.tickets[0] for c in cmds if c.verb == "reply" and c.tickets and c.tickets[0] in newly}
    return Outcome(fs, newly, errors, replied, notes)


def handle(gh: GitHub, fs: Optional[Feishu], issue: dict, comment: dict) -> Optional[Feishu]:
    """旧版：反馈日报还是 issue 时的命令（处理完已有的那几期就用不上了）。"""
    if comment.get("author_association") not in TRUSTED:
        return fs
    if LABEL_FEEDBACK not in {l["name"] for l in issue.get("labels") or []}:
        return fs
    cmds = parse_commands(comment.get("body") or "")
    if not cmds:
        return fs
    number = issue["number"]
    cid = comment["id"]
    body = issue.get("body") or ""
    tickets, pending, done = issue_marks(body)
    out = apply_commands(fs, cmds, tickets, pending, None, f"#{number}", (comment.get("user") or {}).get("login", ""))
    fs, newly, errors = out.fs, out.newly, out.errors
    if newly - done:
        done |= newly
        new_body = MARK_DONE.sub(f"<!-- feedback-done={ids(done)} -->", body)
        gh.write("PATCH", f"/issues/{number}", {"body": new_body})
        issue["body"] = new_body
    if errors:
        msg = MARK_FAILED.format(cid) + "\n" + "\n".join(f"- {e}" for e in errors) + "\n\n" + USAGE
        gh.write("POST", f"/issues/{number}/comments", {"body": msg})
    if newly and not errors:
        gh.write("POST", f"/issues/comments/{cid}/reactions", {"content": "rocket"})
    if pending and pending <= done and issue.get("state") == "open":
        gh.write("POST", f"/issues/{number}/labels", {"labels": [LABEL_DONE]})
        gh.write("PATCH", f"/issues/{number}", {"state": "closed", "state_reason": "completed"})
        issue["state"] = "closed"
        log(f"#{number} 全部处理完，已关闭")
    return fs


def scan() -> None:
    gh = GitHub()
    days = int(env("WINDOW_DAYS", "7"))
    cutoff = datetime.now(timezone.utc) - timedelta(days=days)
    fs: Optional[Feishu] = None
    for issue in gh.pages(f"/issues?labels={LABEL_FEEDBACK}&state=open"):
        labels = {l["name"] for l in issue.get("labels") or []}
        created = datetime.fromisoformat(issue["created_at"].replace("Z", "+00:00"))
        if LABEL_DONE in labels or created < cutoff or "pull_request" in issue:
            continue
        comments = gh.pages(f"/issues/{issue['number']}/comments")
        failed = {c["body"] for c in comments if (c.get("user") or {}).get("login") == BOT}
        for c in comments:
            if issue.get("state") != "open":
                break
            if c.get("author_association") not in TRUSTED or not parse_commands(c.get("body") or ""):
                continue
            if any(MARK_FAILED.format(c["id"]) in b for b in failed) or gh.rocketed(c["id"]):
                continue
            log(f"补处理 #{issue['number']} 评论 {c['id']}")
            fs = handle(gh, fs, issue, c)


def on_comment(number: int, comment_id: int) -> None:
    gh = GitHub()
    issue = gh.call("GET", f"/issues/{number}")
    comment = gh.call("GET", f"/issues/comments/{comment_id}")
    handle(gh, None, issue, comment)


# ---------------------------------------------------------------- 讨论区命令

_COMMENT_FIELDS = """id body authorAssociation author{login}
    replyTo{id body}
    discussion{id number body closed category{name}}"""


def is_feedback_discussion(category: str) -> bool:
    return category == env("FEEDBACK_DISCUSSION_CATEGORY", "反馈日报")


def run_discussion_commands(gwrite, fs: Optional[Feishu], c: dict) -> Optional[Outcome]:
    """执行讨论里一条评论的 /reply、/skip：写飞书、更新帖子标记、给反馈楼标反应、都处理完关帖。
    回复某层楼时工单从那层楼的标记认；顶层评论里得写工单号（或 /skip all）。
    结果消息由调用方发（事件走 discussion_commands.py 的框架，兜底扫描走 [handle_discussion]）。
    [gwrite]：(query, **variables) -> data。不归这里管（不是反馈日报、已关闭、没有命令）返回 None。"""
    disc = c["discussion"]
    if not is_feedback_discussion(disc["category"]["name"]) or disc["closed"]:
        return None
    cmds = parse_commands(c.get("body") or "")
    if not cmds:
        return None
    parent = c.get("replyTo")
    default = None
    if parent:
        m = MARK_ITEM.search(parent.get("body") or "")
        default = m.group(1) if m else None
    tickets, pending, done = issue_marks(disc["body"])
    out = apply_commands(fs, cmds, tickets, pending, default, f"讨论 #{disc['number']}",
                         (c.get("author") or {}).get("login", ""))

    # 飞书已经写了：后面这几步失败只记一条警告，不把整次算失败
    def step(what: str, query: str, **variables: Any) -> bool:
        try:
            gwrite(query, **variables)
            return True
        except Exception as e:  # noqa: BLE001
            out.errors.append(f"{what}失败（飞书那边已写好）：{e}")
            return False

    if out.newly - done:
        done |= out.newly
        disc["body"] = MARK_DONE.sub(f"<!-- feedback-done={ids(done)} -->", disc["body"])
        step("更新帖子里的处理标记",
             """mutation($d:ID!,$b:String!){updateDiscussion(input:{discussionId:$d,body:$b}){discussion{id}}}""",
             d=disc["id"], b=disc["body"])
    # 那层反馈楼上标一下：回复过 🎉，跳过 👀，扫一眼就知道哪条还没处理
    if parent and default in out.newly:
        step("给反馈楼加表情",
             """mutation($s:ID!,$e:ReactionContent!){addReaction(input:{subjectId:$s,content:$e}){reaction{content}}}""",
             s=parent["id"], e="HOORAY" if default in out.replied else "EYES")
    if pending and pending <= done and step(
            "关闭讨论", """mutation($d:ID!){closeDiscussion(input:{discussionId:$d,reason:RESOLVED}){discussion{id}}}""",
            d=disc["id"]):
        disc["closed"] = True
        out.notes.append("本期都处理完了，已关闭")
        log(f"讨论 #{disc['number']} 全部处理完，已关闭")
    return out


def result_lines(out: Outcome) -> str:
    return "\n".join([f"- ✅ {n}" for n in out.notes] + [f"- ⚠️ {e}" for e in out.errors])


def handle_discussion(gh: GitHub, fs: Optional[Feishu], c: dict) -> Optional[Feishu]:
    """兜底扫描用：执行后自己在那串楼里回一条结果（事件触发时这一步由框架做）。"""
    if c.get("authorAssociation") not in TRUSTED:
        return fs
    out = run_discussion_commands(gh.gwrite, fs, c)
    if out is None:
        return fs
    msg = result_lines(out)
    if out.errors:
        msg = MARK_FAILED.format(c["id"]) + "\n" + msg + "\n\n" + USAGE
    if msg:
        gh.gwrite("""mutation($d:ID!,$r:ID!,$b:String!){
            addDiscussionComment(input:{discussionId:$d,replyToId:$r,body:$b}){comment{id}}}""",
                  d=c["discussion"]["id"], r=(c.get("replyTo") or c)["id"], b=msg)
    return out.fs


def scan_discussions() -> None:
    """兜底：事件漏触发时补处理。只看 7 天内、还开着的反馈日报；已成功（done 覆盖）或已回过失败说明的跳过。"""
    gh = GitHub()
    _, category_id = gh.feedback_category()
    cutoff = datetime.now(timezone.utc) - timedelta(days=int(env("WINDOW_DAYS", "7")))
    fs: Optional[Feishu] = None
    for d in gh.feedback_discussions(category_id, limit=20, open_only=True):
        if datetime.fromisoformat(d["createdAt"].replace("Z", "+00:00")) < cutoff:
            continue
        full = gh.graphql(f"""query($id:ID!){{node(id:$id){{... on Discussion{{
            comments(first:100){{nodes{{{_COMMENT_FIELDS} replies(first:100){{nodes{{{_COMMENT_FIELDS}}}}}}}}}}}}}}}""",
                          id=d["id"])["node"]
        flat = []
        for top in full["comments"]["nodes"]:
            flat.append(top)
            flat += top["replies"]["nodes"]
        failed = "".join(x["body"] for x in flat if "feedback-cmd-failed:" in x["body"])
        for c in flat:
            cmds = parse_commands(c.get("body") or "")
            if not cmds or c.get("authorAssociation") not in TRUSTED or MARK_FAILED.format(c["id"]) in failed:
                continue
            tickets, pending, done = issue_marks(c["discussion"]["body"])
            parent = MARK_ITEM.search((c.get("replyTo") or {}).get("body") or "")
            targets = {t for x in cmds for t in (x.tickets or ([parent.group(1)] if parent else []))}
            if any(x.skip_all for x in cmds):
                targets |= pending
            if c["discussion"]["closed"] or (targets and targets <= done):
                continue
            log(f"补处理讨论 #{c['discussion']['number']} 评论 {c['id']}")
            fs = handle_discussion(gh, fs, c)


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        raise SystemExit(2)
    cmd = sys.argv[1]
    if cmd == "digest":
        digest()
        scan()
        scan_discussions()
    elif cmd == "scan":
        scan()
        scan_discussions()
    elif cmd == "comment" and len(sys.argv) == 4:
        on_comment(int(sys.argv[2]), int(sys.argv[3]))
    else:
        print(__doc__)
        raise SystemExit(2)


if __name__ == "__main__":
    main()
