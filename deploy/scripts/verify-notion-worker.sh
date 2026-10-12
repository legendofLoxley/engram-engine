#!/usr/bin/env bash
# Verifies the Notion worker token (alfrd-worker) is placed and handled the way the Director's
# Notion input channel assumes. Run it on the box that runs engram-dev.service, after Jacob has
# placed /home/halo/development/hermes-webui-dev/.notion-worker-token and before restarting the
# service onto a build that contains the channel.
#
# It READS the token to compare it against places it must not be, and never prints it, never
# passes it on a command line, and never writes it anywhere. It creates no file in the token's
# directory. It makes no network request and touches nothing in Notion.
#
# What it checks (each prints PASS, FAIL, WARN or SKIP):
#   1. the file is a regular file, mode 600, owned by the service user, not a symlink
#   2. it lies outside the git checkout and is not tracked
#   3. THE JVM CAN READ IT AS THE SERVICE USER, in the same way the service runs: a transient
#      systemd unit (same manager, same SELinux domain transition as engram-dev.service) runs the
#      probe's --check-token mode. The JVM is started THROUGH /usr/bin/bash, as engram-dev.service
#      starts it: systemd exec'ing a binary under /home directly is denied for init_t
#      ({ execute } on a gconf_home_t/user_home_t file, exit 203/EXEC) while the same binary
#      started by bash is allowed, so a direct launch fails for a reason that says nothing about
#      the token. This is the check that matters under SELinux enforcing; an interactive shell
#      (unconfined_t) proves nothing about it, so without root this check is downgraded to a WARN
#      rather than passed.
#   4. no AVC denial was logged for that launch: for the JVM binary, the probe jar or the token
#      file (a denial on the binary or the jar means the token was never reached, so matching only
#      the token file's name misses exactly the failure that check 3 reports). If ausearch itself
#      cannot run, this FAILs rather than reporting a pass it did not earn.
#   5. it lies under no Hermes mount: the configured Hermes home and workspace, the WebUI home, and
#      the Source of every mount of every running container
#   6. the token value is in no running container's environment or mounts config, not in the
#      engram-dev.service process environment, not in the service's recent journal, and not in any
#      tracked file of the checkout
#
# Exit status: 0 when nothing FAILed (WARNs and SKIPs are printed and counted), 1 otherwise.
#
# Overrides (for another box or for testing): REPO_DIR, NOTION_TOKEN_FILE, SERVICE_USER,
# EXPECT_OWNER, JAVA_BIN, BASH_BIN, PROBE_CLASSPATH, NOTION_WORKER_CONFIG, HERMES_DEV_HOME_DIR,
# HERMES_DEV_WORKSPACE_DIR, WEBUI_DEV_DIR.
set -euo pipefail
set +x  # never trace: a traced line could show the token

REPO_DIR="${REPO_DIR:-/home/halo/development/engram-engine}"
WEBUI_DEV_DIR="${WEBUI_DEV_DIR:-/home/halo/development/hermes-webui-dev}"
TOKEN_FILE="${NOTION_TOKEN_FILE:-$WEBUI_DEV_DIR/.notion-worker-token}"
SERVICE_USER="${SERVICE_USER:-halo}"
EXPECT_OWNER="${EXPECT_OWNER:-$SERVICE_USER:$SERVICE_USER}"
JAVA_BIN="${JAVA_BIN:-/home/halo/.local/jdks/jdk-21.0.12.1+1/bin/java}"
BASH_BIN="${BASH_BIN:-/usr/bin/bash}"
PROBE_CLASSPATH="${PROBE_CLASSPATH:-$REPO_DIR/build/libs/engram-engine.jar}"
CONFIG="${NOTION_WORKER_CONFIG:-$REPO_DIR/deploy/config/notion-agent-worker.json}"
HERMES_HOME_DIR="${HERMES_DEV_HOME_DIR:-/home/halo/development/hermes-dev/home}"
HERMES_WORKSPACE_DIR="${HERMES_DEV_WORKSPACE_DIR:-/home/halo/development/hermes-dev/workspace}"

