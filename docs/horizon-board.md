# The zoomed-out board

When nothing is focused, the Horizon that the owner's tool and the Director tick return is a **board**: a fixed-size status view of the whole graph.
It is built by code from the graph on every read (`HorizonBoard.kt`, `HorizonBoardSource.kt`). No model writes any of it, and it carries no Director commentary.
Its size is set by a token budget, not by how many projects or items exist.

A focused view (a project, an area or one item) keeps its own listing, held to its own size budget (see "Focused views"). Conversational turns keep the existing
renderer and are not held to either budget (see "Not done").

## What it shows

```
Board as of 2026-11-11 09:00: 40 projects in 8 areas, 192 open items.
Needs attention:
- c9 Williams [Area 1]: 4 open, !1, ~1
  ! i1 "File the permit": overdue 2d (hard)
  ~ i2 "Reply from the inspector": waiting on Sam, check-in date reached
- c10 Meridian demo [Area 2]: 2 open, !1
  ! i3 "Send the deck": due tomorrow (hard)
Everything else (quiet): c2 Area 2: 5 projects, 25 open; c3 Area 3: 5 projects, 25 open; 5 more areas: 22 projects, 110 open.
Since you last read (2026-11-10 09:00):
- i4 "Order the soil": state open to done
Legend: cN project or area, iN item; open either with set_focus on its ref. !n overdue or due soon, ...
```

Every line names real things by their ref (`cN` for a project or area, `iN` for an item), the same refs the existing verbs take, so a reader opens a line with
`set_focus` on its ref. A project's "open" count is the number of items its focused view lists (open, active and waiting, not done, dropped or suppressed).

## Counts

"Open" means open, active or waiting, and **never suppressed**. Suppressed items are "hidden", counted apart, and the same two numbers are said everywhere the
standing context shows them: a project reads `5 open, 5 hidden` on the board, in its focused view's heading, and in a focused area's per-project lines. An area's
roll-up on the board sums open only, so it equals the sum of the open numbers its projects show in the area's focused view. (Conversational turns still count
suppressed items in a project's open number, as before; the standing context takes them out using the container view's `hiddenItems`.)

## Marks

| Mark | Meaning |
|---|---|
| `!n` | n items overdue or due soon (the "also due" windows) |
| `~n` | n waiting items whose check-in date has arrived |
| `*n` | n items changed since this channel last read (from change records) |
| `?n` | n items maybe stale; asked about together as one "still live?" question per project |
| `^` | pinned (`set_attention`) |

## Pressure

Each project gets a pressure from signals already tracked. Each signal is 0 to 1, multiplied by its weight, and combined the way the selector combines
activations (each adds, none passes 1; `HorizonPolicy.combine`).

| Signal | Weight (`HorizonPolicy`) | Default | 1.0 when |
|---|---|---|---|
| Overdue | `boardWeightOverdue` | 0.9 | a hard item is overdue (0.7 for a soft one that is not stale) |
| Due soon | `boardWeightDueSoon` | 0.7 | an item is inside the also-due window, or the project's own date is near |
| Waiting, due for a nudge | `boardWeightWaitingDue` | 0.6 | a waiting item's check-in date has arrived |
| Changed since last read | `boardWeightChanged` | 0.5 | a change record touches the project or its items (0.5 plus 0.1 per changed item, up to 1) |
| Stale but open | `boardWeightStale` | 0.5 | a soft overdue item has no recent evidence |
| Focused | `boardWeightFocused` | 0.8 | scaled by a casual mention's strength now (a mention of an area reaches its projects) |
| Pinned | `boardWeightPinned` | 1.0 | the project is pinned |

A project gets a line only if it has something open (or is pinned) and its pressure is at or above `boardQuietBelow` (0.15). Below that, or with nothing open (an
empty project that has a change record, a faint casual mention), it is quiet, whatever small pressure it has. Quiet projects are never listed on their own; they roll up into their area ("Everything else"). The board
lists qualifying projects by pressure until the budget is used. "Not shown for space" counts only qualifying projects that did not fit; a quiet project is never
counted there, so the line appears only when the budget really cut something. There is no separate cap on needs-attention projects: the budget (and the pinned cap)
is the only limit.

