#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""飞书反馈表 → GitHub issue 日报，以及 issue 里的 /reply、/skip 回写飞书回复表。

    python3 feedback_digest.py digest            # 每日：发日报，再扫一遍漏处理的命令
    python3 feedback_digest.py scan              # 只扫命令
    python3 feedback_digest.py comment ISSUE ID  # issue_comment 事件：处理这一条评论

日报
* 取飞书提交表最近 WINDOW_DAYS 天的记录，跳过已收进往期日报的工单（看 issue 正文的隐藏标记），
  所以漏跑的天数下次自动补上。
* 崩溃单独一条 issue，只收核心版本 ≥ CRASH_MIN_VERSION 的（预览版按核心版本算），按异常签名聚合。
* 其余进反馈 issue：版本评价按档计数、有文字的逐条列；功能坏了 / 有点难用 / 想要新功能 / 其他逐条列。
  「投票·」开头的是公告投票，不进日报。联系方式一律不发。
* 审核（MODERATION）：off 不审；mark（默认）用 Qwen3Guard 打标签，Unsafe / Controversial 照发但标出并折叠；
  block 时这两档不展示正文，只列工单号。

命令（只认 OWNER / MEMBER / COLLABORATOR，只在带 feedback 标签的 issue 里）
    /reply T工单号 [私密]
    回复正文（可多行）；正文第一行写「问题: …」可覆盖公开问答里的问题，默认用反馈原文
    /skip T工单号 [T工单号…] | /skip all
* /reply 往回复表追加一条（工单号、回复、回复时间、公开、问题），公开默认勾上，写「私密」等则不勾。
* 一条评论可以有多段命令。成功点 🚀，失败回一条说明。
* 待处理的工单都回复或跳过后，打「已处理」标签并关闭 issue。
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

CATEGORIES = ["功能坏了", "有点难用", "想要新功能", "其他"]
RATING_PREFIX = "版本评价"
VOTE_PREFIX = "投票"
CRASH = "崩溃"
EMPTY_NOTES = {"", "（未填写）", "(未填写)"}

TICKET_RE = re.compile(r"T[A-Z0-9]{8,16}")
MARK_TICKETS = re.compile(r"<!-- feedback-digest kind=(\w+) tickets=([A-Z0-9,]*) -->")
MARK_PENDING = re.compile(r"<!-- feedback-pending=([A-Z0-9,]*) -->")
MARK_DONE = re.compile(r"<!-- feedback-done=([A-Z0-9,]*) -->")
MARK_FAILED = "<!-- feedback-cmd-failed:{} -->"
COMMAND_RE = re.compile(r"^[ \t]*/(reply|skip)\b[ \t]*(.*)$", re.IGNORECASE)
QUESTION_RE = re.compile(r"^\s*问题\s*[:：]\s*(.+)$")
PRIVATE_WORDS = {"私密", "不公开", "否", "private", "no", "false", "0"}
TRUSTED = {"OWNER", "MEMBER", "COLLABORATOR"}

