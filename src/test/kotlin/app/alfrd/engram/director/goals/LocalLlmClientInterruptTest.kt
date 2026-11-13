package app.alfrd.engram.director.goals

import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmTimeoutError
import app.alfrd.engram.cognitive.providers.local.LocalLlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `LocalLlmClient.doComplete` now blocks in `runInterruptible` so a cancelled caller can stop waiting. What every other caller (Notion cycles, Hermes, conversation) sees must
 * not change: a normal answer, the retry on a bad answer, and the timeout. Only a cancelled caller is different, and it must not be retried.
 */
class LocalLlmClientInterruptTest {
    private val server = FakeModelServer()

    @AfterEach fun stop() = server.stop()

    private fun client() = LocalLlmClient(baseUrl = server.baseUrl)
    private fun request(timeoutMs: Long = 20_000) = LlmRequest(prompt = "hello", timeoutMs = timeoutMs, tools = listOf(GoalPlanDirector.PLAN_TOOL), forcedToolName = "propose_goal_plan")

    @Test
    fun `an ordinary call returns its answer exactly as before`() {
        server.on("propose_goal_plan") { FakeModelServer.Reply("""{"steps":[]}""", completionTokens = 7) }
        val r = runBlocking { client().complete(request()) }
        assertEquals("propose_goal_plan", r.toolCalls.single().name)
        assertEquals(7, r.completionTokens)
        assertEquals(0, r.retryCount)
        assertEquals(1, server.calls("propose_goal_plan"))
    }

    @Test
    fun `a bad answer is retried the same number of times and then fails with the same error`() {
        server.on("propose_goal_plan") { FakeModelServer.Reply("", emptyLength = true) }
        val e = try { runBlocking { client().complete(request()) }; null } catch (e: Exception) { e }
        assertTrue(e is RuntimeException && e.message!!.contains("empty completion"), e.toString())
        assertEquals(3, server.calls("propose_goal_plan"), "one try and two retries, as before")
    }

    @Test
    fun `a timeout is still an LlmTimeoutError, at the timeout and not after the slow answer`() {
        server.on("propose_goal_plan") { FakeModelServer.Reply("""{"steps":[]}""", delayMillis = 3_000) }
        val started = System.currentTimeMillis()
        val e = try { runBlocking { client().complete(request(timeoutMs = 300)) }; null } catch (e: Exception) { e }
        assertTrue(e is LlmTimeoutError, e.toString())
        assertTrue(System.currentTimeMillis() - started < 2_500, "gave up at the timeout, not after the slow answer")
    }

    @Test
    fun `a cancelled caller stops waiting at once and is not retried`() {
        server.on("propose_goal_plan") { FakeModelServer.Reply("""{"steps":[]}""", delayMillis = 15_000) }
        val started = System.currentTimeMillis()
        runBlocking {
            val job = async(Dispatchers.Default) { client().complete(request()) }
            while (server.calls("propose_goal_plan") == 0) delay(10)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
        assertTrue(System.currentTimeMillis() - started < 8_000, "did not wait out the 15 s answer")
        assertEquals(1, server.calls("propose_goal_plan"), "a cancelled call is not tried again")
    }
}
