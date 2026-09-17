# Reproducible local dev/POC packaging (arx-box)

Supervision, installation, update, rollback and verification for the three
unsupervised dev components identified in the Sep 17, 2026 investigation:
**engram-engine** (Director/backend), **the webui-bridge runner adapter**,
and **hermes-webui-dev** (the pinned, dev-patched Hermes WebUI container).
The vendor product stack (`llama-server.service`, the production
`hermes-halo`/`hermes-webui`/`signal-cli` Docker Compose stack, and the
`halo-onboard` OTA/backup pipeline) is untouched by everything in this
directory.

This is deliberately **not** the customer-facing signed OTA app-track
(`halo_onboard.updates.applier_app`, Ed25519-signed manifests, CDN-hosted
releases). That pipeline is for versioned, shippable product components;
this is a dev-loop supervision setup for an actively-iterating feature
branch. Promoting this integration into the OTA app-track is a deliberate
future step, not part of this pass.

## Topology

```
Docker (restart: unless-stopped)          systemd (Restart=on-failure)
┌─────────────────────────┐               ┌──────────────────────────┐
│ hermes-webui-dev          │  runner-local │ hermes-runner-adapter.svc│
│ 127.0.0.1:8788 -> 8787   │──────────────▶│ 172.17.0.1:8091          │
│ pinned image + 3 patches │  bearer auth  └──────────────┬───────────┘
└─────────────────────────┘                               │ bearer auth
                                                            ▼
                                              ┌──────────────────────────┐
                                              │ engram-dev.service       │
                                              │ 127.0.0.1:8082           │
                                              │ embedded ArcadeDB        │
                                              └──────────────┬───────────┘
                                                              │ per-assignment,
                                                              │ docker run --rm
                                                              ▼
                                              ┌──────────────────────────┐
                                              │ one-shot dev Hermes      │
                                              │ (halo-home/hermes-halo)  │
                                              │ isolated hermes-dev/home │
                                              └──────────────────────────┘

Shared, untouched: llama-server.service (127.0.0.1:8081, LAN-blocked)
```

## What's committed vs. what stays outside version control

Committed (this directory + `webui-bridge/`):
- The three launch scripts (`deploy/scripts/run-*.sh`) — the canonical,
  single source of truth for how each component starts. A manual run and the
  systemd `ExecStart=` run the exact same script.
- The two systemd unit files (`deploy/systemd/*.service`).
- `install.sh` / `stop-manual.sh` — idempotent install and pre-install-stop
  helpers.
- The vendor patch files (`webui-bridge/vendor-patches/*.patch`) and the
  script that regenerates/applies them (`apply.sh`) — already existed before
  this pass.
- `RELEASE_MANIFEST.md` — pinned component identities.

Deliberately **not** committed (host-level, fixed paths referenced by the
scripts above, never copied into a customer unit):
- Secrets: `/home/halo/development/hermes-webui-dev/.engram-debug-token`,
  `.webui-password`, `.runner-api-key` — mode `600`, `halo:halo`.
