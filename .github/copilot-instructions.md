# Copilot Instructions — engram-engine

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

---

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

---

## Test Verification

- Always use `--rerun-tasks` when verifying a fix (see Coroutine Pitfall 3).
- For pass/fail counts, use the canonical one-liner in `src/test/kotlin/SKILL.md`. Do not improvise alternative grep pipelines.
- Never declare a task done without running the full suite (`./gradlew test --rerun-tasks`) at the end — not just the new test class.
- Report numbers in the form `N tests, M failures` (e.g. `206 tests, 0 failures`). Do not paraphrase.

---

## Planning

- For tasks spanning 3+ files or involving cross-layer wiring (service + route + DTO + test), start in **Planning mode**. Produce a written plan before any edits. Session memory persists the plan across turns — do not re-derive it each turn.
- Treat the "VSCode Agent Prompt" section of a Notion task page as the authoritative spec. If the page has a numbered "What to build" section, mirror that numbering in your plan and in progress updates.
- Progress updates use the form `Starting: <step name> (N/total)` so the user can see position at a glance.

---

## Output conventions

- When summarizing completed work, lead with the headline number (`206 tests, 0 failures`) then list implementation points 1..N matching the task spec.
- Do not restate the task spec in the output — the user has it. Say what changed and why it works, not what was requested.