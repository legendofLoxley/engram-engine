# Google Drive shelf (read-only)

Fetches the PDFs in one configured Google Drive folder, reads each into page-located chunks ([pdf-source-extraction.md](pdf-source-extraction.md)) and
commits each as one Source ([source-chunk-model.md](source-chunk-model.md)). Source and chunks only: no claim or item extraction, nothing surfaces in chat.
Code: `app.alfrd.engram.drive`; routes `POST /debug/sources/drive`, `GET /debug/drive/files`; CLI `app.alfrd.engram.scripts.DriveSourceCli`.

## Read-only by construction

- The only HTTP client is `DriveHttp`. It refuses, before the request leaves, anything but a `GET` under `/drive/v3/` and the token endpoint's `POST /token`,
  to `www.googleapis.com` / `oauth2.googleapis.com` over https. No PUT, PATCH, DELETE, upload, share, move or send call exists in the code, and redirects are not followed.
- Auth is a Google **service account**: the key file is read, a JWT is signed with it (RS256) and exchanged at the key's `token_uri` for an access token. The scope asked
  for is the constant `https://www.googleapis.com/auth/drive.readonly`; there is no way to ask for another. If Google reports a scope on the token and it reaches beyond read-only
  (anything but `drive.readonly`, `drive.metadata.readonly`, `gmail.readonly` and the identity scopes `openid`, `email`, `profile`), the token is refused, naming the scope. The account is
  also only a Viewer of the Shared Drive, so Drive would refuse a write on that ground as well.
- The key file's `token_uri` is only reached through the same guard, so a key file cannot send the signed assertion to another host or path.
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

- **Tested against a fake Google** (mock web server that verifies the JWT signature with the public key): the key file (owner-only mode, every group/other bit refused, malformed and non-RSA
  keys, wrong `type`), the JWT header and claims (`iss`, `scope`, `aud`, `iat`, `exp`, no `sub`) and signature, the token exchange and caching until a minute before expiry,
  scope enforcement, the read-only HTTP guard, `supportsAllDrives`/`includeItemsFromAllDrives`, pagination, retries, md5 checking, boundary and skipping, idempotent reruns,
  changed content, provenance, the routes and the CLI, and that no key material reaches a log or a message.
- **Not yet run against real Google from this repository's sandbox**: that needs the key below, which is not (and must never be) in the sandbox or the repo. The three real PDFs were read
  for the extraction results in [pdf-source-extraction.md](pdf-source-extraction.md) through a separate Drive connector in the development session, which is not the engine's Drive client.

## Service-account setup and the real three-document test on the box

**The account.** `alfrd-drive-reader@alfrd-box.iam.gserviceaccount.com` (project `alfrd-box`), a **Viewer** member of Shared Drive *PPS*. The Google Drive API has to be enabled in the project
`alfrd-box`. No domain-wide delegation is used (the assertion has no `sub`), and nothing is done on behalf of a user.

**The key.** One JSON key for that account, as downloaded from Google (`type` `service_account`, `client_email`, `private_key`, `token_uri`; other fields are ignored). It goes on the box, **outside
the repo**, readable by the user that runs the engine only:

```
/home/halo/.config/engram/google-drive-sa.json        # mode 600, owner = the user that runs the engine
install -d -m 700 /home/halo/.config/engram
install -m 600 /path/where/you/saved/the/key.json /home/halo/.config/engram/google-drive-sa.json
stat -c '%a %U' /home/halo/.config/engram/google-drive-sa.json      # expect: 600 halo
```

The engine refuses a file with any group or other permission bit, before it reads it. Move the key onto the box by a secure channel, delete the downloaded copy, and never put it in the repo,
chat, Notion, a log or an environment variable's value. The key is the only secret: the folder id and the service-account address are not.

**Configuration** (in the environment of the engine's service, set by whoever owns the box; this change does not touch the service files, the deploy scripts, `.do/app.yaml` or `CLAUDE.md`):
`GOOGLE_DRIVE_FOLDER_ID=1RPbk1LKygWEFBpImRdjtovnQOHNNWeu8` (the *Evidence* folder), `DEBUG_CONVERSE_ENABLED=true`, `DEBUG_CONVERSE_TOKEN=<the existing debug token>`; optionally
`GOOGLE_DRIVE_CREDENTIALS_FILE` (a path) if the key is not at the default above, and `ENGRAM_PDF_CACHE_DIR`. Tesseract (`apt install tesseract-ocr`) is optional for these three files
(every page has a text layer); without it, a page with no text layer is recorded `unread`.

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
The Drive md5 and the SHA-256 should agree with the file that was uploaded.

**If something fails**, the error carries a status or a rule, never the key:

| What you see | Meaning |
|---|---|
| `the credentials file must be mode 600` | `chmod 600` the key file (and check it is owned by the engine's user) |
| `the credentials file is not a service-account key` / `lacks a valid client_email` / `private_key ... not a readable PKCS#8 RSA key` | the file is not the JSON key Google downloads, or it was damaged in transit |
| `token request failed (status 400, error invalid_grant)` | the key was deleted or disabled in Google, or the box clock is far off |
| `token request failed (status 403` or `drive request failed (status 403)` | the Drive API is not enabled in project `alfrd-box`, or the account is not allowed in |
| `drive request failed (status 404)` on the folder | the account is not a member of Shared Drive PPS, or the folder id is wrong |
| `the token's scope goes beyond read-only` | Google reported a scope wider than `drive.readonly`: stop and look at the key's account |
