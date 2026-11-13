# Graph schema: integrity, history and constraints

What the graph enforces about itself, what it remembers about itself, and how to check it. Everything here is additive and
idempotent: `SchemaBootstrap` runs against the live graph at every engine start and only adds what is missing.

## Types the Horizon reads

`Phrase` is the supertype of the items: `Phrase` itself is a **fact**, and `Task`, `Event` and `Question` are subtypes. Containers are
`Domain` and `Project`. All of that is listed once, in code, in the **item kind registry**
(`cognitive/pipeline/horizon/ItemKinds.kt`): for each `ItemKind` its vertex type, whether it carries `state` and the time fields, its Horizon
label, the property its time lives in (`dueAt` for a task, `occursAt` for an event) and which scans include it
(`urgency`, `open-unfiled`, `state`, `also-due`, `status-on-item`, `open-only listing`). A new kind is added there and nowhere else;
`ItemKindsTest` fails if a kind has no answer for any list, or if a kind literal appears in `src/main` outside the registry, the schema, the
change marker and the Notion agent-work vocabulary.

A Phrase subtype the registry does not know still reads as a fact, so nothing breaks, but it is **logged once** (`item-kinds: vertex type 'X' is not
in the item kind registry`) and listed by audit check **A7**.

## Constraints (`SCHEMA_ENFORCE_CONSTRAINTS`, default off)

With `SCHEMA_ENFORCE_CONSTRAINTS=true` the bootstrap also asks ArcadeDB (25.1.1: `mandatory`, `notNull`, `readonly`, `regexp` and unique indexes are all
supported and enforced on write) to enforce the graph's own rules. Each rule is applied **only if no existing row breaks it**; a rule that
would be broken is skipped with a warning naming the count and a few uids, and startup never fails. Why: a row that breaks a
constraint can never be saved again, not even to change another field.