fails=0; warns=0; skips=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; fails=$((fails + 1)); }
warn() { printf 'WARN  %s\n' "$1"; warns=$((warns + 1)); }
skip() { printf 'SKIP  %s\n' "$1"; skips=$((skips + 1)); }

# ── 1. the file ──────────────────────────────────────────────────────────────
if [ ! -e "$TOKEN_FILE" ]; then
  fail "token file does not exist: $TOKEN_FILE (Jacob places it; this script never creates it)"
  printf '\n%d FAIL, %d WARN, %d SKIP\n' "$fails" "$warns" "$skips"
  exit 1
fi
if [ -L "$TOKEN_FILE" ]; then fail "token file is a symlink; it must be the file itself"; else pass "token file is not a symlink"; fi
if [ -f "$TOKEN_FILE" ]; then pass "token file is a regular file"; else fail "token file is not a regular file"; fi

mode="$(stat -c '%a' "$TOKEN_FILE")"
owner="$(stat -c '%U:%G' "$TOKEN_FILE")"
if [ "$mode" = "600" ]; then pass "mode is 600"; else fail "mode is $mode, expected 600"; fi
if [ "$owner" = "$EXPECT_OWNER" ]; then pass "owner is $owner"; else fail "owner is $owner, expected $EXPECT_OWNER"; fi
if [ -s "$TOKEN_FILE" ]; then pass "file is not empty"; else fail "file is empty"; fi

