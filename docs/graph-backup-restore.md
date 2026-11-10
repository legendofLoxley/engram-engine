# Graph backup and restore (Arx box)

The live graph is `data/dev/engram-db`, an embedded ArcadeDB owned by `engram-dev.service`. It holds Jacob's
`owner-jacob` working memory, so it is the one thing on the box that cannot be rebuilt.

## What runs

| What | How |
|---|---|
| Daily backup | `engram-graph-backup.timer` fires at **05:20 local** (clear of the vendor timers: updater 03:00, `halo-backup` 03:30 to 03:45). It runs `deploy/scripts/graph-backup.sh`, which asks the running engine to run its own `BACKUP DATABASE` (`POST /debug/graph-backup`, loopback only, debug token). The engine stays up. |
| Where | `/home/halo/backups/engram-graph/` (mode 700), outside the repo and outside `data/`. Backups are `auto/graph-<UTC yyyyMMddTHHmmssZ>.zip` (mode 600). |
| Retention | Done by the same job, after the new backup is verified (every zip entry read through its CRC): the newest backup of each of the **14 most recent days** and of each of the **8 most recent ISO weeks** (UTC). The newest backup is never pruned, and nothing is pruned if the new backup failed. Only `auto/graph-*.zip` is ever pruned; `cold-*` folders and anything else are left alone. |
| Second destination | `GRAPH_BACKUP_COPY_TO`, unset. Set it in `deploy/systemd/engram-dev.service.d/graph-backup.conf` to a folder on a mounted drive and every backup is also copied there (a plain local copy; no network, no cloud). A failed copy is reported in the reply and the log, and does not fail the backup. |
| Logs | The engine logs `graph-backup: wrote <file> (<bytes> bytes, <n> entries) in <ms> ms; kept <n>, pruned <n>`. Counts, sizes, durations and file names only; no graph content. |

The box has one disk, so the folder above protects against a bad deploy, a bad migration or a deleted directory, not
against losing the disk. Set `GRAPH_BACKUP_COPY_TO` to a drive that is not that disk for that.

## Take a backup now

```bash
systemctl start engram-graph-backup.service && journalctl -u engram-graph-backup -n 5 --no-pager
```

## Prove a backup restores (does not touch the live graph)

```bash
deploy/scripts/graph-restore-proof.sh /home/halo/backups/engram-graph/auto/graph-<stamp>.zip
```

It restores the backup into a scratch directory, starts the engine on port 8095 against it (no Notion, no model),
compares vertex and edge counts per type with the live engine (`GET /debug/graph-counts`), confirms the owner-jacob
user is present in both the live and the restored graph, then stops the scratch engine and deletes the scratch
directory. Exit 0 means the counts match and owner-jacob is present in both. Live counts are read at that moment, so a write between the backup and the proof shows as a
difference; compare a backup taken just before. For a cold copy taken while the engine was stopped, pass
`LIVE_COUNTS_FILE=<counts saved at restart>`.

## Restore for real (the live graph is lost or damaged)

This replaces the live graph, so it is done by hand and with the engine stopped.

1. `sudo systemctl stop engram-dev.service`
2. Keep what is there: `mv data/dev/engram-db data/dev/engram-db.damaged-$(date +%Y%m%d)`
3. Put the backup in place.
   - From a zip: `python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" <backup.zip> data/dev/engram-db`
   - From a cold copy: `cp -a /home/halo/backups/engram-graph/cold-<stamp> data/dev/engram-db`
4. `sudo systemctl start engram-dev.service`, then check `GET /health` and that the Hermes WebUI still answers as
   `owner-jacob`.
5. When satisfied, delete the `.damaged-` directory. Do not send a turn as `owner-jacob` to test it.

## Snapshot before anything risky

Before a migration, a bulk import or a deploy that touches the graph, take a snapshot first and keep its file name:
`systemctl start engram-graph-backup.service && journalctl -u engram-graph-backup -n 3 --no-pager` (the log line names
the new `auto/graph-<stamp>.zip`). Never copy `data/dev/engram-db` while `engram-dev.service` is running: the embedded
database holds a lock and a live copy is not consistent. To restore that snapshot, use "Restore for real" above.

## The purge guard

`DELETE /debug/converse/purge` (debug token, `DEBUG_CONVERSE_ENABLED=true`) deletes every user whose email ends in
`@test.alfrd.internal`, which `owner-jacob` does. It now skips protected identities, and the reply says which
(`{"deletedUserCount":N,"skippedProtected":["debug+owner-jacob@test.alfrd.internal"],...}`). Protected is the union of

