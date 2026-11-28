#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""讨论区公告分类（slug announcements）→ App 公告（bulletin.json，工作流提交到 announce 分支、再传 OSS）。

    python3 announce.py event   # 讨论区有变动（新建 / 编辑 / 关闭 / 删除…）：重建公告，不统计
    python3 announce.py tally   # 每 4 天：统计投票、删已截止投票的票、过期的帖子关掉、生成「大家在问」，再重建
    python3 announce.py poll    # 评论区 /tally：只统计 POLL_NUMBER 那一帖的投票（不删票、不关帖），再重建

* 一帖一条公告：标题 = 帖子标题，其余按发帖表单（.github/DISCUSSION_TEMPLATE/announcements.yml）的「### 小标题」解析。
  只认按表单格式写的帖（有「### 正文」一节）；分类里别的帖子（旧帖、随手发的讨论）不进 App。
  不查作者：公告分类是 GitHub 公告格式，只有维护者能发帖或把 Issue 转进来，帖子在这里就是维护者放行的。
  「校园系统状态」Issue 的最后一项就叫「正文」，转为讨论后直接生效，标题开头的「[系统]」自动去掉。
  关帖 = 删除（普通公告与投票都从 App 撤下，机器人不再回帖）；要提前结束投票就改「截止时间」。
* 公告 id =「发帖日期-d帖子号」。bulletin.json 每次整份重新生成，只含讨论区的公告；
  一条表单公告都还没有时不生成（announce 分支上手写的旧公告不动）。
* 投票：票在反馈后端（App 写，这里用 FEEDBACK_ADMIN_TOKEN 读 /admin/polls/votes）。同一设备只算最后一次，截止后投的不算。
  结果存在机器人在帖子里的结果回复（隐藏 JSON），重建时从那里读回；截止后另发一条最终结果，
  两条都写进帖子后，下一步把后端里这次投票的票删掉。
* 机器人在帖子里回一条解析结果；分类里没按表单写的帖子（如 App 里发的）新建或编辑时回一条提示和表单骨架。
* feedback-qa.json（只在 tally 时生成）：「大家在问」，取自反馈后端的 /public-qa。
文件写进 OUT_DIR（announce 分支的检出），提交推送由工作流做（announce.yml）。
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
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Optional

BEIJING = timezone(timedelta(hours=8))
GITHUB = "https://api.github.com"
GRAPHQL = "https://api.github.com/graphql"
DEFAULT_FEEDBACK_URL = "https://api.dzhz.top/feedback"
BOT_LOGINS = {"github-actions", "github-actions[bot]"}
VOTE_PREFIX = "投票"
DRY_RUN = os.environ.get("DRY_RUN", "").strip().lower() in {"1", "true", "yes"}


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


def http(method: str, url: str, body: Any = None, headers: Optional[dict] = None) -> Any:
    data = json.dumps(body).encode() if body is not None else None
    h = {
        "Content-Type": "application/json; charset=utf-8",
        "User-Agent": "dzhz-announce/1.0",
        **(headers or {}),
    }
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


class GitHub:
    def __init__(self) -> None:
        self.repo = env("GITHUB_REPOSITORY")
        self.auth = {"Authorization": "Bearer " + env("GITHUB_TOKEN"),
                     "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28"}

    def owner_name(self) -> tuple[str, str]:
        owner, name = self.repo.split("/", 1)
        return owner, name

    def graphql(self, query: str, **variables: Any) -> dict:
        r = http("POST", GRAPHQL, {"query": query, "variables": variables}, self.auth)
        if r.get("errors") or r.get("_status"):
            raise RuntimeError(f"GitHub GraphQL 失败：{json.dumps(r.get('errors') or r.get('message'), ensure_ascii=False)[:300]}")
        return r["data"]

    def gwrite(self, query: str, **variables: Any) -> dict:
        if DRY_RUN:
            log(f"[dry-run] GraphQL {query.strip().splitlines()[0][:80]}")
            return {}
        return self.graphql(query, **variables)


