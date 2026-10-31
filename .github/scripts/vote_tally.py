#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""统计 Discussion #213「新功能投票总楼」的投票结果，并在对应投票楼层下回复统计结论。

投票口径：楼层评论上的 👍（THUMBS_UP）记赞成、👎（THUMBS_DOWN）记反对，
其它 reaction 忽略；同一账号同时点了 👍 和 👎 的，两边都不计。

自动统计（不带 --comment-id / COMMENT_ID 为空）只处理满足下列条件的顶层楼层：
  * 楼层发布时间（createdAt，不是编辑时间）距今 >= 14 天，且从未统计过；或
  * 最近一条统计结论是「建议等待」，且距该条统计评论发布 >= 14 天。
  * 最近一条统计结论是「建议实施 / 建议搁置」的楼层不再自动统计。

手动统计（--comment-id 或 COMMENT_ID 非空）只统计指定楼层，忽略时间与「是否统计过」的限制；
指定的评论如果是楼中楼（replyTo 非空）则直接报错，不做任何写入。

统计评论开头的隐藏 HTML 注释标记区分三种结论，用来识别历史统计：
  <!-- vote-tally:implement -->
  <!-- vote-tally:wait -->
  <!-- vote-tally:shelve -->
  （标记本身不会出现在渲染后的评论里；识别时会同时要求评论作者是 bot。）

鉴权：读环境变量 GH_TOKEN 或 GITHUB_TOKEN（workflow 里用 GITHUB_TOKEN，
需要 permissions: discussions: write + contents: read）。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, NoReturn, Optional

GRAPHQL_URL = "https://api.github.com/graphql"
DISCUSSION_NUMBER = 213

# workflow runner 的 stdout 默认就是 UTF-8；这里兜底 Windows 本地调试时的 GBK 控制台。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
    except (AttributeError, ValueError):
        pass

WAIT_DAYS = 14
MIN_VOTERS = 5
MIN_GAP_PERCENT = 10.0

# 评论里写的「统计时间」统一用北京时间（UTC+8）；用固定偏移，避免 runner 上缺 tzdata。
BEIJING = timezone(timedelta(hours=8))

RESULT_MARKERS = {
    "implement": "<!-- vote-tally:implement -->",
    "wait": "<!-- vote-tally:wait -->",
    "shelve": "<!-- vote-tally:shelve -->",
}
MARKER_RE = re.compile(r"<!--\s*vote-tally:(implement|wait|shelve)\s*-->")
CONCLUSIONS = {
    "implement": "建议实施",
    "wait": "数据不足，建议等待",
    "shelve": "建议搁置",
}

COMMENT_FIELDS = """
          id
          databaseId
          createdAt
          body
          author { login __typename }
          replyTo { databaseId }
"""

DISCUSSION_QUERY = """
query($owner: String!, $name: String!, $number: Int!, $after: String) {
  repository(owner: $owner, name: $name) {
    discussion(number: $number) {
      id
      title
      comments(first: 100, after: $after) {
        pageInfo { hasNextPage endCursor }
        nodes {
%s        }
      }
    }
  }
}
""" % COMMENT_FIELDS

REPLIES_QUERY = """
query($id: ID!, $after: String) {
  node(id: $id) {
    ... on DiscussionComment {
      replies(first: 100, after: $after) {
        pageInfo { hasNextPage endCursor }
        nodes {
%s        }
      }
    }
  }
}
""" % COMMENT_FIELDS

REACTIONS_QUERY = """
query($id: ID!, $content: ReactionContent!, $after: String) {
  node(id: $id) {
    ... on DiscussionComment {
      reactions(first: 100, after: $after, content: $content) {
        pageInfo { hasNextPage endCursor }
        nodes { user { login __typename } }
      }
    }
  }
}
"""

ADD_COMMENT_MUTATION = """
mutation($discussionId: ID!, $replyToId: ID, $body: String!) {
  addDiscussionComment(input: {discussionId: $discussionId, replyToId: $replyToId, body: $body}) {
    comment { id databaseId url createdAt }
  }
}
"""


def log(message: str) -> None:
    print(message, flush=True)


def warn(message: str) -> None:
    print(f"::warning::{message}", flush=True)


def fail(message: str) -> NoReturn:
    print(f"::error::{message}", file=sys.stderr, flush=True)
    raise SystemExit(1)


