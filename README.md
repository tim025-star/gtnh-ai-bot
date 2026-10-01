# GTNH AI Bot

[![CI](https://github.com/tim025-star/gtnh-ai-bot/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/tim025-star/gtnh-ai-bot/actions/workflows/ci.yml)

GTNH AI Bot is an experimental AI assistant for **GT New Horizons (GTNH)**, the Minecraft 1.7.10 modpack. It combines a client-side Forge mod with a local web dashboard. You give it a goal in plain English, the AI plans an action using the observed game state, and the mod validates and executes that action inside Minecraft.

The companion uses the official Codex app-server for AI planning, with ChatGPT sign-in or an OpenAI Platform API key. You can also control the bot through typed dashboard actions and in-game commands.

## My goal for this project

My goal is to build a bot that can turn a plain-English goal into useful progress in GTNH. It needs to understand what the player has, what a recipe needs, where the ingredients are, which actions it can take, and whether those actions worked.

I want to grow this from basic movement and crafting into a bot that can plan longer recipe chains, work with GTNH machines, and handle more of the modpack's progression. The player should be able to see what it is doing, set limits, interrupt it, and take back control at any time.

## Current status

This is an **early experimental source release**. The mod and companion build and pass their automated checks. Real GTNH gameplay verification remains a release gate, so treat the current implementation as something to test and develop.

The current implementation includes:

- Manual movement, following, block interaction, item selection, and recipe diagnosis.
- AI goal planning with step, action, and time limits, pause/cancel controls, and persistent run history. The companion waits for an action's result before planning the next step.
- Crafting plans using loaded recipes, the player's inventory, and nearby chest contents. Execution currently supports the player grid and vanilla crafting tables.
- Local pairing and loopback-only connections. In-game commands can interrupt AI control.

Furnace and GregTech recipes are available for diagnosis. Machine crafting, custom workbench containers, tunnelling, and bridging still need implementation.

> Back up your world and supervise the bot. Its actions can move the player, consume items, and change blocks. Use it on servers only where the server's rules allow automation.

## Where I want to take it

1. Verify and improve movement, chest retrieval, and crafting in real GTNH sessions.
2. Expand crafting support to GTNH machines and their inventory interactions.
3. Plan and execute longer chains of tasks with useful progress and failure reporting.
4. Build toward broader GTNH progression while keeping player control and explicit limits.

## Architecture

The Minecraft client owns world observation, validation, and action execution. The TypeScript companion provides the dashboard, saved run history, sourced GTNH knowledge, and AI planning.

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

The companion waits for each Minecraft action to complete before planning the next step. Manual actions report completion or failure through dashboard notifications. An in-game command interrupts AI control, including a decision that was still being planned.

Crafting uses exact registry IDs, for example `/gtnhbot craft minecraft:stick 4`. The count is the desired total in the player's inventory. The bot resolves loaded recipes and dependencies, retrieves ingredients from nearby chests, and uses the player grid or a nearby vanilla crafting table. It checks observed inventory results after standard Minecraft container clicks. Furnace and GregTech recipes are indexed for diagnosis; their machine executors are not implemented.

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
- Automatic crafting executes player-grid and vanilla crafting-table recipes. Machine crafting and custom workbench containers are not supported.
- GTNH wiki results are advisory and are passed to the model as untrusted reference data.
- ChatGPT access depends on the user's Codex entitlement, workspace policy, and usage limits.
- A real GTNH gameplay smoke test is required before the first public binary release.

## License

MIT. See [LICENSE](LICENSE).
