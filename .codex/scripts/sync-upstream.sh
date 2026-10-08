#!/usr/bin/env bash
# sync-upstream.sh — WebHTV 上游 beta 同步助手
#
# 把「侦察 -> 合并 -> 合并后校验 -> 打恢复标签」固化成可重复执行的入口，
# 用于防止再次出现 2026-08-24 那类坏合并（冲突标记被提交进 main、上游文件被静默删除）。
#
# 用法:
#   bash .codex/scripts/sync-upstream.sh status [--remote R] [--branch B]
#   bash .codex/scripts/sync-upstream.sh merge  [--remote R] [--branch B] [--tag TASK-ID] [--dry-run]
#   bash .codex/scripts/sync-upstream.sh verify [--remote R] [--branch B]
#   bash .codex/scripts/sync-upstream.sh tag    TASK-ID
#   bash .codex/scripts/sync-upstream.sh help
#
# 退出码: 0 通过 / 2 用法或环境错误 / 4 安全门 / 5 合并后校验发现问题
#
# 安全约束:
#   - 本脚本不做任何破坏性回滚（不执行 reset --hard、checkout .、clean）。需要回滚时
#     只列出可用恢复标签，由人工决定。
#   - 通过 GIT_CONFIG_COUNT / GIT_CONFIG_KEY_0 / GIT_CONFIG_VALUE_0 局部注入
#     core.quotePath=false，使中文路径不被 C-quote 污染，且不修改仓库或全局 git 配置。
#   - 本仓库独有内容由 LOCAL_ONLY_PATTERNS 白名单保护，只报「意外差异」，不误伤定制目录。

set -euo pipefail

usage() {
  cat <<'EOF'
用法:
  sync-upstream.sh status [--remote R] [--branch B]
      只读侦察：fetch 上游、列出落后提交、差异规模、本地独有/上游独有/意外差异分组。
      可在任意工作树状态下运行，不改动任何文件。

  sync-upstream.sh merge [--remote R] [--branch B] [--tag TASK-ID] [--dry-run]
      预检（干净工作树 / merge.renames=false / 无活跃守卫 / 不在合并中）
      -> git merge -> 合并后完整性校验 -> 可选打恢复标签。

  sync-upstream.sh verify [--remote R] [--branch B]
      只做合并后完整性校验（不联网、不 fetch），对照本地已有的远程引用。

  sync-upstream.sh tag TASK-ID
      为当前 HEAD 创建注解恢复标签 recovery/<TASK-ID>/<时间戳>-<12位 sha>。

默认: --remote upstream --branch beta
EOF
}

if [[ -z "${BASH_VERSION:-}" ]]; then
  printf 'ERROR: 需要 bash 运行（Windows 下用: & "C:\\Program Files\\Git\\bin\\bash.exe" <脚本>）\n' >&2
  exit 2
fi

REMOTE="upstream"
BRANCH="beta"

# 本仓库独有/有意分歧的路径前缀。白名单内出现的差异不报警。
# 注意 .codex/scripts/task_guard.sh 在上游同样存在，故只精确放行本仓库自有的同步助手，
# 以免掩盖 task_guard.sh 的真实分歧。
# .gitattributes/.gitignore 是本仓库有意定制的仓库级配置（*.sh eol=lf、/plans/ 等），同属有意分歧。
LOCAL_ONLY_PATTERNS=(
  ".codex/scripts/sync-upstream.sh"
  ".gitattributes"
  ".gitignore"
  "docs/cf-"
  "plans/"
  "serverless/playback-identity-fixtures/"
  "serverless/webhtv-playback-sync-cloudflare/"
  "serverless/webhtv-remote-cloudflare/"
  "serverless/webhtv-remote-cloudflare-custom/"
  "third_party/mpv-native-overrides/"
  "third_party/patches/"
  "webhome-devkit/"
)

# 必须与上游逐字节一致的目录（上游自有代码）。
CORE_DIRS=(app catvod gradle scripts)

