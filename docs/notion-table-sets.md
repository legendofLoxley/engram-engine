# Notion table sets

The Notion client in `app.alfrd.engram.notion` serves any **table set**: a named, fixed list of
Notion tables (data sources), each with its own access mode, reached with one token. The alfrd
memory template is one table set. An Agent Work + PPS Evidence Ledger set is another. This note
covers how a set is defined, what the guard enforces, which ids the configuration must contain, and
what has not been verified against the real service.

Nothing here makes a live Notion call. Everything below is proven against the in-memory fake and a
mock HTTP server only; section 4 lists what a live call must still confirm.

## 1. Defining a set

```
NotionTableSet(name, tables, readablePages)
NotionTable(name, dataSourceId, access, pageBodyAppend, idempotencyKeyProperty, allowIncomingRelations)
```

| Field | Meaning |
|---|---|
| `access` | `READ_ONLY` or `READ_WRITE`. Query and retrieve work on both; create, update and body append need `READ_WRITE`. |
| `pageBodyAppend` | Blocks may be appended to this table's pages (the memory ledger in Sessions). Off by default. Writable tables only. |
| `idempotencyKeyProperty` | Opt in to idempotent creates (section 3). Off by default. Writable tables only. |
| `allowIncomingRelations` | A row of another table may be given a relation to a row of this one. Defaults to true for read-write, false for read-only. |
| `readablePages` | Plain pages (such as the persona page) that can be read and never written. |

The allowlist is static: it is fixed when the guard is built. Which rows a particular goal may touch
(its Scope) is **not** the client's concern; the Director enforces that above the client.

### Two ways to configure one

- **Memory, per person** (unchanged): `NotionIdentityConfig.parse(json)` with `identityId`, `tokenRef`,
  `timezone` and the five `dataSources` plus `persona`. `config.tableSet` is the set
  `alfrd-memory.<identityId>`: five read-write tables, appends on Sessions only, persona readable.
- **Anything else**: `NotionTableSetConfig.parse(json)`.

```json
{
  "setName": "agent-worker",
  "tokenRef": "env:ALFRD_WORKER_NOTION_TOKEN",
  "tables": [
    { "name": "goals",     "dataSourceId": "collection://00000000-0000-4000-8000-000000000001", "access": "read-write" },
    { "name": "cycles",    "dataSourceId": "collection://00000000-0000-4000-8000-000000000002", "access": "read-write", "pageBodyAppend": true },
    { "name": "tasks",     "dataSourceId": "collection://00000000-0000-4000-8000-000000000003", "access": "read-write", "idempotencyKeyProperty": "Op key" },
    { "name": "claims",    "dataSourceId": "collection://00000000-0000-4000-8000-000000000004", "access": "read-only" }
  ]
}
```

`access` has no default; every table says whether it can be written. Unknown keys are rejected. The
ids above are placeholders: the real ones are the `collection://` ids on the design and task pages.

A run that must not write somewhere tightens the same set instead of keeping a second config:

```kotlin
val plannerView = config.tableSet.withReadOnly(listOf("claims", "evidence"))   // dry run: PPS read-only
```

Tightening is the only direction offered, and an unknown table name is an error (a typo cannot leave
a table writable).

### Sets must not overlap

`NotionSetRegistry(bindings)` refuses sets that share a **token reference**, a **table or readable
page**, or a **name**. A token that reaches a person's memory must not also reach the agent worker's
tables, and the reverse. After secrets are available, `verifyDistinctTokens(resolver)` also catches two
different references that resolve to the same secret. Refusals name the sets, never a reference's
contents or a token.

## 2. What the guard enforces

`AllowlistedNotionApi(delegate, tableSet)` wraps the client. In the production stack it is outermost:
`AllowlistedNotionApi(RateLimitedNotionApi(HttpNotionApi))`.

| Call | Refused unless |
|---|---|
| query | the table is in the set |
| create | the table is in the set **and read-write**, and, if it opted in, the row carries a non-blank idempotency key |
| update | the page came back from a table in the set, that table is **read-write**, and the update does not touch the idempotency key |
| append blocks | the page is known, its table is **read-write** and has `pageBodyAppend` |
| retrieve / list blocks | the page is a readable page, or its table is in the set (read-only tables included) |
| any relation value | every target is a known page whose table accepts incoming relations |

Why relations are checked: Notion mirrors a dual relation onto its target row. Writing a relation
from a read-write row to a row of a read-only table would therefore edit the read-only table. A
read-only table refuses incoming relations unless it is marked `allowIncomingRelations` after
someone has confirmed the relation is one-way.

