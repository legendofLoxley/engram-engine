package app.alfrd.engram.cognitive

import app.alfrd.engram.cognitive.pipeline.CognitivePipeline
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.cognitive.providers.cloud.CloudLlmClient
import com.arcadedb.database.Database

/**
 * Assembles a [CognitivePipeline] with production-grade dependencies:
 * - [InMemoryEngramClient] for memory (ArcadeDB-backed client is a future task)
 * - [CloudLlmClient] wired to Anthropic and Google AI when API keys are present
 * - [ResponseSelectionService] when a database instance is provided
 *
 * If both API keys are absent the pipeline is created without an LLM client and
 * all branches degrade gracefully to their rule-based fallbacks.
 */
object CognitivePipelineFactory {

    fun create(db: Database? = null): CognitivePipeline {
        val anthropicKey = System.getenv("ANTHROPIC_API_KEY") ?: ""
        val googleKey    = System.getenv("GOOGLE_AI_API_KEY") ?: ""

        val llmClient = if (anthropicKey.isNotBlank() || googleKey.isNotBlank()) {
            CloudLlmClient(
                anthropicApiKey = anthropicKey,
                googleApiKey    = googleKey,
            )
        } else null

        val selectionService = db?.let { ResponseSelectionService(it) }

        return CognitivePipeline(
            engramClient = InMemoryEngramClient(),
            llmClient    = llmClient,
            selectionService = selectionService,
        )
    }
}