class FeedbackBackend:
    def __init__(self) -> None:
        self.base = env("FEEDBACK_BASE_URL", DEFAULT_FEEDBACK_URL).rstrip("/")
        self.auth = {"Authorization": "Bearer " + env("FEEDBACK_ADMIN_TOKEN")}

    def _call(self, method: str, path: str, body: Any = None) -> Any:
        r = http(method, f"{self.base}{path}", body, self.auth)
        if isinstance(r, dict) and r.get("_status"):
            raise RuntimeError(f"反馈后端 {method} {path} → {r['_status']} {r.get('error') or r.get('message')}")
        return r

    def public_qa(self) -> list[dict]:
        d = http("GET", f"{self.base}/public-qa")
        if isinstance(d, dict) and d.get("_status"):
            raise RuntimeError(f"拉取公开问答失败：{d['_status']} {d.get('error') or d.get('message')}")
        return [
            {"q": x.get("question", ""), "a": x.get("answer", ""), "c": x.get("category", ""), "b": x.get("by", "")}
            for x in (d.get("items") or [])
            if x.get("question") and x.get("answer")
        ]

    def poll_votes(self, poll_ids: list[str]) -> list[dict]:
        out: list[dict] = []
        for i in range(0, len(poll_ids), 40):
            ids_q = urllib.parse.quote(",".join(poll_ids[i:i + 40]))
            d = self._call("GET", f"/admin/polls/votes?ids={ids_q}")
            out += d.get("votes") or []
        return out

    def purge_votes(self, poll_ids: list[str]) -> int:
        deleted = 0
        for i in range(0, len(poll_ids), 40):
            ids_q = urllib.parse.quote(",".join(poll_ids[i:i + 40]))
            d = self._call("DELETE", f"/admin/polls/votes?ids={ids_q}")
            deleted += int(d.get("deleted") or 0)
        return deleted

QA_FILE = "feedback-qa.json"
BULLETIN_FILE = "bulletin.json"
QA_LIMIT = 300
OTHER = "其他"
MAX_OPTIONS = 10
POLL_DAYS = 7
LEVELS = {"普通": "info", "提醒": "warn", "更新": "update", "重要": "critical", "强制更新": "force_update"}
MARK_STATUS = "<!-- announce-status -->"
MARK_RESULT = "<!-- poll-result:{} -->"
MARK_FINAL = "<!-- poll-final:{} -->"
DATA_RE = re.compile(r"<!-- poll-data:(\{.*?\}) -->", re.S)
NO_RESPONSE = {"_No response_", "None", "无"}
FORM_LABELS = {"等级", "正文", "链接", "开始时间", "截止时间", "最低版本", "最高版本", "低于此版本强制更新", "投票选项", "设置"}
FENCE_RE = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")


# ---------------------------------------------------------------- 文件

def save(path: Path, data: Any) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as f:  # 仓库是 LF
        f.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def parse_time(raw: Any) -> Optional[datetime]:
    """2026-12-01 / 2026-12-01 08:00 / 带时区的 ISO；不带时区按北京时间。格式不对返回 None。"""
    s = str(raw or "").strip().replace("：", ":")
    if not s:
        return None
    try:
        t = datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None
    return t if t.tzinfo else t.replace(tzinfo=BEIJING)


def iso(t: datetime) -> str:
    return t.astimezone(BEIJING).replace(microsecond=0).isoformat()


# ---------------------------------------------------------------- 讨论帖 → 公告

@dataclass
class Notice:
    node_id: str
    number: int
    url: str
    created: datetime
    closed_at: Optional[datetime]
    # 按表单格式写的；只有这种才进 App、才回解析结果
    managed: bool
    bulletin: Optional[dict]
    errors: list[str]
    bot_comments: list[dict] = field(default_factory=list)

    def bot_comment(self, mark: str) -> Optional[dict]:
        # 取最新一条：旧的删不掉时会留下多条
        return next((c for c in reversed(self.bot_comments) if mark in c["body"]), None)


def sections(body: str) -> Optional[dict[str, str]]:
    """表单生成的「### 小标题\\n\\n内容」；没有「正文」这一节返回 None（不是用表单发的）。"""
    out: dict[str, str] = {}
    cur: Optional[str] = None
    buf: list[str] = []
    fence = ""
    for line in body.splitlines():
        marker = FENCE_RE.match(line)
        if fence:
            buf.append(line)
            if marker and marker[1].startswith(fence) and not marker[2].strip():
                fence = ""
            continue
        if marker and cur is not None:
            fence = marker[1]
            buf.append(line)
            continue
        m = re.match(r"^###\s+(.+?)\s*$", line)
        if m and m[1] in FORM_LABELS:
            if cur is not None:
                out[cur] = "\n".join(buf).strip()
            cur, buf = m.group(1), []
        elif cur is not None:
            buf.append(line)
    if cur is not None:
        out[cur] = "\n".join(buf).strip()
    if "正文" not in out:
        return None
    return {k: "" if v in NO_RESPONSE else v for k, v in out.items()}