repo_root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
  printf 'ERROR: 不在 Git 工作树内\n' >&2
  exit 2
}
cd "$repo_root"

# 局部注入，规避 git 默认 core.quotePath=true 把中文路径 C-quote 成 "plans/EXO\351..." 的问题。
export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0=core.quotePath
export GIT_CONFIG_VALUE_0=false

if [[ -t 1 ]]; then
  C_RESET=$'\033[0m'; C_RED=$'\033[31m'; C_YEL=$'\033[33m'; C_GRN=$'\033[32m'; C_BLU=$'\033[34m'
else
  C_RESET=""; C_RED=""; C_YEL=""; C_GRN=""; C_BLU=""
fi

info() { printf '%s[INFO]%s %s\n' "$C_BLU" "$C_RESET" "$*"; }
ok()   { printf '%s[OK]%s %s\n' "$C_GRN" "$C_RESET" "$*"; }
# stderr 写入失败（某些受限终端/管道下 stderr 不可写）不得因 set -e 提前退出，
# 否则 die_usage/die_gate 无法返回文档化的 2/4 退出码。
warn() { printf '%s[WARN]%s %s\n' "$C_YEL" "$C_RESET" "$*" >&2 || true; }
fail() { printf '%s[FAIL]%s %s\n' "$C_RED" "$C_RESET" "$*" >&2 || true; }

die_usage() { printf 'ERROR: %s\n' "$1" >&2 || true; exit 2; }
die_gate()  { printf 'SAFETY_GATE: %s\n' "$1" >&2 || true; exit 4; }

remote_ref() { printf 'refs/remotes/%s/%s\n' "$REMOTE" "$BRANCH"; }
remote_name() { printf '%s/%s\n' "$REMOTE" "$BRANCH"; }

is_local_only() {
  local path="$1" pattern
  for pattern in "${LOCAL_ONLY_PATTERNS[@]}"; do
    if [[ "$path" == "$pattern" ]] || [[ "$path" == "$pattern"* ]]; then
      return 0
    fi
  done
  return 1
}

dirty_count() { git status --porcelain | wc -l | tr -d ' '; }

# 差异分组结果（由 classify_divergence 填充）
DIV_LOCAL_ONLY=()      # HEAD 有、上游无，且在白名单内（预期保留）
DIV_UNEXPECTED_DROP=() # HEAD 有、上游无，且不在白名单内（上游删除了我们在用的东西，需确认）
DIV_UPSTREAM_ONLY=()   # 上游有、HEAD 无，且在白名单内（预期不跟踪或已按策略删除）
DIV_MISSING=()         # 上游有、HEAD 无，且不在白名单内（上游新增文件，本地缺失，需引入）
DIV_LOCAL_MOD=()       # 内容有差异，且在白名单内（本地定制改动）
DIV_CONFLICT_CONTENT=() # 内容有差异，且不在白名单内（未对齐，需处理）

# classify_divergence <from> <to>
# 方向: from -> to。D = from 独有, A = to 独有, M/T = 都有但内容不同。
classify_divergence() {
  local from="$1" to="$2"
  local st p1 p2

  DIV_LOCAL_ONLY=(); DIV_UNEXPECTED_DROP=()
  DIV_UPSTREAM_ONLY=(); DIV_MISSING=()
  DIV_LOCAL_MOD=(); DIV_CONFLICT_CONTENT=()

  while IFS=$'\t' read -r st p1 p2; do
    [[ -n "${st:-}" ]] || continue
    [[ -n "${p1:-}" ]] || continue
    case "$st" in
      D)
        if is_local_only "$p1"; then DIV_LOCAL_ONLY+=("$p1"); else DIV_UNEXPECTED_DROP+=("$p1"); fi
        ;;
      A)
        if is_local_only "$p1"; then DIV_UPSTREAM_ONLY+=("$p1"); else DIV_MISSING+=("$p1"); fi
        ;;
      M|T)
        if is_local_only "$p1"; then DIV_LOCAL_MOD+=("$p1"); else DIV_CONFLICT_CONTENT+=("$p1"); fi
        ;;
      *)
        DIV_CONFLICT_CONTENT+=("$st $p1")
        ;;
    esac
  done < <(git diff --name-status --no-renames "$from" "$to")
}

