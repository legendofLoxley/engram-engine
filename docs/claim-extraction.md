# Baseline claim extraction

Reads every chunk of a stored Source twice with the local model and records the claims both passes agree on.
Code: `app.alfrd.engram.claims`; routes `POST /debug/sources/{uid}/claims/extract`, `GET /debug/claim-runs/{runId}`, `GET /debug/sources/{uid}/claims`,
`GET /debug/sources/{uid}/claim-disagreements`; CLI `app.alfrd.engram.scripts.ClaimExtractionCliKt`. Sources come from `docs/source-chunk-model.md`,
`docs/pdf-source-extraction.md` and `docs/google-drive-connector.md`.

**Status.** Implemented and tested in the cloud against a scripted model. It has **not** been run against Arx/Qwen: what the real model reports, how often
its two passes agree and whether its attributions are right are open until the box gate (below).

## What a claim is

One checkable assertion the source explicitly makes: a fact, a number with units or date, a decision or vote, a commitment or deadline, a stated requirement,
or an attributed position. Not headings, boilerplate, rhetoric or inference. Independent assertions are split. The prompt (`PromptSet`, version
`claims-v2`) says this to the model; code enforces what it can:

| Kept on the claim | How it is held |
|---|---|
| exact quote | a verbatim passage of its chunk. A quote that differs from the chunk only in spacing or line breaks is replaced by the chunk's own text; any other difference rejects the claim. Checked again by the commit path, replays included. |
| source, chunk, page, location | the claim is a FACT item tied to its chunk by `QUOTES`; `GET .../claims` lists every location. |
| claimant (person and/or organization) | resolved to a canonical name (aliases below). The name as written is kept as `claimantAsWritten`. None named, none stored. |
| subject, kind | `fact number decision vote commitment deadline requirement position`. Descriptive; **not** compared between passes. |
| place | optional: the card, box, section, column or row inside the chunk. Compared between passes when both give one. |

A claim's identity in its Source is its quote plus its place. The item's text is the quote (`quote [place]` when a place is given), so the same passage found
again, by a later run or in another chunk, is the same claim and gains a location instead of a copy; one table row quoted for three columns is three claims.

## Establishment

1. **Two independent passes per chunk.** Separate requests, different instruction wording, no shared context; neither sees the other's answer. One forced
   tool call each (`report_claims`) to the local server. `LocalClaimModel` takes a `LocalLlmClient`, not the general client interface, so no hosted model can
   be passed: extraction inference is local only.
2. **Establish only on agreement** on the quote, the canonical claimant, and the place (the chunk is the location; when both passes name a place in it, those
   must match; one naming none does not block). Everything else is retained whole as a disagreement: `passes differ on the claimant/place`, `only pass 1/2
   reported it`, `quote is not in the chunk`, `quote is outside the section its place names`, `place is too long to be a place` (over 120 characters: text copied into the field), `both passes name more than one claimant for this passage`.
   Disagreements are `ClaimDisagreement` rows holding both versions; they are never made items.
3. **A place that is a heading is checked.** If a claim's place is a line of its chunk (a card or scenario title), the passage must lie under that heading, up
   to the next heading some claim names. A count printed under SCENARIO A cannot be filed under SCENARIO B. A place that is not a line of the chunk (a table
   column) can only be compared between the passes.
4. An established claim is a FACT item committed through `SourceCommitService.commitStored` (existing keys, within-call conflict check, quote validation),
   written by `claims-baseline`, with `checkState = Settled`. **No new Horizon kind.** The box records claims, not verdicts; there is no numeric confidence
   anywhere. The agreement rate in a run report is a diagnostic about the two passes, not a truth score.

## Canonical claimants

`ClaimantResolver` maps a canonical name to its aliases (`{"Portland Public Schools": ["PPS", "the District"]}`). Names compare without case, punctuation or a
leading "the". An unlisted name stands for itself; nothing is guessed. One alias under two canonical names is refused. Pass the list as `aliases` in the request or
`--aliases file.json`. The list is per run: change it and a later run may disagree with an earlier one, which is what Re-checking is for.

## Prompts

