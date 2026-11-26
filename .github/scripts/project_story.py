#!/usr/bin/env python3
"""汇总项目的公开数据（提交、Star、issue、讨论区、发版），给学期总结的「关于岱宗盒子」页用。

用法（在仓库根目录、完整 git 历史下）：
  GITHUB_TOKEN=... python3 .github/scripts/project_story.py out.json
  GITHUB_TOKEN=... python3 .github/scripts/project_story.py --kotlin app/src/main/java/com/xjtu/toolbox/termreport/ProjectStorySnapshot.kt

讨论区只有 GraphQL 能拿，必须带 token；本地可用 `GITHUB_TOKEN=$(gh auth token)`。
时间一律换成北京时间。
"""
import json
import os
import subprocess
import sys
import urllib.request
from collections import Counter
from datetime import datetime, timedelta, timezone

OWNER, REPO = "xjtu-dzhz", "dzhz"
BJ = timezone(timedelta(hours=8))
TOKEN = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")


def request(url, body=None, accept="application/vnd.github+json"):
    req = urllib.request.Request(url, data=json.dumps(body).encode() if body else None)
    req.add_header("Accept", accept)
    req.add_header("User-Agent", "project-story")
    if TOKEN:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r), r.headers.get("Link", "")


def rest_all(path, accept="application/vnd.github+json"):
    url = f"https://api.github.com/repos/{OWNER}/{REPO}/{path}"
    url += ("&" if "?" in url else "?") + "per_page=100"
    out = []
    while url:
        page, link = request(url, accept=accept)
        out += page
        url = next((p.split(";")[0].strip("<> ") for p in link.split(",") if 'rel="next"' in p), None)
    return out


def graphql(query, variables):
    data, _ = request("https://api.github.com/graphql", {"query": query, "variables": variables})
    if "errors" in data:
        raise RuntimeError(data["errors"])
    return data["data"]


def bj(ts):
    return datetime.fromisoformat(ts.replace("Z", "+00:00")).astimezone(BJ)


def human(user):
    return bool(user) and user.get("type") != "Bot" and not user.get("login", "").endswith("[bot]")


def git_commits():
    log = subprocess.run(
        ["git", "log", "--no-merges", "--reverse", "--format=%aI%x09%s"],
        capture_output=True, text=True, encoding="utf-8", check=True,
    ).stdout.strip().splitlines()
    times = [(datetime.fromisoformat(line.split("\t")[0]).astimezone(BJ), line.split("\t", 1)[1]) for line in log]
    return {
        "born": times[0][0].strftime("%Y-%m-%dT%H:%M"),
        "firstCommit": times[0][1],
        "commits": len(times),
        "commitDays": len({t.date() for t, _ in times}),
        "lateCommits": sum(1 for t, _ in times if t.hour < 5),
    }


DISCUSSIONS = """
query($owner:String!,$repo:String!,$after:String){repository(owner:$owner,name:$repo){discussions(first:40,after:$after){
  totalCount pageInfo{hasNextPage endCursor}
  nodes{author{__typename login} comments(first:100){nodes{author{__typename login} replies(first:100){nodes{author{__typename login}}}}}}}}}
"""


def discussions():
    talkers, total, after = Counter(), 0, None
    while True:
        d = graphql(DISCUSSIONS, {"owner": OWNER, "repo": REPO, "after": after})["repository"]["discussions"]
        total = d["totalCount"]
        for n in d["nodes"]:
            people = [n["author"]] + [c["author"] for c in n["comments"]["nodes"]] + \
                [r["author"] for c in n["comments"]["nodes"] for r in c["replies"]["nodes"]]
            for p in people:
                if p and p["__typename"] != "Bot" and not p["login"].endswith("[bot]"):
                    talkers[p["login"]] += 1
        if not d["pageInfo"]["hasNextPage"]:
            return total, talkers
        after = d["pageInfo"]["endCursor"]


def ranked(counter):
    return [{"name": k, "n": v} for k, v in sorted(counter.items(), key=lambda kv: (-kv[1], kv[0].lower()))]


def build():
    story = {"date": datetime.now(BJ).strftime("%Y-%m-%d")}
    story.update(git_commits())

    stars = rest_all("stargazers", accept="application/vnd.github.star+json")
    story["stars"] = sorted(bj(s["starred_at"]).strftime("%Y-%m-%d") for s in stars)

    releases = [r for r in rest_all("releases") if not r["draft"] and not r["prerelease"]]
    story["releases"] = sorted(
        ({"v": r["tag_name"].lstrip("v"), "d": bj(r["published_at"] or r["created_at"]).strftime("%Y-%m-%d")} for r in releases),
        key=lambda r: r["d"],
    )

    items = rest_all("issues?state=all&direction=asc")
    issues = [i for i in items if "pull_request" not in i]
    pulls = {i["number"] for i in items if "pull_request" in i}
    first = issues[0]
    hottest = max(issues, key=lambda i: i["comments"])
    story["issues"] = len(issues)
    story["issuesClosed"] = sum(1 for i in issues if i["state"] == "closed")
    story["firstIssue"] = {
        "n": first["number"], "title": first["title"], "date": bj(first["created_at"]).strftime("%Y-%m-%d"),
        "closeMinutes": int((bj(first["closed_at"]) - bj(first["created_at"])).total_seconds() // 60) if first["closed_at"] else None,
    }
    story["hottestIssue"] = {"n": hottest["number"], "title": hottest["title"], "comments": hottest["comments"]}

    askers = Counter(i["user"]["login"] for i in issues if human(i["user"]))
    for c in rest_all("issues/comments"):
        if int(c["issue_url"].rsplit("/", 1)[1]) not in pulls and human(c["user"]):
            askers[c["user"]["login"]] += 1

    coders = Counter({c["login"]: c["contributions"] for c in rest_all("contributors") if human(c)})
    for p in rest_all("pulls?state=all"):
        if human(p["user"]) and p["user"]["login"] not in coders:
            coders[p["user"]["login"]] = 1

    story["discussions"], talkers = discussions()
    story["coders"], story["askers"], story["talkers"] = ranked(coders), ranked(askers), ranked(talkers)
    return story


KOTLIN = '''package com.xjtu.toolbox.termreport

/**
 * 「关于岱宗盒子」的数据快照，截至 {date}（北京时间）。线上有更新的就用线上那份（见 [ProjectStory]）。
 *
 * 更新：在仓库根目录跑
 * `GITHUB_TOKEN=$(gh auth token) python3 .github/scripts/project_story.py --kotlin {path}`
 */
internal const val PROJECT_STORY_SNAPSHOT = """{json}"""
'''


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__, file=sys.stderr)
        return 2
    story = build()
    text = json.dumps(story, ensure_ascii=False, separators=(",", ":"))
    if args[0] == "--kotlin":
        path = args[1]
        body = KOTLIN.replace("{date}", story["date"]).replace("{path}", path).replace("{json}", text.replace("$", "${'$'}"))
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(body)
    else:
        with open(args[0], "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
    print(f"stars={len(story['stars'])} issues={story['issues']} discussions={story['discussions']} "
          f"coders={len(story['coders'])} askers={len(story['askers'])} talkers={len(story['talkers'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
