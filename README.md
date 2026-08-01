# GTNH AI Bot

GTNH AI Bot is a local-first automation mod and control dashboard for GT New Horizons on Minecraft 1.7.10. The Minecraft client owns all world observation, validation, and action execution. A TypeScript companion provides the web dashboard, durable run history, sourced GTNH knowledge, and optional planning through the official Codex app-server.

> This is an early release. Automation can move the player, craft items, and interact with blocks. Back up your world and supervise destructive goals.

## Architecture

```text
Browser on 127.0.0.1
        │ tRPC + Zod
        ▼
TypeScript companion ─── Codex app-server ─── ChatGPT login or API key
        │ authenticated, versioned JSON
        ▼
Minecraft client mod ─── validation ─── client tick thread ─── game actions
```

The dashboard and Minecraft bridge bind only to loopback. The companion never reads or stores Codex OAuth tokens. API keys are passed directly to app-server and are not written to the GTNH AI Bot database.

## Requirements

- GT New Horizons / Forge for Minecraft 1.7.10
- Java 8 for the game
- Java 21 or newer to run Gradle
- Node.js 24 and pnpm 11 for source development
- Windows 11 for the packaged v1 companion

## Run from source

Build the mod:

```powershell
.\gradlew.bat --no-configuration-cache build
```

Install the resulting JAR from `build\libs` into the client `mods` folder. Then start the companion:

```powershell
cd companion
pnpm install --frozen-lockfile
pnpm dev
```

Open `http://127.0.0.1:3001`. In Minecraft, run `/gtnhbot pair`, enter the six-digit code in Setup, then sign in with ChatGPT or use an OpenAI Platform API key.

## Controls

The dashboard supports natural-language goals and typed manual actions. The in-game command is also available:

```text
/gtnhbot pair
/gtnhbot goto <x> <y> <z>
/gtnhbot follow <player>
/gtnhbot break <x> <y> <z>
/gtnhbot use <x> <y> <z> <side>
/gtnhbot place <x> <y> <z> <side>
/gtnhbot craft <item> [count]
/gtnhbot status [target]
/gtnhbot diagnose [target]
/gtnhbot stop
/gtnhbot list
```

Only one web goal runs at a time. Every goal has independently enforced step, action, and wall-clock budgets. Pause, cancel, and emergency stop remain available while a goal is running.

## Development checks

```powershell
.\gradlew.bat --no-configuration-cache test
cd companion
pnpm check
pnpm check-types
pnpm test
pnpm build
```

See [Architecture](docs/ARCHITECTURE.md), [Contributing](CONTRIBUTING.md), and [Security](SECURITY.md) before making changes or exposing new interfaces.

## Current limitations

- V1 controls one local Minecraft client.
- Pathfinding handles ordinary walkable terrain; it is not a full tunnelling or bridging implementation.
- GTNH wiki results are advisory and are passed to the model as untrusted reference data.
- ChatGPT access depends on the user's Codex entitlement, workspace policy, and usage limits.
- A real GTNH gameplay smoke test is required before the first public binary release.

## License

MIT. See [LICENSE](LICENSE).
