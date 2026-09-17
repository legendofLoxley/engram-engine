#!/usr/bin/env bash
# Stops any manually-launched (pre-supervision) instances of the three dev
# components, so a supervised replacement can bind the same ports/addresses
# without conflict. Safe to re-run — every step is a no-op if its target
# isn't running or is already supervised.
set -uo pipefail  # not -e: "nothing to stop" cases below are not errors

DEV_DIR="/home/halo/development"

stop_pidfile() {
  local name="$1" pidfile="$2"
  if [ -f "$pidfile" ]; then
    local pid
    pid="$(cat "$pidfile" 2>/dev/null)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      echo "stopping $name (pid $pid)..."
      kill -TERM "$pid"
      for _ in $(seq 1 20); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.5
      done
      if kill -0 "$pid" 2>/dev/null; then
        echo "  $name did not exit after 10s — sending SIGKILL" >&2
        kill -KILL "$pid" 2>/dev/null || true
      fi
    else
      echo "$name: pidfile present but process $pid not running"
    fi
    rm -f "$pidfile"
  else
    echo "$name: no pidfile at $pidfile, nothing to stop"
  fi
}

stop_pidfile "engram-dev" "$DEV_DIR/engram-dev.pid"
stop_pidfile "runner-adapter" "$DEV_DIR/hermes-webui-dev/runner-adapter.pid"

# Belt-and-suspenders: a stale pidfile (observed live during this script's
# first real run — it named a PID that was no longer the actual adapter
# process) must never leave a real stray process silently running. Match by
# command line as a fallback, independent of whatever the pidfile claimed.
for pattern in "build/libs/engram-engine.jar" "webui-bridge/runner_adapter.py"; do
  pids="$(pgrep -f "$pattern" || true)"
  if [ -n "$pids" ]; then
    echo "found stray process(es) matching '$pattern' not covered by a pidfile: $pids — stopping"
    kill -TERM $pids 2>/dev/null || true
    sleep 1
    still="$(pgrep -f "$pattern" || true)"
    if [ -n "$still" ]; then
      echo "  still running after SIGTERM — sending SIGKILL: $still" >&2
      kill -KILL $still 2>/dev/null || true
    fi
  fi
done

if docker inspect hermes-webui-dev >/dev/null 2>&1; then
  policy="$(docker inspect hermes-webui-dev --format '{{.HostConfig.RestartPolicy.Name}}')"
  if [ "$policy" = "no" ]; then
    echo "stopping pre-supervision hermes-webui-dev container (RestartPolicy=no)..."
    docker stop hermes-webui-dev >/dev/null
    docker rm hermes-webui-dev >/dev/null
  else
    echo "hermes-webui-dev already supervised (RestartPolicy=$policy) — leaving it; re-run deploy/scripts/run-hermes-webui-dev.sh to reconfigure"
  fi
else
  echo "hermes-webui-dev: no such container, nothing to stop"
fi

echo "done."
