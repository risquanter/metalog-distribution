#!/usr/bin/env bash
# PreToolUse gate enforcing the register working protocol (G1/G3/G6/G7).
#
# Dispatched on tool_name from the hook's stdin JSON.
#
# Bash — three denials, in this order:
#   (a) Any command referencing the protocol machinery: the approval token
#       (.claude/protocol/), this hook (.claude/hooks/), the approval script
#       (.claude/bin/), or the hook wiring (.claude/settings.json). All of it
#       is user-owned. The agent reads these files with the Read tool, which is
#       not gated, and never writes or runs them.
#   (b) Any command that downloads or installs a third-party package. Nothing
#       enters this machine or this repository without the user running it.
#       Covers pip/pipx/uv, npm/npx/yarn/pnpm/bun, cargo, gem, go, the system
#       package managers, coursier, and piping a fetched script into a shell.
#       Not covered, deliberately: mvn, which resolves the dependencies pom.xml
#       pins as ordinary build behaviour.
#   (c) Any command that would WRITE into the gated region (src/**,
#       pom.xml) or into a file inventory (docs/dev/plans/*-INVENTORY.md).
#       File writes through Bash never reach the checks further down, so
#       allowing them would make those checks optional. Source edits go through
#       Edit/Write, where the check runs; inventories are written only by the
#       user, through .claude/bin/approve-inventory.
#       Detection is heuristic: it catches carelessness, not a determined
#       bypass. It looks for a redirection whose target is in the gated region,
#       an in-place file command, or an interpreter running inline code that
#       writes. A read-only command can therefore be denied by mistake; the
#       answer is to ask, not to reshape the command.
#
#   (b) and (c) each embed the command verbatim in the denial, because the
#   agent is required to hand it back to the user as a copy-pasteable block and
#   stop. Each has its own one-shot exemption file, created by the user from
#   their own terminal; they are separate so approving a batched edit can never
#   also approve a download. Using one removes it and appends the command to
#   the override log.
#
# Edit/Write/NotebookEdit — two categories.
#
#   Never writable by the agent, whatever the token says: the protocol
#   machinery under .claude/ (the skills directory excepted, which is
#   documentation) and every file inventory. These files decide what the agent
#   may write, and an agent that can edit its own authorization can authorize
#   anything.
#
#   This denial is NOT absolute, and must not be relied on as though it were.
#   It inspects the text of a command before a shell parses it, so a command
#   that builds a protected path at runtime is not matched. What it provides is
#   prevention against careless self-authorization, plus the guarantee that any
#   circumvention had to be deliberate — there is no way to construct one by
#   accident. Inventories are tracked by git, which supplies the evidence.
#
#   Gated by the inventory: src/** and pom.xml. The token must name an
#   approved plan document, and the edited file must be listed in that plan's
#   file inventory. Approval is PLAN-SCOPED (user ruling 2026-07-24): not
#   time-based, not blanket — the token binds to one plan's declared file set,
#   so unplanned files are denied even while a plan is approved (that denial IS
#   the escalation trigger: stop, present, wait).
#
#   Where the inventory lives: a document beside the plan, named after it —
#   docs/dev/plans/PLAN-<name>.md is paired with
#   docs/dev/plans/PLAN-<name>-INVENTORY.md. The pairing is a string
#   transform, so there is one naming rule and no ambiguity. An approved plan
#   with no inventory document authorizes nothing, and says so.
#
#   Matching rule: only bullet lines ("- ...") in the inventory document
#   authorize a file. Anything else in it — its heading, its explanatory
#   paragraphs, a table, a "Not touched" note — grants nothing, even when it
#   names the path. A path written as a markdown table row is a path the hook
#   does not see.
#
#   Test escape hatch: an edit to src/test/** is also authorized when the
#   inventory lists any src/main/** file, so a signature change in production
#   source covers the forced edits to the tests without re-listing each.
#   Production source and pom.xml still require an exact bullet entry.
#
#   Amending an inventory: the agent proposes, the user applies. The agent
#   prints a block naming the plan and the paths; the user pastes it into
#   .claude/protocol/pending, reads it, and runs .claude/bin/approve-inventory.
#   Inventories are tracked by git, so an unauthorized change to one shows up
#   in git status and in the commit diff.
#
# The user approves a plan from their own terminal:
#   mkdir -p .claude/protocol && echo "docs/dev/PLAN-<name>.md" > .claude/protocol/approved
# Multiple concurrent plans: one repo-relative plan path per line.
# Revoke / close out: rm -rf .claude/protocol   (the agent flags plan
# completion in its landing report so the user knows when).
set -euo pipefail

