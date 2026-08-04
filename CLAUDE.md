# CLAUDE.md

Guidance for Claude Code (and similar agents) working in this repository.

## Observability

Production logs (DigitalOcean App Platform deployment, `master` branch, auto-deployed
on push per `.do/app.yaml`) are collected by **Better Stack** under the source
`engram-engine-prod` (source id `2650284`, platform `digitalocean`, region `eu-fsn-3`).

### Access

Log access is via Better Stack's MCP server, registered in the local Claude Code
user-level MCP config (`~/.claude.json`) — this is machine/environment config, not
part of this repo:

```json
{
  "mcpServers": {
    "betterstack": {
      "type": "http",
      "url": "https://mcp.betterstack.com",
      "headers": {
        "Authorization": "Bearer ${BETTERSTACK_API_TOKEN}"
      }
    }
  }
}
```

- **Transport**: HTTP, not OAuth — chosen because headless/CI environments have no
  browser to complete an OAuth flow.
- **Auth**: header-based Bearer token, resolved from the `BETTERSTACK_API_TOKEN`
  environment variable at connect time. The token is never written into the config
  file or committed anywhere.
- **Tool permissions** are scoped read-only in this repo's `.claude/settings.local.json`:
  `mcp__betterstack__sources`, `mcp__betterstack__query_help`, `mcp__betterstack__query`.

### Querying logs

The `query` tool runs ClickHouse SQL against two collections per source:

| Collection | Table function | Coverage |
|---|---|---|
| `t578556_engram_engine_prod_logs` | `remote(...)` | Hot tier — last ~30 minutes |
| `t578556_engram_engine_prod_s3` | `s3Cluster(primary, ...)` | Cold tier — everything older; filter `_row_type = 1` for log rows |

Log lines are JSON in a `raw` column; extract fields with
`JSONExtract(raw, 'field', 'Nullable(String)')`. Example — last 30 minutes of
`CognitivePipeline` turn logs:

```sql
SELECT
  dt,
  JSONExtract(raw, 'message', 'Nullable(String)') AS message
FROM remote(t578556_engram_engine_prod_logs)
WHERE dt > now() - INTERVAL 30 MINUTE
ORDER BY dt DESC
LIMIT 100
```