- Persistent data: `/home/halo/development/engram-engine/data/dev/engram-db`
  (embedded ArcadeDB), `/home/halo/development/hermes-webui-dev/home`
  (WebUI's own session/state store), `/home/halo/development/hermes-dev/home`
  (the one-shot delegation Hermes's own persistent home — deliberately never
  reset).

## Installation

First-time install (or after `git pull` picks up unit/script changes):

```bash
cd /home/halo/development/engram-engine
deploy/scripts/install.sh
```

This stops any stray manually-launched instance first (see `stop-manual.sh`),
copies the two unit files into `/etc/systemd/system/`, `daemon-reload`s,
enables + starts both native units, and (re)launches the `hermes-webui-dev`
container with `--restart unless-stopped`. Requires passwordless sudo for
exactly three commands (`cp` into `/etc/systemd/system`, `daemon-reload`,
`enable --now`) — already granted to `halo` on this box.

## Start / stop

```bash
# Native components — normal systemd
sudo systemctl start|stop|restart|status engram-dev.service
sudo systemctl start|stop|restart|status hermes-runner-adapter.service

# Container — normal Docker (supervised by --restart unless-stopped, not systemd)
docker stop hermes-webui-dev    # a *manual* stop; Docker will NOT restart it
                                 # until it's started again (unless-stopped
                                 # only means "restart unless a human said stop")
docker start hermes-webui-dev
docker logs -f hermes-webui-dev
```

Logs: `journalctl -u engram-dev.service -f` and
`journalctl -u hermes-runner-adapter.service -f`. Pre-supervision log files
(`/home/halo/development/engram-dev.log`, `hermes-webui-dev/runner-adapter.log`)
are historical only — nothing writes them anymore once a component runs
under systemd.

## Update

```bash
cd /home/halo/development/engram-engine
git pull
./gradlew shadowJar                # rebuild the jar engram-dev.service runs
deploy/scripts/install.sh          # re-applies units/scripts, restarts both native
                                    # components, recreates the webui-dev container
                                    # if its launch config changed
```

`install.sh` is idempotent: re-running it after a `git pull` that changed
nothing still works (it just restarts the two native services and recreates
the container from the same config).

## Rollback

```bash
cd /home/halo/development/engram-engine
git log --oneline -10                    # find the last-known-good SHA
git checkout <known-good-sha>
./gradlew shadowJar
sudo systemctl restart engram-dev.service
```

**Schema/state compatibility must be checked before rolling back, not
assumed.** `SchemaBootstrap.kt` is additive/idempotent (`ensureVertex`-style
checks before create), which makes *rolling code backward while the graph
has already been written to by newer code* generally safe for the common
case — old code simply doesn't know about a new property or vertex/edge
type and ignores it. It is **not** guaranteed safe for a rollback that
crosses a commit which introduced a new vertex/edge type that a broad,
non-defensive read path (a `when` over edge types, for instance) doesn't
handle as "unknown and ignorable." No such case is known to exist as of this
writing, but this file makes no blanket promise that a destructive downgrade
is safe — check the actual diff between the current and target SHA for
schema-shaped changes before rolling back, the same way any other rollback
against live data should be checked.

Rolling back `hermes-webui-dev` (image or vendor patches) is separate and
lower-risk: change the pinned digest and/or the patch files, re-run
`webui-bridge/vendor-patches/apply.sh` then
`deploy/scripts/run-hermes-webui-dev.sh`. The container holds no schema, only
its own session/state store, which the patches were specifically written
not to corrupt on either direction of that change.

## Failure behavior (what "recoverable" actually means here)

- **Model (llama-server) unreachable**: `LocalLlmClient` throws
  (`LlmTimeoutError` on timeout, `RuntimeException` on a non-2xx response);
  `CognitivePipeline`'s actor-call path catches broadly and falls back to
  `ctx.actorResult?.source ?: "degraded"` — an existing, already-tested
  degraded-response contract, not something this pass added. engram-engine
  itself does not crash or restart; only the affected turn degrades. Once
  the model is reachable again, the very next turn succeeds normally — no
  restart of engram-dev.service is needed or triggered.
- **Docker unreachable when a Hermes delegation is triggered**:
  `HermesAcpClient.inspectFixture`'s `ProcessBuilder(...).start()` is
  wrapped in a try/catch that returns `HermesAssignmentOutcome.Failed("spawn_failed: …")`
  for that one assignment — again, not a crash, and not something this pass
  added; just confirmed and documented here. Ordinary (non-delegating) chat
  turns are entirely unaffected.
- **Docker unreachable at engram-dev/adapter startup**: neither service
  requires Docker to start — engram-engine doesn't touch Docker until a
  delegation-triggering turn arrives, and `hermes-runner-adapter.service`
  only *binds* `172.17.0.1` (the docker0 bridge gateway address), which
  needs `docker.service` to have created the bridge interface first. The
  unit orders `After=docker.service`/`Wants=docker.service` for a clean
  first-boot sequence; if Docker is still down when this unit tries to
  start, the bind fails, the process exits non-zero, and
  `Restart=on-failure` (unbounded via `StartLimitIntervalSec=0`) keeps
  retrying every 5s until Docker is up — no manual intervention needed,
  just a delay proportional to how long Docker takes to come up.
- **A restart mid-Hermes-assignment**: see "Observed: mid-assignment restart
  behavior" below — this is a real, verified gap, not a hypothetical.

## Observed: mid-assignment restart behavior

Verified live on 2026-09-17 using the test-only delay gate (`HERMES_DEV_TEST_DELAY_MS`,
temporarily set via a systemd drop-in, removed immediately after — never left
enabled). A fresh WebUI conversation triggered a real delegation
(assignmentId `c215f576-…`, container `priceless_villani`), then
`sudo systemctl restart engram-dev.service` was run ~26s into the delay
window, well before the assignment could complete. Observed, not assumed:

- **The in-flight one-shot Hermes container is not killed by the restart.**
  `docker ps` immediately after the restart still showed it `Up 27 seconds`,
  continuing to run as a genuine orphan — systemd's cgroup-wide SIGTERM
  reaches the `docker run` *client* process (a child of engram-dev.service),
  but the container itself is owned by dockerd/containerd, not that client's
  process tree, so killing the client doesn't stop it. It exited on its own
  (self-removed via `--rm`, confirmed gone ~15s later) — most likely once its
  stdin/stdout pipe to the now-dead client broke.
- **The new JVM has zero knowledge of the assignment.** `GET
  /debug/hermes-assignment/c215f576-…` against the freshly restarted service
  returned `{"error":"no completion recorded yet for this assignment"}` —
  exactly the same response this route gives for an assignment that never
  existed, matching this codebase's own documented convention of never
  letting a caller distinguish "too late" from "never was." Whatever the
  orphaned container actually produced was never ingested into the graph and
  is unrecoverable.
- **The conversation itself gets stuck, not just the delegation.** The
  runner adapter's own run record (`GET /v1/runs/edc9831e…`) stayed at
  `status: "running"` indefinitely — still polling 200 OK a minute later, in
  contrast to a clean (non-restarted) delegation's run in the same session,
  which is confirmed to poll at `running` for the delegation's *entire*
  natural duration (~30s) and only then flips to completed. A restart mid-
  flight never flips it — there is no timeout that resolves it. WebUI's own
  persisted transcript for that conversation shows **0 messages**: even the
  Director's own quick acknowledgment turn was never durably saved, because
  WebUI's persistence patch only writes on a `done` event, and no `done`
  event for this run ever arrives. The browser is left showing "Thinking"
  forever; only a page reload into a new conversation recovers the user.

**This is real, uncontained data loss for that one in-flight assignment and a
stuck conversation, not a graceful degradation — stated plainly, not
softened.** Durable job recovery (resuming, reconciling, or even just
detecting and reporting a stuck run) is explicitly out of scope for this
packaging pass. This is an accepted, documented limitation of the current
Hermes delegation increment, not something `deploy/` fixes or claims to fix.
Restarting `engram-dev.service` is therefore not risk-free: it is safe with
respect to the *graph* (verified: identical vertex/edge counts and byte-
identical existing transcripts across every restart performed during this
pass) but not safe with respect to whatever single delegation and
conversation happen to be in flight at that exact moment.

## Verification results — September 17, 2026

Performed after installing supervision (see "Installation" above), before
committing this directory.

- **SELinux, discovered live, not anticipated**: the enforcing policy denies
  `init_t` (systemd's own domain) executing a `user_home_t`-labeled file
  directly — `ExecStart=<script path>` failed every time with
  `code=203/EXEC`, confirmed via `ausearch -m avc` showing `avc: denied
  { execute } ... scontext=init_t tcontext=user_home_t`. Fixed by routing
  ExecStart through `/usr/bin/bash <path>` (open+read, not exec) — see both
  unit files' own comments. Re-verified zero new AVC denials after the fix,
  across every restart performed in this pass.
- **A clean `systemctl stop`/`restart` initially showed as `failed`**, not
  `inactive` — the JVM's shutdown hook runs correctly on SIGTERM (confirmed:
  `Application.kt` registers one that closes the ArcadeDB handle), but the
  process's own resulting exit code (143 = 128+SIGTERM) reads as a failure
  to systemd by default. Fixed with `SuccessExitStatus=143` on both units;
  re-verified `systemctl is-active`/`is-failed` report `inactive`, not
  `failed`, after a deliberate stop.
- **`StartLimitIntervalSec` was initially misplaced** under `[Service]` in
  both units (systemd logged "Unknown key ... ignoring") — it's a `[Unit]`-
  section key. Moved and confirmed the warning is gone.
- **Graph persistence — exact counts, not estimated.** A standalone,
  read-only counter (opens the embedded ArcadeDB directly while
  engram-dev.service is stopped, since ArcadeDB embedded holds an exclusive
  file lock) recorded **790 total records** across all 10 vertex + 10 edge
  types before any change was made, and **790, identical per type**, after
  installing supervision and again after a subsequent deliberate
  `systemctl stop`/`start` cycle.
- **Transcript persistence — exact bytes, not "looks the same".** SHA-256 of
  three specific pre-existing WebUI session files (a plain fact-memory
  conversation, a cancelled-delegation conversation, and an 8-message
  cross-session recall conversation) matched exactly before and after the
  entire migration.
- **Specific stored evidence, inspected directly, not inferred from chat
  output.** `GET /phrases?...&q=zephyr` confirmed the `zephyr-quokka-42`
  fact recorded 2026-09-13 is still present in the graph, verbatim, with its
  original trust score and timestamp. A live chat query for it in a brand
  new conversation did *not* surface it in the Actor's reply — this is the
  same pre-existing "committed and eligible but lost to the Actor's own
  prompt-budget ladder against competing history" characteristic already
  documented in `webui-bridge/README.md`, not a regression: a second live
  recall query, for a different previously-recorded fact (the "Cobalt
  Lantern demo, due September 18" commitment) in another brand new
  conversation, succeeded correctly, confirming the retrieval pipeline
  itself works end-to-end under the new supervision.
- **One real Director→Hermes round trip**, under supervision, correlated
  across three independent layers: backend log (`hermes-delegation
  dispatching assignmentId=96428be3-… ` at 16:59:20, `hermes-delegation
  completed … outcome=Completed decision=Accepted ingestOutcome=Committed`
  at 16:59:52 — a real ~32s one-shot container run, not a stub), the debug
  endpoint (`GET /debug/hermes-assignment/96428be3-…` independently
  confirming `toolName: "read"`, `toolSucceeded: true`, and the exact
  expected marker text), and the live WebUI (the marker appearing correctly
  in the persisted transcript after reload).
- **Mid-assignment restart** — see the dedicated section below. Real data
  loss for the in-flight assignment and conversation, observed and
  documented, not glossed over.

## Remaining limits

- No health-check endpoint on the runner adapter (`webui-bridge/runner_adapter.py`
  only implements the `/v1/runs*` contract) — liveness today is inferred from
  `systemctl status`, or a `GET /v1/runs/<id>` returning 401 without a bearer
  / 404 for an unknown id with a valid one (confirmed live), not a dedicated
  probe.
- No automatic backup/snapshot of `data/dev/engram-db` or the two Hermes home
  directories — this is dev/POC state, not covered by the box's real
  `halo-backup.service` (which only backs up customer/product state).
  Losing these directories loses the dev graph and dev Hermes state; nothing
  in this pass changes that.
- `ProtectSystem=full` without a `ReadWritePaths=` allowlist means the two
  new units get modest, not maximal, sandboxing (see the units' own comments
  for why). This is an intentional trade against the box's own documented
  `ReadWritePaths` misconfiguration history, not an oversight.
- Full-host-reboot survival is prepared but **not yet executed** — see
  "Reboot check" below.
- This is not the OTA app-track. There is no signed manifest, no CDN
  release, no automatic rollback-on-failed-probe for this stack — rollback
  here is the manual git/rebuild/restart procedure above.

## Reboot check (prepared, awaiting an operator-chosen window)

Coordinated with the still-open post-reboot item on the arx-box 8081
hardening task. A single `sudo reboot`, once triggered, will exercise both
checklists in one pass:

1. `llama-server.service` restarts (`enabled`, `Restart=on-failure`) and both
   its existing consumers still work (dev engine `reasonProvider: local`;
   `hermes-halo` gets HTTP 200 from `host.docker.internal:8081/v1/models`).
2. The 4 firewalld rich-rules blocking LAN access to :8081 are still present
   (runtime + permanent) after the reboot.
3. `engram-dev.service` and `hermes-runner-adapter.service` are both
   `active (running)` without manual intervention
   (`systemctl is-active engram-dev hermes-runner-adapter`).
4. `hermes-webui-dev` is back up via Docker's own `unless-stopped` policy
   (`docker inspect hermes-webui-dev --format '{{.State.Status}}'`), not a
   manual re-run of `run-hermes-webui-dev.sh`.
5. A fresh WebUI conversation still round-trips through to the Director
   after the reboot (not just that the ports are open).

**This reboot has not been performed.** It is intentionally left for the
operator to trigger at a chosen time.