| Rule id | What it enforces |
|---|---|
| `uid-mandatory:<Type>`, `uid-unique:<Type>` | every type that declares `uid` (Phrase, Concept, Source, Chunk, User, ScoreType, Scope, ResponsePhrase, Utterance, Domain, Project, ExtractionRun, ClaimDisagreement, EntityAlias). Unique on `Phrase` holds across the whole hierarchy: a Task and an Event cannot share a uid. The existing non-unique index on `uid` is replaced (and put back if the unique one cannot be built). |
| `mandatory:Domain.ownerEmail`, `mandatory:Project.ownerEmail` | containers have an owner |
| `mandatory:Domain.createdAt`, `mandatory:Project.createdAt`, `mandatory:Phrase.createdAt` | creation time (Phrase's covers Task, Event and Question) |
| `readonly:Phrase.sourceQuote` | the user's own words are written once, with the item |
| `readonly:Phrase.directorGoalUid`, `readonly:Phrase.directorCycle` | what the Director wrote says which goal and cycle on the item, written once with it: no later write moves an item in or out of a retraction set (see `director-source.md`) |
| `state-vocabulary:Task/Event/Question/Goal` | `state` is one of `open, active, waiting, done, dropped` when it is set (absent is allowed: older rows) |
| `approval-vocabulary:Goal` | `Goal.approval` is `proposed` or `approved` |
| `mandatory:Goal.approval` | a goal is never written without an approval: it is `proposed` when made, and only the owner's approval (a quoted `approve_goal`) makes it `approved`. An approved goal is **frozen**: its sentence, `doneWhen`, `stopWhen`, `budget` and project do not change, and a done or stopped goal is final (see `horizon-board.md`, "The rules for a goal"). Those rules are enforced in code, not by a constraint, because a constraint cannot tell a first write from a later one. |

Left out on purpose:

- **`ownerEmail` mandatory on Task/Event/Question.** The property is declared on `Phrase`, a subtype cannot redeclare or loosen it, and `Phrase` has
  legitimately ownerless rows (ordinary conversational ingest owns by the `User -TRUSTS-> Source -ASSERTS-> item` chain, not a stamp). Making it
  mandatory on `Phrase` would reject `DatabaseEngramClient.ingest`, `ingestEnvironmentSignal` and `ingestActorEvent`.
- **`text` read-only.** `DatabaseEngramClient.amendPhrase` (called from `Script.kt`, the conversational correction path) overwrites `Phrase.text` in
  place. Making it read-only would make corrections fail silently. Fixing that is a design change (corrections as supersession), not a constraint.
- **`state` mandatory.** Rows written before `state` existed have none (they read their `status`).

Turning the flag off later does not remove constraints already in place. Constraints persist in the schema and in backups.

## The audit (read-only)

`SchemaAudit` (`db/SchemaAudit.kt`) counts what the schema does not enforce but the code assumes, with the first few uids per finding:
A1 vertices without a uid, A2 duplicate uids (per distinct row; a query on a Phrase subtype returns inherited duplicates), A3 items with no
`ownerEmail`, A4 `ownerEmail` disagreeing with the TRUSTS→ASSERTS chain, A5 items with no ASSERTS from a Source in the owner's trusted set
(passages, which are never ASSERTed by design, are A5i), A6 null or unknown `state`, A7 Phrase rows of an unregistered type, A8 dangling
edges, A9a/b/c/d filing (missing Project or Domain, several parents, a PART_OF joining two owners), A10–A13 missing owner, creation time or text.

Run it against a **scratch restore**, never against a directory the engine has open:

```bash
./gradlew schemaAudit -PauditDb=/path/to/restored/engram-db -PauditExamples=5 -q
```

It opens the database read-only. The restore itself is in `graph-backup-restore.md`.

The Director tick runs the same audit **at most once per 24 hours** and writes a short report to `~/alfrd/director-tick/integrity.md` (the tick's output folder:
counts and the first three uids per finding; the time of the last run is its `ran:` line). It is read-only, and it does not touch the tick's prompt,
`latest.md`, `tick-*.md`, the change marker or the tick's counters. `DIRECTOR_INTEGRITY_CHECK=off` disables it.

## Change records

Item state used to be overwritten in place. `ChangeRecord` is an **append-only** vertex that keeps what it was.

- Properties: `uid, ownerEmail, targetUid, targetType, family (change | retraction), field, oldValue, newValue, changedAt, cause, actor, reason?`
- Linked by `target -HAS_CHANGE-> ChangeRecord` (`bidirectional=false`; read outward, or with SQL on `targetUid`). Not a Phrase and not an item, so no
  Horizon scan sees it. Not named `Event`, which is the world-event item type.
- Written **in the same transaction** as the change it describes, only for the fields in `ChangeTracking` (one short opt-in list):
  `state, dueAt, dueKind, waitingOn, nudgeAt, attention`, plus the edges `PART_OF` (re-filing) and `FOCUSES_ON` (release; recorded before the edge is deleted).
  An unchanged value writes nothing. `updatedAt`, `lastEvidenceAt` (confirming an item is not a change), hashes, index keys, run ids and the coarse `status` are never recorded.
  For an event the stored `occursAt` is recorded as `dueAt`.
- `cause` is the verb being applied (`set_state`, `file_item`, `correct_item`, ...; the operation applier sets it), else the store operation's own name
  (`set_state`, `set_due`, `release_focus`, ...). `actor` is the owner's identity unless a `ChangeContext` names another.
- Append-only at the database level: every property is read-only once written (an UPDATE is rejected) and a delete or update is vetoed by a listener
  registered at every start. In code `ChangeLog` has `append` and `forTarget` and nothing else. This type is new, so these constraints do not depend on the flag.
  Neither stops someone replacing the database files.
- Reading: `HorizonStructureStore.changesFor(userEmail, targetUid)`, which returns nothing for a target the caller does not own.
- Records stay when an item is superseded (`correct_item` closes the old item and links the new one; the old item keeps its history).
- The `retraction` family is the record shape only: nothing writes it and nothing reads the family yet.
- `pruneNudges` also deletes `FOCUSES_ON` edges (nudges fading); that is housekeeping and is not recorded.

## Progress edge

`item -PROGRESS_OF-> item` (`createdAt, ownerEmail, cycleSeq, writer`; no score, no strength, no decay). `HorizonStructureStore.addProgress`,
`progressOn(toUid)` and `progressBy(fromUid)` create and list it; both ends must be items the caller owns, the pair is idempotent, and a link whose
other end is not the caller's is left out of a listing. No verb creates it and the Horizon does not show it.

## Edges created with `bidirectional=false`

Documented once, with the table and the `@in`/`@out` SQL that reads them backward, in the header of `HorizonOwnership.kt`.

## Write paths outside `HorizonStructureStore` (audit, 2026-11-08; report only)

Every `newVertex`, `newEdge`, `CREATE`, `INSERT`, `.save()` or `DELETE` in `src/main` other than `HorizonStructureStore`:

| File:line | Writes | Enforces ownership? |
|---|---|---|
| `api/OnboardingService.kt:98–256` | User, `INVITED`, Source, `TRUSTS`, seed Phrases + `ASSERTS`, `FOLLOWS` | Creates the owner and its chain; Phrases carry no `ownerEmail` |
| `cognitive/UserGraphService.kt:88, 212` | User, `VERIFIED` User→Jacob | Creates the owner; n/a |
| `api/DebugConverseService.kt:48, 90, 140–145` | synthetic User, UserScaffoldState; purge deletes SELECTED/OUTCOME/UserScaffoldState/User | Debug fixture; purge guard exempts protected identities |
| `api/ScaffoldRoutes.kt:183–194` | UserScaffoldState | Keyed by the caller's `userId`; no `ownerEmail` |
| `cognitive/pipeline/memory/DatabaseEngramClient.kt:93–120` | Source, `TRUSTS`, Phrase, `ASSERTS` (conversational ingest) | Via the user's own Source; Phrase stamped with the owner's `ownerEmail` (since schema-cleanup phase 1) |
| `…/DatabaseEngramClient.kt:155` (`amendPhrase`) | rewrites `Phrase.text`, `hash`, `updatedAt` by uid | **No ownership check at all** (uid only), and no history |
| `…/DatabaseEngramClient.kt:217–223, 255–281, 355` | `CONFIDENT_IN`, Utterance + `FOLLOWS`, Concept | User by email / `userId` stamp; Concept is global |
| `cognitive/pipeline/horizon/RequestLedger.kt:131–149` | ProcessedRequest (+ bounded delete) | Scoped by `userEmail` property |
| `cognitive/pipeline/horizon/HorizonGraphStore.kt:266–275, 412–423` | Phrase + `ASSERTS` (environment signal, actor event) | Via the user's own Source; Phrase stamped with the owner's `ownerEmail` (since schema-cleanup phase 1) |
| `…/HorizonGraphStore.kt:526, 656, 698–705` | `ASSERTS` status history; Source + `TRUSTS` | Yes: `ownedAssertsEdges` before the edge is touched; sources found through the user's `TRUSTS` |
| `…/RelatedToEdges.kt:26, 42` | `RELATED_TO` (`relevant_to`, `supersedes`) | Stamped with `ownerEmail`; callers check both ends (`findPhraseOwnedByUser`) |
| `cognitive/pipeline/selection/ResponseSelectionService.kt:337, 387` | `OUTCOME`, `SELECTED` User→ResponsePhrase | By `userId`; the phrase is global seed content |
| `director/OwnerHorizon.kt:360–371` | Utterance ledger + `FOLLOWS` | The configured owner identity only |
| `db/AliasRegistry.kt:34, 58` | EntityAlias, its `active` flag | `ownerEmail` on the row |
| `db/ResponsePhraseSeed.kt:268–307` | system Source, ResponsePhrase, `ASSERTS` ResponsePhrase→Source (reversed) | Global seed; no owner |
| `db/ConceptStore.kt:100–252` | Concept (global), `POSSIBLY_SAME`, `ABOUT` | Edges carry `ownerEmail`; **`linkAbout` loads the Phrase by uid without checking the owner** (callers do) |
| `db/SourceStore.kt:135–331` | Source, Chunk, `TRUSTS`, `CONTAINS`, `QUOTES`, `supersededBy`, `issuer`, `opKey` | Yes: `ownerEmail` on every row and query; `linkItem` uses `findPhraseOwnedByUser` |
| `claims/ClaimLinker.kt:83` | `CORROBORATES`, `CONFLICTS_WITH` | Yes: both ends through `findPhraseOwnedByUser` |
| `claims/ClaimStore.kt:55–220` | ExtractionRun, ClaimDisagreement, passage Phrase, `QUOTES`, `FOLLOWS`, `claimAsOf`, `checkState` | `ownerEmail` on every row; chunk checked by owner; items via `findPhraseOwnedByUser` |
| `cognitive/pipeline/horizon/ChangeRecords.kt` (new) | ChangeRecord, `HAS_CHANGE` | Owner stamped by the store that already checked the target |

## Notion vocabulary conflict (reported, not changed)

`AgentWorkSchema.kt` (`GoalStatus`) carries two Goal Status vocabularies: the live workspace (Draft, Queued, Active, Met, Abandoned, Expired) and the converged design (Draft, Active, Done, Stopped).
Item `state` is a third (`open, active, waiting, done, dropped`). `Active`, `Done` and `Dropped`/`Stopped`/`Abandoned` mean nearly the same thing in different places. The goal kind should
pick one before it is built.

## Item lines in the Horizon prompt: the deadline wording

An item's line is `"<text>" [state] — <why it is here>[; <deadline>][; no evidence since <date>]`. *Why it is here* is the surfacing reason, which is the
highest score component (ties go to the more specific one: a mention or activation ahead of urgency). The **deadline wording does not depend on that
reason**: an open or active item with a due date that is overdue, due today, or inside its also-due window (hard: `alsoDueHardDays`, soft:
`alsoDueSoftDays`; the same `HORIZON_ALSO_DUE_*` settings the also-due block uses) carries the same text an `Urgent` item has (`overdue by 78 days
(2026-08-23, soft)`, `due today (...)`), after the reason. An `Urgent` item is not given it twice, an item with no due date, a closed item and an event that
has already started are unchanged, and the soft/hard label always shows. Scores, the tie-break and which items are selected are untouched. Before this, a
mentioned overdue task read only "connected to what they have been talking about (mentioned)". (Code: `Actor.kt`, `deadlineNote`.)

## Ownerless rows that remain

The conversational, environment-signal and actor-event ingest paths stamp `ownerEmail` on the Phrase they create. Existing rows are not backfilled (the
live audit still reports them as A3). `OnboardingService.seedBatch` still writes Phrases with no `ownerEmail`: the per-user "personal" seed Phrases, and the
shared public seed Phrases (ownerless on purpose: shared content, audit A3i). `ChangeMarker`'s `Phrase` count is by `ownerEmail`, so new conversational facts now
also move it (they already moved `Asserted`).