The two system prompts live in code (`PromptSet.DEFAULT`, version `claims-v5`), reviewed and tested; a run records the version. `claims-v2` follows the first live
run on the districtwide sheet: for a table, quote the whole row and give the column header as the place (value-only cell quotes never agreed with whole-row
quotes, so a table gave no claim); a number's quote must carry what it counts; no headings, footers or "Updated <date>" stamps; one sentence is not cut into
fragments; the place is the outermost heading that names a scenario. `claims-v3` follows the second live run: the passes disagreed on the place of page-2 claims (one gave
the card name, the other the scenario), which cost the whole page. Now a scenario/option/party heading is always the place, never a card, box or list name; those go in the
subject, which is now required. `claims-v4` follows the third live run: the passes agreed on place but not on the shape of the quote (pass 1 one item per claim, pass 2 a whole list; label and number
reordered or invented), so most of page 2 stayed unestablished. Now a list is one claim per item, words are copied in printed order, and an assertion that cannot be copied exactly is left out.
`claims-v5` adds context (below): a sentence that states several values is one claim per value with the sentence as its passage, and each claim may name the
things it is about and its period, as printed.

**Trying other wording without a rebuild.** `ClaimExtractionCli --prompts prompts.json` (`{"pass1": "...", "pass2": "..."}`) or `prompts` in the route request
replaces only the wording. The run is recorded as `custom-<first 16 hex of the SHA-256 of both prompts>`, so the record still names the exact text; the text itself
is never stored or logged. The tool, the agreement rules, the verbatim-quote check and the model check stay in code. Wording that works is promoted into the code
as the next version through a normal PR. Both prompts are required, non-blank, at most 20,000 characters.

**Logging.** Per chunk and pass the engine logs the number of claims reported, the time taken, how the server says the answer ended (finish reason) and its completion tokens, and per chunk the agreed, disagreement and rejected counts. Counts and
times only; never a passage.

## Claims in context

Design: the Notion page "Claims in context: passages, values, and things resolved at read time". A value without its context ("78% students of color", no
school, no year) is right but not usable, so an established claim also keeps:

```
Passage (Phrase, role = passage) --QUOTES--> Chunk            "In 2023–24, 78% of Clark's students were students of color and 39% were ..."
   └─FOLLOWS (relation = decomposition)--> Claim (FACT item)   quote "78% of Clark's students were students of color", claimAsOf "2023–24"
                                              └─ABOUT--> Concept "Clark Elementary" (kind school, parent Portland Public Schools)
```

- **Passage:** the printed sentence, list item, card text or table row the claim's quote sits in, kept verbatim and found in the chunk. It is a Phrase that is never
  ASSERTed, keyed per Source and text, so the values of one sentence share one passage and a rerun adds nothing. The claim's own quote stays its identity, so
  agreement, replay and Re-checking are unchanged.
- **About:** the things the value describes, each a Concept identified by name + kind + parent (`db/ConceptStore.kt`). Known aliases are resolved first (PPS =
  Portland Public Schools). A namesake with another kind or parent is a separate Concept linked by `POSSIBLY_SAME` with the reason, never merged. Concepts the
  Horizon makes in conversation (kind `topic`, no `conceptKey`) are never reused for a document claim, and the Horizon never reuses a document Concept.
- **Period** (`claimAsOf`): when the value was true, as printed ("2023–24"); not when the document said it.
- **Agreement:** context is kept only where both passes give it and the chunk prints it ([ClaimAgreement.context]); a thing or period one pass leaves out, or the
  chunk does not print, is left off. Context never decides whether a claim is established. The vision reader checks the same against the page before the chunk
  is made (`docs/vision-reading.md`).
- **Read time:** `GET /debug/concepts?syntheticUserId=…&name=…` answers a name with every Concept that has it: kind, parent, `POSSIBLY_SAME` links with reasons,
  whether it was named in conversation, and how many of the user's claims (from which Sources) are about it. Nothing is chosen or merged there; the caller picks.
  `GET /debug/sources/{uid}/claims` shows each claim's `passage`, `about` and `asOf`.
- **Chat:** established document claims and passages stay out of the Context Horizon (selection and `itemsAbout`) until the Horizon can present them as the
  source's, not the user's.
- **Next:** linking the claims of the two readers that state the same value about the same thing and period (corroboration) and putting disagreeing ones into
  Re-checking. Not built yet.

## Runs, stamps, idempotency

A run has an id the caller chooses (the CLI/route make one if omitted). The `ExtractionRun` record holds: the source, the **model name the server reported on its
answers** (never the configured label), the endpoint, the prompt version, the pass count, progress and the counts. Each claim carries the id of the run that
established it, and each location link the run that found it there.

* **Model guard.** The server's reported name must contain `Qwen3.6-35B-A3B` (case and punctuation aside), or the run stops (`stopped`) before that chunk is
  written. `--expect-model S` changes the requirement; `--any-model` removes it and the run's report says `modelExpectation: null` (an unverified stamp). A
  server that reports no model name is stamped with the client's configured label, which fails the default check.