# ── 2. outside the checkout ──────────────────────────────────────────────────
real="$(readlink -f "$TOKEN_FILE")"
repo_real="$(readlink -f "$REPO_DIR")"
case "$real" in
  "$repo_real"|"$repo_real"/*) fail "token file is inside the git checkout ($repo_real)" ;;
  *) pass "token file is outside the git checkout" ;;
esac
if git -C "$REPO_DIR" ls-files --error-unmatch -- "$real" >/dev/null 2>&1; then fail "token file is tracked by git"; else pass "token file is not tracked by git"; fi

# The token value, held only in this shell variable. Never echoed, never an argument.
token="$(tr -d '\n' < "$TOKEN_FILE" 2>/dev/null || true)"
if [ -z "$token" ]; then
  fail "the token could not be read by $(id -un) (is this script running as $SERVICE_USER or root?)"
  printf '\n%d FAIL, %d WARN, %d SKIP\n' "$fails" "$warns" "$skips"
  exit 1
fi

# ── 3. the JVM, as the service user, under the service's SELinux domain ──────
selinux="not installed"
if command -v getenforce >/dev/null 2>&1; then selinux="$(getenforce)"; fi
label="$(stat -c '%C' "$TOKEN_FILE" 2>/dev/null || true)"
if [ -z "$label" ] || [ "$label" = "?" ]; then label="none"; fi
printf 'INFO  SELinux: %s; token file label: %s\n' "$selinux" "$label"
if [ "$selinux" != "Enforcing" ]; then
  warn "SELinux is $selinux: a pass below says nothing about enforcing mode"
fi

probe_args=(-cp "$PROBE_CLASSPATH" app.alfrd.engram.scripts.NotionWorkerProbeKt --check-token --config "$CONFIG")
read_started="$(date '+%Y-%m-%d %H:%M:%S')"
# ausearch -ts takes TWO arguments, a date in the locale's own format ("10/12/2026" in en_US) and a
# time. An ISO date is rejected with "Invalid start date", and that exit status (1) is the same as
# "no matches", which is why the AVC check below inspects ausearch's output, not its status.
audit_date="$(date '+%x')"; audit_time="$(date '+%T')"
if [ ! -x "$JAVA_BIN" ]; then
  skip "JVM read: $JAVA_BIN is not executable"
elif [ ! -r "$PROBE_CLASSPATH" ]; then
  skip "JVM read: $PROBE_CLASSPATH not found (build it: ./gradlew shadowJar)"
else
  jvm_out=""; jvm_rc=0
  if [ "$(id -u)" = "0" ] && command -v systemd-run >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
    # Through bash, like engram-dev.service: `exec "$@"` replaces bash with the JVM, so the JVM is the
    # unit's main process; systemd never exec's the JDK itself (see check 3 in the header).
    jvm_out="$(systemd-run --quiet --wait --pipe --collect --uid="$SERVICE_USER" "$BASH_BIN" -c 'exec "$@"' bash "$JAVA_BIN" "${probe_args[@]}" 2>&1)" || jvm_rc=$?
    mode_note="transient systemd unit as $SERVICE_USER, started through $BASH_BIN"
  elif command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1 && command -v systemd-run >/dev/null 2>&1 && [ -d /run/systemd/system ]; then
    jvm_out="$(sudo -n systemd-run --quiet --wait --pipe --collect --uid="$SERVICE_USER" "$BASH_BIN" -c 'exec "$@"' bash "$JAVA_BIN" "${probe_args[@]}" 2>&1)" || jvm_rc=$?
    mode_note="transient systemd unit as $SERVICE_USER, started through $BASH_BIN (via sudo)"
  else
    jvm_out="$("$JAVA_BIN" "${probe_args[@]}" 2>&1)" || jvm_rc=$?
    mode_note="interactive shell"
  fi
  if [ "$jvm_rc" -eq 0 ] && printf '%s' "$jvm_out" | grep -q 'token: readable by this process'; then
    if [ "$mode_note" = "interactive shell" ]; then
      warn "JVM read the token from an interactive shell only, which is not the service's SELinux domain. Run this script as root (or with passwordless sudo) on a box with systemd to test it as the service runs"
    else
      pass "JVM read the token ($mode_note)"
    fi
  else
    fail "JVM could NOT read the token ($mode_note, exit $jvm_rc): $(printf '%s' "$jvm_out" | grep -v '^$' | head -3 | tr '\n' ' ')"
  fi

  # ── 4. AVC denials for that launch ─────────────────────────────────────────
  # Any denial in the window whose target is the JVM binary, the probe jar or the token file (or whose
  # command is the JVM). Matching only the token file's name missed a denied { execute } on the JDK,
  # which stops the launch before the token is ever read. Audit lines carry names, contexts and
  # permissions, never file contents, so printing a few of them cannot show the token.
  can_root=0
  if [ "$(id -u)" = "0" ] || { command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; }; then can_root=1; fi
  if command -v ausearch >/dev/null 2>&1 && [ "$can_root" -eq 1 ]; then
    run_root() { if [ "$(id -u)" = "0" ]; then "$@"; else sudo -n "$@"; fi; }
    java_name="$(basename "$JAVA_BIN")"; jar_name="$(basename "$PROBE_CLASSPATH")"; token_name="$(basename "$TOKEN_FILE")"
    ausearch_out="$(run_root ausearch -m AVC,USER_AVC -ts "$audit_date" "$audit_time" 2>&1 || true)"
    # ausearch prints audit records ("type=..."), "----"/"time->" separators and "<no matches>"; anything
    # else is its own complaint (a bad -ts value, a bad option), and its exit status cannot tell that
    # apart from "no matches".
    ausearch_err="$(printf '%s\n' "$ausearch_out" | grep -v -E '^(type=|----|time->|<no matches>|$)' | head -1 || true)"
    if [ -n "$ausearch_err" ]; then
      fail "AVC check could not run (ausearch said: $ausearch_err)"
    else
      denied="$(printf '%s\n' "$ausearch_out" | grep 'denied' \
        | grep -F -e "name=\"$java_name\"" -e "name=\"$jar_name\"" -e "name=\"$token_name\"" \
                  -e "comm=\"($java_name)\"" -e "comm=\"$java_name\"" || true)"
      if [ -n "$denied" ]; then
        fail "$(printf '%s\n' "$denied" | wc -l) SELinux denial(s) for $java_name, $jar_name or $token_name since $read_started (see: ausearch -m AVC -ts $audit_date $audit_time)"
        printf '%s\n' "$denied" | sed -E 's/audit\([0-9.:]+\)//; s/^type=[A-Z_]+ msg= *//' | head -3 | sed 's/^/        /'
      else
        pass "no SELinux denial for $java_name, $jar_name or $token_name since $read_started"
      fi
    fi
  else
    skip "AVC check: ausearch or root not available"
  fi
fi

