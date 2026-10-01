# Architecture

## Trust boundaries

The browser is an untrusted input surface. Hono validates host, origin, method, and body size before tRPC handles a request. tRPC and Zod validate every public input. The companion can plan actions but cannot directly mutate Minecraft state.

The Java mod exposes versioned loopback endpoints for health, pairing, snapshots, actions, action status, and stop. Pairing exchanges a short-lived, one-use code for a random bearer token. Requests are size-limited. Action IDs are idempotent while retained in the bounded action cache, and pending actions are never evicted. Game-state reads and writes are queued onto the Minecraft client tick thread; requests that time out before execution are cancelled.

## Goal loop

One goal run owns one persisted Codex thread. For each bounded step, the companion gathers a Minecraft snapshot and optional allowlisted knowledge, asks app-server for one schema-constrained decision, validates it with Zod, and sends at most one typed action to Minecraft. Authentication loss, malformed output, disconnection, or budget exhaustion stops the loop without executing another action.

Codex receives a strict flat object schema; the companion converts it to the typed decision union and validates it before dispatch. Persisted threads are resumed after app-server restarts. Concurrent account/model requests share one initialized process.

The companion waits for the action's terminal status and feeds that result into the next planning step. Pause and cancellation abort pending planning and polling, retain goal ownership until cleanup finishes, and stop Minecraft. Failures during an owned action also request a stop. AI actions include the snapshot's control revision, which the mod checks on its client thread before execution so a newer in-game command keeps control.

Codex credentials live in a dedicated `codex-home`. Model turns receive a separate empty `agent-workspace` under a read-only sandbox, with network disabled and automatic approvals forbidden.

## Crafting

The client indexes loaded vanilla, Forge ore-dictionary, furnace, and GregTech recipes incrementally. A bounded planner reserves inventory and chest resources, explores alternative recipes without consuming another candidate's reservations, and orders dependencies before their consumers. Reusable ingredients are retrieved once for repeated crafts. Furnace and GregTech recipes are diagnostic entries with no executable station adapter.

The executor uses standard Minecraft container clicks for retrieval, crafting, and hotbar selection. Chest planning preserves damage and NBT, and vanilla double chests share one cache entry. The executor checks observed inventory changes, reports missing ingredients and unreachable stations, and clears its crafting grid when finished or stopped. Real GTNH gameplay verification remains required before a binary release.

## Data

SQLite stores settings, run state, events, and cached knowledge. OpenAI OAuth tokens and API keys are never stored in these tables. Runs found active after a restart are marked interrupted and require explicit user resume.

## Release layout

The source repository contains the Forge mod and a Better-T-Stack pnpm workspace under `companion/`. Windows release packaging combines the mod JAR, built Hono/React application, production dependencies, Codex runtime, and a pinned portable Node LTS runtime.
