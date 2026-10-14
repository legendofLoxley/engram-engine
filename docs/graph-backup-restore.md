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

Never call `DELETE /debug/converse/purge`: it deletes `owner-jacob` along with the test users.

## Cold copy

When the engine has to be stopped anyway (a deploy), copy the directory while it is stopped:
`cp -a data/dev/engram-db /home/halo/backups/engram-graph/cold-$(date -u +%Y%m%dT%H%M%SZ)` (mode 700), and record its
size and file count. A cold copy is consistent by construction.