- `PURGE_PROTECTED_IDENTITIES`, comma-separated ids or full emails (unset means `owner-jacob`; setting it replaces that default), and
- `OWNER_IDENTITY` and `DIRECTOR_TICK_IDENTITY`, read from the engine's own environment, so they cannot be left off.

At start the engine logs `purge-guard: protected identities (never purged): <emails>`. Names only. Purging when only
protected users match is a normal `200` with `deletedUserCount: 0`. The guard does not make it safe to *send turns*
as `owner-jacob`; that rule in `CLAUDE.md` still stands, and a snapshot is still the answer to "what if".

## Remove fixture users and their data (offline tool)

`DELETE /debug/converse/purge` cannot do this job: it takes no list, it deletes every unprotected `@test.alfrd.internal` user
(that includes the `debug+pps-*` users), and it removes only the User vertex, its SELECTED/OUTCOME/UserScaffoldState rows and
its edges, leaving the user's Sources, Chunks, Phrases and Utterances in the graph with nobody who can reach them. Do not use
it for this. The route is unchanged.

`FixturePurge` (`db/FixturePurge.kt`) is an **offline** tool. It opens the graph directory itself, so the engine must be
stopped: ArcadeDB's lock makes `--apply` fail with a clear message (exit 4) while the engine holds the database. It adds no
route and needs no unit change. The plan mode opens the directory read-only, and that open does **not** fail while the
engine is running, so only trust a plan made with the engine stopped or on a restore.

```bash
./gradlew purgeFixtureUsers -PpurgeDb=<dir> -PpurgeList=<file> -q                       # plan (read-only)
./gradlew purgeFixtureUsers -PpurgeDb=<dir> -PpurgeList=<file> -PpurgeApply=true -q     # apply
java <the --add-opens flags in run-engram-dev.sh> -cp <engram-engine.jar> app.alfrd.engram.db.FixturePurgeCli <dir> <file> [--apply]
```

The list file has one id or email per line (`#` comments and blank lines are skipped). Exit codes: 0 ok, 2 usage, 3 refused,
4 the database could not be opened (the engine holds it), 5 a user's deletion failed.