* **Same run id, finished:** answered from the stored report. No model call, no row of any kind.
* **Same run id, stopped:** continues from the chunk it reached; earlier chunks are not read again.
* **Another run id over the same Source:** adds nothing it already has.
* **Replay with a changed field.** `SourceCommitService` now remembers each item's quote, claimant and date on its link and refuses (`IllegalArgumentException`, nothing
  written) a later call that replays the item with a different one. The quote is also checked against its chunk on the replay path. Before this change such a replay
  was answered "already there". (This is the one change to shared plumbing; it is additive: links made before it carry no quote and are accepted as before.)
* Work is committed in batches of ten chunks so a stop loses at most a batch; each batch's commit allocates one cycle number.

## Re-checking

Check state is **Settled** or **Re-checking**. A later run (a different run id) moves an established claim to Re-checking, keeping it exactly as it was (quote,
claimant, chunks, establishing run) with `recheckRunId` set, when:

* its two passes did not both reproduce the claim in a chunk where it sits (and no pass failed on that chunk), or
* its passes agree on the same passage in the same chunk under a different claimant. The new attribution is retained as a disagreement; no second claim is made.

A claim stays Re-checking until a person decides: a later agreeing run does not settle it. Nothing deletes or rewrites a claim.

## Schema (additive)

New vertex types `ExtractionRun` and `ClaimDisagreement` (never Phrases, never ASSERTed, so the Context Horizon cannot surface them). New optional properties on
`Phrase` (`claimKind claimSubject claimantAsWritten claimClaimant claimQuote claimPlace claimSourceUid claimQuoteKey claimRunId checkState recheckRunId`) and on
`QUOTES` (`draftQuote draftDueAt runId`), plus indexes. Nothing is written to Notion or to chat.

## Box gate (separate from the cloud tests)

Needs, on the box: the engine running with `DEBUG_CONVERSE_ENABLED=true` and `DEBUG_CONVERSE_TOKEN`, `LOCAL_LLM_BASE_URL` pointing at the Arx llama-server
(Qwen3.6-35B-A3B), the three PPS Sources already ingested under the synthetic identity. Synthetic identity only (`--user pps-claims-test`), never owner-jacob.

```
java -cp engram-engine.jar app.alfrd.engram.scripts.ClaimExtractionCliKt --user pps-claims-test --locator gdrive:<districtwide file id> \
     --run-id pps-district-1 --aliases aliases.json
java -cp engram-engine.jar app.alfrd.engram.scripts.ClaimExtractionCliKt --user pps-claims-test --source <uid> --show claims
java -cp engram-engine.jar app.alfrd.engram.scripts.ClaimExtractionCliKt --user pps-claims-test --source <uid> --show disagreements --run-id pps-district-1
```

Order: **District wide scenarios 10.04.26** first (2 pages, 6 chunks). Only if agreement and attribution look reasonable to Jacob, SEGC Equity Audit Evaluation Report
(157 pages, 381 chunks), then SEGC School by School Analysis (130 pages, 297 chunks). No agreement threshold is built in: the report is shown and Jacob decides.

**What the box reports per run** (the run report, plus the lists): run id; the model name the server reported and the endpoint; prompt version; chunks total/done/failed
and the failure lines; claims established, already there, locations added, Re-checking; disagreements retained and quotes rejected; claims agreed over claims reported
(a diagnostic); then, for the districtwide sheet, the established claims for the A 80% / B 73% / status quo 48% row and the narrative boxes with their places and
locations, every count on page 2 with the scenario it was filed under, and every retained disagreement with both versions. Anything uncertain goes to Jacob unsettled.

Unchanged limits (reported, not fixed here): text inside the 30 school-by-school chart images is not read; Figure 6.1 on audit p. 146 is not detected as a table; Tesseract is
not installed, so scanned pages are unread and those Sources partial; Arx image input is unverified. No OCR or connector work belongs to this task.

## Known limits of the cloud work

* The model is scripted in every cloud test. They prove the contract around the model, not what Qwen answers.
* **Subject and kind are not compared**, so two passes that agree on quote, claimant and place but describe the claim differently establish it with pass 1's wording.
* **Consistent misattribution is invisible to agreement.** If both passes put a figure under the wrong scenario, and that scenario's title is not a line of the chunk, nothing
  here catches it. The heading check covers a place that is a line of the chunk; a table column is only compared between the passes.
* A very short quote (a bare "38") is located at its first occurrence for the section check, which passes if any occurrence lies in the section.
* One claim per (quote, place): a passage with a place in one run and none in another is the same claim; two places for one quote in a chunk are two claims.
* A finished run with failed chunks stays finished; to retry them start a new run id (what is already there is not repeated).