def checked(text: str) -> set[str]:
    return {m.group(1).strip() for m in re.finditer(r"^\s*-\s*\[[xX]\]\s*(.+)$", text, re.M)}


def clean(text: str) -> str:
    return re.sub(r"<!--.*?-->", "", text, flags=re.S).strip()


def to_bulletin(d: dict, created: datetime, closed_at: Optional[datetime]) -> tuple[Optional[dict], list[str]]:
    """一帖 → 一条公告（不含投票结果）。有错返回 (None, 错误)；
    不是用表单发的、已关闭的帖子返回 (None, [])。"""
    if closed_at:
        return None, []
    sec = sections((d.get("body") or "").replace("\r\n", "\n"))
    if sec is None:
        return None, []
    errors: list[str] = []
    b: dict[str, Any] = {"id": f"{created:%Y-%m-%d}-d{d['number']}",
                         "level": LEVELS.get(sec.get("等级", "").strip(), sec.get("等级", "").strip() or "info"),
                         "title": re.sub(r"^\s*\[系统\]\s*", "", d["title"]).strip(), "body": clean(sec.get("正文", ""))}
    if not b["body"]:
        errors.append("「正文」是空的：写上要在 App 里显示的话")
    if b["level"] not in LEVELS.values():
        errors.append(f"等级「{b['level']}」不认识，用：{'、'.join(LEVELS)}")
    if sec.get("链接", "").strip():
        b["url"] = sec["链接"].strip()
    times = {}
    for key, label in (("startsAt", "开始时间"), ("endsAt", "截止时间")):
        raw = sec.get(label, "").strip()
        if raw:
            t = parse_time(raw)
            if t is None:
                errors.append(f"{label}「{raw}」看不懂，写成 2026-12-01 或 2026-12-01 08:00")
            else:
                times[key] = t
    for key, label in (("minVersion", "最低版本"), ("maxVersion", "最高版本"), ("forceBelow", "低于此版本强制更新")):
        if sec.get(label, "").strip():
            b[key] = sec[label].strip().lstrip("vV")
    options = [re.sub(r"^\s*(?:[-*•]|\d+[.、)])\s*", "", ln).strip() for ln in sec.get("投票选项", "").splitlines()]
    options = [o for o in options if o]
    flags = checked(sec.get("设置", ""))
    if options:
        other = OTHER in options
        options = [o for o in options if o != OTHER]
        if not 2 <= len(options) <= MAX_OPTIONS:
            errors.append(f"投票选项要 2～{MAX_OPTIONS} 个（「其他」不算）")
        if len(set(options)) != len(options):
            errors.append("投票选项有重复")
        b.update(options=options, allowMultiple="投票可多选" in flags, allowOther=other)
        # 投票默认 7 天
        times["endsAt"] = times.get("endsAt") or (created + timedelta(days=POLL_DAYS)).astimezone(BEIJING).replace(
            hour=23, minute=59, second=0, microsecond=0)
    b.update({k: iso(v) for k, v in times.items()})
    b["mustAck"] = "必须点确认才能关掉" in flags
    b["block"] = "启动时拦住不让用（慎用）" in flags
    b["discussionUrl"] = d["url"]
    if not b["title"] or not b["body"]:
        errors.append("标题和正文都不能空")
    return (None, errors) if errors else (b, [])


def notices(gh: GitHub) -> list[Notice]:
    """公告分类里最近 100 帖（开着的和关了的）。按 slug 认（ANNOUNCE_CATEGORY），名字也认。"""
    owner, name = gh.owner_name()
    want = env("ANNOUNCE_CATEGORY", "announcements")
    repo = gh.graphql("""query($o:String!,$n:String!){repository(owner:$o,name:$n){
        discussionCategories(first:50){nodes{id name slug}}}}""", o=owner, n=name)["repository"]
    cat = next((c["id"] for c in repo["discussionCategories"]["nodes"] if want in (c["slug"], c["name"])), None)
    if not cat:
        raise RuntimeError(f"讨论区没有「{want}」分类")
    got = gh.graphql("""query($o:String!,$n:String!,$c:ID!){repository(owner:$o,name:$n){
        discussions(first:100, categoryId:$c, orderBy:{field:CREATED_AT, direction:DESC}){nodes{
          id number title body url createdAt closed closedAt
          comments(first:100){nodes{id body author{login}}}}}}}""", o=owner, n=name, c=cat)
    out = []
    for d in got["repository"]["discussions"]["nodes"]:
        created = datetime.fromisoformat(d["createdAt"].replace("Z", "+00:00")).astimezone(BEIJING)
        closed_at = (datetime.fromisoformat(d["closedAt"].replace("Z", "+00:00")) if d["closed"] and d.get("closedAt")
                     else None)
        managed = sections(d.get("body") or "") is not None
        b, errors = to_bulletin(d, created, closed_at) if managed else (None, [])
        bots = [c for c in d["comments"]["nodes"] if ((c.get("author") or {}).get("login") or "") in BOT_LOGINS]
        n = Notice(d["id"], d["number"], d["url"], created, closed_at, managed, b, errors, bots)
        if b and b.get("options"):
            restore_results(n)
        out.append(n)
    return out