Limit: a relation property is replaced as a whole. If a person had linked a read-only row to a
read-write row through a dual relation, an update that rewrites that property on the read-write row
removes the link from the read-only row too. The guard cannot see current values. Do not write dual
relations that point into read-only tables from this client.

Refusals mention tables, ids and property **names**, never property values.

## 3. Idempotent writes: any table can opt in

A Notion create is not idempotent. The layers below never retry one after an ambiguous failure (a
5xx, or a connection that dropped after the request left); they raise `NotionAmbiguousWriteException`.

A table opts in by naming a text (rich text) property in `idempotencyKeyProperty`. Then:

- the guard requires that property on every create and forbids changing it afterwards;
- `IdempotentCreator(api, table)` gives one path for callers: `find(key)`, `create(key, props)`,
  `ensure(key, props)` (find, then create). On an ambiguous create it looks the row up by key, a few
  times with short waits, and returns it if it is there. If it still cannot be found it rethrows and
  **does not create again**; the next `ensure` of the same key settles it.

Keys are 1-200 characters, trimmed, no control characters, built from stable inputs (for example
goal, cycle, plan version and task order) so repeating the same work yields the same key.

`NotionDataSources.toTableSet(idempotentWrites = true)` opts the memory template's Items (`Op key`)
and Sessions (`Key`) in. It is off by default, which keeps memory behaviour as it was.

A Notion update that sets a property to a value is repeatable, so updates are retried on transient
failures. Notion has no conditional update, so a lease (`Lease Until`, `Worker`) is only safe while a
single worker owns the table set.

## 4. Data source ids and database ids (API 2025-09-03)

**What the client does** (from the code, covered by the mock-server tests):

- It sends `Notion-Version: 2025-09-03` on every request.
- It queries with `POST /v1/data_sources/{id}/query` and creates with `POST /v1/pages` and
  `parent: {"type": "data_source_id", "data_source_id": …}`.
- It identifies a returned page's table from `parent.data_source_id`. A page whose parent carries no
  data source id has `dataSourceId = null`, and the guard refuses it. The client fails closed.
- It never calls a `/v1/databases/…` endpoint and never discovers or translates ids. It trusts the
  configuration.
- `normalizeNotionId` accepts a bare id, a dashed or capitalised id, a `collection://` reference or a
  Notion URL. A database id and a data source id look identical, so the id's shape cannot tell them apart.

**What the configuration must contain.** The data source id: the UUID inside a `collection://…`
reference, which is what the design and task pages list for each table. Not a database id, not a
database or page URL. A database with several data sources needs one entry per data source.
`NotionTableSetConfig` refuses an `http(s)://` URL for a data source id because a URL names a database
or a page. (`NotionIdentityConfig`, the memory format, still accepts URLs; that is unchanged.)

**If a database id is configured by mistake**, the first query goes to `/v1/data_sources/<database id>/query`.
The guard turns a 404 on a configured table into a message that names the table and says to check
for a database id used in place of a data source id, and that the integration must be connected to the
database. That message is tested against the fake only.

**Not verifiable without a live call** (nothing in this repository has run against real Notion):

1. That the UUID in a `collection://` reference is accepted as-is by `/v1/data_sources/{id}`. The
   evidence is the Notion MCP tool descriptions, which describe a `data_source_id` as "the ID of the
   parent data source (collection)" and tell callers to use the `collection://` URL for it. That is
   strong, but it is the MCP's contract, not the REST API's.
2. That Notion answers a database id on the data source endpoint with a 404 `object_not_found`
   rather than another status. The hint is attached to 404 only.
3. That returned pages carry `parent.data_source_id` in 2025-09-03. It is documented; the mock
   server tests encode the belief.
4. That sharing a database with the integration grants access to its data sources, including for a
   database that has more than one.
5. Whether a rich-text `equals` filter on an idempotency key is case-sensitive and whitespace-exact
   (keys are restricted to trimmed text so the question does not arise for the usual cases), and how
   long a query lags behind a fresh create. The creator looks up after 0, 1 and 3 seconds; those
   waits are a guess to be tuned.

## 5. Property types

The client reads and writes title, rich text, select, checkbox, date and relation properties
(and reads created time). Every other property type is read as `Unsupported` and cannot be written.
Fields such as numbers, URLs, the native Status type, multi-select, people and formulas are not
handled yet; a consumer that needs them needs value types added first.