A casual mention fades by the selector's own function (`HorizonPolicy.nudgeStrengthAt`: it halves every `nudgeHalfLifeCycles` turns and is nothing below `nudgeFloor`), read at the cycle the Horizon is being read at, which a pure read takes as the next cycle without allocating or writing one; a pin never fades.

Staleness is the existing rule, called from one place: `StateRules.stale` (open or active, due date passed, no evidence for more than `staleEvidenceDays`,
default 14). A soft stale item is asked about; a hard one keeps its overdue alarm. The assembler and the board both call it.

## Goals

A goal is an item kind (`goal`) that the owner proposes and approves; the board shows them in their own section, after the one-line summary and before "Needs attention". Zero goals renders nothing.

```
Goals: 1 running, 1 approved, 1 waiting for approval, 1 stalled.
  ! i4 "Review the open Williams items": stalled, no evidence since 2026-10-01
  ? i7 "Propose project tags": waiting for approval
  - i5 "Check the import dates": approved, not started
  - i2 "Tidy the import": running
```

- **Running means active and nothing else.** An approved goal that has not been started reads "approved, not started". Waiting for approval: only proposed. Stalled: approved and paused (waiting), or running with no evidence for the stale window (an approved goal that has not started is never stalled). Order: stalled, waiting for approval, approved, running.
- The section is bounded on its own: the counts line, then at most `boardGoalLines` (4) goal lines, and the whole section within `boardGoalTokens` (150) tokens however many goals there are. What is left out is counted ("N more goals").
- Goals are not counted among the open items.
- A goal's state words: running is active, paused is waiting, done is done, stopped is dropped (`set_state` accepts both).

### The rules for a goal

The owner's decision: **an approved goal is frozen.** What a goal means is changed by stopping it and writing a new goal, linked to the old one with `link_items`, never by editing it.

| | Proposed | Approved |
|---|---|---|
| Sentence, `done_when`, `stop_when`, budget (`edit_goal`) | may change, from the owner's words (a new sentence must use their words or the old one's) | refused |
| Re-file under another project (`file_item`) | allowed | refused (the project is its scope) |
| `correct_item` | refused (use `edit_goal`) | refused |
| Start, pause, resume, stop, done (`set_state`) | start is refused until approved; the rest allowed | allowed |
| Evidence, `confirm_item`, pin or suppress | allowed | allowed |

- **Approval** needs the owner's own words as a grounded quote (a question, a negation or an ungrounded quote is refused). Approving freezes the goal and keeps the quote.
- **Done and stopped goals are final.** `reopen_item` on one is refused, and so is any other state change to a done or stopped goal. A paused goal may resume.
- Every refusal carries the same words: *a goal is frozen once it is approved and final once it is done or stopped. To change what it means, stop it, propose a new goal, and link it to the old one with link_items.*
- A goal cannot be recorded from a document, and the legacy correction path (`amendPhrase`) leaves a goal's text alone.
- Changes to `approval`, the sentence (`sentence`), `done_when` (`doneWhen`), `stop_when` (`stopWhen`) and the budget leave change records; "since last time" shows them as *approval*, *sentence*, *done-when*, *stop-when* and *budget*.

## Settings

| Setting | Default | Environment override |
|---|---|---|
| `boardTokenBudget` | 800 | `HORIZON_BOARD_TOKENS` |
| `zoomTokenBudget` | 1000 | `HORIZON_ZOOM_TOKENS` (holds each focused view in the tick and the tool; see "Focused views") |
| `charsPerToken` | 3.5 | |
| `boardQuietBelow` | 0.15 | |
| `boardPinnedCap` | 8 | |
| `boardItemsPerNode` | 2 | |
| `boardSinceLines` | 4 | |
| `boardRollupLines` | 3 | |
| `boardFirstReadLookbackHours` | 24 | |
| `boardScanLimit` / `boardChangeScanLimit` | 5000 / 2000 | |
| the seven weights above | see table | |

Tokens are measured on the rendered text: characters divided by `charsPerToken`. The board has a floor (the header, the legend, the roll-up and the
"since" section take roughly 230 tokens); a budget below that cannot be met.

## Decisions

