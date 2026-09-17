# arx-box dev/POC release manifest

Pinned component identities for the Director/Hermes-WebUI dev stack on
arx-box, as actually installed and verified. This is a local, unsigned
manifest for reproducible dev-loop installs — it is not the customer-facing
signed OTA app manifest (`halo_onboard.updates.manifest`), which has its own,
separate `components`/`restart_plan`/`signature` shape and governs the
shipped hermes-halo/hermes-webui/llama-server/system components only.

Update this file whenever a pinned identity below actually changes (a new
vendor image digest, a new/removed vendor patch, a JDK bump). It is not
regenerated automatically.

| Component | Identity | Notes |
|---|---|---|
| engram-engine | `git rev-parse HEAD` of this checkout | Not hardcoded here — this file travels with the code, so the running version is always whatever commit it's checked out at. |
| JDK | Temurin 21.0.12.1+1, user-scoped at `/home/halo/.local/jdks/jdk-21.0.12.1+1` | Selected by `~/.gradle/gradle.properties`; system Java is untouched. |
| ArcadeDB | 25.1.1 (`arcadedb-engine` + `arcadedb-integration`) | Pinned in `build.gradle.kts`; embedded, no separate server/container. |
| hermes-webui image (dev instance) | `ghcr.io/nesquena/hermes-webui@sha256:48ba6ee4a837079955c00e997b751065cc0324ae6eaa0f2fec592c8f4b2a746e` (v0.52.113) | Same pinned digest as the production `hermes-webui` container — one vendor image, two independent instances/configs. Never modified in place; patches are bind-mounted over `/apptoo`, not baked into the image. |
| Vendor patches (dev instance only) | `webui-bridge/vendor-patches/routes_py_runner_session_persistence.patch`, `routes_py_runner_cancel_routing.patch`, `messages_js_model_chip_fallback.patch` | Regenerated/applied via `webui-bridge/vendor-patches/apply.sh`. Production `hermes-webui` carries none of these. |
| Hermes agent (one-shot delegation image) | `halo-home/hermes-halo:0.0.1` | Shared, untouched image — the same one production's `hermes-halo` container runs. Each delegation runs it one-shot (`docker run --rm`) against an isolated `hermes-dev/` home/workspace, never the production home dir. |
| Model | Qwen3.6-35B-A3B-UD-Q5_K_XL.gguf, served by the existing `llama-server.service` | Untouched, shared with production `hermes-halo`. Not part of this manifest's install/update/rollback — owned by the box's own model-select/OTA tooling. |
| Docker | 29.7.1 | Host-installed; not managed by this manifest. |

## What this manifest does *not* cover

- The vendor product stack (`llama-server.service`, `hermes-halo`/`hermes-webui`/`signal-cli` via `/opt/halo-onboard/docker/docker-compose.yml`, and the whole `halo-onboard` OTA/backup pipeline) — untouched by this work, and not this manifest's responsibility.
- Secret *values* (debug token, WebUI password, runner API key) and persistent *data* (`data/dev/engram-db`, `hermes-webui-dev/home`, `hermes-dev/home`) — deliberately outside version control and outside this manifest. See `deploy/README.md` for their paths.
