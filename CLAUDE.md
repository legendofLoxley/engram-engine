# CLAUDE.md

Guidance for Claude Code (and similar agents) working in this repository.

## Observability

Production logs (DigitalOcean App Platform deployment, `master` branch, auto-deployed
on push per `.do/app.yaml`) are collected by **Better Stack** under the source
`engram-engine-prod` (source id `2650284`, platform `digitalocean`, region `eu-fsn-3`).

### Access

Log access is via Better Stack's MCP server, registered project-wide in this repo's
`.mcp.json`:

```json
{
  "mcpServers": {
    "betterstack": {
      "type": "http",
      "url": "https://mcp.betterstack.com",
      "headers": {
        "Authorization": "Bearer ${BETTERSTACK_API_TOKEN}"
      }
    }
  }
}
```

- **Transport**: HTTP, not OAuth — chosen because headless/CI environments have no
  browser to complete an OAuth flow.
- **Auth**: header-based Bearer token, resolved from the `BETTERSTACK_API_TOKEN`
  environment variable at connect time. The token is never written into the config
  file or committed anywhere — it must be set as an env var wherever this repo runs
  (already configured as an environment-level secret in Claude Code on the web).
- **Trust and tool scoping** live in the committed `.claude/settings.json` (not
  `.claude/settings.local.json`, which is gitignored and machine-local — Claude Code
  on the web sessions run in fresh, ephemeral containers each time, so anything
  needed "next session" must be committed, not left in local/user-level config):
  `enabledMcpjsonServers: ["betterstack"]` pre-trusts the server so it connects
  without an interactive approval prompt, and `permissions.allow` scopes usage to
  the three read-only tools this repo needs: `mcp__betterstack__sources`,
  `mcp__betterstack__query_help`, `mcp__betterstack__query` (the server exposes many
  more — incidents, monitors, dashboards, teams, etc. — out of scope here).

### Querying logs

The `query` tool runs ClickHouse SQL against two collections per source:

| Collection | Table function | Coverage |
|---|---|---|
| `t578556_engram_engine_prod_logs` | `remote(...)` | Hot tier — last ~30 minutes |
| `t578556_engram_engine_prod_s3` | `s3Cluster(primary, ...)` | Cold tier — everything older; filter `_row_type = 1` for log rows |

Log lines are JSON in a `raw` column; extract fields with
`JSONExtract(raw, 'field', 'Nullable(String)')`. Example — last 30 minutes of
`CognitivePipeline` turn logs:

```sql
SELECT
  dt,
  JSONExtract(raw, 'message', 'Nullable(String)') AS message
FROM remote(t578556_engram_engine_prod_logs)
WHERE dt > now() - INTERVAL 30 MINUTE
ORDER BY dt DESC
LIMIT 100
```

## Coroutine Testing Pitfalls

These issues have burned time in this codebase. Avoid them.

### 1. Don't use `callbackFlow` + `awaitClose` with inner `launch` children

`awaitClose` does not cooperate with `runTest`'s virtual-time scheduler when child coroutines are present — every test that calls `collectJob.join()` will hang for 1 minute then fail with `UncompletedCoroutinesError`.

**Use instead:** `channelFlow` with an explicit `Channel<T>` bridge. WebSocket listener posts to the channel; the `channelFlow` body iterates with `for (item in channel)`. Closing the channel from `onClose`/`onError` terminates the loop cleanly.

### 2. `while (isActive) { delay(...) }` loops break `advanceUntilIdle()`

Any loop that reschedules a `delay` on each iteration causes `advanceUntilIdle()` to spin forever — it advances virtual time to run the delay, which reschedules another, indefinitely.

**Fix:** Gate the loop on a condition that becomes false when the work is done. For example, a KeepAlive job that polls Deepgram should exit when `audioJob.isCompleted` — with `emptyFlow()` in tests the audio job finishes immediately, so the loop never enters and no delays are scheduled.

**For tests with a never-completing flow:** use `backgroundScope.launch { }` instead of `launch { }` so the coroutine is auto-cancelled at `runTest` exit rather than requiring explicit `cancel()`/`join()`.

### 3. Gradle incremental compilation can hide file edits