def restore_results(n: Notice) -> None:
    """投票结果从机器人的结果回复里读回来。"""
    c = n.bot_comment(MARK_RESULT.format(n.bulletin["id"]))
    m = DATA_RE.search(c["body"]) if c else None
    if m:
        try:
            n.bulletin.update(json.loads(m.group(1)))
        except ValueError:
            pass




# ---------------------------------------------------------------- 投票统计

def vote_category(poll: dict) -> str:
    return f"{VOTE_PREFIX}·{poll['id']}"


def split_vote(content: str, options: list[str]) -> tuple[set[str], bool]:
    """App 提交的是选项原文用「、」连起来（自己填的里的「、」已换成「，」）。选项本身可能带「、」，
    所以按整段匹配、长的先认。返回 (命中的选项, 有没有自己填的)。"""
    rest = "、" + "、".join(p.strip() for p in content.split("、") if p.strip()) + "、"
    hit: set[str] = set()
    for o in sorted(options, key=len, reverse=True):
        token = "、" + o + "、"
        if token in rest:
            hit.add(o)
            rest = rest.replace(token, "、", 1)
    return hit, bool(rest.strip("、"))


def tally(poll: dict, rows: list[dict]) -> tuple[list[dict], int]:
    options = list(poll.get("options") or [])
    ends = parse_time(poll.get("endsAt"))
    ends_ms = ends.timestamp() * 1000 if ends else None
    latest: dict[str, tuple[int, str]] = {}
    for row in rows:
        if row.get("pollId") != poll["id"]:
            continue
        at = int(row.get("updatedAt") or 0)
        if ends_ms and at > ends_ms:
            continue
        who = str(row.get("anonId") or "").strip()
        if not who:
            continue
        raw_sel = row.get("selected")
        content = "、".join(str(x) for x in raw_sel) if isinstance(raw_sel, list) else str(raw_sel or "")
        if who not in latest or at >= latest[who][0]:
            latest[who] = (at, content)
    counts: Counter = Counter()
    other = 0
    for _, content in latest.values():
        hit, extra = split_vote(content, options)
        counts.update(hit)
        other += extra
    results = [{"option": o, "votes": counts[o]} for o in options]
    if poll.get("allowOther") or other:
        results.append({"option": OTHER, "votes": other})
    return results, len(latest)


def fetch_votes(fb: FeedbackBackend, polls: list[dict]) -> list[dict]:
    if not polls:
        return []
    return fb.poll_votes([p["id"] for p in polls])


def update_polls(fb: FeedbackBackend, gh: GitHub, ns: list[Notice], now: datetime, force: bool = False) -> None:
    """统计还没出最终结果的投票，写回结果回复（截止的另发最终结果；上次没发出去的这次补发）。
    [force]：票数没变也重写结果回复（手动统计要让人看到「统计于」更新了）。"""
    due = [n for n in ns if n.bulletin and n.bulletin.get("options") and not final_saved(n)
           and (parse_time(n.bulletin.get("startsAt")) or now) <= now]
    if not due:
        return
    rows = fetch_votes(fb, [n.bulletin for n in due])
    for n in due:
        p = n.bulletin
        results, voters = tally(p, rows)
        final = (parse_time(p.get("endsAt")) or now + timedelta(1)) <= now
        if not force and results == p.get("results") and voters == p.get("voters", 0) and not final:
            continue
        p.update(results=results, voters=voters, resultsAt=iso(now))
        if final:
            p["final"] = True
        log(f"投票 {p['id']}：{voters} 人{'（最终）' if final else ''} "
               + "、".join(f"{r['option']} {r['votes']}" for r in results))
        try:
            post_results(gh, n)
        except Exception as e:  # noqa: BLE001 —— 回帖失败下次再写；结果这次也进不了 App，所以要喊出来
            log(f"::warning::{p['id']} 结果没写进帖子：{e}")


