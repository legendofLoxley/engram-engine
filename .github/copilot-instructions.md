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