**Refusals (the whole run, nothing is read past the checks).** Any listed user that is a protected identity (`owner-jacob` always,
plus `PURGE_PROTECTED_IDENTITIES`, `OWNER_IDENTITY` and `DIRECTOR_TICK_IDENTITY` if they are in the shell's environment), is not a
`debug+...@test.alfrd.internal` address, matches `debug+pps-*`, or exists with a username other than `debug-synthetic` or a tier other
than -1. The refusal names every bad entry. An empty list is refused.

**What goes, per listed user** (`L` is the whole list; "outside" means not in `L`):

| Row | Deleted when |
|---|---|
| User | listed |
| Source | trusted only by users in `L` (or by nobody and `ownerEmail` in `L`). **Kept and reported** if anyone outside `L` also trusts it, if its `ownerEmail` is outside `L`, or if it asserts a shared seed Phrase (an ownerless Phrase asserted by a Source that more than one user trusts: the audit's A3i; two listed users sharing a Source look the same, so that Source stays too) |
| Chunk | owned by `L` or ownerless, and contained only by deleted Sources; or owned by `L` and in no Source. Kept and reported if it sits in a kept Source |
| Phrase / Task / Event / Question | `ownerEmail` in `L`, or ownerless and asserted only through Sources trusted only by `L`; and every Source that asserts it is deleted. **Kept and reported** when also asserted by a kept Source. Shared seed Phrases and any row whose `ownerEmail` is outside `L` are never touched |
| Utterance (`userId`), ProcessedRequest (`userEmail`), EntityAlias, ExtractionRun, ClaimDisagreement, Domain, Project, ChangeRecord (`ownerEmail`), UserScaffoldState (`userId`, email or uid) | the key is in `L` |
| Edges | every edge at a deleted vertex (found by scanning each edge type, so an edge made with `bidirectional=false` is found too: ASSERTS, TRUSTS, ABOUT, QUOTES, CONTAINS, FOLLOWS, RELATED_TO, SELECTED, OUTCOME, CONFIDENT_IN, HAS_CHANGE, PART_OF); plus any edge stamped with an owner key in `L` whose two ends both stay |
| Never | a Concept, a ResponsePhrase, any row owned outside `L`, the shared seed Phrases |

**Safety nets.** (1) A deleted vertex with an edge to a kept row that is owned outside `L` is a *blocking boundary*, and `--apply`
refuses the whole run (the plan prints it). (2) A row of a type the tool does not know that carries an owner key in `L` also
blocks the run. (3) Each record is re-checked right before it is deleted: an owner stamp outside `L` aborts that user's
transaction. (4) One transaction per user, so a failure leaves that user whole and the others deleted; the exit code is 5 and
the log says which. (5) Idempotent: the plan is recomputed from the graph each time, so a second `--apply` finds nothing.

**ChangeRecord and the delete veto.** The engine vetoes deleting a `ChangeRecord` with an in-process listener that is registered
at every start and never persisted, so a tool that opens the directory by itself does not have it. The tool still goes through
`ChangeRecords.withDeleteVetoLifted`, an `internal` function that lifts only the delete veto (the update veto stays) for the span
of one user's deletions on the one handle and puts it back; it deletes a record only if its `ownerEmail` is in `L`. The fixture
tests run with the veto registered and show a protected owner's record surviving.

**Concepts.** None is deleted. The apply prints how many Concepts now have no ABOUT edge, and how many of the Concepts a
deleted row pointed at are shared with an owner outside `L`.

### Live procedure (exact)

The tool is not in the deployed jar. Build the jar from the branch **in a separate worktree** (a `shadowJar` in the repo would
overwrite the live engine's jar), and run the tool with that jar on the classpath. The engine's own jar is left as it is.

1. **Backup, and keep its name.** `sudo -n systemctl start engram-graph-backup.service && journalctl -u engram-graph-backup -n 3 --no-pager`,
   then `unzip -tq /home/halo/backups/engram-graph/auto/graph-<stamp>.zip`. This is the rollback point.
2. **Rehearse on a restore of that backup** (restore into a scratch directory as in "Restore for real", step 3, then run the plan,
   then `--apply`, then the audit). The numbers of the live run must match it, apart from anything written since.
3. **Stop the engine:** `sudo systemctl stop engram-dev.service`. The Hermes WebUI has no engine until step 6.
4. **Plan** on `data/dev/engram-db` and read it: no refusal, `blocking: 0`, unhandled `0`, and the per-user table matches the rehearsal.
5. **Apply** (same command with `--apply`). It prints one `purge:` line per user and `apply finished in N ms`; any `FAILED` line means
   exit 5: stop there and read it (the failed user is untouched).
6. **Start the engine:** `sudo systemctl start engram-dev.service`, then check `GET /health`, `GET /debug/graph-counts`
   (`ownerJacobPresent` true), and one `POST /owner/horizon` with an empty body (200).
7. **Roll back** if owner-jacob's counts or the pps users' changed: `sudo systemctl stop engram-dev.service`, then "Restore for real"
   with the step 1 backup. Anything written since is lost.

Rehearsed on a restore of `graph-20261110T102037Z.zip` (2026-11-10): plan 0.5 s; apply 0.6 s of work (1.2 s wall with the JVM);
the engine downtime is dominated by the engine's own stop and start, not by the purge.

## What is missing (as of 2026-11-08, found by audit, not built)

- **One disk.** `GRAPH_BACKUP_COPY_TO` is unset, so every backup sits on the disk the graph is on. `halo-backup.timer`
  (03:30) is a separate vendor job; whether it covers `/home/halo/backups` was not verified.
- **The installed unit does not match the repo.** The unit in `/etc/systemd/system` runs
  `/home/halo/development/engram-engine/deploy/scripts/graph-backup.sh` (the checkout), not the
  `~/.local/share/engram/graph-backup.sh` copy that the repo unit and `deploy/README.md` describe (that file does not
  exist). It works today because that checkout is on `master`; checking out a branch without the script, or with a
  changed one, changes what the 05:20 job runs.
- **Failures are silent.** A failed run is only a `failed` unit and a journal line; nothing alerts anyone. Nobody is told if
  the engine is down at 05:20, the disk fills, or the timer stops.
- **The restore proof is manual.** `graph-restore-proof.sh` is not scheduled, so a backup that stops restoring is found
  only when someone runs it. (Each backup is CRC-verified at write time.)
- **Backups are unencrypted** and contain all of Jacob's graph (mode 600 in a mode 700 folder).
- **No automatic snapshot before risky operations**; it is the manual step above.

## Audit a restore

`./gradlew schemaAudit -PauditDb=<restored engram-db directory> -q` reads a restored copy (never the live directory) and prints the integrity
findings; see `graph-schema.md`. The Director tick writes the same report daily to `~/alfrd/director-tick/integrity.md`.

## Cold copy

When the engine has to be stopped anyway (a deploy), copy the directory while it is stopped:
`cp -a data/dev/engram-db /home/halo/backups/engram-graph/cold-$(date -u +%Y%m%dT%H%M%SZ)` (mode 700), and record its
size and file count. A cold copy is consistent by construction.
