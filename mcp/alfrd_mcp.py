"""alfrd MCP server: one tool, `horizon`, over stdio.

The tool calls the engine's owner route (POST /owner/horizon on loopback) with the owner token and returns the response as text:
the Director's standing prompt (self-state, change marker, Horizon), the refs the operation verbs may name, and what happened to each
operation sent. The operation verbs and the `sources` field documented in the tool's input schema are read from ops.schema.json, which the
engine's test suite checks against the Kotlin definition (Interpreter.OWNER_TOOL_SCHEMA), so they are the code's, not a paraphrase.

The token is read from ~/.config/engram/owner-token (refused if group or others can read it) on every call and is never printed or logged.
Nothing is written to stdout except the MCP protocol: stdout is the transport.
"""
from __future__ import annotations

import json
import os
import stat
import sys
from pathlib import Path
from typing import Annotated, Any

import httpx
from mcp.server.fastmcp import FastMCP
from pydantic import Field

HERE = Path(__file__).resolve().parent
ENGINE_URL = os.environ.get("ALFRD_ENGINE_URL", "http://127.0.0.1:8082").rstrip("/")
TOKEN_FILE = Path(os.environ.get("ALFRD_OWNER_TOKEN_FILE", str(Path.home() / ".config" / "engram" / "owner-token")))
TIMEOUT_SECONDS = float(os.environ.get("ALFRD_HORIZON_TIMEOUT_S", "60"))

_SCHEMA: dict[str, Any] = json.loads((HERE / "ops.schema.json").read_text())
OPS_SCHEMA: dict[str, Any] = _SCHEMA["operations"]
SOURCES_SCHEMA: dict[str, Any] = _SCHEMA["sources"]

DESCRIPTION = """\
Look at the owner's Horizon, and optionally write to it. Every call returns the refreshed Horizon: alfrd's standing context (its situation,
what changed in memory, and what the Context Horizon currently brings into view), then the refs you can name, then what happened to each
operation you sent.

With no arguments this is a pure read: nothing is written. With `message` and/or `operations` it is a write, attributed to this Claude
session, which the owner trusts.

`message` is what you are telling alfrd, in the owner's own words or yours. It is recorded as an utterance on the "claude" channel. Every
operation's `quote` must be a verbatim passage of `message` (that is the guard: a write needs the words it rests on). Operations sent
without a message are refused for that reason.

Refer to existing things only by the refs in the "Snapshot" section of the last response (c1, i2 ...). Refs are positional: take them from
the most recent response, not an earlier one. A refused operation reports the guard's reason and writes nothing. At most 8 operations per call.

Sourced writes are for documents (a Notion page first): send the page in `sources` (a stable `id`, `title`, `kind` notion_page, and its `text` or
`chunks`; re-sending unchanged text adds nothing, changed text supersedes the earlier text), then file an item with a record_item that cites it:
`source` is that id and `quote` is a verbatim passage of the source's text. The item is shown as coming from the document, never as the owner's
statement; the owner's own statements still use the message. `last_edited` (ISO date or time) on a source sets the evidence date of the items filed
from it: they read as fresh as the page says, never later than now, so an old page's tasks are asked about ("Still live?") rather than raised as
overdue; sending the page again with a later `last_edited` refreshes them, and with an earlier one corrects items imported earlier that only a
source has touched (confirmed or changed items are never lowered). Without `last_edited` an item starts fresh as of the import. A record_item
`title` (task or event only) is kept only if at least half of its content words (four letters or more) come from the quote; otherwise it is dropped
and the quote becomes the item's label, so write a title from the quote's own words.

Goals are what the owner wants worked on: record_item with kind goal (a one-sentence `title`, optional `done_when`, `stop_when` and `budget`, and the project it
serves) writes a proposal from the owner's words. Only the owner's own words approve it (`approve_goal`, with a quote; a question or a "no" is refused), and
approving freezes it: its sentence, done_when, stop_when, budget and project cannot change again. A proposed goal can still be re-worded and re-filed with
`edit_goal` and `file_item`. To change what an approved goal means, stop it (`set_state` stopped), write a new goal, and link the two with `link_items`; every
refusal says so. A goal runs (`set_state` running) only after approval, can be paused and resumed, and once done or stopped it is final and cannot be reopened.
The board's Goals line reads running (active only), approved (not started), waiting for approval and stalled.

A goal that was stopped is replaced in one step: record_item kind goal with `replaces` set to the stopped goal's ref (it serves the same project unless you name
another, and it is proposed like any goal). The replacement is written with its link to the old goal, so there is no separate link_items to forget; a goal that is
not stopped, or already has a replacement, cannot be replaced.

The Director works approved, running goals as its own source. Everything it writes is its own, never the owner's statement: items it filed say so and name the
goal and the cycle, and a note of its own may suggest a change to the owner's item, but it never edits, resolves, re-files or deletes what it did not write.
What it sends you appears at the end of the Horizon as "From the Director" (questions, failures and finished goals; routine progress stays quiet). To take back
everything one cycle of a goal wrote, use retract_cycle (item = the goal, cycle = its number, quote from the owner's own words): its items and notes become
dropped and its messages are marked dropped, each with a change record. Nothing is deleted.

Items have a state: open, active, waiting, done or dropped (record_item takes an optional `state`; `set_state` moves an existing item, with an
optional `nudge` date to check in and `waiting_on`, who or what it waits on; waiting is accepted without waiting_on, and the result says so).
`resolve_item` is set_state done and `reopen_item` is set_state open. A due date can be marked `due_kind` hard or soft (soft when not said);
record_item also takes `starts` (scheduled start) and `happened`, and correct_item may change `due`, `due_kind` and `nudge`. Dates keep the
precision you give (a date, or a date and time). Done and dropped items never show in the Horizon; a waiting item shows only when its project is
in focus or its nudge date has arrived; overdue applies only to open and active items. From a document, a record_item may set `state` and
`due_kind`, which then read as the document's claim, not the owner's. When a Notion status maps: Not Reviewed and Approved = open, In Progress =
active, External = waiting, Completed = done, Cancelled = dropped.

An open or active item whose due date has passed and which nothing has confirmed for a while (14 days by default) shows as a question ("? ...
Still live?") rather than an alarm; a hard deadline stays overdue with a no-evidence note. Answer with set_state done or dropped, correct_item
for a new date, or `confirm_item` (item, quote) for "yes, still live", which only refreshes when it was last confirmed. Pinning or suppressing
is not confirmation. "Also due" lists hard deadlines within 3 days (or overdue) and soft ones due today or tomorrow, whatever the focus.
"""