# ── 5. not under any Hermes mount ────────────────────────────────────────────
under() { case "$real" in "$1"|"$1"/*) return 0 ;; *) return 1 ;; esac; }
mount_fail=0
for dir in "$HERMES_HOME_DIR" "$HERMES_WORKSPACE_DIR" "$WEBUI_DEV_DIR/home"; do
  [ -e "$dir" ] || continue
  if under "$(readlink -f "$dir")"; then fail "token file is inside the Hermes path $dir"; mount_fail=1; fi
done
if command -v docker >/dev/null 2>&1 && docker ps -q >/dev/null 2>&1; then
  ids="$(docker ps -q)"
  if [ -n "$ids" ]; then
    while IFS= read -r src; do
      [ -n "$src" ] || continue
      if under "$(readlink -f "$src" 2>/dev/null || echo "$src")"; then fail "token file is inside a running container's mount source: $src"; mount_fail=1; fi
    done < <(docker inspect --format '{{range .Mounts}}{{println .Source}}{{end}}' $ids 2>/dev/null)
  fi
  if [ "$mount_fail" -eq 0 ]; then pass "token file is under no Hermes path and no running container's mount"; fi
else
  if [ "$mount_fail" -eq 0 ]; then pass "token file is under no configured Hermes path"; fi
  skip "running-container mounts: docker not available to this user"
fi

# ── 6. the value is nowhere it must not be ───────────────────────────────────
# Runs a command and succeeds when its output contains the token. pipefail is switched off inside the
# subshell: `grep -q` exits at the first match, the producer then dies of SIGPIPE, and with pipefail on
# that would turn a real leak into a "no leak" answer. The token reaches grep as a pattern *file*
# (a pipe), never as an argument, so it is in no process's argv.
leaks_from() { ( set +o pipefail; "$@" 2>/dev/null | grep -qF -f <(printf '%s' "$token") ); }

if command -v docker >/dev/null 2>&1 && docker ps -q >/dev/null 2>&1 && [ -n "$(docker ps -q)" ]; then
  # shellcheck disable=SC2046
  if leaks_from docker inspect $(docker ps -q); then fail "the token value appears in a running container's configuration (environment or mounts)"; else pass "the token value is in no running container's configuration"; fi
else
  skip "container environments: docker not available or nothing running"
fi

pid="$(systemctl show -p MainPID --value engram-dev.service 2>/dev/null || true)"
if [ -n "$pid" ] && [ "$pid" != "0" ] && [ -r "/proc/$pid/environ" ]; then
  if leaks_from tr '\0' '\n' < "/proc/$pid/environ"; then fail "the token value is in the environment of engram-dev.service (pid $pid)"; else pass "the token value is not in the environment of engram-dev.service (pid $pid)"; fi
else
  skip "engram-dev.service process environment: service not running or not readable"
fi

if command -v journalctl >/dev/null 2>&1 && journalctl -u engram-dev.service --since "-1 day" --no-pager -q 2>/dev/null | head -c 1 | grep -q .; then
  if leaks_from journalctl -u engram-dev.service --since "-1 day" --no-pager -q; then fail "the token value appears in the last day of engram-dev.service logs"; else pass "the token value is not in the last day of engram-dev.service logs"; fi
else
  skip "service journal: not readable by this user or empty"
fi

if git -C "$REPO_DIR" grep -qF -f <(printf '%s' "$token") -- . 2>/dev/null; then
  fail "the token value appears in a tracked file of the checkout (files: $(git -C "$REPO_DIR" grep -lF -f <(printf '%s' "$token") -- . | tr '\n' ' '))"
else
  pass "the token value is in no tracked file of the checkout"
fi
if git -C "$REPO_DIR" grep -qE '(ntn_|secret_)[A-Za-z0-9]{20,}' -- . ':!src/test' 2>/dev/null; then
  fail "a tracked file outside src/test contains something shaped like a Notion token (files: $(git -C "$REPO_DIR" grep -lE '(ntn_|secret_)[A-Za-z0-9]{20,}' -- . ':!src/test' | tr '\n' ' '))"
else
  pass "no tracked file outside src/test contains a Notion-token-shaped string"
fi

unset token
printf '\n%d FAIL, %d WARN, %d SKIP\n' "$fails" "$warns" "$skips"
[ "$fails" -eq 0 ]