def result_table(p: dict) -> str:
    rows = p.get("results") or []
    total = sum(r["votes"] for r in rows) or 1
    lines = ["| 选项 | 票数 | |", "|---|---:|---|"]
    for r in rows:
        share = r["votes"] / total
        bar = "█" * round(share * 10) + "░" * (10 - round(share * 10))
        lines.append(f"| {r['option'].replace('|', '｜')} | {r['votes']} | `{bar}` {share:.0%} |")
    return "\n".join(lines)


def post_results(gh: GitHub, n: Notice) -> None:
    p = n.bulletin
    data = {k: p[k] for k in ("results", "voters", "resultsAt", "final") if k in p}
    # 「>」只会出现在 JSON 字符串里，转义掉免得提前结束 HTML 注释
    blob = json.dumps(data, ensure_ascii=False).replace(">", "\\u003e")
    ends, when = parse_time(p.get("endsAt")), parse_time(p.get("resultsAt"))
    state = "已截止，最终结果" if p.get("final") else (f"截止 {ends:%m-%d %H:%M}" if ends else "进行中") + " · 每 4 天统计一次"
    body = (f"{MARK_RESULT.format(p['id'])}\n<!-- poll-data:{blob} -->\n### 📊 投票结果\n\n{result_table(p)}\n\n"
            f"<sub>{p.get('voters', 0)} 人参与 · {state}" + (f" · 统计于 {when:%m-%d %H:%M}" if when else "") + "</sub>")
    upsert_comment(gh, n, MARK_RESULT.format(p["id"]), body)
    if p.get("final") and not n.bot_comment(MARK_FINAL.format(p["id"])):
        final = f"{MARK_FINAL.format(p['id'])}\n🗳️ **投票已截止**，{p.get('voters', 0)} 人参与：\n\n{result_table(p)}"
        got = gh.gwrite(ADD, d=n.node_id, b=final)
        n.bot_comments.append(((got.get("addDiscussionComment") or {}).get("comment")) or {"id": "", "body": final})


def final_saved(n: Notice) -> bool:
    """最终结果确实写进了帖子：结果回复里存的是最终结果，且「投票已截止」那条也发了。
    只看这次读到或成功写进去的回复，不看内存里刚算出来的 final。"""
    pid = n.bulletin["id"]
    c = n.bot_comment(MARK_RESULT.format(pid))
    m = DATA_RE.search(c["body"]) if c else None
    if not m or not n.bot_comment(MARK_FINAL.format(pid)):
        return False
    try:
        return json.loads(m.group(1)).get("final") is True
    except ValueError:
        return False


def purge_votes(fb: FeedbackBackend, ns: list[Notice]) -> None:
    """最终结果已写进帖子的投票，删掉 D1 里的票：之后只从帖子读结果，票留着只占地方。
    回帖失败的那次不删，免得结果没存下来票先没了；删失败下一趟再删。"""
    done = [n.bulletin for n in ns if n.bulletin and n.bulletin.get("options") and final_saved(n)]
    if not done:
        return
    ids = [p["id"] for p in done]
    if DRY_RUN:
        log(f"[dry-run] D1 清理投票：{'、'.join(ids)}")
        return
    deleted = fb.purge_votes(ids)
    if deleted:
        log(f"已截止的投票（{'、'.join(ids)}）：已清理 {deleted} 条票")


CLOSE = """mutation($d:ID!){closeDiscussion(input:{discussionId:$d,reason:OUTDATED}){discussion{id}}}"""


def close_expired(gh: GitHub, ns: list[Notice], now: datetime) -> None:
    """过了截止时间的公告关帖（= 撤下，讨论区也清爽）；没设截止时间的不动。
    投票要等最终结果写进帖子再关：关了的帖子机器人回不了帖。先把回执改成「已撤下」再关。"""
    for n in ns:
        b = n.bulletin
        ends = parse_time(b.get("endsAt")) if b else None
        if not ends or ends > now or (b.get("options") and not final_saved(n)):
            continue
        try:
            n.bulletin = None
            upsert_comment(gh, n, MARK_STATUS, status_body(n))
            gh.gwrite(CLOSE, d=n.node_id)
            n.closed_at = now
            log(f"#{n.number} {b['id']} 已过截止时间，关帖撤下")
        except Exception as e:  # noqa: BLE001 —— 这一帖关不掉下一趟再关，不拦别的
            n.bulletin = b
            log(f"::warning::#{n.number} 到期没关掉：{e}")


