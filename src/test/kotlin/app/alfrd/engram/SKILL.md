Testing Skill — engram-engine
Scope: everything under src/test/kotlin/. For repo-wide rules see .github/copilot-instructions.md.
Running the suite
Full suite: ./gradlew test --rerun-tasks
Single class: ./gradlew test --tests "app.alfrd.engram.<fqcn>" --rerun-tasks
Compile check only: ./gradlew compileKotlin compileTestKotlin --rerun-tasks
Always pass --rerun-tasks. Gradle incremental compilation has caused stale-class bugs in this repo.
Counting test results (canonical)
Use these one-liners. Do not improvise alternatives.
# Total tests across the suite
grep -h 'tests=' build/test-results/test/*.xml \
  | grep -oP 'tests="\K[^"]+' \
  | awk '{s+=$1} END {print "Total:", s}'

# Failures across the suite
grep -h 'failures=' build/test-results/test/*.xml \
  | grep -oP 'failures="\K[^"]+' \
  | awk '{s+=$1} END {print "Failures:", s}'
​
Report the result verbatim: N tests, M failures.
Coroutine test patterns
Reference: Coroutine Testing Pitfalls in .github/copilot-instructions.md. Patterns below are the positive form.
WebSocket / listener-style flows → channelFlow
fun listen(): Flow<Frame> = channelFlow {
    val bridge = Channel<Frame>(Channel.BUFFERED)
    val listener = object : FrameListener {
        override fun onFrame(f: Frame) { bridge.trySend(f) }
        override fun onClose() { bridge.close() }
        override fun onError(t: Throwable) { bridge.close(t) }
    }
    socket.attach(listener)
    for (item in bridge) send(item)
}
​
Listener posts via trySend
channelFlow body iterates for (item in bridge)
onClose / onError close the channel → loop terminates cleanly
Do not use callbackFlow + awaitClose with inner launch children — see pitfall §1
Polling loops → gate on completion signal
// ❌ Bad — breaks advanceUntilIdle()
while (isActive) {
    delay(500)
    sendKeepAlive()
}

// ✅ Good — exits when the work it protects is done
while (!audioJob.isCompleted) {
    delay(500)
    sendKeepAlive()
}
​
With emptyFlow() in tests the audio job completes immediately; the loop never enters; no delays are scheduled; advanceUntilIdle() resolves.
Never-completing flows in tests → backgroundScope
@Test
fun `keeps keep-alive running until cancel`() = runTest {
    backgroundScope.launch {    // NOT launch { }
        service.run(neverEndingFlow)
    }
    advanceTimeBy(10.seconds)
    // assertions…
    // no explicit cancel()/join() — backgroundScope cleans up at runTest exit
}
​
Test style conventions
Templates
Unit-ish: MemoryWriteServiceTest.kt, SelectionScorerTest.kt
Integration (full pipeline): MemoryBridgeTest.kt, CognitiveInitTest.kt
Scaffold / state machines: ScaffoldStateTest.kt, TrustPhaseTransitionTest.kt
Match the style of the closest existing template before writing a new test class.
Fakes vs. real backends
Use InMemoryEngramClient for unit-ish tests.
Reserve HttpEngramClient + real ArcadeDB for integration tests that specifically need persistence or round-trip serialization coverage.
If a test is verifying DTO shape, it must hit HttpEngramClient — InMemoryEngramClient bypasses DTO mapping.
Naming
Class: <UnitUnderTest>Test.kt
Methods: backtick-quoted sentences describing observable behavior
✅  advances to WORKING_RHYTHM after 3 categories including IDENTITY 
❌ testAdvancement1
Clock injection
Services that depend on time take Clock in the constructor (default Clock.systemUTC()).
In tests, inject a Clock.fixed(...) or a mutable test clock. Never use System.currentTimeMillis() inline in service code.