# engram-engine

The cognitive backend for **Alfrd** — a personal AI assistant. engram-engine runs a multi-stage intent pipeline, manages user memory as a property graph, and orchestrates LLM calls for response generation.

## What it does

Each user utterance travels through four pipeline stages:

```
Utterance → Attention → Comprehension → Router → Branch → Expression → Response
```

| Stage | Responsibility |
|---|---|
| **Attention** | Pre-filter (passthrough today; reserved for interrupts/context-switching) |
| **Comprehension** | Two-tier intent classifier: fast rule engine (< 1 ms) → optional LLM escalation |
| **Router** | Maps `IntentType` to the appropriate `Branch` |
| **Branch** | Domain logic (onboarding, social, question-answering, task, clarification) |
| **Expression** | Renders a response string from a strategy-driven list of streaming phases |

Personal memory is stored in an embedded **ArcadeDB** property graph. Phrases, Concepts, Sources, Users, Scopes, and ScoreTypes are vertices; relationships are typed edges (`FOLLOWS`, `CONTAINS`, `ASSERTS`, `RELATED_TO`, `TRUSTS`, `INVITED`, `QUOTES`).

## Architecture overview

```
src/main/kotlin/app/alfrd/engram/
├── Application.kt               # Wires deps, starts Ktor/Netty
├── api/
│   ├── Routes.kt                # GET /health, GET /schema
│   └── CognitiveRoutes.kt       # POST /cognitive/chat
├── cognitive/
│   ├── CognitivePipelineFactory.kt
│   ├── SessionManager.kt        # Per-session pipeline cache (30 min TTL)
│   └── pipeline/
│       ├── CognitivePipeline.kt
│       ├── CognitiveContext.kt  # Mutable context object passed through stages
│       ├── types.kt             # IntentType, ResponseStrategy, AffectConfig, …
│       ├── Attention.kt
│       ├── Comprehension.kt
│       ├── Expression.kt
│       ├── Router.kt
│       ├── Branch.kt
│       ├── OnboardingBranch.kt  # Scaffold loop (IDENTITY → CONTEXT)
│       ├── SocialBranch.kt      # Greetings, farewells, thanks
│       ├── QuestionBranch.kt    # Graph-augmented LLM Q&A
│       ├── TaskBranch.kt
│       ├── ClarificationBranch.kt
│       ├── StubBranches.kt      # CorrectionBranch, MetaBranch
│       └── memory/
│           ├── EngramClient.kt         # Interface
│           ├── InMemoryEngramClient.kt # Dev/test implementation
│           └── HttpEngramClient.kt     # HTTP client for remote engram API
│   └── providers/
│       ├── LlmClient.kt               # AbstractLlmClient with retry + backoff
│       ├── SttClient.kt               # Flow<ByteArray> → Flow<TranscriptionResult>
│       ├── TtsClient.kt               # String → Flow<ByteArray>
│       └── cloud/
│           ├── CloudLlmClient.kt      # Anthropic + Google Gemini
│           ├── DeepgramSttClient.kt   # Deepgram Nova-3 WebSocket streaming
│           └── ElevenLabsTtsClient.kt # ElevenLabs streaming TTS
├── db/
│   ├── DatabaseManager.kt       # ArcadeDB wrapper
│   └── SchemaBootstrap.kt       # Idempotent schema init on startup
└── model/
    ├── Vertices.kt
    └── Edges.kt
```

## Tech stack

| Layer | Library | Version |
|---|---|---|
| Language | Kotlin JVM 21 | 2.1.10 |
| HTTP server | Ktor (Netty) | 3.1.1 |
| Serialization | kotlinx.serialization-json | 1.8.0 |
| Coroutines | kotlinx.coroutines-core | 1.10.1 |
| HTTP client (TTS) | OkHttp | 4.12.0 |
| Graph database | ArcadeDB (embedded) | 25.1.1 |
| Fat JAR | Shadow Plugin | 8.1.1 |
| Test runner | JUnit Jupiter | 5.12.0 |

## Getting started

### Prerequisites

