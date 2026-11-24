#!/usr/bin/env bash
# 把一个 ref 推到 Gitee 镜像：先按普通方式推；只有被拒（Gitee 上有分叉的提交，或同一版本重发时 tag 被重打）
# 才强推，并把被覆盖的提交打成警告。网络、权限这类错误直接失败，不盲目强推。
# 用法：GITEE_TOKEN=… gitee_push.sh <本地 ref> <远端 ref>，例如 HEAD refs/heads/main、refs/tags/v6.2.0 refs/tags/v6.2.0
set -euo pipefail
src="$1"
dst="$2"
url="https://gitee.com/yeliqin666/xjtu-toolbox-android.git"
: "${GITEE_TOKEN:?GITEE_TOKEN missing}"
export GIT_TERMINAL_PROMPT=0
# token 不进 URL（会写进 .git/config、报错时可能打出来），只经 env 交给 credential helper
git config --local --replace-all 'credential.https://gitee.com.helper' \
  '!f() { echo "username=yeliqin666"; echo "password=$GITEE_TOKEN"; }; f'

err=$(mktemp)
if git push -q "$url" "$src:$dst" 2>"$err"; then
  echo "Gitee $dst 已同步"
  exit 0
fi
cat "$err" >&2
if ! grep -qE 'rejected|non-fast-forward|fetch first|already exists' "$err"; then
  exit 1
fi

old=$(git ls-remote "$url" "$dst" | cut -f1)
echo "::warning::Gitee 上的 $dst（${old:0:8}）与 GitHub 分叉，改为强推覆盖"
if [ -n "$old" ] && git fetch -q "$url" "$dst" 2>/dev/null; then
  git log --oneline "$src..FETCH_HEAD" | sed 's/^/  被覆盖：/' || true
fi
git push -q --force "$url" "$src:$dst"
echo "Gitee $dst 已强推同步"
