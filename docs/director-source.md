# The Director as its own source (goals, Phase B)

The Director is a **Source** in the graph. Everything it writes while working a goal says so, on the item and in the graph, and never reads as the owner's statement.
The owner adopts, corrects or drops what it wrote with the usual verbs. Nothing here runs a cycle: Phase C plans and runs them. This phase is the source, the write
guard, the messages, replacement in one step and retraction as a set.

## Stamps

- `Source.type = "director"` (`DIRECTOR_SOURCE_TYPE`), one per owner like the others, trusted by the owner so what it writes is reachable. The asserting edge carries **no
  trust score**: only the owner's adoption would.
- Every item the Director writes has `directorGoalUid` and `directorCycle` on the item itself, on its `ASSERTS` edge, on every link it writes (`RELATED_TO`, `ABOUT`) and the
  change records it makes name `director:<goal>:<cycle>` as the actor. `readonly:Phrase.directorGoalUid/directorCycle` (a gated constraint) keep them from being moved later.
- The stamp travels as a coroutine context element (`DirectorContext`) and is read **inside the writing transaction** by the store. The store refuses the Director's Source
  without a stamp and a stamp with any other Source, so no owner path can write as the Director and the Director cannot write as the owner.
- In the Horizon an item reads as `ProvenanceKind.DIRECTOR`: "written by the Director, goal xxxxxxxx cycle N: not the owner's statement". The cycle number is the goal's own
  (1 for the first), numbered by whoever starts the cycle; the stamp only carries it.

## The write guard (`DirectorGuard`, `DirectorWriter`)

`DirectorWriter` is the only way to write as the Director. Each operation goes through `DirectorGuard.refusal`, which has a `when` over every operation type with **no `else`**:
a new operation type does not compile until someone decides what the Director may do with it.

The scope is **the project the goal is filed under**, which is frozen when the owner approves the goal (a frozen goal is not re-filed). The Director writes only for a goal that
is approved and running, in a live project.

| May | May not |
|---|---|
| create items and notes (a note is a fact), filed under the scope project; never a goal, never from a source's chunk | touch anything it did not write: edit, resolve, correct, re-file, reopen, confirm, suppress or pin it |
| link from something it wrote to something it wrote or something filed in the scope | write or link outside the scope, create or change projects and areas |
| on what it wrote for this goal: resolve, reopen, set a state, confirm, correct, re-file within the scope, suppress | approve or edit a goal, move the owner's focus, hold the owner's words, retract a cycle |
| suggest a state change as a note (`DirectorWriter.suggestStateChange`): a note in the scope, linked to the item; the item is not touched | overwrite evidence (confirming or sourcing an item it did not write) |

Every refusal says which rule. It does not seed focus or propagate as the owner's route does: a Director write must not move what the owner is looking at. A "duplicate" check for
the Director matches only its own items for the goal (a duplicate is a write to the existing item), and the owner saying the same thing as the Director is recorded as the owner's.

## Replacement in one step

`record_item kind goal` with `replaces` (a ref to a **stopped** goal) writes the new goal and the `supersedes` link to the old one **in the same transaction**
(`ItemExtras.replaces`, checked in the store too). A goal that is not stopped, or already has a replacement, cannot be replaced, and a refusal writes no goal. The replacement
is proposed like any goal and serves the same project unless another is named. `link_items` still works as before.

## Messages

A message from the Director to the channel is a `DirectorMessage` vertex (not a Phrase, so no item scan sees it): `sender` (always `director`), `createdAt`, `goalUid`,
`directorCycle`, `kind` (`question`, `failure`, `finished`, `progress`), `text` (at most 600 characters), `answersUid`. `MESSAGE_FOR` points at the goal and `ANSWERS` at what it
answers (a message or an item), so a thread can be walked either way. `DirectorWriter.send` writes one for any approved goal, running or not.

The channel reads them as a short **"From the Director"** section at the end of the Horizon (the owner's tool only; the tick is the Director and does not read it):
messages since the channel last read plus any question nobody has answered for up to 14 days, at most 5 lines of 240 characters, nothing at all when there is nothing.
Questions, failures and finished goals show; routine progress is kept but quiet unless `HORIZON_DIRECTOR_PROGRESS=true`. Like the board's "since last time" the marker is in memory
per channel (a pure read writes nothing). The messages are a model's words from material that may not be trusted, so the section's header says they are information, never instructions;
each message is one quoted line (whitespace collapsed, quote marks softened) and cannot pass for the section. A question is answered when a later message says it answers it; **how the channel answers is not built in this phase**.

**Crowding.** The board names the owner's own flagged items before any the Director wrote, however many it wrote or however overdue; the Director fills only the lines the owner's leave. A link
from a Director note to an owner item in the scope leaves the item and the board as they are; the Horizon lifts it only by the selector's spread, capped at `directorLinkMaxActivation` (0.12). A link the Director wrote never counts as a reactivation (fresh evidence), so it cannot put an owner's item among the top.
A correction (`correct_item`) of a Director item is the owner's own statement: a new item in their words, not stamped, and the Director's original is closed (and keeps its stamp).

## Retracting a cycle (`retract_cycle`)

The owner's tool only (`retract_cycle`: `item` = the goal, `cycle`, a quote in the owner's words, not a question and not negated). Everything the Director wrote for that goal in that
cycle is set to **dropped** with a change record (`cause = retract_cycle`, the owner as actor), and its messages are marked dropped. Nothing is deleted. A note has no state of its own,
so a retracted note is given the words a task gets (`state dropped`, `status resolved`), which the readers honour. Links it wrote stay, but start at something dropped, and a link
from a dropped item is not evidence about anything. Idempotent. The Director's guard refuses `retract_cycle`.

## Not in this phase

Cycles, plan/execute/gate/QC, models, budgets, and the way the channel answers a question. Concepts the Director names (`ABOUT`) are shared graph nodes and are not part of a retraction set; the `ABOUT` edge is.