ADD = """mutation($d:ID!,$b:String!){addDiscussionComment(input:{discussionId:$d,body:$b}){comment{id body}}}"""
DELETE = """mutation($c:ID!){deleteDiscussionComment(input:{id:$c}){comment{id}}}"""


def upsert_comment(gh: GitHub, n: Notice, mark: str, body: str) -> None:
    """GITHUB_TOKEN 改不了讨论回复（updateDiscussionComment 报 FORBIDDEN），所以发新的、删旧的。"""
    current = n.bot_comment(mark)
    if current is not None and current["body"].strip() == body.strip():
        return
    got = gh.gwrite(ADD, d=n.node_id, b=body)
    n.bot_comments.append(((got.get("addDiscussionComment") or {}).get("comment")) or {"id": "", "body": body})
    if current is not None and current.get("id"):
        try:
            gh.gwrite(DELETE, c=current["id"])
            n.bot_comments.remove(current)
        except Exception as e:  # noqa: BLE001 —— 新的已发出，旧的留着也只是多一条
            log(f"::warning::#{n.number} 旧回复没删掉：{e}")


# ---------------------------------------------------------------- 发布回执

LEVEL_NAMES = {v: k for k, v in LEVELS.items()}
# 与发帖表单的小标题同序；App 里发帖时预填的是同一份（GithubDiscussions.kt 的 ANNOUNCE_SKELETON）
SKELETON = ("### 等级\n\n普通\n\n### 正文\n\n\n\n### 链接\n\n\n\n### 开始时间\n\n\n\n### 截止时间\n\n\n\n"
            "### 最低版本\n\n\n\n### 最高版本\n\n\n\n### 投票选项\n\n\n\n### 设置\n\n- [ ] 投票可多选\n"
            "- [ ] 必须点确认才能关掉\n- [ ] 启动时拦住不让用（慎用）")
NOT_FORM = (MARK_STATUS + "\nℹ️ **没进 App**：这帖不是按公告表单写的（缺「### 正文」一节）。"
            "要发到 App，把正文改成下面的格式，只有「正文」必填，空着的小节可以删：\n\n"
            f"````markdown\n{SKELETON}\n````\n\n<sub>网页端在本分类发帖会出表单；App 里选本分类会预填这份骨架。</sub>")


def status_body(n: Notice) -> str:
    if not n.managed:
        return NOT_FORM
    if n.errors:
        return MARK_STATUS + "\n⚠️ **没发布**，改一下帖子就会重试：\n\n" + "\n".join(f"- {e}" for e in n.errors)
    b = n.bulletin
    if b is None:
        return MARK_STATUS + "\n🗑️ 已从 App 撤下（帖子关闭了）。重新打开就恢复。"
    bits = [LEVEL_NAMES.get(b["level"], b["level"])]
    if b.get("startsAt"):
        bits.append(f"{parse_time(b['startsAt']):%m-%d %H:%M} 起")
    if b.get("endsAt"):
        bits.append(f"截止 {parse_time(b['endsAt']):%m-%d %H:%M}")
    if b.get("minVersion") or b.get("maxVersion"):
        bits.append(f"版本 {b.get('minVersion', '')}～{b.get('maxVersion', '')}")
    if b.get("options"):
        bits.append(f"投票 {len(b['options'])} 项" + ("，可多选" if b.get("allowMultiple") else "")
                    + ("，可自己填" if b.get("allowOther") else ""))
    if b.get("mustAck"):
        bits.append("必须确认")
    if b.get("block"):
        bits.append("⚠️ 启动拦截")
    return (MARK_STATUS + f"\n✅ 已发布到 App（`{b['id']}`）：{' · '.join(bits)}\n\n"
            "<sub>几分钟后生效。改帖子 = 改公告；关帖 = 删除</sub>")


# ---------------------------------------------------------------- 大家在问

def public_qa(fb: FeedbackBackend) -> list[dict]:
    """从反馈后端拉取公开问答兜底快照。"""
    return fb.public_qa()[:QA_LIMIT]


# ---------------------------------------------------------------- main

def event_number() -> Optional[int]:
    path = os.environ.get("GITHUB_EVENT_PATH")
    if not path or not os.path.exists(path):
        return None
    return ((json.load(open(path, encoding="utf-8")).get("discussion") or {}).get("number"))


