# Google Drive shelf (read-only)

Fetches the PDFs in one configured Google Drive folder, reads each into page-located chunks ([pdf-source-extraction.md](pdf-source-extraction.md)) and
commits each as one Source ([source-chunk-model.md](source-chunk-model.md)). Source and chunks only: no claim or item extraction, nothing surfaces in chat.
Code: `app.alfrd.engram.drive`; routes `POST /debug/sources/drive`, `GET /debug/drive/files`; CLI `app.alfrd.engram.scripts.DriveSourceCli`.

## Read-only by construction

- The only HTTP client is `DriveHttp`. It refuses, before the request leaves, anything but a `GET` under `/drive/v3/` and the token endpoint's `POST /token`,
  to `www.googleapis.com` / `oauth2.googleapis.com` over https. No PUT, PATCH, DELETE, upload, share, move or send call exists in the code, and redirects are not followed.
- The access token's scope is checked on every refresh: anything beyond `drive.readonly`, `drive.metadata.readonly`, `gmail.readonly` (and the identity scopes
  `openid`, `email`, `profile`) is refused, naming the scope. A credential that can write is not used.
- The configured folder is the ingestion boundary: `GOOGLE_DRIVE_FOLDER_ID`. Only its direct children are read; sub-folders are listed as skipped, not entered;
  only `application/pdf` is fetched. The route never takes a folder from the caller.
- Shared Drives: every files call carries `supportsAllDrives=true`; listings also `includeItemsFromAllDrives=true` and `corpora=allDrives`.
- A download is checked against the size and md5 Drive reported. A rate limit or 5xx is retried (four attempts, Retry-After honoured to 30 s); a 401 refreshes the token once.
- Logs carry counts, ids and outcomes. Never a name, a content, a token or a credential (tested).

## Provenance

On the Source: `locator = gdrive:<file id>` (stable across renames), `driveFileId`, `driveFileName` and `driveWebViewLink` as read, `driveVersion`, `driveMd5`,
`driveModifiedTime`, `driveId` (the Shared Drive), `driveHeadRevisionId`, `driveFolderId`, plus the file's SHA-256 (`fileSha256`). On each chunk: physical page,
printed page, section, kind, how it was read. `GET /debug/sources/{uid}/provenance` shows all of it; the `source.drive` object carries the Drive facts.

## Reruns and changes

A file whose md5 matches a Source already read under its id is `unchanged` and is not downloaded. Changed content is a new Source and the old one stays, with the version
it was read at (the existing source-versioning contract). The same bytes under a newer Drive version are not a new Source. A file that cannot be fetched or read is
`failed` with a reason and writes nothing; the others go on, and the next run retries it.

## What is verified, and what is not

- **Tested against a fake Google** (mock web server): token refresh and caching, scope refusal, the read-only guard, `supportsAllDrives`/`includeItemsFromAllDrives`,
  pagination, retries, md5 checking, boundary and skipping, idempotent reruns, changed content, provenance, the routes and the CLI.
- **Not yet run against real Google from this repository's sandbox**: that needs the credentials below, which are not (and must never be) in the sandbox or the repo.
  The three real PDFs were read for the extraction results in [pdf-source-extraction.md](pdf-source-extraction.md) through a separate Drive connector in the
  development session, which is not the engine's Drive client.

## Running the real three-document test on the box

You need, from Jacob, through a secure channel (never chat, Notion, the repo, logs or an environment variable's value):

1. **Membership.** The dedicated alfrd Google user is a member of Shared Drive *PPS* with a view-only role (Viewer; or the *Evidence* folder shared to it as Viewer).
   A 404 from `--check` means the account cannot see the folder.
2. **An OAuth client** (type Desktop app) in a Google Cloud project, consent for the alfrd user with the single scope
   `https://www.googleapis.com/auth/drive.readonly` (add `gmail.readonly` only for the later email phase). Use an *Internal* user type (Workspace) or publish the app:
   refresh tokens of an external app left in *Testing* expire after 7 days and the run then fails with `invalid_grant`.
3. **A refresh token** for that user and client, obtained once on a trusted machine by signing in as the alfrd user (for example with the OAuth consent flow of
   your choice, or `gcloud auth application-default login --client-id-file=client.json --scopes=https://www.googleapis.com/auth/drive.readonly`, whose
   output file has these three fields; that route is not tested here).

Put them in one JSON file, **outside the repo**, beside the box's other secrets, readable by the engine's user only:

```
/home/halo/development/hermes-webui-dev/.google-drive-credentials.json      # mode 600, owner = the user that runs the engine
{"client_id": "...", "client_secret": "...", "refresh_token": "..."}          # extra fields are ignored
```

`chmod 600` it. The engine refuses a file with any group or other permission bit, and refuses any file whose token reaches beyond read-only. Then, in the environment of the
engine's service (set by whoever owns the box; this change does not touch the service files, the deploy scripts, `.do/app.yaml` or `CLAUDE.md`): 
`GOOGLE_DRIVE_CREDENTIALS_FILE=<that path>` (a path, not the secret), `GOOGLE_DRIVE_FOLDER_ID=1RPbk1LKygWEFBpImRdjtovnQOHNNWeu8`, `DEBUG_CONVERSE_ENABLED=true`,
`DEBUG_CONVERSE_TOKEN=<the existing debug token>`, optionally `ENGRAM_PDF_CACHE_DIR`. Tesseract (`apt install tesseract-ocr`) is optional for these three files (all pages have a text layer).

With this branch built on the box (the owner's step; nothing here deploys or rebuilds anything):

```
# 1. Read-only check of access: lists the folder, downloads nothing, touches no graph. Expect folder "Evidence" and 3 PDFs:
java -cp engram-engine.jar app.alfrd.engram.scripts.DriveSourceCliKt --check
#    District wide scenarios 10.04.26 (2).pdf       227,552 bytes
#    SEGC Equity Audit Evaluation Report.pdf     1,393,167 bytes
#    SEGC School by School Analysis.pdf          3,478,054 bytes

# 2. Ingest under a synthetic identity (never owner-jacob). The engine fetches, reads and commits, on the box:
java -cp engram-engine.jar app.alfrd.engram.scripts.DriveSourceCliKt --user pps-drive-test
#    expect: created 3. Per file: pageCount, chunkCount, tables, readMode, elapsedMs, fileSha256

# 3. Provenance: Drive file id, link, version, and the page of every chunk
curl -s -H "Authorization: Bearer $DEBUG_CONVERSE_TOKEN" "http://127.0.0.1:8080/debug/sources?syntheticUserId=pps-drive-test"
curl -s -H "Authorization: Bearer $DEBUG_CONVERSE_TOKEN" "http://127.0.0.1:8080/debug/sources/<uid>/provenance?syntheticUserId=pps-drive-test"

# 4. Idempotency: run step 2 again. Expect created 0, unchanged 3, and no download in the engine's log.
# 5. Changed content: re-upload an edited copy of one PDF; step 2 creates one new Source and the old one stays.
```

Expected from the real files as read in the development session (same extractor): districtwide 2 pages / 6 chunks / 1 table; equity audit 157 pages / 381 chunks / 2 tables;
school-by-school 130 pages / 297 chunks / 0 tables; all `full`, all pages from the text layer. Chunk counts follow the extractor version, so they are a check, not a contract.
The Drive md5 and the SHA-256 should agree with the file Jacob uploaded. Anything else (a 403, 404, a 503 naming the mode, `invalid_grant`) is reported as it stands:
the error carries a status or a rule, never a secret.