mcp = FastMCP("alfrd", instructions="alfrd's memory Horizon. One tool: horizon.")


def _token() -> str:
    try:
        mode = TOKEN_FILE.stat().st_mode
    except FileNotFoundError:
        raise RuntimeError(f"owner token file not found: {TOKEN_FILE} (it is created by the engine when OWNER_IDENTITY is set)") from None
    if mode & (stat.S_IRWXG | stat.S_IRWXO):
        raise RuntimeError(f"owner token file {TOKEN_FILE} is accessible to group or others; it must be mode 600")
    token = TOKEN_FILE.read_text().strip()
    if not token:
        raise RuntimeError(f"owner token file {TOKEN_FILE} is empty")
    return token


def _format(body: dict[str, Any]) -> str:
    parts = [body["horizon"].rstrip(), "", body.get("refs", "").rstrip()]
    cycle = body.get("cycleSeq")
    ops = body.get("operations") or []
    if cycle is not None or ops:
        parts += ["", f"Write cycle: {cycle}" if cycle is not None else "Write cycle: none"]
    if ops:
        parts.append("Operations:")
        for i, op in enumerate(ops, 1):
            status = "applied" if op.get("applied") else "REFUSED"
            quote = f' (quote: "{op["quote"]}")' if op.get("quote") else ""
            where = f' [source: "{op["source"]}", {op.get("location", "")}]' if op.get("source") else ""
            parts.append(f"  {i}. {op.get('verb', '?')}: {status} - {op.get('detail', '')}{quote}{where}")
    sources = body.get("sources") or []
    if sources:
        parts.append("Sources:")
        for i, src in enumerate(sources, 1):
            parts.append(f"  {i}. {src.get('id', '?')} ({src.get('title', '')}): {'stored' if src.get('stored') else 'REFUSED'} - {src.get('detail', '')}")
    return "\n".join(parts).strip() + "\n"


@mcp.tool(name="horizon", description=DESCRIPTION)
def horizon(
    message: Annotated[
        str | None,
        Field(description="What you are telling alfrd. Recorded as an utterance on the claude channel; each operation's quote must be verbatim from it."),
    ] = None,
    operations: Annotated[
        list[dict[str, Any]] | None,
        Field(description="Writes to apply, validated by the engine's guards. Each entry is one of the verbs below.", json_schema_extra=OPS_SCHEMA),
    ] = None,
    sources: Annotated[
        list[dict[str, Any]] | None,
        Field(description="Documents you supply so operations can cite them (see the description). Stored by content hash; sending them is a write.", json_schema_extra=SOURCES_SCHEMA),
    ] = None,
) -> str:
    payload: dict[str, Any] = {}
    if message is not None:
        payload["message"] = message
    if operations:
        payload["operations"] = operations
    if sources:
        payload["sources"] = sources
    try:
        response = httpx.post(
            f"{ENGINE_URL}/owner/horizon",
            headers={"Authorization": f"Bearer {_token()}", "Content-Type": "application/json"},
            content=json.dumps(payload) if payload else b"",
            timeout=TIMEOUT_SECONDS,
        )
    except httpx.HTTPError as e:
        raise RuntimeError(f"could not reach the engine at {ENGINE_URL}: {type(e).__name__}") from None
    if response.status_code != 200:
        try:
            reason = response.json().get("error", "")
        except ValueError:
            reason = ""
        raise RuntimeError(f"the engine refused the call: HTTP {response.status_code} {reason}".strip())
    return _format(response.json())


if __name__ == "__main__":
    print("alfrd-mcp: serving over stdio", file=sys.stderr)
    mcp.run(transport="stdio")
