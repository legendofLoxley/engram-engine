# engram-engine

The cognitive backend for **alfrd** — a stateful personal AI assistant. engram-engine maintains a per-user memory graph and runs the modality-agnostic conversational brain.

## The current model

```
Input → Director → Script → Actor → Memory write → Render
```

- **Director** — attention, comprehension, routing, and branches. It produces non-linguistic directives (retrieval intent, pacing, attunement, and response strategy); it never writes the reply.
- **Script** — retrieves and prepares graph grounding: relevant phrases/facts, corrections, persona, and confidence context.
- **Actor** — the sole writer of user-facing language. A stateless LLM call receives only the utterance, retrieved script, and conditioners.
- **Memory write** — persists new information and learning signals after the turn.
- **Render** — text today; STT/TTS, streaming, latency hedges, and turn-taking are voice I/O concerns.

There is one writer per semantic unit: the actor. Phrase pools and graph retrieval condition the actor; they never emit user-facing text directly.

## Brain vs. I/O

The cognitive brain is shared between text and voice. The active modality changes identity and rendering, not the cognitive model.

| Brain (shared) | I/O (modality-specific) |
|---|---|
| Director state, routing, attunement | STT |
| Memory read/write and graph retrieval | TTS |
| Persona and per-topic confidence | Streaming and latency hedges |
| Single LLM actor call | Turn-taking and barge-in |
| Mood and pacing directives | Text vs. spoken rendering |

The actor is pluggable: frontier models can be replaced with smaller or local models without changing the director or memory graph.

## Memory and conditioning

Personal memory lives in an embedded **ArcadeDB** property graph. Core vertices include Phrase, Concept, Source, User, Scope, and ScoreType; typed edges include FOLLOWS, CONTAINS, ASSERTS, RELATED_TO, TRUSTS, INVITED, and QUOTES.

The actor receives relevant graph material as grounding (not verbatim output), a modality-correct persona, attunement, topic confidence, and session mood.

- **Topic confidence** is earned epistemic confidence attached to a user/concept relationship. It affects honesty about knowledge, never tone.
- **Mood** is a separate session-level tone state (GUARDED through PLAYFUL), explicitly overridable or slowly adjusted from recent outcomes.
- Confidence grows through demonstrated competence, explicit affirmation, and resolved corrections. An unresolved contradiction marks temporary uncertainty but does not lower accumulated confidence.

## Architecture overview

```
src/main/kotlin/app/alfrd/engram/
├── Application.kt
├── api/
│   ├── Routes.kt                # GET /health, GET /schema
│   └── CognitiveRoutes.kt       # POST /cognitive/chat
├── cognitive/
│   ├── CognitivePipelineFactory.kt
│   ├── SessionManager.kt
│   └── pipeline/
│       ├── CognitivePipeline.kt # Turn orchestrator
│       ├── Actor.kt             # Sole user-facing writer
│       ├── Script.kt            # Centralized retrieval/grounding
│       ├── Attention.kt
│       ├── Comprehension.kt
│       ├── Router.kt
│       ├── Branch.kt            # Directives, not copy
│       ├── posture/             # Attunement and pacing
│       ├── affect/              # Session mood
│       ├── confidence/          # Per-topic confidence
│       ├── memory/
│       └── scaffold/
│   └── providers/               # LLM, STT, TTS, cloud clients
├── db/                          # ArcadeDB and schema bootstrap
└── model/
```

## Tech stack

| Layer | Library |
|---|---|
| Language | Kotlin/JVM 21 |
| HTTP server | Ktor / Netty |
| Graph database | ArcadeDB (embedded) |
| LLMs | Anthropic Claude and Google Gemini |
| Voice I/O | Deepgram STT and ElevenLabs TTS |
| Tests | JUnit Jupiter |

## Run locally

Prerequisites: JDK 21+. Provider API keys are optional for local development.

```bash
./gradlew run
```

The server listens on port 8080 by default. Build a runnable JAR with:

```bash
./gradlew shadowJar
java -jar build/libs/engram-engine.jar
```

Or use Docker:

```bash
docker build -t engram-engine .
docker run -p 8080:8080 -e ANTHROPIC_API_KEY=sk-ant-... engram-engine
```

## Configuration

| Variable | Used by |
|---|---|
| PORT | HTTP listen port (default: 8080) |
| ANTHROPIC_API_KEY | Claude actor and classification calls |
| GOOGLE_AI_API_KEY | Gemini classification fallback |
| DEEPGRAM_API_KEY | Deepgram STT |
| ELEVENLABS_API_KEY | ElevenLabs TTS |
| DB_PATH | ArcadeDB path (default: `./data/engram-db`) |
| SPACES_ACCESS_KEY | DigitalOcean Spaces access key, for hosted graph persistence |
| SPACES_SECRET_KEY | DigitalOcean Spaces secret key |
| SPACES_BUCKET | DigitalOcean Spaces bucket name for snapshots |
| SPACES_ENDPOINT | DigitalOcean Spaces endpoint, e.g. `https://nyc3.digitaloceanspaces.com` |
| SPACES_REGION | Region passed to the S3 SDK (default: `us-east-1`; DO Spaces ignores the value but the SDK requires one) |
| SNAPSHOT_ENCRYPTION_KEY | Base64 AES-256 key for snapshot encryption — generate with `openssl rand -base64 32` |

If no LLM provider is configured, the actor returns one explicit degraded response rather than allowing branches or phrase pools to write a substitute reply.

Hosted graph persistence (restore-on-boot + periodic snapshots to DigitalOcean Spaces) requires all five `SPACES_*`/`SNAPSHOT_ENCRYPTION_KEY` variables to be set together. If any are missing, persistence is disabled and the app runs exactly as it did before this feature existed — an embedded ArcadeDB with no durability across redeploys. See `GraphBackupCoordinator.kt`/`GraphRestore.kt` and the [Memory Custody & Portability](https://app.notion.com/p/3ccd0721d4e48105adaafa52a86855bf) design doc for the durability guarantee (5-minute recovery-point objective, bounded-loss recovery, hard-fail on an invalid-but-present snapshot rather than a silent empty-graph fallback).

## API

### POST /cognitive/chat

Processes a text turn through the Director → Script → Actor path.

```json
{
  "utterance": "What do I have scheduled tomorrow?",
  "sessionId": "abc123",
  "userId": "user-456"
}
```

The response includes generated text, resolved intent, latency, and comprehension tier.

### GET /health

Returns uptime, database status, service version, and provider configuration.

### GET /schema

Returns vertex and edge types and their properties from the live ArcadeDB schema.

## Tests

```bash
./gradlew test
./gradlew test --rerun-tasks
```

The suite covers graph schema and CRUD, routing/classification, actor prompt conditioning, retrieval and corrections, modality-correct identity, attunement, topic confidence, mood, and multi-turn stability.

## Graceful degradation

- No LLM client or actor failure → one centralized, clearly labeled degraded response
- Missing graph context → the actor receives no grounding rather than invented graph output
- Retrieval or memory-write failures → handled without crashing the turn
- Voice infrastructure remains isolated behind the modality boundary; text does not claim voice capabilities