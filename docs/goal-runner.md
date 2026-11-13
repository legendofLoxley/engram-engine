# The goal runner (goals, phase C)

The goal runner works one approved goal at a time on the box's idle compute. It is **code, not the model**: it chooses the goal, moves its state, keeps the budget, decides when to stop, and writes only through `DirectorWriter` (phase B). The models plan, do steps and judge; none of them sees a uid, a verb or a state, and none can move a goal.

It hangs off the Director tick (`DirectorTick`): after each tick it may start a cycle in the background. The Notion cycle code (`CyclePlanDirector`, `DirectorTaskExecutor`, `CycleQcDirector`) is unchanged and stays in the code; `NOTION_CYCLES_POLLING=off` switches its polling off.

## Switches

Off by default. Everything is an environment variable; `GoalRunnerConfig.fromEnvironment` is the one place that reads them, and a nonsense value falls back to the default rather than turning a guard off.

| Variable | Default | Meaning |
|---|---|---|
| `GOAL_RUNNER_ENABLED` | `false` | `true` lets the tick start cycles when the box is idle. The single-shot trigger works with it off. |
| `GOAL_RUNNER_KILL_FILE` | `~/alfrd/goal-runner/STOP` | While the file exists no cycle starts, and a running one stops at the next check. Remove it to allow work again. No restart needed. |
| `NOTION_CYCLES_POLLING` | on | `off` (or `false`, `0`, `no`) stops the Notion cycle channel polling. |
| `GOAL_RUNNER_IDLE_MINUTES` | 10 | Quiet time (no conversation) before a cycle may start. |
| `GOAL_RUNNER_MIN_GAP_MINUTES` | 30 | Least time between two cycles of one goal. |
| `GOAL_RUNNER_PLAN_URL` | `http://127.0.0.1:8083/v1` | Plan and QC model. |
| `GOAL_RUNNER_STEP_URL` | `http://127.0.0.1:8081/v1` | Step model. Needs two slots; the runner uses one. |
| `GOAL_RUNNER_MAX_STEPS` | 8 | Most steps a plan may have. |
| `GOAL_RUNNER_REPLAN_CAP` | 2 | Most times a major impact may replan before the goal is paused. |
| `GOAL_RUNNER_DEFAULT_MINUTES`, `GOAL_RUNNER_DEFAULT_TOKENS` | 30, 60000 | The budget of a goal that names none. |
| `GOAL_RUNNER_MIN_FREE_RAM_MB`, `GOAL_RUNNER_MIN_FREE_GPU_MB` | 4096, 2048 | No cycle starts below these. |
| `GOAL_RUNNER_REASONING` | `on` | Whether the plan and QC calls let the model reason. See "Thinking". |
| `GOAL_RUNNER_STEP_REASONING` | `on` | The same for step calls, so reasoning can be off for steps while it stays on for plan and QC. |
| `GOAL_RUNNER_STEP_MAX_TOKENS`, `GOAL_RUNNER_STEP_TIMEOUT_SECONDS` | 16000, 900 | The most a step may generate (reasoning counts against it) and wait. |
| `GOAL_RUNNER_MIN_EVIDENCE_SCORE` | 1.5 | The least relevance for a closed item to count as evidence. |
| `GOAL_RUNNER_MIN_SHARED_WORDS` | 2 | The fewest meaningful words a closed item must share with the open one. A shared concept that is not common in the project counts instead. |
| `GOAL_RUNNER_CONCEPT_EVIDENCE` | `on` | Whether a shared concept alone can make a closed item evidence. `off` is the strictest bar (words only). |
| `GOAL_RUNNER_VERIFIER_MAX_TOKENS` | 6000 | The most one verifier call (one done or dropped claim) may generate, reasoning counts. |

The runner is built when `DIRECTOR_TICK_IDENTITY` is set and `LLM_PROVIDER=local`. It works the tick's identity's goals.

## Idle

A cycle may start only when all of these hold. The single-shot trigger waives the first and nothing else.

