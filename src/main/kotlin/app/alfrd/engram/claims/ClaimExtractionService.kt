package app.alfrd.engram.claims

import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.SourceCommitService
import app.alfrd.engram.cognitive.pipeline.horizon.SourceItemDraft
import app.alfrd.engram.db.ChunkRecord
import app.alfrd.engram.db.LinkOutcome
import app.alfrd.engram.db.SourceRecord
import app.alfrd.engram.db.SourceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Baseline claim extraction over every chunk of a stored Source.
 *
 * **Two independent passes per chunk** against the local model ([ClaimPassModel]); neither sees the other. A claim is **established
 * only when both passes agree** on its quote, claimant and place ([ClaimAgreement]); everything else is retained, with both versions,
 * as a [Disagreement] and never established. The box records claims, not verdicts: an established claim is a FACT item committed
 * through [SourceCommitService] (so its quote is checked against its chunk, replays are keyed from content and a replay that
 * changed the quote or claimant is refused) and is **Settled**, or **Re-checking** once a later run questions it. There is no score.
 *
 * **Identity.** A claim is its passage within its Source: the item's text is the verbatim quote, so the same passage found by a later
 * run, or again in another chunk, is the same claim and gains that location, not a copy. The same passage under a different
 * claimant is not a second claim: when the chunk already holds it, the existing claim goes to Re-checking and the new attribution
 * is retained as a disagreement.
 *
 * **Runs.** A run has an id the caller chooses. Its model name is what the server reported on the answers, and it is stored with the
 * prompt version, the endpoint and the counts. A run that finished is answered from its record: no model call, nothing written. A run
 * that stopped is continued from the chunk it reached. A **later run** (another id) over the same Source adds nothing it already has;
 * an established claim that its two passes do not both reproduce is moved to Re-checking (kept, not changed) with the disagreement
 * retained. A claim stays Re-checking until a person decides: nothing here settles it again.
 *
 * **Context.** An established claim also keeps what both passes agreed of its context ([ClaimAgreement.context]): the passage it was
 * decomposed from, the things it is about and the period it refers to ([ClaimStore.attachContext]). Context never decides whether a
 * claim is established.
 *
 * Chunks are committed in batches of [batchSize] so that work survives a stop; the cycle number each commit uses is one per batch.
 */