# Fail closed. This hook can only BLOCK a call in two ways: print the deny JSON
# and exit 0, or exit 2. Any other exit, with or without output, is treated as a
# non-blocking error and the tool call proceeds. So every way this script can
# stop early has to end at exit 2, or a malfunction silently allows the edit.
gate_error() {
  echo "protocol-gate: $1 — blocking the call rather than allowing it." >&2
  exit 2
}
trap 'gate_error "internal error at line $LINENO"' ERR

command -v jq >/dev/null 2>&1 || gate_error "jq is not available"

# Both sides of every path comparison are normalized the same way, so that a
# trailing slash, a "." or a ".." segment cannot make a gated path look
# ungated. readlink -m resolves a path that does not exist yet, which is the
# normal case for a file a plan is about to create.
ROOT="${CLAUDE_PROJECT_DIR:-$(pwd)}"
ROOT="$(readlink -m -- "$ROOT")" || gate_error "the project root does not resolve"
TOKEN="$ROOT/.claude/protocol/approved"
BASH_OVERRIDE="$ROOT/.claude/protocol/bash-edit-approved"
INSTALL_OVERRIDE="$ROOT/.claude/protocol/install-approved"
OVERRIDE_LOG="$ROOT/.claude/protocol/override-log"

deny() {
  jq -n --arg reason "$1" \
    '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$reason}}'
  exit 0
}

# bullet_grants <inventory> <path> [prefix]
#
# True when a bullet line in the inventory carries the path as a WHOLE
# whitespace-delimited token, once markdown punctuation is stripped from each
# end. Substring containment is not enough: a bullet naming Registry.scala.bak
# must not authorize Registry.scala.
#
# A bullet may carry several paths and trailing prose, which is how they are
# actually written — "`pom.xml`, `README.md` (version bump)" grants
# all three. Every token on the line is considered, not just the first.
#
# With a third argument, the token only has to START with the path, which is
# what the same-module test escape hatch needs.
bullet_grants() {
  awk -v want="$2" -v prefix="${3:-}" '
    /^[[:space:]]*- / {
      line = $0
      sub(/^[[:space:]]*-[[:space:]]+/, "", line)
      n = split(line, parts, /[[:space:]]+/)
      for (i = 1; i <= n; i++) {
        p = parts[i]
        gsub(/^[`("*]+/, "", p)
        gsub(/[`)"*,;:.]+$/, "", p)
        if (p == "") continue
        if (prefix == "") {
          if (p == want) { found = 1; exit }
        } else {
          if (index(p, want) == 1) { found = 1; exit }
        }
      }
    }
    END { exit(found ? 0 : 1) }
  ' "$1"
}

# consume <override-file> <kind> <detail> <command>
consume() {
  local file="$1" kind="$2" detail="$3" cmd="$4" note
  note="$(head -c 400 "$file" 2>/dev/null | tr '\n' ' ' || true)"
  rm -f "$file"
  {
    printf '%s consumed the one-shot %s approval\n' "$(date -Is)" "$kind"
    printf '  approved for : %s\n' "${note:-(no note given)}"
    printf '  detected     : %s\n' "$detail"
    printf '  command      : %s\n\n' "$cmd"
  } >> "$OVERRIDE_LOG" 2>/dev/null || true
}

INPUT="$(cat)"
TOOL="$(jq -r '.tool_name // empty' <<<"$INPUT")"

if [ "$TOOL" = "Bash" ]; then
  CMD="$(jq -r '.tool_input.command // empty' <<<"$INPUT")"

  # --- (a) the protocol machinery is user-owned -----------------------------
  case "$CMD" in
    *".claude/protocol"*|*".claude/bin"*|*".claude/hooks"*|*".claude/settings.json"*)
      deny "Blocked: the working-protocol machinery is user-owned — the approval token (.claude/protocol/), this hook (.claude/hooks/), the approval script (.claude/bin/), and the hook wiring (.claude/settings.json). Only the user creates, refreshes, runs, or removes any of it (working-protocol G7).