1. No conversation for `GOAL_RUNNER_IDLE_MINUTES`: the owner's Horizon tool, a chat turn, or a debug conversation (`ConversationActivity`, a timestamp and nothing else).
2. No Hermes assignment in flight.
3. The kill file does not exist.
4. Free RAM at least the floor (`MemAvailable`), and, where it can be read (AMD VRAM through sysfs), free GPU memory at least the floor. A figure the box cannot read never blocks; a known low one always does.
5. The step server has at least two free slots, so the runner takes one and conversation always keeps one. The plan server needs a free slot.

While a cycle runs it re-checks 1 to 3 and the step server's other slot between steps, and every 1.5 s during a model call. When any fails it cancels the call (closing the connection, which stops llama.cpp generating) and **yields**: the cycle is closed as `yielded` (a quiet message that carries what it used, and a change record `running` to `yielded`, actor `director`), the goal **keeps running**, and the next idle window tries again; the 30-minute gap between cycles does not apply after a yield. What the cycle wrote before stays and `retract_cycle` removes it.

## State

The runner may do two things to a goal's state, and the type that does them (`RunnerState`) has no way to say anything else: approved to running (`active`) at the start, and running to paused (`waiting`). It pauses a goal for exactly four reasons: the budget is spent, the replan cap is hit, **two cycles in a row failed** (no plan, no usable step answer, QC could not run, an exception), or the engine restarted mid-cycle. A single failed cycle sends a `failure` message and leaves the goal running; an interruption by a conversation is a yield, not a pause. Every move has a change record whose actor is `director` and whose cause is `goal_runner:<reason>`. Every cycle also ends with exactly one closing message (`completed`, `yielded`, `failed` or `paused`) with a change record of its own.

It never marks a goal done or stopped. When QC says the done-when looks met (and none of its sampled items was found unsupported) it sends a `finished` message; the owner closes the goal through the channel. A goal whose latest message is `finished` is not picked again.

A goal is picked only if it is approved, open or running, filed under an active project, and has no `finished` message waiting. Proposed, paused, stopped and done goals are never picked. Oldest approval first, one at a time.

## The cycle