class ClaimExtractionService(
    private val sources: SourceStore,
    private val claims: ClaimStore,
    private val commit: SourceCommitService,
    private val resolver: ClaimantResolver = ClaimantResolver(),
    private val batchSize: Int = 10,
) {
    private val logger = LoggerFactory.getLogger(ClaimExtractionService::class.java)

    /** The model required by default. The server's reported name must contain it (case and punctuation aside). */
    companion object {
        const val DEFAULT_EXPECTED_MODEL = "Qwen3.6-35B-A3B"
        const val WRITER = "claims-baseline"
        private val RUN_ID = Regex("[A-Za-z0-9._:-]{1,64}")
        private val ACTIVE = ConcurrentHashMap.newKeySet<String>()
        private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(24)
    }

    /** The server answered with a model the run did not expect; the run stops before anything of that chunk is written. */
    private class ModelRefused(message: String) : RuntimeException(message)

    private class Tally(var r: RunReport)

    suspend fun run(
        userEmail: String, sourceUid: String, runId: String, model: ClaimPassModel,
        expectModel: String? = DEFAULT_EXPECTED_MODEL, shouldStop: () -> Boolean = { false }, onProgress: (RunReport) -> Unit = {},
    ): RunReport {
        require(RUN_ID.matches(runId)) { "runId must be 1-64 characters of letters, digits and . _ : -" }
        val source = sources.sourceByUid(userEmail, sourceUid) ?: throw IllegalArgumentException("unknown source")
        val chunks = sources.chunks(userEmail, sourceUid)
        require(chunks.isNotEmpty()) { "the source has no chunks" }
        val lockKey = "$userEmail|$runId"
        check(ACTIVE.add(lockKey)) { "this run is already in progress" }
        try {
            val stored = claims.run(userEmail, runId)
            if (stored != null) {
                require(stored.sourceUid == sourceUid) { "this run id belongs to another source" }
                if (stored.status == "complete") stored.report?.let { return it }
            } else {
                claims.startRun(userEmail, runId, sourceUid, model.endpoint, model.promptVersion, 2, chunks.size)
            }
            // What the Source's own text defines as short forms, before any claim is attached: the same Source read twice gives the same things.
            claims.recordDefinedAliases(userEmail, sourceUid, chunks)
            claims.seedAliases(userEmail, resolver)
            val names = (stored?.modelName.orEmpty()).split(" | ").filter { it.isNotBlank() }.toMutableSet()
            val tally = Tally(stored?.report ?: blank(runId, sourceUid, model, chunks.size, expectModel))
            var next = stored?.chunksDone ?: 0
            fun snapshot(status: String) = tally.r.copy(status = status, modelName = names.sorted().joinToString(" | "), chunksDone = next, chunksTotal = chunks.size).also { tally.r = it }

            try {
                while (next < chunks.size) {
                    val batch = chunks.subList(next, minOf(chunks.size, next + batchSize))
                    val done = batch.map { chunk -> process(source, chunk, model, expectModel, names, stored?.modelName.orEmpty().isNotBlank()) }
                    persist(userEmail, source, runId, done, tally)
                    next += batch.size
                    claims.updateRun(userEmail, runId, modelName = names.sorted().joinToString(" | "), chunksDone = next, report = snapshot("running"))
                    onProgress(tally.r)
                    // A stop is asked for between batches: the batch in hand is finished and kept, and the run can be started again with its id.
                    if (next < chunks.size && shouldStop()) {
                        val report = snapshot("stopped")
                        claims.updateRun(userEmail, runId, status = "stopped", modelName = report.modelName, chunksDone = next, report = report)
                        logger.info("claims: run stopped on request at chunk {} of {}", next, chunks.size)
                        return report
                    }
                }
            } catch (e: ModelRefused) {
                tally.r = tally.r.copy(failures = tally.r.failures + (e.message ?: "model refused"))
                val report = snapshot("stopped")
                claims.updateRun(userEmail, runId, status = "stopped", modelName = report.modelName, report = report)
                logger.warn("claims: run stopped at chunk {} of {}: the model was not the one expected", next, chunks.size)
                return report
            }
            val finished = finalReport(snapshot("complete"))
            claims.updateRun(userEmail, runId, status = "complete", modelName = finished.modelName, chunksDone = next, report = finished)
            logger.info("claims: run complete: {} chunk(s), {} claim(s) established, {} disagreement(s)", chunks.size, finished.claimsEstablished, finished.disagreements)
            // Whichever reader finishes last links what the two read in common. Linking never decides whether a run succeeded.
            try {
                val links = claims.linkReaders(sources, userEmail, sourceUid, runId)
                logger.info("claims: readers linked: {} corroborated, {} conflict(s), {} new link(s), {} structure-only, {} text-only", links.corroborated, links.conflicts, links.newLinks, links.structureOnly, links.textOnly)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("claims: linking the readers failed: {}", e::class.java.simpleName)
            }
            return finished
        } finally {
            ACTIVE.remove(lockKey)
        }
    }

    private fun blank(runId: String, sourceUid: String, model: ClaimPassModel, total: Int, expect: String?) = RunReport(
        runId = runId, sourceUid = sourceUid, status = "running", modelName = "", modelEndpoint = model.endpoint, promptVersion = model.promptVersion,
        passes = 2, chunksTotal = total, chunksDone = 0, chunksFailed = 0, claimsEstablished = 0, claimsAlreadyThere = 0, locationsAdded = 0,
        claimsRechecking = 0, disagreements = 0, quotesRejected = 0, claimsAgreed = 0, claimsReported = 0, agreementRate = null, modelExpectation = expect,
    )

    private fun finalReport(r: RunReport) = r.copy(agreementRate = if (r.claimsReported == 0) null else r.claimsAgreed.toDouble() / r.claimsReported)

    /** What two passes made of one chunk. */
    private class Processed(val chunk: ChunkRecord, val compared: ClaimAgreement.Compared?, val failure: String?, val skipped: Boolean = false)

    private suspend fun process(source: SourceRecord, chunk: ChunkRecord, model: ClaimPassModel, expect: String?, names: MutableSet<String>, resumed: Boolean): Processed {
        if (!model.reads(chunk)) return Processed(chunk, null, null, skipped = true)
        // The two passes are separate requests that share nothing, so they run together (the server serves two at once). The first one
        // that fails ends the chunk and the other is cancelled; a server answering with the wrong model stops the whole run.
        val outcome = coroutineScope {
            val answers = Channel<Pair<Int, Result<PassResult>>>(2)
            val jobs = (1..2).map { pass ->
                launch {
                    val started = System.currentTimeMillis()
                    // A cancellation raised inside the model is reported like any other end of the pass, so the chunk's wait cannot hang on it.
                    val r = try { Result.success(model.extract(source, chunk, pass)) } catch (e: Exception) { Result.failure(e) }
                    r.getOrNull()?.let { result ->
                        // Counts and time only, never text.
                        logger.info(
                            "claims: chunk {} pass {} reported {} claim(s) in {} ms (finish {}, {} completion token(s))",
                            chunk.ordinal, pass, result.claims.size, System.currentTimeMillis() - started, result.finishReason ?: "-", result.completionTokens ?: "-",
                        )
                    }
                    answers.send(pass to r)
                }
            }
            val got = HashMap<Int, PassResult>()
            var failure: String? = null
            repeat(2) {
                if (failure != null) return@repeat
                val (pass, r) = answers.receive()
                r.onSuccess { got[pass] = it }.onFailure { e ->
                    if (e is CancellationException) { jobs.forEach { it.cancel() }; throw e }
                    // The class name only: a provider's message could echo the passage.
                    failure = "chunk ${chunk.ordinal}: pass $pass failed (${(e as? ClaimPassFailure)?.message ?: e::class.java.simpleName})"
                    jobs.forEach { it.cancel() }
                }
            }
            failure?.let { return@coroutineScope it to emptyList<PassResult>() }
            null to listOf(got.getValue(1), got.getValue(2))
        }
        outcome.first?.let { return Processed(chunk, null, it) }
        val results = outcome.second
        results.forEach { result ->
            if (expect != null && !squash(result.modelName).contains(squash(expect))) {
                throw ModelRefused("the server reported model '${result.modelName}', not one containing '$expect'; the run was stopped")
            }
            if (resumed && names.isNotEmpty() && result.modelName !in names) {
                throw ModelRefused("the server now reports model '${result.modelName}', which is not the model this run started with; the run was stopped")
            }
            names += result.modelName
        }
        val compared = ClaimAgreement.compare(chunk, results[0].claims, results[1].claims, resolver)
        logger.info(
            "claims: chunk {} agreed {} claim(s), {} disagreement(s), {} quote(s) rejected",
            chunk.ordinal, compared.outcome.agreed.size, compared.outcome.disagreements.size, compared.rejected,
        )
        return Processed(chunk, compared, null)
    }

    private fun placeFits(a: String?, b: String?) = a == null || b == null || ClaimAgreement.quoteKey(a).lowercase() == ClaimAgreement.quoteKey(b).lowercase()
    private fun sameClaim(item: ClaimItem, ag: AgreedClaim) =
        ClaimAgreement.quoteKey(item.quote) == ClaimAgreement.quoteKey(ag.quote) && ClaimantResolver.key(item.claimant) == ClaimantResolver.key(ag.claimant) && placeFits(item.place, ag.place)

    /** The key a claim has within a batch: its passage, its claimant and its place. */
    private fun identity(ag: AgreedClaim) = "${ClaimStore.quoteHash(ag.quote)}|${ClaimantResolver.key(ag.claimant)}|${ag.place?.let { ClaimAgreement.quoteKey(it).lowercase() }.orEmpty()}"

    /** The item's text: the quote, and where in the chunk when it names a place, so one row quoted for three columns is three claims. */
    private fun label(ag: AgreedClaim) = ag.place?.let { "${ag.quote} [$it]" } ?: ag.quote

    private data class Place(val itemUid: String?, val claim: AgreedClaim, val chunk: ChunkRecord, val key: String)

    private suspend fun persist(userEmail: String, source: SourceRecord, runId: String, done: List<Processed>, tally: Tally) {
        val drafts = ArrayList<SourceItemDraft>()
        val draftClaims = ArrayList<AgreedClaim>()
        val queued = HashMap<String, Int>()
        val places = ArrayList<Place>()
        var r = tally.r

        fun versionOf(c: ClaimItem) = ClaimVersion(c.quote, c.claimant, c.claimantAsWritten, c.subject, c.kind)
        fun versionOf(c: AgreedClaim) = ClaimVersion(c.quote, c.claimant, c.claimantAsWritten, c.subject, c.kind.name, c.place)

        val questioned = HashSet<String>()
        fun question(item: ClaimItem, chunk: ChunkRecord, reason: String, other: ClaimVersion?) {
            questioned += item.uid
            if (claims.markRechecking(userEmail, item.uid, runId)) r = r.copy(claimsRechecking = r.claimsRechecking + 1)
            if (claims.addDisagreement(userEmail, runId, source.uid, Disagreement(chunk.uid, reason, versionOf(item), other))) r = r.copy(disagreements = r.disagreements + 1)
        }

        done.forEach { p ->
            val chunk = p.chunk
            if (p.skipped) return@forEach
            if (p.compared == null) {
                r = r.copy(chunksFailed = r.chunksFailed + 1, failures = r.failures + (p.failure ?: "chunk ${chunk.ordinal} failed"))
                return@forEach
            }
            val outcome = p.compared.outcome
            r = r.copy(
                quotesRejected = r.quotesRejected + p.compared.rejected, claimsAgreed = r.claimsAgreed + outcome.agreed.size,
                claimsReported = r.claimsReported + p.compared.reported,
            )
            outcome.disagreements.forEach { d ->
                if (claims.addDisagreement(userEmail, runId, source.uid, d)) r = r.copy(disagreements = r.disagreements + 1)
            }
            outcome.agreed.forEach { ag ->
                // The same claim is the same passage at a compatible place (one that names no place fits any).
                val existing = claims.claimsWithQuote(userEmail, source.uid, ag.quote).filter { placeFits(it.place, ag.place) }
                val same = existing.firstOrNull { ClaimantResolver.key(it.claimant) == ClaimantResolver.key(ag.claimant) }
                val other = existing.firstOrNull { chunk.uid in it.chunkUids && ClaimantResolver.key(it.claimant) != ClaimantResolver.key(ag.claimant) }
                val dk = identity(ag)
                when {
                    same != null && chunk.uid in same.chunkUids -> r = r.copy(claimsAlreadyThere = r.claimsAlreadyThere + 1)
                    same != null -> places += Place(same.uid, ag, chunk, dk)
                    other != null -> question(
                        other, chunk,
                        if (other.runId == runId) "this run attributes a passage it already established to a different claimant"
                        else "a later run attributes an established claim to a different claimant",
                        versionOf(ag),
                    )
                    dk in queued -> places += Place(null, ag, chunk, dk)
                    else -> {
                        queued[dk] = drafts.size
                        drafts += SourceItemDraft(ItemKind.FACT, label(ag), ag.quote, chunk.ordinal, claimant = ag.claimant)
                        draftClaims += ag
                    }
                }
            }
            // Claims an earlier run established in this chunk that this run's two passes do not both reproduce are questioned, not
            // dropped or changed. Not for a chunk a pass failed on: nothing was learned about it.
            val reproduced = outcome.agreed
            claims.claimsInChunk(userEmail, source.uid, chunk.uid).filter { it.runId != runId && it.uid !in questioned }.forEach { prior ->
                if (reproduced.none { sameClaim(prior, it) }) {
                    question(prior, chunk, "a later run's two passes did not both reproduce an established claim", null)
                }
            }
        }

        if (drafts.isNotEmpty()) {
            val report = commit.commitStored(userEmail, source.uid, drafts, WRITER, runId)
            report.items.forEachIndexed { i, item ->
                val uid = item.uid
                if (!item.applied || uid == null) {
                    r = r.copy(failures = r.failures + "a claim in chunk ${draftClaims[i].chunkUid.take(8)} could not be committed")
                } else {
                    claims.markEstablished(userEmail, uid, source.uid, draftClaims[i], runId)
                    claims.attachContext(userEmail, uid, source.uid, draftClaims[i].chunkUid, draftClaims[i], runId, source.issuer)
                    r = if (item.created) r.copy(claimsEstablished = r.claimsEstablished + 1) else r.copy(claimsAlreadyThere = r.claimsAlreadyThere + 1)
                }
            }
        }

        // Further places a claim was found (another chunk, or the same passage again in this batch): the claim gains the location.
        places.forEach { place ->
            val uid = place.itemUid ?: claims.claimsWithQuote(userEmail, source.uid, place.claim.quote)
                .firstOrNull { sameClaim(it, place.claim) }?.uid
            if (uid == null || ClaimAgreement.locate(place.chunk.text, place.claim.quote) != place.claim.quote) {
                r = r.copy(failures = r.failures + "a repeated claim in chunk ${place.chunk.ordinal} could not be located")
                return@forEach
            }
            val outcome = sources.linkItem(
                userEmail, uid, place.chunk.uid, WRITER, place.claim.claimant, opKey = "src:${source.uid}:at:${sha(place.key)}:${place.chunk.ordinal}",
                draftQuote = place.claim.quote, runId = runId,
            )
            if (outcome != LinkOutcome.REFUSED) claims.attachContext(userEmail, uid, source.uid, place.chunk.uid, place.claim, runId, source.issuer)
            r = when (outcome) {
                LinkOutcome.CREATED -> r.copy(locationsAdded = r.locationsAdded + 1)
                LinkOutcome.ALREADY_LINKED -> r.copy(claimsAlreadyThere = r.claimsAlreadyThere + 1)
                LinkOutcome.REFUSED -> r.copy(failures = r.failures + "a repeated claim in chunk ${place.chunk.ordinal} could not be linked")
            }
        }
        tally.r = r
    }
}