To READ any of these files, use the Read tool — it is not gated. To have one of them run or changed, hand the user the exact command and stop."
      ;;
  esac

  # --- (b) third-party package downloads -----------------------------------
  INSTALL=""
  SEP='(^|[;&|(`[:space:]])'

  if grep -qE "${SEP}(pip3?[[:space:]]+[^;|&]*install|python3?[[:space:]]+-m[[:space:]]+pip[[:space:]]+[^;|&]*install|pipx[[:space:]]+install|uv[[:space:]]+pip[[:space:]]+[^;|&]*install|uv[[:space:]]+add)([[:space:]]|$)" <<<"$CMD"; then
    INSTALL="a Python package install"
  elif grep -qE "${SEP}(npm[[:space:]]+(install|i|add|update)|npx|yarn[[:space:]]+add|pnpm[[:space:]]+add|bun[[:space:]]+add)([[:space:]]|$)" <<<"$CMD"; then
    INSTALL="a JavaScript package install or npx download"
  elif grep -qE "${SEP}(cargo[[:space:]]+(install|add)|gem[[:space:]]+install|go[[:space:]]+(get|install))([[:space:]]|$)" <<<"$CMD"; then
    INSTALL="a Rust, Ruby, or Go package install"
  elif grep -qE "${SEP}(apt(-get)?[[:space:]]+[^;|&]*install|apk[[:space:]]+add|dnf[[:space:]]+[^;|&]*install|yum[[:space:]]+[^;|&]*install|brew[[:space:]]+install)([[:space:]]|$)" <<<"$CMD"; then
    INSTALL="a system package install"
  elif grep -qE "${SEP}(cs|coursier)[[:space:]]+install([[:space:]]|$)" <<<"$CMD"; then
    INSTALL="a coursier install"
  elif grep -qE "${SEP}(curl|wget)[^|]*\|[[:space:]]*(sudo[[:space:]]+)?(ba|z|k|da|a)?sh([[:space:]]|$)" <<<"$CMD"; then
    INSTALL="a fetched script piped into a shell"
  fi

  if [ -n "$INSTALL" ]; then
    if [ -f "$INSTALL_OVERRIDE" ]; then
      consume "$INSTALL_OVERRIDE" "install" "$INSTALL" "$CMD"
    else
      deny "Blocked: this command downloads or installs a third-party package. Detected $INSTALL.

Nothing is installed on this machine or into this repository except by the user running it themselves.

Hand the command below back to the user verbatim, as a copy-pasteable block. Say what it is for, what it would pull in, and why it is needed. Then STOP and wait.

  $CMD

The user either runs it in their own terminal, or grants a ONE-SHOT exemption:

  mkdir -p .claude/protocol && printf '%s\n' '<what was approved>' > .claude/protocol/install-approved

