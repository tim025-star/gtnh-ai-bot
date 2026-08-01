# Architecture

## Trust boundaries

The browser is an untrusted input surface. Hono validates host, origin, method, and body size before tRPC handles a request. tRPC and Zod validate every public input. The companion can plan actions but cannot directly mutate Minecraft state.

The Java mod exposes five versioned loopback endpoints. Pairing exchanges a short-lived, one-use code for a random bearer token. Requests are size-limited and action IDs are idempotent. Game-state reads and writes are queued onto the Minecraft client tick thread.

## Goal loop

One goal run owns one persisted Codex thread. For each bounded step, the companion gathers a Minecraft snapshot and optional allowlisted knowledge, asks app-server for one schema-constrained decision, validates it with Zod, and sends at most one typed action to Minecraft. Authentication loss, malformed output, disconnection, or budget exhaustion stops the loop without executing another action.

Codex credentials live in a dedicated `codex-home`. Model turns receive a separate empty `agent-workspace` under a read-only sandbox, with network disabled and automatic approvals forbidden.

## Data

SQLite stores settings, run state, events, and cached knowledge. OpenAI OAuth tokens and API keys are never stored in these tables. Runs found active after a restart are marked interrupted and require explicit user resume.

## Release layout

The source repository contains the Forge mod and a Better-T-Stack pnpm workspace under `companion/`. Windows release packaging combines the mod JAR, built Hono/React application, production dependencies, Codex runtime, and a pinned portable Node LTS runtime.
