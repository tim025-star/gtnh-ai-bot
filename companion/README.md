# GTNH AI Bot companion

This workspace contains the local dashboard and planning service for [GTNH AI Bot](../README.md). The Minecraft mod observes the world and executes game actions; the companion connects the player, the mod, and the official Codex app-server.

See the [project README](../README.md) for the project's goal, current capabilities, requirements, and limitations.

## Run for development

Use Node.js 24 and the pnpm version pinned in `package.json`.

```powershell
pnpm install --frozen-lockfile
pnpm dev
```

Open the dashboard at [http://127.0.0.1:3001](http://127.0.0.1:3001). The API listens on [http://127.0.0.1:3000](http://127.0.0.1:3000). Install and launch the Minecraft mod, run `/gtnhbot pair`, and enter the pairing code in the dashboard. Sign in through Codex to use AI goals.

The companion creates its local SQLite tables on startup. The `.env.example` files show the development defaults. Keep credentials, local databases, and Codex account files out of Git.

## Workspace layout

- `apps/web`: React dashboard, manual controls, goal progress, and settings.
- `apps/server`: Hono HTTP server with host/origin checks and tRPC routes.
- `packages/api`: Validated action contracts, goal lifecycle, Minecraft bridge client, Codex process management, and GTNH knowledge lookup.
- `packages/db`: SQLite schema, settings, run history, and cached knowledge.
- `packages/env`: Validated configuration and loopback defaults.
- `packages/ui`: Shared UI primitives and styles.
- `packages/config`: Shared TypeScript configuration.

The workspace started from [Better-T-Stack](https://github.com/AmanVarshney01/create-better-t-stack).

## Checks and build

```powershell
pnpm check
pnpm check-types
pnpm test
pnpm audit --prod
pnpm build
```

See [Architecture](../docs/ARCHITECTURE.md) for the trust boundaries and data flow, and [Contributing](../CONTRIBUTING.md) for development expectations.
