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
| `GOAL_RUNNER_REASONING` | `on` | Whether the forced-tool-call model calls let the model reason. See "Thinking". |

The runner is built when `DIRECTOR_TICK_IDENTITY` is set and `LLM_PROVIDER=local`. It works the tick's identity's goals.

## Idle

A cycle may start only when all of these hold. The single-shot trigger waives the first and nothing else.

1. No conversation for `GOAL_RUNNER_IDLE_MINUTES`: the owner's Horizon tool, a chat turn, or a debug conversation (`ConversationActivity`, a timestamp and nothing else).
2. No Hermes assignment in flight.
3. The kill file does not exist.
4. Free RAM at least the floor (`MemAvailable`), and, where it can be read (AMD VRAM through sysfs), free GPU memory at least the floor. A figure the box cannot read never blocks; a known low one always does.
5. The step server has at least two free slots, so the runner takes one and conversation always keeps one. The plan server needs a free slot.

While a cycle runs it re-checks 1 to 3 and the step server's other slot between steps, and every 1.5 s during a model call. When any fails it cancels the call (closing the connection, which stops llama.cpp generating), pauses the goal and sends a quiet message. What it wrote before stays and `retract_cycle` removes it.

## State

The runner may do two things to a goal's state, and the type that does them (`RunnerState`) has no way to say anything else: approved to running (`active`) at the start, and running to paused (`waiting`) when the budget is spent, the replan cap is hit, the model gives no usable answer, a cycle is interrupted, the cycle fails, or the engine restarted mid-cycle. Every move has a change record whose actor is `director` and whose cause is `goal_runner:<reason>`.

It never marks a goal done or stopped. When QC says the done-when looks met (and none of its sampled items was found unsupported) it sends a `finished` message; the owner closes the goal through the channel. A goal whose latest message is `finished` is not picked again.

A goal is picked only if it is approved, open or running, filed under an active project, and has no `finished` message waiting. Proposed, paused, stopped and done goals are never picked. Oldest approval first, one at a time.

## The cycle

1. **Plan** (strong model). Reads the goal, its done-when, the project's items (as refs `i1`, `i2` ... so a model never sees a uid), what the Director has already written, and the last cycle's summary. Writes at most `GOAL_RUNNER_MAX_STEPS` steps. Code drops a step that is malformed, too long, repeats an earlier one, asks to delete or overwrite something (`ForbiddenActions`, from the Notion cycle), or names a ref it was not shown. Work the graph already covers is skipped: the planner is shown the Director's earlier items, and a step's item whose text matches an item already in the project is counted as covered and not written.
2. **Execute** (fast model). Each step is one forced tool call over at most eight items. The answer is parsed field by field: at most five items, kinds `note`, `task` or `question` only, text cut to 400 characters, state suggestions limited to four states. Anything else in the answer (an `operations` list, a `verb`, a uid) is never read. Items are written through `DirectorWriter`, so its guard decides every write.
3. **Gate.** The step reports its impact on the plan: none, minor or major. Major replans, up to the cap, then the goal is paused and a `failure` message is sent. Code raises a step to major when most of its writes were refused.
4. **QC** (strong model). Recomputes against the done-when, judges a sample of the new items against their sources (the owner's item each one concerns), writes a summary message, and records what the goal left unclear as a Director note.

## Messages

`DirectorMessage` (phase B): questions (at most two a cycle), failures, finished goals, and a quiet progress message that closes every cycle and carries what the cycle used. Messages are capped at 600 characters and are shown to the channel under the "From the Director" heading that labels them as written by a model from material that may not be trusted. Routine progress stays quiet unless `HORIZON_DIRECTOR_PROGRESS` is set.

## Budget

A goal's `budget` text is read each time ("30 minutes, 50k tokens", "2 hours"); what it does not say falls back to the defaults. Tokens are the completion tokens llama.cpp reports plus prompt tokens estimated from characters (it reports none for the prompt). Minutes are wall-clock minutes of runner work. Usage is summed over all of a goal's cycles from the closing message of each; a spent budget pauses the goal before a cycle starts, and a cycle stops between steps when it runs out.

## Cycle numbers

The runner assigns them (`DirectorCycles.begin`): one past the highest the goal has carried on a message or an item, retracted ones included, so a number is never reused. The start is recorded on a quiet message, so even a cycle that wrote nothing has used its number. `DirectorWriter`'s public methods take a `DirectorCycle` only the runner can issue; the ones that take a bare number are `internal`.

## Restart

At startup, a goal that is running and whose latest cycle never ended (no closing message) is paused, with a `failure` message and a change record (`goal_runner:restart_recovery`). It is never resumed. A goal that is running between cycles is left as it is.

## Trying it once

`POST /debug/goal-runner/run-once` (loopback, debug token; body optional `{"goalUid": "..."}`) runs one cycle now and returns its report: steps, timings, tokens, replans, the Director's proposals, the messages. It works with the runner off. `GET /debug/goal-runner` shows whether the runner is built, whether it is on, what is running and the last report.

## Thinking

The Notion cycle's three calls (`CyclePlanDirector`, `DirectorTaskExecutor`, `CycleQcDirector`) all set `reasoning = true` on a forced tool call: the local template cannot call a tool after the pre-closed think block that `enable_thinking=false` produces, and writes its reasoning as visible text instead (`LlmRequest.reasoning`). The runner's calls do the same by default (`GOAL_RUNNER_REASONING=on`). Turning it off sends `enable_thinking=false` and is likely to make forced tool calls fail on long prompts; it is there to be measured, not recommended.