- **The also-due block counts against the board budget, because the board replaces it.** Zoomed out, the also-due deadlines are the board's "due soon" signal
  (same windows, `DueWindows.alsoDue`, shared with the assembler), so there is no separate block. A focused view still shows the also-due block.
- **Pinned nodes count against the budget.** They are admitted first, before pressure ranking, capped at `boardPinnedCap`; the rest are counted in a line
  ("N more pinned not shown for space"). A pinned area has one aggregate line of its own and does not force its projects in. A pinned item keeps a line
  under its project.
- **"Since last read" is kept per channel, in memory.** A pure read of the Horizon writes nothing, and the schema is not changed for this. Each channel
  (the owner's tool is `claude`, the tick is `director-tick`) has its own marker, and it moves only when that channel is shown a board. A restart
  forgets the markers; the first read after one looks back `boardFirstReadLookbackHours` and says so ("Since the last day (no earlier read on record)").
  A write call shows its own changes once, in its own response.
- **The legend is a mark table plus one sentence per verb**, travelling with every board (about 100 tokens), with one-character marks on the lines.
- New items are counted from their creation time ("N new items"); change records cover changes to existing items.

## The tool response is bounded too

The owner's tool returns the horizon text and a `refs` listing next to it. Zoomed out, the listing is bounded like the board: every **area**, the **projects
that have a line on the board**, and the project of every item the listing names (so an item's `[in cN]` always points at a listed line). The projects left
out are counted in one line, and are reached through their area: `set_focus` on the area's ref, and the response then lists the projects in it with refs.

Refs are positional and per response: they are numbered afresh on every call (areas first, then projects oldest first, items in the order the listing builds
them), so a ref should be taken from the latest response only, never carried over from an earlier one. Bounding the listing does not change which number a
thing gets within a response, because every ref is still resolved against the full snapshot. What the board guarantees is narrower and checkable: **every ref
it shows resolves in the next call** (provided nothing else changed the graph in between), with every verb (`set_focus`, `set_attention`, `confirm_item`,
`file_item` and the rest).

Two things make "any ref shown works on the next call" true:

- Items the board names beyond the snapshot's own pools (an old overdue item among many) get a ref only because the board lists them. The service remembers the
  items the last response named, in order, and builds the next call's snapshot with them, so the ref it was shown is accepted. (Before this, such a ref was refused with
  `unknown_ref`. A restart forgets the memory; a ref from before a restart then falls back to the snapshot's own pools.)
- The listing grows with the number of **areas**, not projects. Areas are the handles for everything rolled up, so all of them are listed.

Measured on a real scratch graph through `/owner/horizon` (the whole JSON response, tokens at 3.5 characters each; 3 projects need attention, the rest are quiet):

| Projects | Whole response | Horizon text | Refs listing |
|---|---|---|---|
| 4 | 817 | 569 | 212 |
| 40 | 878 | 602 | 240 |
| 400 | 888 | 607 | 245 |

Before the listing was bounded, the same graph returned 817, 1272 and 5671 tokens (the listing alone was 212, 624 and 4915).

## Focused views

A focused view in the tick and the tool is held to `zoomTokenBudget` the way the board is held to its own. The size is measured on the rendered section
(orientation, also-due lines, grouped items, with a small allowance per item for its ref and for the note). When the view is larger, the **lowest-pressure
items are dropped first** (an item's score, the same one that ranks it), the rest keep their order, and the view says how many it left out:

> 77 more items were left out of this view to stay within its size budget (lowest pressure first); they are still in memory.

For a focused project the count includes the project's open items that were never assembled into the view, so "listed plus left out" is the project's open
count. Measured on a project of 100 tasks (5 of them hard-overdue): 3580 tokens untrimmed, 912 trimmed, 23 items kept, 77 left out; through the tool, 916 tokens
with refs. The overdue items are kept. A view that already fits is returned unchanged.

The refs listing of a focused view is not bounded: it still lists every area and project, because that is how an item is re-filed into any project.

## Not done

- Conversational turns still use the earlier zoomed-out listing and are not held to the focus budget (`HorizonItemsRenderer` is shared, and the budget lives in the
  standing context). The board and the focus budget apply to what the tick and the owner's tool return. Switching turns is a decision about live
  conversation prompts, so it was left alone.
- A focused view's refs listing still grows with the number of projects (see above).