def main() -> None:
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode not in {"event", "tally", "poll"}:
        print(__doc__)
        raise SystemExit(2)
    out = Path(env("OUT_DIR", "out"))
    out.mkdir(parents=True, exist_ok=True)
    now = datetime.now(BEIJING)
    gh = GitHub()
    ns = notices(gh)
    if mode == "tally":
        fb = FeedbackBackend()
        items = public_qa(fb)
        old = json.loads((out / QA_FILE).read_text(encoding="utf-8")) if (out / QA_FILE).exists() else {}
        if old.get("items") != items:  # 没变就不动，免得每 4 天一个空提交
            save(out / QA_FILE, {"updatedAt": iso(now), "items": items})
        log(f"大家在问：{len(items)} 条" + ("" if old.get("items") != items else "，无变化"))
        update_polls(fb, gh, ns, now)
        try:
            purge_votes(fb, ns)
            expiring = ns
        except Exception as e:  # noqa: BLE001 —— 删票失败下一趟再删，不该拦住公告重建
            log(f"::warning::已截止投票的票没清理：{e}")
            # 关了的帖子不再统计也不再删票，票没删掉的投票这趟先不关
            expiring = [n for n in ns if not (n.bulletin and n.bulletin.get("options"))]
        close_expired(gh, expiring, now)
    elif mode == "poll":
        number = int(env("POLL_NUMBER", "0") or 0)
        target = [n for n in ns if n.number == number and n.bulletin and n.bulletin.get("options")]
        if target:
            update_polls(FeedbackBackend(), gh, target, now, force=True)
        else:
            log(f"::warning::#{number} 不是进行中的投票（已关帖、没按表单写或没有投票选项），只重建公告")
    else:
        number = event_number()
        touched = [n for n in ns if n.number == number]
        for n in touched:
            # 关帖 = 删除：已经不进 bulletin.json，也不回帖（关了的帖子机器人没有写权限）
            if n.closed_at:
                continue
            try:
                upsert_comment(gh, n, MARK_STATUS, status_body(n))
            except Exception as e:  # noqa: BLE001 —— 回帖失败不该拦住公告重建
                log(f"::warning::#{n.number} 解析结果没回进帖子：{e}")
    if not any(n.managed for n in ns):
        # 过渡：讨论区还没发过表单公告，announce 分支上手写的旧公告先别覆盖
        log("讨论区还没有表单公告，不生成 bulletin.json")
        return
    bulletins = [n.bulletin for n in ns if n.bulletin]
    # updatedAt 只在公告内容变了时更新：旧版 App 同时读 OSS 和 Gitee，靠它挑较新的一份；
    # 内容没变就沿用旧时间，免得每次重建都多一个空提交
    path = out / BULLETIN_FILE
    old = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    updated = old.get("updatedAt") if isinstance(old, dict) and old.get("bulletins") == bulletins else None
    save(path, {"updatedAt": updated or iso(now), "bulletins": bulletins})
    log(f"公告 {len(bulletins)} 条：" + "、".join(b["id"] for b in bulletins))