Do not retry, do not reshape the command, and do not look for another route to the same download."
    fi
  fi

  # --- (c) writes into the gated region ------------------------------------
  # Nothing below applies unless the command names the gated region at all.
  if ! grep -qE '(^|[^A-Za-z0-9_./-])src/|pom\.xml|docs/dev/plans/.*-INVENTORY\.md' <<<"$CMD"; then
    exit 0
  fi

  WRITE=""

  # A redirection whose TARGET is in the gated region. Only the target is
  # tested, so reading a gated file into the scratchpad stays allowed.
  while IFS= read -r TGT; do
    [ -n "$TGT" ] || continue
    case "$TGT" in
      src/*|*/src/*|*pom.xml|*docs/dev/plans/*-INVENTORY.md) WRITE="a redirection into '$TGT'"; break ;;
    esac
  done <<<"$(grep -oE '(^|[^0-9&<>])>>?[[:space:]]*[^[:space:];|&<>()]+' <<<"$CMD" \
             | sed -E 's/^.*>>?[[:space:]]*//' || true)"

  # A command whose job is to modify, move, or delete files in place.
  if [ -z "$WRITE" ] && grep -qE "${SEP}(sed[[:space:]]+[^;|&]*--?[A-Za-z]*i|perl[[:space:]]+[^;|&]*-[A-Za-z]*i|tee|cp|mv|install|rsync|dd|truncate|shred|patch|ln|mkdir|git[[:space:]]+(checkout|restore|apply|mv|rm|clean|stash))([[:space:]]|$)" <<<"$CMD"; then
    WRITE="an in-place file command"
  fi

  # An interpreter running inline code (heredoc, -c or -e) carrying a write
  # construct. This is the route that batches many edits into one call.
  if [ -z "$WRITE" ] \
     && grep -qE "${SEP}(python3?|perl|ruby|node|php)([[:space:]]|$)" <<<"$CMD" \
     && grep -qE '<<|open\(|\.write\(|writeFile|writelines|Path\(' <<<"$CMD"; then
    WRITE="an interpreter running inline code that writes files"
  fi

  [ -n "$WRITE" ] || exit 0

  # The one-shot Bash-write exemption covers the source region only. A file
  # inventory decides what the agent may write, so no exemption reaches one.
  case "$CMD" in
    *docs/dev/plans/*-INVENTORY.md) ;;
    *)
      if [ -f "$BASH_OVERRIDE" ]; then
        consume "$BASH_OVERRIDE" "Bash write" "$WRITE" "$CMD"
        exit 0
      fi
      ;;
  esac

  deny "Blocked: this Bash command would write into the gated region (src/, pom.xml) or into a file inventory (*-INVENTORY.md). Detected $WRITE.

Bash file writes never reach the file-inventory check that gates Edit/Write, so allowing them would make that check optional. Source edits there go through the Edit/Write tools.

A file inventory is never written by the agent at all, by any route. To have paths added to one, print the amendment block and let the user run .claude/bin/approve-inventory. The one-shot exemption below does not cover inventories.

If a batched script is genuinely the better tool — many mechanical edits at once — hand the command below back to the user verbatim, as a copy-pasteable block, together with what it does, the exact list of files it touches, and why it beats separate Edit calls. Then STOP and wait.

  $CMD

The user either runs it themselves, or grants a ONE-SHOT exemption:

  mkdir -p .claude/protocol && printf '%s\n' '<what was approved>' > .claude/protocol/bash-edit-approved

The exemption lifts the tool restriction only, never the plan: every file must still be a bullet in the approved plan's file inventory.

Do not retry and do not reshape the command. If this is a false positive on a read-only command, say so and ask."
fi

FILE="$(jq -r '.tool_input.file_path // .tool_input.notebook_path // empty' <<<"$INPUT")"
[ -n "$FILE" ] || exit 0
case "$FILE" in
  /*) ABS="$FILE" ;;
  *)  ABS="$ROOT/$FILE" ;;
esac
ABS="$(readlink -m -- "$ABS")" || gate_error "the edited path does not resolve"

REL="${ABS#"$ROOT"/}"

# Never writable, whatever the token says. These files decide what the agent
# may write; an agent that can edit its own authorization can authorize
# anything. .claude/skills/ and .claude/rules/ are excepted: both are
# documentation, and the hook reads nothing from either.
case "$REL" in
  .claude/skills/*|.claude/rules/*)
    ;;
  .claude/*)
    deny "Blocked: '$REL' is part of the working-protocol machinery, which the agent never writes. That covers the approval token (.claude/protocol/), the gate itself (.claude/hooks/), the approval script (.claude/bin/), and the hook wiring (.claude/settings.json).

Reading these files is fine — the Read tool is not gated. To change one, present the exact edit to the user and stop."
    ;;
  docs/dev/plans/*-INVENTORY.md)
    PLANMATE="${REL%-INVENTORY.md}.md"
    deny "Blocked: '$REL' is a file inventory. It is the list this gate reads to decide which files the agent may edit, so the agent never writes it — not to add a path, not to correct one, not to reformat it.

To have paths added, print this block for the user, who pastes it into .claude/protocol/pending and runs .claude/bin/approve-inventory:

  $PLANMATE
  <one repo-relative path per line>

Then stop and wait."
    ;;
esac

case "$ABS" in
  "$ROOT"/src/*|"$ROOT"/pom.xml) ;;
  *) exit 0 ;;
esac

if [ ! -f "$TOKEN" ]; then
  deny "Blocked (working-protocol G3): no approved plan (token absent). Present the quality-gated plan document and wait. The user approves by running: mkdir -p .claude/protocol && echo \"docs/dev/PLAN-<name>.md\" > .claude/protocol/approved"
fi

MISSING=""
# Every token line the loop below actually looked at, recorded with what was
# found there. The denial prints it, so "the token names a different plan" and
# "the right plan does not list this file" are distinguishable from the message
# alone instead of both reading as a bare refusal.
CONSULTED=""
# "|| [ -n "$LINE" ]": still process a final token line that lacks a
# trailing newline (read returns nonzero there but does fill LINE).
while IFS= read -r LINE || [ -n "$LINE" ]; do
  LINE="${LINE%%#*}"
  LINE="$(echo "$LINE" | xargs 2>/dev/null || true)"
  [ -n "$LINE" ] || continue
  # A token line may be written repo-relative or absolute; both resolve here.
  case "$LINE" in /*) PLAN="$LINE" ;; *) PLAN="$ROOT/$LINE" ;; esac
  case "$LINE" in
    *-INVENTORY.md)
      MISSING="$LINE"
      CONSULTED="$CONSULTED
  - $LINE — this is an inventory document. The token names the PLAN; the
    inventory beside it is found from that name."
      continue
      ;;
  esac
  if [ ! -f "$PLAN" ]; then
    MISSING="$LINE"
    CONSULTED="$CONSULTED
  - $LINE — named by the token, but there is no such file
    (resolved to $PLAN)"
    continue
  fi
  SIDECAR="${PLAN%.md}-INVENTORY.md"
  if [ ! -f "$SIDECAR" ]; then
    MISSING="$LINE"
    CONSULTED="$CONSULTED
  - $LINE — the plan exists, but it has no inventory document
    (expected ${SIDECAR#"$ROOT"/})"
    continue
  fi
  # Authorization comes ONLY from bullet lines in the inventory document. Its
  # heading, its paragraphs, and any table rows grant nothing, even when they
  # name the path.
  if bullet_grants "$SIDECAR" "$REL"; then
    exit 0
  fi
  CONSULTED="$CONSULTED
  - $LINE — inventory read (${SIDECAR#"$ROOT"/}); no bullet line contains this path"
done < "$TOKEN"

# Test escape hatch: authorize a test edit when a plan touches production
# source. Runs only after exact-match above fails. Production source and
# pom.xml are unaffected — they match neither this test pattern nor gain a
# src/main entry from it.
case "$REL" in
  src/test/*)
    while IFS= read -r LINE || [ -n "$LINE" ]; do
      LINE="${LINE%%#*}"
      LINE="$(echo "$LINE" | xargs 2>/dev/null || true)"
      [ -n "$LINE" ] || continue
      case "$LINE" in /*) PLAN="$LINE" ;; *) PLAN="$ROOT/$LINE" ;; esac
      SIDECAR="${PLAN%.md}-INVENTORY.md"
      [ -f "$SIDECAR" ] || continue
      if bullet_grants "$SIDECAR" "src/main/" prefix; then
        exit 0
      fi
    done < "$TOKEN"
    CONSULTED="$CONSULTED
  (test escape hatch also declined: no approved plan's inventory lists any
   src/main/ file)"
    ;;
esac

if [ -n "$MISSING" ]; then
  deny "Blocked (working-protocol G3): the approval token names plan '$MISSING' which does not exist. Ask the user to point the token at the approved plan document.

Plans the token named, and what each gave:$CONSULTED"
fi

deny "Blocked (working-protocol G3): '$REL' is not a bullet entry in the '## File inventory' section of the approved plan(s) named by the token.

Plans the token named, and what each gave:$CONSULTED

If the plan listed above is not the one that covers this file, the token points at the wrong plan and the user has to repoint it. If it is the right plan, this is a plan deviation — stop, present why the file is needed, and wait; after approval the plan's file inventory must be amended."
