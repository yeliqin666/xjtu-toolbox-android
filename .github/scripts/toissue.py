#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""处理 Discussion 评论里的 /toissue 命令。

命令约定：

    /toissue <type> <priority> [flags] [assignee]

* type：bug / feature；
* priority：0 / 1 / 2 / 3；
* flags：q 加 Question 标签，t 把 Awaiting Assignment 换成 Awaiting Triage；
  顺序无关，大小写不敏感，未定义字符忽略并在回复末尾提示；
* assignee：@用户名，可省略；flags 位置以 @ 开头时跳过 flags。

触发位置决定转换对象：顶层回复 → 转换讨论主帖；楼中楼 → 转换该楼中楼本身。
只有仓库 write / maintain / admin 权限的作者会触发；权限不足时静默跳过。

幂等：转出的 issue 正文里带隐藏标记 `<!-- toissue:source=... -->`，再次收到同一来源的
命令时用 GitHub 搜索找回已有 issue；标签组一致只回链接，不一致则更新标签并说明。

这是给以后更多讨论区命令用的最小框架：命令注册在 HANDLERS，每个 handler 只负责
解析自己的参数并返回回复正文，GitHub 读写统一走 GitHub 类。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

GRAPHQL_URL = "https://api.github.com/graphql"
REST_URL = "https://api.github.com"

# 评论里的时间统一按北京时间展示；固定偏移，避免 runner 缺 tzdata。
BEIJING = timezone(timedelta(hours=8))

TYPE_LABELS = {
    "bug": "🐛 Type:Bug",
    "feature": "✨ Type:Feature",
}
PRIORITY_LABELS = {
    "0": "🚨 Priority: P0 - Critical",
    "1": "⚠️ Priority: P1 - High",
    "2": "🔹 Priority: P2 - Medium",
    "3": "🌱 Priority: P3 - Low",
}
LABEL_ASSIGNMENT = "📥 State:Awaiting Assignment"
LABEL_TRIAGE = "🕒 State:Awaiting Triage"
LABEL_QUESTION = "Question"
MANAGED_LABELS = frozenset(
    list(TYPE_LABELS.values())
    + list(PRIORITY_LABELS.values())
    + [LABEL_ASSIGNMENT, LABEL_TRIAGE, LABEL_QUESTION]
)

TITLE_PREFIX = {"bug": "[Bug]", "feature": "[功能]"}
TITLE_PREFIX_RE = re.compile(r"^\s*\[(?:bug|功能|feature)\]\s*", re.IGNORECASE)
COMMAND_RE = re.compile(r"^[ \t]*(/[A-Za-z][A-Za-z0-9-]*)(?:[ \t]+(.*))?$")
LOGIN_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$")

USAGE = """用法：

`/toissue <type> <priority> [flags] [assignee]`

- `type`：`bug` 或 `feature`
- `priority`：`0`、`1`、`2`、`3`，分别对应 P0～P3
- `flags`：可组合的小写字母
  - `q`：额外加 `Question` 标签
  - `t`：把 `📥 State:Awaiting Assignment` 换成 `🕒 State:Awaiting Triage`
  - 未定义的字符会被忽略，并在回复末尾提示
- `assignee`：`@用户名`，可省略
- flags 位置以 `@` 开头时跳过 flags，直接按 assignee 解析

示例：`/toissue bug 1 qt @someone`"""


def log(message: str) -> None:
    print(message, flush=True)


def warn(message: str) -> None:
    print(f"::warning::{message}", flush=True)


def fatal(message: str) -> None:
    print(f"::error::{message}", file=sys.stderr, flush=True)
    raise SystemExit(1)