def self_test() -> None:
    assert split_vote("A、B", ["A", "B", "C"]) == ({"A", "B"}, False)
    assert split_vote("甲、乙、丙", ["甲、乙", "丙"]) == ({"甲、乙", "丙"}, False)
    assert split_vote("A、随便写的", ["A", "B"]) == ({"A"}, True)
    rows = [
        {"pollId": "p", "anonId": "x", "selected": ["A"], "updatedAt": 1},
        {"pollId": "p", "anonId": "x", "selected": ["B"], "updatedAt": 2},
        {"pollId": "p", "anonId": "y", "selected": ["B", "自己写"], "updatedAt": 3},
        {"pollId": "q", "anonId": "z", "selected": ["A"], "updatedAt": 3},
    ]
    res, voters = tally({"id": "p", "options": ["A", "B"]}, rows)
    assert voters == 2 and res == [{"option": "A", "votes": 0}, {"option": "B", "votes": 2},
                                   {"option": OTHER, "votes": 1}], res

    created = datetime(2026, 11, 24, 12, 0, tzinfo=BEIJING)
    form = ("### 等级\n\n提醒\n\n### 正文\n\n明天停电<!-- x -->\n\n### 链接\n\n_No response_\n\n"
            "### 截止时间\n\n2026-12-01 08:00\n\n### 最低版本\n\nv6.0.2\n\n### 投票选项\n\n- 面\n- 饭\n其他\n\n"
            "### 设置\n\n- [X] 投票可多选\n- [ ] 必须点确认才能关掉\n- [ ] 启动时拦住不让用（慎用）")
    d = {"number": 7, "title": "吃什么", "body": form, "url": "u/discussions/7"}
    b, errs = to_bulletin(d, created, None)
    assert not errs and b["id"] == "2026-11-24-d7" and b["level"] == "warn" and b["body"] == "明天停电", (b, errs)
    assert b["options"] == ["面", "饭"] and b["allowOther"] and b["allowMultiple"] and not b["mustAck"]
    assert b["endsAt"] == "2026-12-01T08:00:00+08:00" and b["minVersion"] == "6.0.2" and "url" not in b
    closed = datetime(2026, 11, 25, 9, 0, tzinfo=BEIJING)
    assert to_bulletin(d, created, closed) == (None, []), "关帖 = 删除，投票也一样"
    plain = {"number": 8, "title": "通知", "body": "随便写的正文\n\n### 背景\n\n不是表单", "url": "u"}
    assert to_bulletin(plain, created, None) == (None, [])
    assert status_body(Notice("P", 8, "u", created, None, False, None, [], [])) == NOT_FORM
    filled = to_bulletin({**plain, "body": SKELETON.replace("### 正文\n\n", "### 正文\n\n停电")}, created, None)
    assert filled[0]["body"] == "停电" and not filled[1] and "options" not in filled[0], "骨架填了正文就能发"
    notice = {"number": 9, "title": "通知", "body": "### 等级\r\n\r\n普通\r\n\r\n### 正文\r\n\r\n停水", "url": "u"}
    got = to_bulletin(notice, created, None)[0]
    assert got["level"] == "info" and got["body"] == "停水" and "options" not in got
    assert to_bulletin(notice, created, closed) == (None, [])
    issue = {"number": 10, "title": "[系统] 统一认证维护", "url": "u",
             "body": "### 哪个系统\n\n统一认证\n\n### 现在怎样\n\n学校公告维护中\n\n### 你怎么确认的\n\n信息化通知\n\n"
                     "### 正文\n\n今晚 22:00 前登录不了"}
    got = to_bulletin(issue, created, None)[0]
    assert got["title"] == "统一认证维护" and got["body"] == "今晚 22:00 前登录不了" and got["level"] == "info", got
    blank = {**issue, "body": issue["body"].replace("今晚 22:00 前登录不了", "_No response_")}
    assert to_bulletin(blank, created, None)[0] is None, "正文没写不发布，等维护者补上"
    bad = {**d, "body": form.replace("2026-12-01 08:00", "下周一")}
    assert to_bulletin(bad, created, None)[0] is None

    class FakeGH:
        def gwrite(self, query: str, **v: Any) -> dict:
            return {"addDiscussionComment": {"comment": {"id": "c", "body": v["b"]}}} if "b" in v else {}

    poll = {"id": "p", "options": ["A"], "results": [{"option": "A", "votes": 1}], "voters": 1, "final": True}
    voted = Notice("D", 1, "u", created, None, True, poll, [])
    assert not final_saved(voted), "内存里 final 了但还没写进帖子，不能删票"
    post_results(FakeGH(), voted)
    assert final_saved(voted), "结果回复和「投票已截止」都写进去了"
    half = Notice("D", 2, "u", created, None, True, dict(poll), [], [voted.bot_comment(MARK_RESULT.format("p"))])
    assert not final_saved(half), "「投票已截止」没发出去，不删票"

    now = datetime(2026, 12, 10, 9, 0, tzinfo=BEIJING)
    def notice_ending(num: int, ends: str, **extra: Any) -> Notice:
        return Notice(f"D{num}", num, "u", created, None, True, {"id": f"n{num}", "endsAt": ends, **extra}, [])
    expired, future, forever = notice_ending(3, "2026-12-01T08:00:00+08:00"), notice_ending(4, "2026-12-31T08:00:00+08:00"), \
        Notice("D5", 5, "u", created, None, True, {"id": "n5"}, [])
    open_poll = notice_ending(6, "2026-12-01T08:00:00+08:00", options=["A", "B"])
    close_expired(FakeGH(), [expired, future, forever, open_poll, voted], now)
    assert expired.closed_at == now and expired.bulletin is None, "过期公告关帖"
    assert future.closed_at is None and forever.closed_at is None, "没到期、没设截止的不动"
    assert open_poll.closed_at is None, "最终结果没写进帖子的投票不关"
    assert voted.closed_at is None, "结果已存但没设截止时间的投票不关"
    print("self-test ok")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    else:
        main()