USAGE = """<details open><summary>用法</summary>

```
/reply T工单号 [私密]
问题: 公开问答里显示的问题（可省，默认用反馈原文）
回复正文，可多行
```

- 默认进应用内「大家在问」；工单号后加 `私密`（或 `不公开`、`private`）则只有提问的人能看到
- 一条评论里可以写多段 `/reply`，每段一个工单
- 不回复：`/skip T工单号 [T工单号…]`，或 `/skip all` 跳过本期剩下的全部
- 待处理的都回复或跳过后，issue 自动打「已处理」并关闭

</details>"""

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
        r = http("POST", f"{FEISHU}/bitable/v1/apps/{self.base}/tables/{self.reply_table}/records",
                 {"fields": fields}, self.auth)
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

    @staticmethod
    def of(row: dict) -> Optional["Record"]:
        f = row.get("fields") or {}
        m = TICKET_RE.search(text(f.get("工单号")))
        if not m:
            return None
        ms = row.get("created_time") or 0
        return Record(
            ticket=m.group(0),
            category=text(f.get("类型")).strip(),
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


CATEGORY_ICONS = {"功能坏了": "🐞", "有点难用": "😣", "想要新功能": "✨", "其他": "💬", RATING_PREFIX: "⭐"}
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

def seen_tickets(gh: GitHub, days: int) -> set[str]:
    since = (datetime.now(timezone.utc) - timedelta(days=days + 3)).strftime("%Y-%m-%dT%H:%M:%SZ")
    seen: set[str] = set()
    for label in (LABEL_FEEDBACK, LABEL_CRASH):
        for issue in gh.pages(f"/issues?labels={label}&state=all&since={since}"):
            for m in MARK_TICKETS.finditer(issue.get("body") or ""):
                seen.update(t for t in m.group(2).split(",") if t)
    return seen


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
        if r.content in EMPTY_NOTES:
            continue
        got = feedback_guard.classify(r.content)
        if got:
            out[r.ticket] = got
    return out


def render_item(r: Record, verdict: Optional[tuple[str, list[str]]], mode: str) -> tuple[str, bool]:
    """返回 (markdown, 是否展示了正文)。"""
    badges = [badge("版本", short_version(r.version), "blue")]
    if r.category.startswith(RATING_PREFIX):
        tier = r.category.split("·")[-1].strip()
        badges.insert(0, badge("评价", tier, RATING_COLORS.get(tier, "lightgrey")))
    flagged = verdict is not None and verdict[0] != "Safe"
    if flagged:
        badges.append(badge("审核", verdict[0], VERDICT_COLORS.get(verdict[0], "lightgrey")))
    meta = " · ".join(x for x in [r.device, r.system.split(" · ")[0], r.created.strftime("%m-%d %H:%M")] if x)
    head = f"#### `{r.ticket}` {' '.join(badges)}\n<sub>{meta}</sub>"
    if flagged:
        cats = f"（{', '.join(verdict[1])}）" if verdict[1] else ""
        if mode == "block":
            return f"{head}\n\n⚠️ 疑似 {verdict[0]}{cats}，正文未展示", False
        return (f"{head}\n\n<details><summary>⚠️ 疑似 {verdict[0]}{cats}，点开查看</summary>\n\n"
                f"{quote(r.content)}\n\n</details>"), True
    return f"{head}\n\n{quote(r.content)}", True


def build_feedback(records: list[Record], verdicts: dict, mode: str, window: str) -> Optional[tuple[str, str]]:
    ratings = [r for r in records if r.category.startswith(RATING_PREFIX)]
    others = [r for r in records if r not in ratings]
    if not records:
        return None
    pending: list[str] = []
    parts: list[str] = []
    used = 0

    def add(md: str) -> bool:
        nonlocal used
        if used + len(md) > BODY_BUDGET:
            return False
        parts.append(md)
        used += len(md)
        return True

    overflow: list[str] = []
    if ratings:
        counts: dict[str, Counter] = defaultdict(Counter)
        for r in ratings:
            counts[short_version(r.version)][r.category.split("·")[-1].strip() or "?"] += 1
        tiers = sorted({k for c in counts.values() for k in c})
        rows = ["| 版本 | " + " | ".join(tiers) + " |", "|---" * (len(tiers) + 1) + "|"]
        for ver in sorted(counts, key=lambda v: core_version(v) or (0, 0, 0), reverse=True):
            rows.append(f"| {ver} | " + " | ".join(str(counts[ver][t]) for t in tiers) + " |")
        add(f"### {CATEGORY_ICONS[RATING_PREFIX]} 版本评价（{len(ratings)}）\n\n" + "\n".join(rows))
        noted = [r for r in ratings if r.content not in EMPTY_NOTES]
        if noted:
            add("有文字的：")
            for r in noted:
                md, shown = render_item(r, verdicts.get(r.ticket), mode)
                if shown:
                    pending.append(r.ticket)
                if not add(md):
                    overflow.append(r.ticket)
    for cat in CATEGORIES + sorted({r.category for r in others} - set(CATEGORIES)):
        group = [r for r in others if r.category == cat]
        if not group:
            continue
        add(f"---\n\n### {CATEGORY_ICONS.get(cat, '📝')} {cat or '未分类'}（{len(group)}）")
        for r in group:
            md, shown = render_item(r, verdicts.get(r.ticket), mode)
            if shown:
                pending.append(r.ticket)
            if not add(md):
                overflow.append(r.ticket)
    if overflow:
        parts.append(f"另有 {len(overflow)} 条因篇幅未展开，可直接按工单号回复：" + "、".join(f"`{t}`" for t in overflow))

    flagged = sum(1 for t, v in verdicts.items() if v[0] != "Safe")
    audit = {"off": "未开启审核", "mark": "审核只标记不拦截", "block": "审核未通过的不展示正文"}[mode]
    counts_line = " ".join(
        [badge("新收", str(len(records)), "informational")]
        + [badge(c, str(n), "blue") for c, n in Counter(
            RATING_PREFIX if r.category.startswith(RATING_PREFIX) else (r.category or "未分类") for r in records
        ).most_common()]
        + ([badge("疑似违规", str(flagged), "orange")] if flagged and mode != "off" else [])
    )
    intro = (f"{counts_line}\n\n{window}，{audit}。\n\n"
             "> [!TIP]\n> 回复：评论 `/reply 工单号`（默认进应用内「大家在问」，末尾加 `私密` 则不进），下一行起写回复；"
             "第一行写 `问题: …` 可改公开问答里的问题。\n> 不回复：`/skip 工单号` 或 `/skip all`。全部处理完自动关闭。")
    marks = (f"<!-- feedback-digest kind=feedback tickets={ids(r.ticket for r in records)} -->\n"
             f"<!-- feedback-pending={ids(pending)} -->\n<!-- feedback-done= -->")
    body = marks + "\n\n" + intro + "\n\n" + "\n\n".join(parts) + "\n"
    return body, f"{len(records)} 条"


def build_crash(records: list[Record], min_ver: tuple[int, int, int], window: str) -> Optional[tuple[str, str]]:
    if not records:
        return None
    fresh = [r for r in records if (core_version(r.version) or (0, 0, 0)) >= min_ver]
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
        md = (f"### {i}. `{one_line(sig, 160).replace('`', 'ˋ')}`\n\n"
              + " ".join([badge("次数", str(len(rs)), "red")]
                         + [badge(v, f"×{n}", "blue") for v, n in vers.most_common(4)])
              + f"\n<sub>机型：{'、'.join(f'{d} ×{n}' for d, n in devs.most_common(5))}</sub>\n\n"
              f"<details><summary>样例 {sample.ticket}（{sample.created:%m-%d %H:%M}）</summary>\n\n"
              f"```\n{stack}\n```\n\n</details>")
        if used + len(md) > BODY_BUDGET:
            parts.append(f"另有 {len(groups) - i + 1} 种因篇幅未展开。")
            break
        parts.append(md)
        used += len(md)
    ver_s = ".".join(map(str, min_ver))
    intro = " ".join([badge("崩溃", str(len(fresh)), "red"), badge("种类", str(len(groups)), "orange"),
                      badge("版本门槛", f"≥{ver_s}", "lightgrey")])
    intro += f"\n\n{window}。"
    if old:
        intro += f"另有 {old} 条 {ver_s} 之前版本的崩溃未列出。"
    marks = f"<!-- feedback-digest kind=crash tickets={ids(r.ticket for r in records)} -->"
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
    cutoff = now - timedelta(days=days)

    seen = seen_tickets(gh, days)
    records = [r for r in (Record.of(x) for x in fs.search(fs.submit_table)) if r]
    fresh = [r for r in records if r.created >= cutoff and r.ticket not in seen
             and not r.category.startswith(VOTE_PREFIX)]
    fresh.sort(key=lambda r: r.created)
    log(f"飞书共 {len(records)} 条，{days} 天内未收录 {len(fresh)} 条")
    if not fresh:
        return
    start = fresh[0].created
    window = f"{start:%m-%d %H:%M} ～ {now:%m-%d %H:%M}（北京时间）"
    crashes = [r for r in fresh if r.category == CRASH]
    feedback = [r for r in fresh if r.category != CRASH]

    gh.ensure_labels()
    # 手动补跑时同一天可能有两期，标题带上时间区分
    manual = os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
    date = now.strftime("%Y-%m-%d %H:%M" if manual else "%Y-%m-%d")
    built = build_crash(crashes, min_ver, window)
    if built:
        body, summary = built
        issue = gh.write("POST", "/issues", {"title": f"[崩溃日报] {date}（{summary}）",
                                             "body": body, "labels": [LABEL_CRASH]})
        log(f"崩溃日报：{issue.get('html_url')}")
    verdicts = moderate(feedback, mode)
    built = build_feedback(feedback, verdicts, mode, window)
    if built:
        body, summary = built
        issue = gh.write("POST", "/issues", {"title": f"[反馈日报] {date}（{summary}）",
                                             "body": body, "labels": [LABEL_FEEDBACK]})
        log(f"反馈日报：{issue.get('html_url')}")
        if DRY_RUN:
            log(body)


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


def handle(gh: GitHub, fs: Optional[Feishu], issue: dict, comment: dict) -> Optional[Feishu]:
    """处理一条评论；返回（可能新建的）飞书客户端，便于扫描时复用。"""
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
    errors: list[str] = []
    newly: set[str] = set()
    for c in cmds:
        if c.verb == "skip" and c.skip_all:
            newly |= pending
            continue
        if not c.tickets:
            errors.append(f"`/{c.verb}` 没写工单号")
            continue
        unknown = [t for t in c.tickets if t not in tickets]
        if unknown:
            errors.append(f"{'、'.join(unknown)} 不在这期日报里")
            continue
        if c.verb == "skip":
            newly.update(c.tickets)
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
                question = one_line(text(f.get("内容")), QUESTION_LIMIT)
            fields = {"工单号": t, "回复": c.reply,
                      "回复时间": datetime.now(BEIJING).strftime("%Y-%m-%d %H:%M"),
                      "公开": c.public, "问题": question}
            if DRY_RUN:
                log(f"[dry-run] 回复表 += {json.dumps(fields, ensure_ascii=False)}")
            else:
                fs.add_reply(fields)
            newly.add(t)
            log(f"#{number} {t} 已写回复（公开={c.public}）")
        except Exception as e:  # noqa: BLE001
            errors.append(f"`{t}` 写入飞书失败：{e}")

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


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        raise SystemExit(2)
    cmd = sys.argv[1]
    if cmd == "digest":
        digest()
        scan()
    elif cmd == "scan":
        scan()
    elif cmd == "comment" and len(sys.argv) == 4:
        on_comment(int(sys.argv[2]), int(sys.argv[3]))
    else:
        print(__doc__)
        raise SystemExit(2)


if __name__ == "__main__":
    main()