def parse_datetime(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


class GitHub:
    """最小 GraphQL 客户端（只用标准库，避免在 workflow 里装包）。"""

    def __init__(self, token: str) -> None:
        self.token = token

    def query(
        self, query: str, variables: Optional[dict[str, Any]] = None, retries: int = 3
    ) -> dict[str, Any]:
        payload = json.dumps({"query": query, "variables": variables or {}}).encode("utf-8")
        request = urllib.request.Request(
            GRAPHQL_URL,
            data=payload,
            method="POST",
            headers={
                "Authorization": f"bearer {self.token}",
                "Content-Type": "application/json",
                "User-Agent": "xjtu-toolbox-vote-tally",
            },
        )

        last_error: Optional[str] = None
        raw = ""
        for attempt in range(1, retries + 1):
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    raw = response.read().decode("utf-8")
                break
            except urllib.error.HTTPError as error:
                body = error.read().decode("utf-8", "replace")
                # 401/403 是权限或 token 问题，重试没有意义。
                if error.code in (401, 403):
                    detail = " ".join(body.split())[:800]
                    raise RuntimeError(f"GraphQL 请求被拒绝（HTTP {error.code}）：{detail}") from None
                last_error = f"HTTP {error.code}: {' '.join(body.split())[:800]}"
            except (urllib.error.URLError, TimeoutError, OSError) as error:
                last_error = f"{type(error).__name__}: {error}"
            if attempt < retries:
                time.sleep(2 * attempt)
        else:
            raise RuntimeError(f"GraphQL 请求连续失败：{last_error}")

        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            raise RuntimeError(f"GraphQL 返回的不是 JSON：{raw[:500]}") from None
        if data.get("errors"):
            raise RuntimeError("GraphQL 返回错误：" + json.dumps(data["errors"], ensure_ascii=False)[:1500])
        return data["data"]


@dataclass
class Comment:
    node_id: str
    database_id: int
    created_at: datetime
    body: str
    author_login: str
    author_type: str
    reply_to_id: Optional[int]

    @property
    def is_reply(self) -> bool:
        return self.reply_to_id is not None

    @property
    def author_is_bot(self) -> bool:
        return self.author_type == "Bot" or self.author_login.endswith("[bot]")


@dataclass
class Tally:
    up: int
    down: int
    up_ratio: float
    down_ratio: float
    result: str

    @property
    def voters(self) -> int:
        return self.up + self.down


@dataclass
class Summary:
    database_id: int
    created_at: datetime
    result: str


@dataclass
class Floor:
    comment: Comment
    replies: list[Comment] = field(default_factory=list)

    @property
    def summaries(self) -> list[Summary]:
        found: list[Summary] = []
        for reply in self.replies:
            match = MARKER_RE.search(reply.body)
            if not match:
                continue
            if not reply.author_is_bot:
                # 标记只在 robot 的统计评论里才有意义；人手动引用标记不算统计结果。
                warn(f"忽略非 bot 评论里的投票统计标记：discussioncomment-{reply.database_id}")
                continue
            found.append(Summary(reply.database_id, reply.created_at, match.group(1)))
        return sorted(found, key=lambda item: item.created_at)


def parse_comment(text: dict[str, Any]) -> Comment:
    author = text.get("author") or {}
    reply_to = text.get("replyTo") or {}
    return Comment(
        node_id=text["id"],
        database_id=int(text["databaseId"]),
        created_at=parse_datetime(text["createdAt"]),
        body=text.get("body") or "",
        author_login=author.get("login") or "",
        author_type=author.get("__typename") or author.get("type") or "",
        reply_to_id=(int(reply_to["databaseId"]) if reply_to.get("databaseId") else None),
    )


def fetch_floors(client: GitHub, owner: str, repo: str) -> tuple[str, str, list[Floor]]:
    discussion_id = ""
    title = ""
    comments: list[dict[str, Any]] = []
    after: Optional[str] = None
    while True:
        data = client.query(DISCUSSION_QUERY, {"owner": owner, "name": repo, "number": DISCUSSION_NUMBER, "after": after})
        discussion = (data.get("repository") or {}).get("discussion")
        if not discussion:
            fail(f"找不到 {owner}/{repo} 的 discussion #{DISCUSSION_NUMBER}")
        discussion_id = discussion["id"]
        title = discussion.get("title") or ""
        connection = discussion["comments"]
        comments.extend(connection["nodes"])
        page_info = connection["pageInfo"]
        if not page_info["hasNextPage"]:
            break
        after = page_info["endCursor"]

    floors: list[Floor] = []
    for raw_comment in comments:
        comment = parse_comment(raw_comment)
        floor = Floor(comment)
        after = None
        while True:
            data = client.query(REPLIES_QUERY, {"id": comment.node_id, "after": after})
            node = data.get("node") or {}
            connection = node.get("replies") or {}
            floor.replies.extend(parse_comment(reply) for reply in connection.get("nodes") or [])
            page_info = connection.get("pageInfo") or {}
            if not page_info.get("hasNextPage"):
                break
            after = page_info["endCursor"]
        floors.append(floor)
    return discussion_id, title, floors


def fetch_reactors(client: GitHub, comment_node_id: str, content: str) -> dict[str, str]:
    """返回 {login: author typename}，处理 >100 人的分页。"""
    users: dict[str, str] = {}
    after: Optional[str] = None
    while True:
        data = client.query(REACTIONS_QUERY, {"id": comment_node_id, "content": content, "after": after})
        node = data.get("node") or {}
        connection = node.get("reactions") or {}
        for reaction in connection.get("nodes") or []:
            user = (reaction or {}).get("user") or {}
            login = user.get("login")
            if login:
                users[login] = user.get("__typename") or ""
        page_info = connection.get("pageInfo") or {}
        if not page_info.get("hasNextPage"):
            break
        after = page_info["endCursor"]
    return users


def decide_result(up: int, down: int) -> tuple[float, float, str]:
    """按票数得出比例与结论。纯函数，便于本地单测。"""
    voters = up + down
    if voters:
        up_ratio = up * 100.0 / voters
        down_ratio = down * 100.0 / voters
    else:
        up_ratio = down_ratio = 0.0

    if voters < MIN_VOTERS or abs(up_ratio - down_ratio) < MIN_GAP_PERCENT:
        result = "wait"
    elif up_ratio > down_ratio:
        result = "implement"
    else:
        result = "shelve"
    return up_ratio, down_ratio, result


def compute_tally(client: GitHub, comment: Comment) -> Tally:
    up_users = fetch_reactors(client, comment.node_id, "THUMBS_UP")
    down_users = fetch_reactors(client, comment.node_id, "THUMBS_DOWN")

    # 同一账号两边都点：两边都不算，避免重复计票。
    both = set(up_users) & set(down_users)
    if both:
        log(f"  忽略同时投了 👍 和 👎 的账号：{len(both)} 个")
    up = set(up_users) - both
    down = set(down_users) - both

    up_ratio, down_ratio, result = decide_result(len(up), len(down))

    return Tally(up=len(up), down=len(down), up_ratio=up_ratio, down_ratio=down_ratio, result=result)


def build_comment_body(tally: Tally, now: datetime) -> str:
    timestamp = now.astimezone(BEIJING).strftime("%Y-%m-%d %H:%M")
    return "\n".join(
        [
            RESULT_MARKERS[tally.result],
            "",
            "### 投票统计",
            "",
            f"- 统计时间：{timestamp}（北京时间）",
            f"- 👍 赞成：{tally.up} 票（{tally.up_ratio:.1f}%）",
            f"- 👎 反对：{tally.down} 票（{tally.down_ratio:.1f}%）",
            f"- 投票人数：{tally.voters} 人",
            "",
            f"**结论：{CONCLUSIONS[tally.result]}**",
            "",
        ]
    )


def should_tally(floor: Floor, now: datetime) -> tuple[bool, str]:
    summaries = floor.summaries
    if not summaries:
        deadline = floor.comment.created_at + timedelta(days=WAIT_DAYS)
        if now >= deadline:
            return True, "首次统计（楼层发布已满 14 天）"
        return False, f"楼层发布未满 14 天（{deadline.astimezone(BEIJING):%Y-%m-%d %H:%M} 北京时间后到期）"

    latest = summaries[-1]
    if latest.result != "wait":
        return False, f"已统计过，最近结论为 {CONCLUSIONS[latest.result]}（discussioncomment-{latest.database_id}）"
    deadline = latest.created_at + timedelta(days=WAIT_DAYS)
    if now >= deadline:
        return True, f"上次建议等待（discussioncomment-{latest.database_id}）已满 14 天"
    return False, f"上次建议等待未满 14 天（{deadline.astimezone(BEIJING):%Y-%m-%d %H:%M} 北京时间后到期）"


def post_summary(client: GitHub, discussion_id: str, floor: Floor, tally: Tally, body: str, dry_run: bool) -> None:
    header = (
        f"楼层 discussioncomment-{floor.comment.database_id}：👍 {tally.up} / 👎 {tally.down}"
        f" → {CONCLUSIONS[tally.result]}"
    )
    if dry_run:
        log(f"[dry-run] {header}，将发布以下评论：\n---\n{body}---")
        return

    data = client.query(
        ADD_COMMENT_MUTATION,
        {"discussionId": discussion_id, "replyToId": floor.comment.node_id, "body": body},
        # 不要重试 mutation：服务端可能已经建好评论，只是响应丢了；
        # 重发会在同一楼层下产生两条重复统计。失败就整体报错，下次运行会按 marker 去重。
        retries=1,
    )
    comment = data["addDiscussionComment"]["comment"]
    log(f"{header}；已回复 {comment['url']}")


def find_comment(floors: list[Floor], database_id: int) -> tuple[Optional[Floor], Optional[Comment]]:
    for floor in floors:
        if floor.comment.database_id == database_id:
            return floor, None
        for reply in floor.replies:
            if reply.database_id == database_id:
                return floor, reply
    return None, None


def parse_comment_id(text: str) -> int:
    value = text.strip()
    # 不用 isdigit()：它接受「²」这类上标数字，int() 会再抛 ValueError。
    if not value.isdecimal():
        fail(f"comment_id 必须是十进制数字 databaseId（例如 19200505），收到：{text!r}")
    return int(value)


def main() -> None:
    parser = argparse.ArgumentParser(description="统计 Discussion #213 的投票结果")
    parser.add_argument("--comment-id", default=None, help="手动模式：只统计指定楼层（databaseId）")
    parser.add_argument("--dry-run", action="store_true", help="只打印评论内容，不真正发布")
    args = parser.parse_args()

    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        fail("缺少 GH_TOKEN / GITHUB_TOKEN 环境变量")
    repository = os.environ.get("GITHUB_REPOSITORY") or ""
    if "/" not in repository:
        fail(f"缺少或无法解析 GITHUB_REPOSITORY，当前为：{repository!r}")
    owner, repo = repository.split("/", 1)

    comment_id_text = (args.comment_id or os.environ.get("COMMENT_ID") or "").strip()
    client = GitHub(token)
    now = datetime.now(timezone.utc)

    discussion_id = ""
    title = ""
    floors: list[Floor] = []
    try:
        discussion_id, title, floors = fetch_floors(client, owner, repo)
        log(f"Discussion #{DISCUSSION_NUMBER}「{title}」：{len(floors)} 个顶层楼层")

        if comment_id_text:
            # 手动模式：只统计指定楼层，忽略时间与已统计限制。
            database_id = parse_comment_id(comment_id_text)
            floor, nested = find_comment(floors, database_id)
            if floor is None:
                fail(f"在 Discussion #{DISCUSSION_NUMBER} 中找不到 databaseId={database_id} 的评论")
            if nested is not None:
                fail(
                    f"databaseId={database_id} 是楼中楼（回复自 discussioncomment-{nested.reply_to_id}），"
                    "只能统计顶层投票楼层；本次未做任何写入"
                )
            comment = floor.comment
            if comment.is_reply:
                # 理论上不会发生：顶层 comments 里 replyTo 一定为空，留作兜底。
                fail(f"databaseId={database_id} 不是顶层楼层；本次未做任何写入")
            log(f"手动统计楼层 discussioncomment-{comment.database_id}（忽略 14 天与已统计限制）")
            tally = compute_tally(client, comment)
            log(f"  👍 {tally.up} 票 / 👎 {tally.down} 票，投票人数 {tally.voters} → {CONCLUSIONS[tally.result]}")
            post_summary(client, discussion_id, floor, tally, build_comment_body(tally, now), args.dry_run)
            return

        # 定时模式：统计所有到期且未统计（或上次建议等待已满 14 天）的楼层。
        pending = []
        for floor in sorted(floors, key=lambda item: item.comment.created_at):
            due, reason = should_tally(floor, now)
            log(f"楼层 discussioncomment-{floor.comment.database_id}：{'统计' if due else '跳过'}，{reason}")
            if due:
                pending.append(floor)
        if not pending:
            log("没有需要统计的楼层")
            return

        failures = []
        for floor in pending:
            try:
                tally = compute_tally(client, floor.comment)
                log(f"  👍 {tally.up} 票 / 👎 {tally.down} 票，投票人数 {tally.voters} → {CONCLUSIONS[tally.result]}")
                post_summary(client, discussion_id, floor, tally, build_comment_body(tally, now), args.dry_run)
            except Exception as error:  # 单个楼层失败不影响其它楼层
                failures.append((floor.comment.database_id, error))
                print(f"::error::楼层 discussioncomment-{floor.comment.database_id} 统计失败：{error}", flush=True)
        if failures:
            fail("部分楼层统计失败：" + "、".join(f"discussioncomment-{database_id}" for database_id, _ in failures))
    except RuntimeError as error:
        fail(str(error))


if __name__ == "__main__":
    main()
