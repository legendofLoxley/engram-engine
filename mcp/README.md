# alfrd MCP server

One tool, `horizon`, over stdio. It calls the engine's `POST /owner/horizon` (loopback, `127.0.0.1:8082`) with the owner token and returns
the refreshed Horizon as text. Design: "Channels, the Horizon tool and the alfrd connector" (Notion, Nov 7).

- `alfrd_mcp.py` — the server (official MCP SDK, FastMCP).
- `ops.schema.json` — the `operations` verbs (including the cite-a-source `record_item`, `set_state`, and the state and time fields) and the `sources` field, generated from `Interpreter.OWNER_TOOL_SCHEMA`; `OwnerHorizonTest` fails if it drifts
  (regenerate with `UPDATE_OPS_SCHEMA=1 ./gradlew test --tests '*OwnerHorizonTest*'`).
- `alfrd-mcp` — launcher; install as `~/alfrd/bin/alfrd-mcp`. `ALFRD_REPO` points at the checkout holding `mcp/.venv`.

Setup on the box: `python3 -m venv mcp/.venv && mcp/.venv/bin/pip install -r mcp/requirements.txt`.
The engine serves the route only when `OWNER_IDENTITY` is set (e.g. `owner-jacob`); the token lives in `~/.config/engram/owner-token` (mode 600, created by the engine).
Overrides: `ALFRD_ENGINE_URL`, `ALFRD_OWNER_TOKEN_FILE`.

## Claude Desktop (Windows) `claude_desktop_config.json`

```json
{
  "mcpServers": {
    "alfrd": {
      "command": "ssh",
      "args": [
        "-i", "C:\\Users\\yarda\\.ssh\\alfrd_box",
        "-o", "IdentitiesOnly=yes",
        "-o", "BatchMode=yes",
        "halo@100.96.137.82",
        "/home/halo/alfrd/bin/alfrd-mcp"
      ]
    }
  }
}
```