- JDK 21+
- (Optional) API keys for LLM / STT / TTS providers — see [Environment variables](#environment-variables)

### Run locally

```bash
./gradlew run
```

The server starts on port `8080` by default.

### Build a fat JAR

```bash
./gradlew shadowJar
# Output: build/libs/engram-engine.jar
```

Run the JAR directly (required JVM flags are pre-configured in `gradle.properties`):

```bash
java --add-opens java.base/java.nio=ALL-UNNAMED \
     --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     --add-opens java.base/java.nio.channels.spi=ALL-UNNAMED \
     -Dpolyglot.engine.WarnInterpreterOnly=false \
     -jar build/libs/engram-engine.jar
```

### Docker

```bash
docker build -t engram-engine .
docker run -p 8080:8080 \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  -e GOOGLE_AI_API_KEY=AIza... \
  engram-engine
```

The image uses a two-stage build (`eclipse-temurin:21-jdk-jammy` → `eclipse-temurin:21-jre-alpine`) and is configured with `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`.

## Environment variables

| Variable | Used by | Required |
|---|---|---|
| `PORT` | HTTP listen port (default: `8080`) | No |
| `ANTHROPIC_API_KEY` | Claude models (Haiku 3.5, Sonnet 4.5) | No* |
| `GOOGLE_AI_API_KEY` | Gemini Flash 2.0 | No* |
| `DEEPGRAM_API_KEY` | Deepgram Nova-3 STT | No |
| `ELEVENLABS_API_KEY` | ElevenLabs TTS | No |

\* If neither `ANTHROPIC_API_KEY` nor `GOOGLE_AI_API_KEY` is set, the engine runs in rule-only mode — Comprehension stays at Tier 1 and branches that require an LLM return graceful fallback responses.

## API

### `POST /cognitive/chat`

Process an utterance through the full cognitive pipeline.

**Request**
```json
{
  "utterance": "What do I have scheduled tomorrow?",
  "sessionId": "abc123",
  "userId":    "user-456"
}
```

**Response**
```json
{
  "response":         "Understood. Let me think through that. ...",
  "intent":           "QUESTION",
  "latencyMs":        312,
  "comprehensionTier": 1
}
```

### `GET /health`

Returns uptime, database status, service version, and whether LLM API keys are configured.

### `GET /schema`

Returns all vertex and edge type names and their properties from the live ArcadeDB schema.

## Intent classification

**Tier 1** (rule-based, < 1 ms) evaluates these rules in order:

| Rule | Trigger | Intent | Confidence |
|---|---|---|---|
| 0 | Active onboarding scaffold | `ONBOARDING` | 0.95 |
| 1 | Phatic social phrases | `SOCIAL` | 0.90 |
| 2 | Correction markers ("actually", "wait, …") | `CORRECTION` | 0.80 |
| 3 | Meta patterns ("what do you know about") | `META` | 0.85 |
| 4 | Imperative task verbs (remind/send/create/…) | `TASK` | 0.70 |
| 5 | Interrogative words or trailing `?` | `QUESTION` | 0.70 |
| 6 | Active trust phase fallback | `ONBOARDING` | 0.60 |
| 7 | Default | `AMBIGUOUS` | 0.30 |

**Tier 2** fires when confidence < 0.70 or intent is `AMBIGUOUS` and an LLM key is available. Uses `GEMINI_FLASH_2_0` (fast/cheap) with a zero-shot single-word classification prompt.

## LLM models

| Model enum | Provider | Used for |
|---|---|---|
| `CLAUDE_HAIKU_3_5` | Anthropic | Tier 2 classification fallback |
| `CLAUDE_SONNET_4_5` | Anthropic | OnboardingBranch, QuestionBranch |
| `GEMINI_FLASH_2_0` | Google | Tier 2 classification (preferred) |
| `GEMINI_FLASH_2_0_LITE` | Google | Reserved |

All LLM calls share a retry policy (up to 2 retries, 100/200 ms exponential backoff) with timeout isolation — a `LlmTimeoutError` does not retry.

## Graph schema

**Vertices**: `Phrase`, `Concept`, `Source`, `User`, `Scope`, `ScoreType`

**Edges**: `FOLLOWS`, `CONTAINS`, `ASSERTS`, `RELATED_TO`, `TRUSTS`, `INVITED`, `QUOTES`

All vertex types have a unique index on `uid`. `Phrase` has an additional index on `hash`; `Concept` on `normalizedName`. The schema is bootstrapped idempotently on every startup — no migration tool required.

## Tests

```bash
./gradlew test

# Force recompile (avoids stale incremental cache)
./gradlew test --rerun-tasks
```

| Suite | Coverage |
|---|---|
| `DatabaseManagerTest` | Schema bootstrap, vertex/edge CRUD |
| `ComprehensionTest` | All 8 Tier 1 rules, scaffold override priority |
| `ExpressionTest` | SOCIAL / SIMPLE / COMPLEX / EMOTIONAL rendering |
| `CognitivePipelineIntegrationTest` | End-to-end routing for every intent type |
| `MemoryBridgeIntegrationTest` | Onboarding scaffold loop, QuestionBranch memory injection, LLM timeout fallback |
| `LlmClientTest` | Retry policy, backoff timing (virtual time), timeout no-retry |
| `DeepgramSttClientTest` | Frame parsing, WebSocket integration |
| `ElevenLabsTtsClientTest` | Streaming PCM, multi-chunk, error handling |

## Graceful degradation

Every layer degrades rather than failing:

- **No API keys** → rule-only pipeline (no Tier 2 classification, stub LLM branches)
- **LLM timeout** → `OnboardingBranch` uses hardcoded fallback questions
- **No graph context** → `QuestionBranch` answers from general LLM knowledge
- **Unimplemented HTTP endpoints** → `HttpEngramClient` falls back to local in-memory heuristics