If a source file is edited via a tool but the task shows `UP-TO-DATE`, tests run against the stale compiled class. Always pass `--rerun-tasks` when verifying a fix, or run `compileKotlin --rerun-tasks` explicitly before testing.

## ArcadeDB Interop

### `assertNotNull` ambiguity with ArcadeDB schema API

`schema.getType(x).getProperty(y)` returns a Java platform type (`Property!`), which causes Kotlin overload resolution ambiguity between JUnit 5's `assertNotNull(actual: Any?, message: String)` and its static Java overload. The compiler refuses to compile.

**Fix:** Always use `assertTrue(x != null, message)` when asserting on values returned from ArcadeDB schema API calls (`getProperty`, `getIndex`, etc.).

## Discovery Discipline

Token budget and wall-clock both matter. Most discovery waste comes from over-searching, not under-searching.

### Mapping a feature surface
- Start with ONE exhaustive glob (e.g. `**/src/main/kotlin/**/*.kt`) plus the symbol outline tool. This shows structure faster than 10 sequential greps.
- Only run targeted `grep` for symbols you actually need to modify. Do not grep for every class named in the spec "just to be thorough" — the glob already surfaced the files.
- When a `grep` returns the 20-result cap, narrow it before running it again. The cap means you're not seeing everything.

### Reading files
- NEVER re-read a file already loaded in this session. Use the in-context copy. Only re-read when explicitly notified a file is out of date.
- For files under 300 lines, read the whole file at once. Do not chunk by line ranges unless the file is genuinely large.
- Read test files only when writing tests or debugging failures — do not speculatively read test files during implementation discovery.

### Neighbor files
- Do not speculatively read files adjacent to the target. If the task mentions `MemoryWriteService`, read its file and its direct collaborators (constructor deps), not the whole `memory/` directory.

## Test Verification

- Always use `--rerun-tasks` when verifying a fix (see Coroutine Pitfall 3).
- For pass/fail counts, use the canonical one-liner in `src/test/kotlin/SKILL.md`. Do not improvise alternative grep pipelines.
- Never declare a task done without running the full suite (`./gradlew test --rerun-tasks`) at the end — not just the new test class.
- Report numbers in the form `N tests, M failures` (e.g. `206 tests, 0 failures`). Do not paraphrase.

## Planning

- For tasks spanning 3+ files or involving cross-layer wiring (service + route + DTO + test), start in **Planning mode**. Produce a written plan before any edits. Session memory persists the plan across turns — do not re-derive it each turn.
- Treat the "VSCode Agent Prompt" section of a Notion task page as the authoritative spec. If the page has a numbered "What to build" section, mirror that numbering in your plan and in progress updates.
- Progress updates use the form `Starting: <step name> (N/total)` so the user can see position at a glance.

## Output Conventions

- When summarizing completed work, lead with the headline number (`206 tests, 0 failures`) then list implementation points 1..N matching the task spec.
- Do not restate the task spec in the output — the user has it. Say what changed and why it works, not what was requested.

## Testable Service Classes

Kotlin classes are `final` by default. Any class that needs a test double (fake subclass) must be declared `open` with `open` methods. Do this at authoring time — retrofitting it later breaks compilation mid-test run.

**Pattern for database-backed services:** make the constructor parameter nullable (`val db: Database?`) so tests can pass `null` without hitting Kotlin's null-safety cast. All real call sites pass a real `Database`; the fake subclass overrides every method before any `db` usage is reached.

```kotlin
open class UserGraphService(private val db: Database?) {
    open fun findUserByEmail(email: String): UserRecord? { db!!.query(...) }
    // ...
}

// In tests:
class FakeUserGraphService(...) : UserGraphService(null) {
    override fun findUserByEmail(email: String) = fixedRecord
}
```

## Git: Multiple GitHub Orgs

This repo has two GitHub org remotes (`legendofLoxley` = canonical, `primarykey-solutions` = old). Always verify `git remote -v` before pushing. If origin points to the wrong org:

```bash
git remote set-url origin https://github.com/legendofLoxley/engram-engine.git
```

If the remote has diverged (force-pushed), `git pull --rebase` can silently drop local commits when the remote history wins. Always confirm the files you expect are still present after a rebase with divergent history (`git log --oneline -5` and spot-check key files).