def parse_datetime(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def format_time(value: str) -> str:
    return parse_datetime(value).astimezone(BEIJING).strftime("%Y-%m-%d %H:%M")


def brief(value: Any, limit: int = 80) -> str:
    """把用户输入压成一段单行、不会破坏 Markdown 的短文本。"""
    text = " ".join(str(value).split())
    text = text.replace("`", "'").replace("*", "＊").replace("_", "＿")
    if len(text) > limit:
        text = text[:limit] + "…"
    return text


class UsageNeeded(Exception):
    """命令没有任何参数：回帖展示用法。"""


class ParseError(Exception):
    """参数可以被理解，但不符合约定。"""


class CommandFailure(Exception):
    def __init__(self, message: str, warnings: Optional[list[str]] = None, fatal: bool = False) -> None:
        super().__init__(message)
        self.message = message
        self.warnings = list(warnings or [])
        self.fatal = fatal


class GitHubError(RuntimeError):
    def __init__(self, message: str, errors: Optional[list[dict[str, Any]]] = None) -> None:
        super().__init__(message)
        self.errors = errors or []


class GitHub:
    """最小 GraphQL / REST 客户端，只用标准库。"""

    def __init__(self, token: str) -> None:
        self.token = token
        self._viewer_login: Optional[str] = None

    def viewer_login(self) -> str:
        if self._viewer_login is None:
            data = self.graphql("query { viewer { login } }")
            self._viewer_login = ((data.get("viewer") or {}).get("login") or "")
        return self._viewer_login

    def graphql(
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
                "User-Agent": "xjtu-toolbox-toissue",
            },
        )

        last_error = ""
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
                    raise GitHubError(f"GraphQL 请求被拒绝（HTTP {error.code}）：{' '.join(body.split())[:500]}") from None
                last_error = f"HTTP {error.code}: {' '.join(body.split())[:500]}"
            except (urllib.error.URLError, TimeoutError, OSError) as error:
                last_error = f"{type(error).__name__}: {error}"
            if attempt < retries:
                time.sleep(2 * attempt)
        else:
            raise GitHubError(f"GraphQL 请求连续失败：{last_error}")

        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            raise GitHubError(f"GraphQL 返回的不是 JSON：{raw[:300]}") from None
        if data.get("errors"):
            raise GitHubError("GraphQL 返回错误", data["errors"])
        return data.get("data") or {}

    def rest(self, method: str, path: str) -> tuple[int, dict[str, Any]]:
        request = urllib.request.Request(
            REST_URL + path,
            method=method,
            headers={
                "Authorization": f"bearer {self.token}",
                "Accept": "application/vnd.github+json",
                "User-Agent": "xjtu-toolbox-toissue",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read().decode("utf-8")
                return response.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8", "replace")
            try:
                data = json.loads(raw)
            except json.JSONDecodeError:
                data = {}
            return error.code, data if isinstance(data, dict) else {}
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise GitHubError(f"REST 请求失败：{type(error).__name__}: {error}") from None


COMMENT_QUERY = """
query($id: ID!) {
  node(id: $id) {
    ... on DiscussionComment {
      id
      databaseId
      body
      url
      authorAssociation
      author { login }
      replyTo { id databaseId }
      discussion {
        id
        number
        title
        body
        url
        repository { id }
      }
    }
  }
}
"""

LABELS_QUERY = """
query($owner: String!, $name: String!, $after: String) {
  repository(owner: $owner, name: $name) {
    labels(first: 100, after: $after) {
      pageInfo { hasNextPage endCursor }
      nodes { id name }
    }
  }
}
"""

SEARCH_ISSUES_QUERY = """
query($q: String!) {
  search(query: $q, type: ISSUE, first: 10) {
    nodes {
      ... on Issue { number body author { login } }
    }
  }
}
"""

ISSUE_QUERY = """
query($owner: String!, $name: String!, $number: Int!) {
  repository(owner: $owner, name: $name) {
    issue(number: $number) {
      id
      number
      url
      createdAt
      state
      labels(first: 100) { nodes { id name } }
    }
  }
}
"""

USER_QUERY = """
query($login: String!) {
  user(login: $login) { id login }
}
"""

CREATE_ISSUE_MUTATION = """
mutation($input: CreateIssueInput!) {
  createIssue(input: $input) {
    issue { id number url createdAt }
  }
}
"""

UPDATE_ISSUE_MUTATION = """
mutation($input: UpdateIssueInput!) {
  updateIssue(input: $input) {
    issue { id number url }
  }
}
"""

ADD_COMMENT_MUTATION = """
mutation($discussionId: ID!, $replyToId: ID, $body: String!) {
  addDiscussionComment(input: {discussionId: $discussionId, replyToId: $replyToId, body: $body}) {
    comment { id url }
  }
}
"""


@dataclass
class Discussion:
    node_id: str
    number: int
    title: str
    body: str
    url: str
    repository_id: str


@dataclass
class Comment:
    node_id: str
    database_id: int
    body: str
    url: str
    author_login: str
    author_association: str
    reply_to_id: Optional[int]
    reply_to_node_id: Optional[str]
    discussion: Discussion

    @property
    def thread_root_id(self) -> str:
        # GitHub 不允许回复楼中楼；楼中楼的回复统一落到它所在线程的顶层评论下。
        return self.reply_to_node_id or self.node_id


@dataclass
class Context:
    client: GitHub
    owner: str
    repo: str
    comment: Comment
    command_line_index: int


@dataclass
class ToIssueArgs:
    type: str
    priority: str
    flags: set[str] = field(default_factory=set)
    assignee: Optional[str] = None
    warnings: list[str] = field(default_factory=list)


class CommandHandler:
    """讨论区命令的公共接口；新命令继承它并注册到 HANDLERS。"""

    name: str = ""
    usage: str = ""

    def handle(self, ctx: "Context", args: list[str]) -> str:
        raise NotImplementedError


def usage_details(usage: str) -> str:
    return f"<details>\n<summary>用法</summary>\n\n{usage}\n\n</details>"


def warning_suffix(warnings: list[str]) -> str:
    if not warnings:
        return ""
    return "\n\n*" + "；".join(warnings) + "*"


def failure_body(command: str, usage: str, message: str, warnings: Optional[list[str]] = None) -> str:
    return (
        f"无法处理 `{command}`：{message}"
        f"{warning_suffix(list(warnings or []))}"
        f"\n\n{usage_details(usage)}"
    )


def fetch_comment(client: GitHub, node_id: str) -> Comment:
    data = client.graphql(COMMENT_QUERY, {"id": node_id})
    node = data.get("node") or {}
    discussion_data = node.get("discussion")
    if not node or not discussion_data:
        fatal("找不到这条 Discussion 评论，node 不是 DiscussionComment")

    author = node.get("author") or {}
    reply_to = node.get("replyTo") or {}
    discussion = Discussion(
        node_id=discussion_data["id"],
        number=int(discussion_data["number"]),
        title=discussion_data.get("title") or "",
        body=discussion_data.get("body") or "",
        url=discussion_data.get("url") or "",
        repository_id=(discussion_data.get("repository") or {}).get("id") or "",
    )
    return Comment(
        node_id=node["id"],
        database_id=int(node["databaseId"]),
        body=node.get("body") or "",
        url=node.get("url") or "",
        author_login=author.get("login") or "",
        author_association=node.get("authorAssociation") or "",
        reply_to_id=(int(reply_to["databaseId"]) if reply_to.get("databaseId") else None),
        reply_to_node_id=reply_to.get("id"),
        discussion=discussion,
    )


def find_command(body: str) -> Optional[tuple[str, list[str], int]]:
    """返回第一行已注册命令的 (命令名, 参数, 行号)；忽略引用和围栏代码块。"""
    in_fence = False
    for index, line in enumerate(body.splitlines()):
        stripped = line.strip()
        if stripped.startswith("```") or stripped.startswith("~~~"):
            in_fence = not in_fence
            continue
        if in_fence or stripped.startswith(">"):
            continue
        match = COMMAND_RE.match(line)
        if not match:
            continue
        name = match.group(1)
        if name in HANDLERS:
            return name, (match.group(2) or "").split(), index
    return None


def strip_command_line(body: str, line_index: int) -> str:
    lines = body.splitlines()
    if 0 <= line_index < len(lines):
        del lines[line_index]
    return "\n".join(lines).strip()


def first_content_line(body: str) -> str:
    for line in body.splitlines():
        text = line.strip()
        if not text:
            continue
        text = re.sub(r"^#{1,6}\s*", "", text)
        text = re.sub(r"^(?:[-*+]|\d+[.)])\s+", "", text)
        text = re.sub(r"^>\s*", "", text)
        return text.strip()
    return ""


def issue_title(kind: str, raw: str, fallback: str) -> str:
    text = (raw or "").splitlines()[0].strip() if (raw or "").strip() else ""
    text = TITLE_PREFIX_RE.sub("", text).strip()
    if not text:
        text = fallback
    if len(text) > 120:
        text = text[:119].rstrip() + "…"
    return f"{TITLE_PREFIX[kind]} {text}"


def parse_toissue(args: list[str]) -> ToIssueArgs:
    if not args:
        raise UsageNeeded()

    kind = args[0].lower()
    if kind not in TYPE_LABELS:
        raise ParseError(f"type 必须是 `bug` 或 `feature`，收到 `{brief(args[0])}`。")
    if len(args) < 2:
        raise ParseError("缺少 priority 参数。")
    priority = args[1]
    if priority not in PRIORITY_LABELS:
        raise ParseError(f"priority 必须是 `0`、`1`、`2`、`3`，收到 `{brief(args[1])}`。")

    rest = list(args[2:])
    flags: set[str] = set()
    warnings: list[str] = []
    assignee: Optional[str] = None
    assignee_token: Optional[str] = None

    if rest and rest[0].startswith("@"):
        # flags 位置给的是 @xxx：跳过 flags，按 assignee 解析。
        assignee_token = rest.pop(0)
    elif rest:
        unknown: list[str] = []
        for char in rest.pop(0).lower():
            if char in ("q", "t"):
                flags.add(char)
            else:
                unknown.append(char)
        if unknown:
            warnings.append("已忽略 flags 中未定义的字符：" + brief("".join(dict.fromkeys(unknown))))

    if rest and assignee_token is None and rest[0].startswith("@"):
        assignee_token = rest.pop(0)

    if rest:
        extras = [brief(item, 40) for item in rest]
        warnings.append("已忽略多余参数：" + "、".join(f"`{item}`" for item in extras))

    if assignee_token is not None:
        login = assignee_token[1:]
        if not login:
            raise ParseError("assignee 不能为空，请写成 `@用户名`。")
        if not LOGIN_RE.match(login):
            raise ParseError(f"assignee 格式不正确：`{brief(assignee_token)}`，应写成 `@用户名`。")
        assignee = login

    return ToIssueArgs(kind, priority, flags, assignee, warnings)


def desired_labels(parsed: ToIssueArgs) -> set[str]:
    labels = {
        TYPE_LABELS[parsed.type],
        PRIORITY_LABELS[parsed.priority],
        LABEL_TRIAGE if "t" in parsed.flags else LABEL_ASSIGNMENT,
    }
    if "q" in parsed.flags:
        labels.add(LABEL_QUESTION)
    return labels


def managed_labels(issue: dict[str, Any]) -> set[str]:
    return {
        label["name"]
        for label in (issue.get("labels") or {}).get("nodes") or []
        if label["name"] in MANAGED_LABELS
    }


def format_changes(added: set[str], removed: set[str]) -> str:
    parts = []
    if added:
        parts.append("新增 " + "、".join(f"`{name}`" for name in sorted(added)))
    if removed:
        parts.append("移除 " + "、".join(f"`{name}`" for name in sorted(removed)))
    return "；".join(parts)


def is_maintainer(client: GitHub, owner: str, repo: str, comment: Comment) -> bool:
    """write 及以上权限。REST 读不了权限时按 authorAssociation 兜底。"""
    if comment.author_association == "OWNER":
        return True
    if not comment.author_login:
        return False

    path = (
        f"/repos/{urllib.parse.quote(owner)}/{urllib.parse.quote(repo)}"
        f"/collaborators/{urllib.parse.quote(comment.author_login)}/permission"
    )
    try:
        status, data = client.rest("GET", path)
    except GitHubError as error:
        warn(f"读取 @{comment.author_login} 的仓库权限失败，按 authorAssociation 兜底：{error}")
        return comment.author_association in ("MEMBER", "COLLABORATOR")

    if status == 200:
        return data.get("permission") in ("admin", "maintain", "write") or data.get("role_name") in (
            "admin",
            "maintain",
            "write",
        )
    if status == 404:
        return False
    if status in (401, 403):
        warn(f"读取 @{comment.author_login} 的仓库权限被拒绝（HTTP {status}），按 authorAssociation 兜底")
    else:
        warn(f"读取 @{comment.author_login} 的仓库权限失败（HTTP {status}），按 authorAssociation 兜底")
    return comment.author_association in ("MEMBER", "COLLABORATOR")


def fetch_label_map(client: GitHub, owner: str, repo: str) -> dict[str, str]:
    labels: dict[str, str] = {}
    after: Optional[str] = None
    while True:
        data = client.graphql(LABELS_QUERY, {"owner": owner, "name": repo, "after": after})
        connection = ((data.get("repository") or {}).get("labels") or {})
        for node in connection.get("nodes") or []:
            labels[node["name"]] = node["id"]
        page_info = connection.get("pageInfo") or {}
        if not page_info.get("hasNextPage"):
            return labels
        after = page_info["endCursor"]


def find_existing_issue(client: GitHub, owner: str, repo: str, source_key: str) -> Optional[dict[str, Any]]:
    marker = f"<!-- toissue:source={source_key} -->"
    query = f'repo:{owner}/{repo} "toissue:source={source_key}" in:body'
    data = client.graphql(SEARCH_ISSUES_QUERY, {"q": query})
    candidates = [
        node for node in (data.get("search") or {}).get("nodes") or [] if marker in (node.get("body") or "")
    ]
    if not candidates:
        return None
    # 只认机器人自己建的 issue，避免别人伪造隐藏标记来劫持命令。
    allowed_authors = {client.viewer_login(), "github-actions[bot]", "github-actions"}
    numbers = [
        int(node["number"])
        for node in candidates
        if ((node.get("author") or {}).get("login") or "") in allowed_authors
    ]
    if not numbers:
        return None
    issue = fetch_issue(client, owner, repo, min(numbers))
    return issue


def fetch_issue(client: GitHub, owner: str, repo: str, number: int) -> Optional[dict[str, Any]]:
    data = client.graphql(ISSUE_QUERY, {"owner": owner, "name": repo, "number": number})
    return (data.get("repository") or {}).get("issue")


def lookup_user_id(client: GitHub, login: str) -> str:
    try:
        data = client.graphql(USER_QUERY, {"login": login})
    except GitHubError as error:
        detail = " ".join(
            str(item.get("message") or "") for item in error.errors if isinstance(item, dict)
        ).lower()
        if "could not resolve to a user" in detail or "not found" in detail:
            raise CommandFailure(f"assignee `@{brief(login)}` 不存在。") from None
        raise
    user = data.get("user")
    if not user:
        raise CommandFailure(f"assignee `@{brief(login)}` 不存在。")
    return user["id"]


def create_issue(
    client: GitHub,
    repository_id: str,
    title: str,
    body: str,
    label_ids: list[str],
    assignee_id: Optional[str],
) -> dict[str, Any]:
    issue_input: dict[str, Any] = {
        "repositoryId": repository_id,
        "title": title,
        "body": body,
        "labelIds": label_ids,
    }
    if assignee_id:
        issue_input["assigneeIds"] = [assignee_id]
    data = client.graphql(CREATE_ISSUE_MUTATION, {"input": issue_input}, retries=1)
    return data["createIssue"]["issue"]


def update_issue_labels(
    client: GitHub, issue: dict[str, Any], desired: set[str], label_map: dict[str, str]
) -> None:
    unmanaged = [
        label["id"]
        for label in (issue.get("labels") or {}).get("nodes") or []
        if label["name"] not in MANAGED_LABELS
    ]
    label_ids = unmanaged + [label_map[name] for name in desired]
    client.graphql(
        UPDATE_ISSUE_MUTATION,
        {"input": {"id": issue["id"], "labelIds": label_ids}},
        retries=1,
    )


def public_api_message(error: GitHubError) -> str:
    """把 GitHub 错误翻成调用者能看懂、但不含仓库细节的一句话。"""
    detail = " ".join(
        str(item.get("message") or "") for item in error.errors if isinstance(item, dict)
    ).lower()
    if "assign" in detail:
        return "assignee 无法分配，请确认该用户是仓库成员且有写权限。"
    if "label" in detail:
        return "标签无法添加，请确认仓库标签仍然存在。"
    if "forbidden" in detail or "resource not accessible" in detail or "permission" in detail:
        return "机器人没有创建 issue 或修改标签的权限。"
    return "GitHub API 调用失败，请稍后重试；若持续失败请联系维护者。"


def build_issue_body(source_key: str, source_link: str, content: str) -> str:
    body = f"<!-- toissue:source={source_key} -->\n\n来源：{source_link}\n\n---\n\n"
    if content.strip():
        body += content.strip() + "\n"
    else:
        body += "（来源内容为空。）\n"
    return body


class ToIssueHandler(CommandHandler):
    """把 Discussion 主帖 / 楼中楼转成 issue。"""

    name = "/toissue"
    usage = USAGE

    def handle(self, ctx: Context, args: list[str]) -> str:
        try:
            parsed = parse_toissue(args)
        except UsageNeeded:
            return self.usage
        except ParseError as error:
            raise CommandFailure(str(error)) from None

        try:
            return self.execute(ctx, parsed)
        except CommandFailure as error:
            if not error.warnings:
                error.warnings = parsed.warnings
            raise
        except GitHubError as error:
            log(f"::error::/toissue API 调用失败：{error}；{json.dumps(error.errors, ensure_ascii=False)}")
            raise CommandFailure(public_api_message(error), parsed.warnings, fatal=True) from None
        except Exception as error:
            log(f"::error::/toissue 处理异常：{type(error).__name__}: {error}")
            raise CommandFailure("处理命令时发生内部错误，请联系维护者。", parsed.warnings, fatal=True) from None

    def execute(self, ctx: Context, parsed: ToIssueArgs) -> str:
        comment = ctx.comment
        discussion = comment.discussion
        desired = desired_labels(parsed)
        label_map = fetch_label_map(ctx.client, ctx.owner, ctx.repo)
        missing = sorted(desired - set(label_map))
        if missing:
            raise CommandFailure("仓库缺少标签：" + "、".join(f"`{name}`" for name in missing))

        if comment.reply_to_id is None:
            source_key = f"discussion-{discussion.number}"
            content = discussion.body
            raw_title = discussion.title
            source_link = f"[Discussion #{discussion.number}]({discussion.url})"
            where = "本讨论"
        else:
            source_key = f"discussioncomment-{comment.database_id}"
            content = strip_command_line(comment.body, ctx.command_line_index)
            raw_title = first_content_line(content)
            source_link = f"[Discussion #{discussion.number} 的这条回复]({comment.url})"
            where = "本楼"

        title = issue_title(parsed.type, raw_title, f"Discussion #{discussion.number} 的回复")
        body = build_issue_body(source_key, source_link, content)
        existing = find_existing_issue(ctx.client, ctx.owner, ctx.repo, source_key)

        if existing:
            current = managed_labels(existing)
            state_note = "（当前 issue 已关闭）" if existing.get("state") == "CLOSED" else ""
            if current == desired:
                reply = (
                    f"{where}已于 {format_time(existing['createdAt'])}（北京时间）"
                    f"转为 issue #{existing['number']}：{existing['url']}{state_note}，标签与命令一致。"
                )
            else:
                try:
                    update_issue_labels(ctx.client, existing, desired, label_map)
                except GitHubError as error:
                    log(f"::error::更新 issue #{existing['number']} 标签失败：{error}")
                    raise CommandFailure(public_api_message(error), parsed.warnings, fatal=True) from None
                changes = format_changes(desired - current, current - desired)
                reply = (
                    f"{where}此前已于 {format_time(existing['createdAt'])}（北京时间）"
                    f"转为 issue #{existing['number']}：{existing['url']}{state_note}。"
                    f"已按命令更新标签：{changes}。"
                )
        else:
            assignee_id = lookup_user_id(ctx.client, parsed.assignee) if parsed.assignee else None
            try:
                issue = create_issue(
                    ctx.client,
                    discussion.repository_id,
                    title,
                    body,
                    [label_map[name] for name in desired],
                    assignee_id,
                )
            except GitHubError as error:
                log(f"::error::创建 issue 失败：{error}")
                raise CommandFailure(public_api_message(error), parsed.warnings, fatal=True) from None
            reply = f"已创建 issue #{issue['number']}：{issue['url']}"
            if parsed.assignee:
                reply += f"\n\n已指派给 @{parsed.assignee}。"

        return reply + warning_suffix(parsed.warnings)


HANDLERS: dict[str, CommandHandler] = {
    ToIssueHandler.name: ToIssueHandler(),
}


def post_reply(client: GitHub, discussion_id: str, comment_id: str, body: str) -> str:
    data = client.graphql(
        ADD_COMMENT_MUTATION,
        {"discussionId": discussion_id, "replyToId": comment_id, "body": body},
        # 不重试 mutation：服务端可能已经建好评论，只是响应丢了。
        retries=1,
    )
    return data["addDiscussionComment"]["comment"]["url"]


def with_reply_context(comment: Comment, body: str) -> str:
    """楼中楼的回复只能落到线程顶层，加一行引用说明处理的是哪一条。"""
    if comment.reply_to_id is None:
        return body
    return f"> 回复 [discussioncomment-{comment.database_id}]({comment.url})\n\n{body}"


def self_test() -> None:
    def parse(text: str) -> ToIssueArgs:
        return parse_toissue(text.split())

    parsed = parse("bug 1")
    assert parsed.type == "bug" and parsed.priority == "1"
    assert parsed.flags == set() and parsed.assignee is None and not parsed.warnings

    parsed = parse("feature 3 qT @someone")
    assert parsed.flags == {"q", "t"} and parsed.assignee == "someone"

    parsed = parse("bug 1 @someone")
    assert parsed.flags == set() and parsed.assignee == "someone"

    parsed = parse("bug 1 qxz @someone tail")
    assert parsed.flags == {"q"} and parsed.assignee == "someone"
    assert len(parsed.warnings) == 2

    assert find_command("```\n/toissue bug 1\n```") is None
    assert find_command("> /toissue bug 1") is None
    found = find_command("说明\n  /toissue bug 1 @someone")
    assert found is not None and found[0] == "/toissue"
    assert found[1] == ["bug", "1", "@someone"] and found[2] == 1

    assert strip_command_line("/toissue bug 1\n正文", 0) == "正文"
    assert issue_title("bug", "[Bug] 闪退", "fallback") == "[Bug] 闪退"

    for bad in ("", "bug", "bug 4", "bug 1 @", "nope 1"):
        try:
            parse(bad)
        except (UsageNeeded, ParseError):
            pass
        else:
            raise AssertionError(f"应当拒绝：{bad!r}")

    handler = ToIssueHandler()
    assert handler.handle(None, []) == USAGE
    try:
        handler.handle(None, ["bug", "9"])
    except CommandFailure as error:
        assert not error.fatal
        body = failure_body(handler.name, handler.usage, error.message)
        assert "`/toissue`" in body and "用法" in body and "<details>" in body
    else:
        raise AssertionError("非法 priority 应当返回 CommandFailure")

    print("self-test ok")


def main() -> None:
    parser = argparse.ArgumentParser(description="处理 Discussion 里的 /toissue 命令")
    parser.add_argument(
        "--comment-node-id",
        default=os.environ.get("COMMENT_NODE_ID") or "",
        help="DiscussionComment 的 node ID（workflow 里由 event payload 提供）",
    )
    parser.add_argument("--self-test", action="store_true", help="只跑参数解析自检")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return

    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        fatal("缺少 GH_TOKEN / GITHUB_TOKEN 环境变量")
    repository = os.environ.get("GITHUB_REPOSITORY") or ""
    if "/" not in repository:
        fatal(f"缺少或无法解析 GITHUB_REPOSITORY，当前为：{repository!r}")
    owner, repo = repository.split("/", 1)

    node_id = args.comment_node_id.strip()
    if not node_id:
        fatal("缺少 COMMENT_NODE_ID（DiscussionComment node ID）")

    client = GitHub(token)
    try:
        comment = fetch_comment(client, node_id)
    except GitHubError as error:
        log(f"::error::读取评论失败：{error}")
        raise SystemExit(1)

    found = find_command(comment.body)
    if not found:
        log("评论里没有已注册的命令，跳过")
        return

    if not is_maintainer(client, owner, repo, comment):
        log(f"@{comment.author_login or '(未知用户)'} 没有维护者权限，静默跳过")
        return

    name, command_args, line_index = found
    handler = HANDLERS[name]
    ctx = Context(client, owner, repo, comment, line_index)
    try:
        body = handler.handle(ctx, command_args)
    except CommandFailure as error:
        body = failure_body(handler.name, handler.usage, error.message, error.warnings)
        try:
            url = post_reply(
                client, comment.discussion.node_id, comment.thread_root_id, with_reply_context(comment, body)
            )
            log(f"已回复失败说明：{url}")
        except GitHubError as reply_error:
            log(f"::error::回复失败：{reply_error}；{json.dumps(reply_error.errors, ensure_ascii=False)}")
            raise SystemExit(1)
        if error.fatal:
            raise SystemExit(1)
        return

    try:
        url = post_reply(
            client, comment.discussion.node_id, comment.thread_root_id, with_reply_context(comment, body)
        )
        log(f"已回复：{url}")
    except GitHubError as error:
        log(f"::error::回复失败：{error}；{json.dumps(error.errors, ensure_ascii=False)}")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