1. **Plan** (strong model). Reads the goal, its done-when, the project's **open** items (as refs `i1`, `i2` ... so a model never sees a uid), project-level counts of open items, closed items and notes (so it knows evidence exists), what the Director has already written, and the last cycle's summary. Writes at most `GOAL_RUNNER_MAX_STEPS` steps, each naming the open items it works. The planner is **not** asked to name evidence. Code drops a step that is malformed, too long, repeats an earlier one, names no open item (except an `ask` step), asks to delete or overwrite something (`ForbiddenActions`, from the Notion cycle), or names a ref it was not shown. A plan whose every step was rejected is a failed plan. Work the graph already covers is skipped: the planner is shown the Director's earlier items, and a step's item whose text matches an item already in the project is counted as covered and not written.
2. **Execute** (fast model). For each open item a step covers, **code retrieves up to four related closed items and notes of the same project** (`GoalEvidence`) and hands them to the step as labelled evidence with refs (`e1`, `e2` ...). They are ranked by shared meaningful words (weighted by how rare the word is in the project's evidence), shared concepts (`about` links), and a small recency bonus that only breaks ties; an item that shares no word and no concept is not evidence. Evidence is the owner's closed (done or dropped) items and notes, never the Director's own writing and never a goal. Each step's prompt has a budget (`stepPromptChars`, 12,000): evidence is trimmed from the weakest end, then items are left out, and the prompt says what was left out; a ref that was left out cannot be cited. The answer is parsed field by field: at most five items, kinds `note`, `task` or `question` only, text cut to 400 characters, each item naming the open item it concerns (`about`) and at most three evidence lines that support it (`support`), both checked against what the step was shown; state suggestions limited to four states. Anything else in the answer (an `operations` list, a `verb`, a uid) is never read. Items are written through `DirectorWriter`, so its guard decides every write, and each is linked to the items it cites.
   **Claims are checked in code, for every setting.** A disposition (`done`, `dropped`, `waiting`, `live`, `unclassifiable`) is a field of the step's item; a claim written into the text without the field is still treated as a claim: an explicit form ("Proposed disposition for i4: done", "Mark i7 done"), or any text that names an open item and says it is done or dropped with no word of doubt in it (a sentence such as "no direct evidence this is complete" is not a claim). `done` stands only if the item cites at least one evidence line that was shown **with that open item** and is itself a **done** item; `dropped` only with a **dropped** item (supersession is not read); `waiting` and `live` need a cited related line or an explicit `no_evidence_found`. An open item or a note does not support done or dropped, and neither does a ref the step was not shown. A claim that does not stand is written as "Unclassifiable, no supporting evidence for <item>", with the model's reasoning kept as a separate note labelled "Unverified reasoning". Suggested state changes follow the same rule (an unsupported one is not written). What this cannot judge is whether a shown, qualifying done item really says what the model claims: a weakly related done item can still pass, so the evidence relevance below matters.
   **Evidence relevance.** A closed item is evidence only if its relevance (shared meaningful words and shared concepts, each weighted by how rare it is in the project's evidence) is at least `GOAL_RUNNER_MIN_EVIDENCE_SCORE`. Words and concepts that appear in more than a quarter of the project's items (once it has 20) are ignored, so the project's own name connects nothing. When nothing qualifies the step is told "NO EVIDENCE FOUND" in so many words, so the honest answer is available.
   **Code-side rules.** A plan step whose title or purpose is to show, send, present or hand a list to the owner is rejected (the close-out and the channel do that). A question is cut to 300 characters and cut back to its first ask when it is a list, and the cut is flagged in the step's report (`askCut`).
   **Limits and failures.** A step may generate up to `GOAL_RUNNER_STEP_MAX_TOKENS` and wait `GOAL_RUNNER_STEP_TIMEOUT_SECONDS`. When a step hits a limit or returns nothing, its report carries the reason in plain words (`failureReason`) and the exact error text, cut to 300 characters (`error`; kept in the debug report, not in the logs, because a provider's message could echo a prompt).
   **The verifier (done and dropped claims).** A done or dropped claim that passes the structural check is *held*: nothing about it is written during the step. After the last step and before QC, the runner makes **one call to the strong model per held claim** (reasoning as for plan and QC), each its own call so a hostile line reaches only the claim it is cited for. The verifier is shown the open item's text and each cited line, fenced as untrusted data, and the question; it is not shown the step's reasoning or any uid. It answers `supports`, `does not support` or `unclear`, and quotes the sentence that shows it. **Code decides** (`GoalVerification.judge`): the claim is kept only if the answer is `supports`, the quote is at least 12 characters, and the quote is a verbatim substring (whitespace folded and surrounding quotation marks removed; case and wording must match) of a cited line shown for that claim. A kept claim is written as the step wrote it, with the verified quote on its source for QC. Any other claim, or a claim whose verifier call failed, is written as "Unclassifiable, no supporting evidence for ..." and the step's reasoning is kept as a note labelled "Unverified reasoning", with the verifier's reason in it. A state suggestion (done or dropped) is held and judged the same way and is not written when it fails. The cost is reported per cycle (`verifier` in the run report: claims, kept, downgraded, seconds, tokens; `claimChecks`: one line per claim with the answer, the quote and where it was found) and in the cycle's closing message. Claims still held when a cycle stops early (budget, replan cap, failure, yield) are not written. The verifier can only be fooled by a model that is fooled *and* copies a real sentence out of a cited line; it cannot make code accept an invented quote, a quote from the open item, or a line the step was not shown.
3. **Gate.** The step reports its impact on the plan: none, minor or major. Major replans, up to the cap, then the goal is paused and a `failure` message is sent. Code raises a step to major when most of its writes were refused.
4. **QC** (strong model). Recomputes against the done-when, judges a sample of the new items against their sources (the owner's item each one concerns), writes a summary message, and records what the goal left unclear as a Director note.

## Messages

`DirectorMessage` (phase B): questions (at most two a cycle), failures, finished goals, and a quiet progress message that closes every cycle and carries what the cycle used. Messages are capped at 600 characters and are shown to the channel under the "From the Director" heading that labels them as written by a model from material that may not be trusted. Routine progress stays quiet unless `HORIZON_DIRECTOR_PROGRESS` is set.

## Budget

A goal's `budget` text is read each time ("30 minutes, 50k tokens", "2 hours"); what it does not say falls back to the defaults. Tokens are the completion tokens llama.cpp reports plus prompt tokens estimated from characters (it reports none for the prompt). Minutes are wall-clock minutes of runner work. Usage is summed over all of a goal's cycles from the closing message of each; a spent budget pauses the goal before a cycle starts, and a cycle stops between steps when it runs out.

## Cycle numbers

The runner assigns them (`DirectorCycles.begin`): one past the highest the goal has carried on a message or an item, retracted ones included, so a number is never reused. The start is recorded on a quiet message, so even a cycle that wrote nothing has used its number. `DirectorWriter`'s public methods take a `DirectorCycle`, and the ones that take a bare number are `internal`. Both are only *module*-internal: `DirectorCycle`'s constructor is `internal`, and the engine is one Gradle module, so any code in it can construct one. "Only the runner can issue a cycle" therefore holds by convention (the runner is the only caller of `DirectorCycles.begin` and of the constructor), not because the compiler stops other code.

## Restart

At startup, a goal that is running and whose latest cycle never ended (no closing message) is paused, with a `failure` message and a change record (`goal_runner:restart_recovery`). It is never resumed. A goal that is running between cycles is left as it is. Recovery takes the same gate a cycle does, so it cannot pause a cycle that began a moment ago.

## A scratch engine

To try a cycle without touching a live file, run `deploy/scripts/scratch-engine.sh [backup.zip]`. It restores the newest backup into a new mode-700 directory and runs the engine on port 8095 with `env -i`, `-Duser.home` pointing at the scratch directory (the JVM ignores `$HOME`, and the Director tick, the owner token and the kill file all default to the home directory), and fresh random tokens, then removes everything when it gets SIGTERM. The environment block it sets, if you would rather run it by hand:

```bash
env -i PATH=/usr/bin:/bin HOME="$SCR" PORT=8095 HOST=127.0.0.1 DB_PATH="$SCR/engram-db" \
  DEBUG_CONVERSE_ENABLED=true DEBUG_CONVERSE_TOKEN="$(cat "$SCR/debug-token")" SCHEMA_ENFORCE_CONSTRAINTS=true \
  LLM_PROVIDER=local LOCAL_LLM_BASE_URL=http://127.0.0.1:8081/v1 NOTION_CYCLES_POLLING=off \
  OWNER_IDENTITY=owner-jacob OWNER_TOKEN_FILE="$SCR/owner-token" \
  DIRECTOR_TICK_IDENTITY=owner-jacob DIRECTOR_TICK_DIR="$SCR/tick" GOAL_RUNNER_KILL_FILE="$SCR/never-exists" \
  java -Duser.home="$SCR" -Xmx768m -jar build/libs/engram-engine.jar
```

The scratch engine reaches the live model servers, so a cycle on it spends the box's compute; the slot check makes it wait for a conversation, and a scratch cycle has to be run with the single-shot trigger on port 8095.

## Trying it once

`POST /debug/goal-runner/run-once` (loopback, debug token; body optional `{"goalUid": "..."}`) runs one cycle now and returns its report: steps, timings, tokens, replans, the Director's proposals, the messages. It works with the runner off. `GET /debug/goal-runner` shows whether the runner is built, whether it is on, what is running and the last report.

## Thinking

The Notion cycle's three calls (`CyclePlanDirector`, `DirectorTaskExecutor`, `CycleQcDirector`) all set `reasoning = true` on a forced tool call: the local template cannot call a tool after the pre-closed think block that `enable_thinking=false` produces, and writes its reasoning as visible text instead (`LlmRequest.reasoning`). The runner's calls do the same by default (`GOAL_RUNNER_REASONING=on`). Turning it off sends `enable_thinking=false` and is likely to make forced tool calls fail on long prompts. Plan and QC follow `GOAL_RUNNER_REASONING`; steps follow `GOAL_RUNNER_STEP_REASONING`, so steps can be run with it off while plan and QC keep it on. Because every claim is checked in code (see "The cycle"), the check does not depend on the setting.
