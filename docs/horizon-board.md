# The zoomed-out board

When nothing is focused, the Horizon that the owner's tool and the Director tick return is a **board**: a fixed-size status view of the whole graph.
It is built by code from the graph on every read (`HorizonBoard.kt`, `HorizonBoardSource.kt`). No model writes any of it, and it carries no Director commentary.
Its size is set by a token budget, not by how many projects or items exist.

A focused view (a project, an area or one item) is unchanged: it keeps its own listing. Conversational turns also keep the existing renderer for now (see
"Not done").

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
| Focused | `boardWeightFocused` | 0.8 | scaled by a casual mention's strength (a mention of an area reaches its projects) |
| Pinned | `boardWeightPinned` | 1.0 | the project is pinned |

Below `boardQuietBelow` (0.15) a project is quiet. Quiet projects are never listed on their own; they roll up into their area ("Everything else"). The board
lists nodes by pressure until the budget is used; whatever pressured nodes did not fit are counted ("Not shown for space"), never called quiet.

Staleness is the existing rule, called from one place: `StateRules.stale` (open or active, due date passed, no evidence for more than `staleEvidenceDays`,
default 14). A soft stale item is asked about; a hard one keeps its overdue alarm. The assembler and the board both call it.

## Settings

| Setting | Default | Environment override |
|---|---|---|
| `boardTokenBudget` | 800 | `HORIZON_BOARD_TOKENS` |
| `zoomTokenBudget` | 1000 | `HORIZON_ZOOM_TOKENS` (a setting only for now; see "Not done") |
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

## Not done

- `zoomTokenBudget` is a setting, but the binoculars and telescope views are not yet held to it; they are still bounded by `focusItemCap`.
- Conversational turns still use the earlier zoomed-out listing (`HorizonItemsRenderer`). The board is what the tick and the owner's tool return.
  Switching turns is a one-line change at the turn's render call, but it changes live conversation prompts, so it was left for a decision.
- The refs listing (the `refs` field next to the horizon text) still gives every area and project a `cN` ref, so it grows with the number of projects.
  It is a separate field and is not part of the board's budget.