print_group() {
  local title="$1" marker="$2"; shift 2
  local -a items=()
  local item
  # 调用方可能传入 "${arr[@]-}"，空数组时会展开成一个空词；这里统一过滤。
  for item in "$@"; do
    [[ -n "$item" ]] || continue
    items+=("$item")
  done
  if ((${#items[@]} == 0)); then
    printf '  %s %s: 0\n' "$marker" "$title"
    return 0
  fi
  printf '  %s %s: %s\n' "$marker" "$title" "${#items[@]}"
  for item in "${items[@]}"; do printf '      - %s\n' "$item"; done
}

# ---------------------------------------------------------------- status

cmd_status() {
  info "拉取 $REMOTE（--prune）"
  if ! git fetch "$REMOTE" --prune; then
    die_gate "git fetch $REMOTE 失败（网络或权限），未做任何改动"
  fi

  local ref head behind ahead
  ref="$(git rev-parse --verify --quiet "$(remote_ref)")" || die_gate "找不到 $(remote_name)（先 git fetch $REMOTE）"
  head="$(git rev-parse HEAD)"
  ahead="$(git rev-list --count "$ref..HEAD")"
  behind="$(git rev-list --count "HEAD..$ref")"

  printf '\n%s== 本地 HEAD ==%s\n' "$C_BLU" "$C_RESET"
  git log -1 --format='%H  %ci  %s' HEAD
  printf '\n%s== %s ==%s\n' "$C_BLU" "$(remote_name)" "$C_RESET"
  git log -1 --format='%H  %ci  %s' "$ref"
  printf '\n  本地领先 %s 个提交，上游领先 %s 个提交\n' "$ahead" "$behind"

  if [[ "$behind" == "0" ]]; then
    printf '\n'
    ok "已与 $(remote_name) 同步（上游无新提交）"
    return 0
  fi

  printf '\n%s== 上游新增提交（HEAD..%s）==%s\n' "$C_BLU" "$(remote_name)" "$C_RESET"
  git log --oneline --no-decorate "HEAD..$ref"
  printf '\n'
  git diff --stat "$head" "$ref" | tail -1

  printf '\n%s== 差异分组（HEAD -> %s）==%s\n' "$C_BLU" "$(remote_name)" "$C_RESET"
  classify_divergence "$head" "$ref"
  print_group "本地独有（白名单，预期保留）" "·" "${DIV_LOCAL_ONLY[@]-}"
  print_group "本地定制改动（白名单）" "·" "${DIV_LOCAL_MOD[@]-}"
  print_group "上游独有（白名单，预期不跟踪/已按策略删除）" "·" "${DIV_UPSTREAM_ONLY[@]-}"
  print_group "上游独有·非白名单（合并时会进入本地）" "?" "${DIV_MISSING[@]-}"
  print_group "本地独有·非白名单（上游删除了它们在用的文件，须确认）" "!" "${DIV_UNEXPECTED_DROP[@]-}"
  print_group "内容差异·非白名单（需对齐上游）" "+" "${DIV_CONFLICT_CONTENT[@]-}"

  local item
  for item in "${DIV_MISSING[@]-}"; do
    case "$item" in
      serverless/webhtv-remote-cloudflare/*)
        warn "上游涉及旧 Worker 路径（$item）；merge.renames=false 下会成为显式 modify/delete，属预期"
        ;;
    esac
  done
  for item in "${DIV_CONFLICT_CONTENT[@]-}"; do
    case "$item" in
      app/src/main/java/com/fongmi/android/tv/ad/audio/*)
        warn "上游仍在改动 ad-audio（$item）；本仓库已声明对齐上游删除该模块"
        ;;
    esac
  done

  printf '\n下一步: bash .codex/scripts/sync-upstream.sh merge\n'
}

# ---------------------------------------------------------------- verify

verify_workspace() {
  local ref rc=0
  ref="$(git rev-parse --verify --quiet "$(remote_ref)")" || die_gate "找不到 $(remote_name)"

  printf '\n%s== 合并后完整性校验（对照 %s @ %.12s）==%s\n' "$C_BLU" "$(remote_name)" "$ref" "$C_RESET"

  # 1) 未合并文件
  local unmerged
  unmerged="$(git ls-files -u | wc -l | tr -d ' ')"
  if [[ "$unmerged" == "0" ]]; then
    ok "无未合并文件"
  else
    fail "存在 $unmerged 条未合并记录："
    git diff --name-only --diff-filter=U | head -40 >&2 || true
    warn "解决冲突后执行 git add，再运行 merge/verify"
    rc=5
  fi

  # 2) 残留冲突标记（排除 *.md，避免文档里的示例文本误报）
  local marker_hits marker_count
  marker_hits="$(git grep -l -e '^<<<<<<< ' -e '^>>>>>>> ' -- . ':(exclude)*.md' || true)"
  marker_count="$(printf '%s' "$marker_hits" | grep -c . || true)"
  if [[ "$marker_count" == "0" ]]; then
    ok "无残留冲突标记"
  else
    fail "发现 $marker_count 个文件仍含冲突标记（这是 2026-08-24 事故的根因）："
    printf '%s\n' "$marker_hits" | head -40 >&2 || true
    rc=5
  fi

  # 3) 上游自有目录必须逐字节一致
  local core_stat core_lines
  core_stat="$(git diff --stat HEAD "$ref" -- "${CORE_DIRS[@]}")"
  core_lines="$(printf '%s' "$core_stat" | grep -c . || true)"
  if [[ "$core_lines" == "0" ]]; then
    ok "上游自有目录与 $(remote_name) 逐字节一致（${CORE_DIRS[*]}）"
  else
    fail "上游自有目录仍与 $(remote_name) 存在差异（可能上游又有新提交，或合并未完成）："
    printf '%s\n' "$core_stat" >&2 || true
    rc=5
  fi

  # 4) 全量差异分组：非白名单差异必须为 0
  classify_divergence HEAD "$ref"
  printf '\n  白名单内：本地独有 %s / 本地定制 %s / 上游独有 %s\n' \
    "${#DIV_LOCAL_ONLY[@]}" "${#DIV_LOCAL_MOD[@]}" "${#DIV_UPSTREAM_ONLY[@]}"

  if ((${#DIV_MISSING[@]} > 0)) || ((${#DIV_UNEXPECTED_DROP[@]} > 0)) || ((${#DIV_CONFLICT_CONTENT[@]} > 0)); then
    fail "存在非白名单差异（需人工确认后处理）："
    ((${#DIV_MISSING[@]} == 0))         || printf '  上游独有但本地缺失 (%s): %s\n' "${#DIV_MISSING[@]}" "${DIV_MISSING[*]}" >&2 || true
    ((${#DIV_UNEXPECTED_DROP[@]} == 0)) || printf '  本地独有但上游删除 (%s): %s\n' "${#DIV_UNEXPECTED_DROP[@]}" "${DIV_UNEXPECTED_DROP[*]}" >&2 || true
    ((${#DIV_CONFLICT_CONTENT[@]} == 0)) || printf '  内容未对齐 (%s): %s\n' "${#DIV_CONFLICT_CONTENT[@]}" "${DIV_CONFLICT_CONTENT[*]}" >&2 || true
    rc=5
  else
    ok "非白名单差异为 0（剩余差异全部是本仓库独有内容）"
  fi

  # 5) 上游引用是否已包含在 HEAD 中
  if git merge-base --is-ancestor "$ref" HEAD; then
    ok "$(remote_name) 已包含在当前 HEAD 中"
  else
    warn "$(remote_name) 尚未包含在当前 HEAD 中（合并未完成，或上游有新提交）"
    rc=5
  fi

  # 6) 被 .gitignore 忽略但已进入 index 的路径（守卫 git add 会在此处失败）
  local ignored_tracked
  ignored_tracked="$(git ls-files -ci --exclude-standard || true)"
  if [[ -n "$ignored_tracked" ]]; then
    warn "以下路径已被跟踪但命中 .gitignore，任务守卫 git add 会报错："
    printf '%s\n' "$ignored_tracked" | head -20 >&2 || true
    warn "处理: git rm --cached -q -- <路径>（文件保留在磁盘上，仅移出 index）"
  fi

  return "$rc"
}

# ---------------------------------------------------------------- merge

cmd_merge() {
  local do_tag="" dry_run=0

  while (($# > 0)); do
    case "$1" in
      --tag) (($# >= 2)) || die_usage "--tag 需要一个值"; do_tag="$2"; shift 2 ;;
      --dry-run) dry_run=1; shift ;;
      *) die_usage "merge 未知选项: $1" ;;
    esac
  done

  local ref
  ref="$(git rev-parse --verify --quiet "$(remote_ref)")" || die_gate "找不到 $(remote_name)"

  # --- 预检 -----------------------------------------------------------
  # merge.renames 必须为 false：否则上游改动旧路径 serverless/webhtv-remote-cloudflare
  # 会按内容做重命名配对，静默覆盖本仓库定制目录 webhtv-remote-cloudflare-custom。
  local renames
  renames="$(git config --get merge.renames || true)"
  if [[ "$renames" != "false" ]]; then
    if ((dry_run == 1)); then
      warn "merge.renames 当前为 '${renames:-未设置}'，正式合并时会自动设为 false"
    else
      warn "merge.renames 当前为 '${renames:-未设置}'（本仓库要求 false），正在设置"
      git config merge.renames false
      ok "已设置 merge.renames=false"
    fi
  fi

  if [[ -f .git/MERGE_HEAD ]]; then
    die_gate "当前处于未完成的合并中；先 'git merge --abort' 或解决冲突并提交后再运行"
  fi

  if [[ -f .codex/task-state/current/status ]]; then
    if [[ "$(sed -n '1p' .codex/task-state/current/status)" == "active" ]]; then
      die_gate "存在活跃的任务守卫会话（.codex/task-state/current）；先 finish 或清理后再合并"
    fi
  fi

  local dirty
  dirty="$(dirty_count)"
  if [[ "$dirty" != "0" ]]; then
    fail "工作树不干净（$dirty 个路径）；任务守卫要求 scope 不与既有脏路径重叠。先提交或暂存："
    git status --short | head -30 >&2 || true
    exit 4
  fi

  local head
  head="$(git rev-parse HEAD)"
  local behind
  behind="$(git rev-list --count "HEAD..$ref")"
  ok "预检通过：工作树干净、merge.renames=false、无活跃守卫、不在合并中"
  info "本地 %.12s -> 上游 %s %.12s（落后 %s 个提交）" "$head" "$(remote_name)" "$ref" "$behind"

  if ((behind == 0)); then
    ok "已是最新，无需合并"
    return 0
  fi
  git log --oneline --no-decorate "HEAD..$ref"

  if ((dry_run == 1)); then
    printf '\n'
    ok "--dry-run：预检与差异展示完毕，未执行合并"
    return 0
  fi

  # --- 合并 -----------------------------------------------------------
  printf '\n%s== git merge %s ==%s\n' "$C_BLU" "$(remote_name)" "$C_RESET"
  if git merge "$(remote_name)" --no-edit; then
    ok "合并完成"
  else
    local merge_rc=$?
    fail "合并返回 $merge_rc（很可能有冲突）" >&2
    if git diff --name-only --diff-filter=U | head -40; then :; fi
    printf '\n解决冲突的裁决口径：以代码一致性优先——逐块选择与「文件其余部分/上游最新实现」一致的一侧。\n' >&2 || true
    printf '处理完后: git add -A && git commit --no-edit，然后运行: bash .codex/scripts/sync-upstream.sh verify\n' >&2 || true
    printf '放弃本次合并: git merge --abort\n' >&2 || true
    exit 4
  fi

  # --- 校验 -----------------------------------------------------------
  local verify_rc=0
  verify_workspace || verify_rc=$?

  # --- 打标签 ---------------------------------------------------------
  if ((verify_rc == 0)); then
    printf '\n'
    if [[ -n "$do_tag" ]]; then
      cmd_tag "$do_tag" || true
    else
      info "干净合并会自行生成提交，任务守卫此时无 dirty 可提交，因此直接打恢复标签即可："
      printf '  bash .codex/scripts/sync-upstream.sh tag <task-id>\n'
    fi
  fi

  # --- 后续提示 -------------------------------------------------------
  printf '\n%s== 后续 ==%s\n' "$C_BLU" "$C_RESET"
  printf '  1) 编译验证（需本机 Android SDK 与 local.properties）\n'
  printf '  2) 仍需额外提交时走任务守卫（模式 upstream）:\n'
  printf '     bash .codex/scripts/task_guard.sh start --id <id> --mode upstream --scope <path>...\n'
  printf '     bash .codex/scripts/task_guard.sh finish --verified "<证据>" --commit-message "<msg>"\n'
  printf '  3) 回滚锚点（本脚本不自动回滚，仅列出）:\n'
  git tag -l 'recovery/*' | sort | tail -5 | sed 's/^/     /'

  return "$verify_rc"
}

# ---------------------------------------------------------------- tag

cmd_tag() {
  local task_id="${1:-}"
  [[ -n "$task_id" ]] || die_usage "tag 需要一个 task-id"
  [[ "$task_id" =~ ^[A-Za-z0-9._-]+$ ]] || die_usage "task-id 只能包含字母、数字、点、下划线和连字符"

  local dirty
  dirty="$(dirty_count)"
  if [[ "$dirty" != "0" ]]; then
    warn "工作树存在 $dirty 个未提交路径；恢复标签应指向已验证的提交状态"
  fi

  local commit stamp tag
  commit="$(git rev-parse HEAD)"
  stamp="$(TZ=Asia/Shanghai date +%Y%m%d%H%M%S)"
  tag="recovery/$task_id/$stamp-${commit:0:12}"

  if GIT_OPTIONAL_LOCKS=0 git -c tag.gpgSign=false tag -a "$tag" "$commit" -m "Recovery point for $task_id. Created by sync-upstream.sh"; then
    ok "已创建注解恢复标签: $tag -> $commit"
    return 0
  fi
  die_gate "创建标签失败: $tag（常见原因：同名标签已存在）"
}

# ---------------------------------------------------------------- 入口

command_name="${1:-}"
[[ -n "$command_name" ]] || { usage; exit 2; }
shift

while (($# > 0)); do
  case "$1" in
    --remote) (($# >= 2)) || die_usage "--remote 需要一个值"; REMOTE="$2"; shift 2 ;;
    --branch) (($# >= 2)) || die_usage "--branch 需要一个值"; BRANCH="$2"; shift 2 ;;
    *) break ;;
  esac
done

case "$command_name" in
  status) (($# == 0)) || die_usage "status 不接受额外参数"; cmd_status ;;
  verify) (($# == 0)) || die_usage "verify 不接受额外参数"; verify_workspace ;;
  merge)  cmd_merge "$@" ;;
  tag)    cmd_tag "$@" ;;
  -h|--help|help) usage ;;
  *) die_usage "未知命令: $command_name" ;;
esac
