package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.notion.NotionApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NotionCycleChannelTest {

    private val interval = Duration.ofMinutes(3)
    private val fixture = AgentWorkFixture()

    private fun channel(llm: LlmClient? = AgentWorkFixture.readPlan("Read the memo"), over: NotionApi = fixture.guarded(), traces: CycleTraceStore = CycleTraceStore()) =
        NotionCycleChannel(fixture.processor(llm, traces, over), interval, clock = { fixture.base })

    @Test
    fun `the first poll runs at once and a queued cycle on an active goal is planned and finished`() = runTest {
        val goal = fixture.goal("Active")
        val cycle = fixture.cycle(goal.id)
        val channel = channel()

        channel.start(backgroundScope)
        runCurrent()

        assertEquals("Done", fixture.cycleStatus(cycle.id))
        assertEquals(1, fixture.tasksFor(cycle.id).size)
        val snapshot = channel.snapshot()
        assertEquals(1, snapshot.polls)
        assertEquals(1, snapshot.lastResults.size)
        assertTrue(snapshot.lastResults.single().endsWith(": done"), snapshot.lastResults.toString())
        assertEquals(0, snapshot.consecutiveFailures)
        assertNull(snapshot.lastError)
    }

    @Test
    fun `a cycle queued after a poll waits for the next poll and is picked up within one interval`() = runTest {
        val goal = fixture.goal("Active")
        val channel = channel()
        channel.start(backgroundScope)
        runCurrent()
        assertEquals(1, channel.snapshot().polls)

        val cycle = fixture.cycle(goal.id)
        advanceTimeBy(interval.toMillis() - 1)
        assertEquals("Queued", fixture.cycleStatus(cycle.id), "nothing may happen before the interval has passed")
        assertEquals(1, channel.snapshot().polls)

        advanceTimeBy(1)
        runCurrent()
        assertEquals("Done", fixture.cycleStatus(cycle.id))
        assertEquals(2, channel.snapshot().polls)
    }

    @Test
    fun `a queued cycle on a draft goal is left alone and every poll comes back empty`() = runTest {
        val goal = fixture.goal("Draft")
        val cycle = fixture.cycle(goal.id)
        val llm = AgentWorkFixture.readPlan("never asked")
        val channel = channel(llm)

        channel.start(backgroundScope)
        runCurrent()
        advanceTimeBy(interval.toMillis() * 2)
        runCurrent()

        assertEquals("Queued", fixture.cycleStatus(cycle.id))
        assertEquals(0, fixture.tasksFor(cycle.id).size)
        assertTrue(llm.requests.isEmpty(), "the model must not be asked about a draft goal")
        assertTrue(channel.snapshot().polls >= 3)
        assertEquals(emptyList<String>(), channel.snapshot().lastResults)
    }

    @Test
    fun `a failing poll is recorded and the next poll recovers`() = runTest {
        val goal = fixture.goal("Active")
        val cycle = fixture.cycle(goal.id)
        // The guarded client makes two attempts per call (see AgentWorkFixture.guarded), so two failures fail exactly the first poll.
        fixture.fake.failNext("query", app.alfrd.engram.notion.NotionApiException(502, "bad_gateway", "upstream"), times = 2)
        val channel = channel()

        channel.start(backgroundScope)
        runCurrent()
        val failed = channel.snapshot()
        assertEquals(1, failed.consecutiveFailures)
        assertTrue(failed.lastError!!.contains("could not list cycles"), failed.lastError)
        assertEquals("Queued", fixture.cycleStatus(cycle.id))

        advanceTimeBy(interval.toMillis())
        runCurrent()
        val recovered = channel.snapshot()
        assertEquals(0, recovered.consecutiveFailures)
        assertEquals("Done", fixture.cycleStatus(cycle.id))
        assertEquals("could not list cycles: NotionApiException", recovered.lastError, "the last error stays visible after recovery")
    }

    @Test
    fun `an unexpected exception in a poll never stops the loop`() = runTest {
        val goal = fixture.goal("Active")
        val cycle = fixture.cycle(goal.id)
        val exploding = object : NotionApi by fixture.fake {
            var calls = 0
            override suspend fun query(dataSourceId: String, filter: app.alfrd.engram.notion.NotionFilter?, sorts: List<app.alfrd.engram.notion.NotionSort>, startCursor: String?, pageSize: Int): app.alfrd.engram.notion.QueryPage {
                if (++calls == 1) throw IllegalStateException("boom: not for logs")
                return fixture.fake.query(dataSourceId, filter, sorts, startCursor, pageSize)
            }
        }
        val channel = channel(over = fixture.guarded(exploding))

        channel.start(backgroundScope)
        runCurrent()
        assertEquals("IllegalStateException", channel.snapshot().lastError, "a class name, never the message")
        assertEquals("Queued", fixture.cycleStatus(cycle.id))

        advanceTimeBy(interval.toMillis())
        runCurrent()
        assertEquals("Done", fixture.cycleStatus(cycle.id))
    }

    @Test
    fun `cancelling the scope stops the polling`() = runTest {
        val channel = channel()
        val job = channel.start(backgroundScope)
        runCurrent()
        val before = channel.snapshot().polls

        job.cancel()
        advanceTimeBy(interval.toMillis() * 3)
        runCurrent()

        assertEquals(before, channel.snapshot().polls)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `a poll interval of zero is refused`() {
        val processor = fixture.processor(null)
        val refused = runCatching { NotionCycleChannel(processor, Duration.ZERO) }.exceptionOrNull()
        assertTrue(refused is IllegalArgumentException, "expected a refusal, got $refused")
    }

    @Test
    fun `the snapshot carries ids and counts and no free text from the cycle`() = runTest {
        val goal = fixture.goal("Active")
        fixture.cycle(goal.id)
        val channel = channel()
        channel.start(backgroundScope)
        runCurrent()

        val text = kotlinx.serialization.json.Json.encodeToString(ChannelSnapshot.serializer(), channel.snapshot())
        assertTrue(!text.contains("Read it and note"), "instructions belong in the trace, not in the poll status: $text")
        assertEquals(Instant.parse("2026-10-12T10:00:00Z").toString(), channel.snapshot().lastPollAt)
    }
}
