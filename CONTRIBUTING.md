# Contributing

Use Java 21 for Gradle, retain Java 8-compatible mod code, and use the pnpm version pinned in `companion/package.json`.

Before opening a pull request:

1. Keep Minecraft action authority inside the mod.
2. Add only the smallest tests needed for changed security or behavior boundaries.
3. Run the Java and TypeScript checks documented in the README.
4. Never commit credentials, generated worlds, logs, caches, databases, or release artifacts.
5. Describe manual GTNH verification when the change affects gameplay.

Changes that expose a non-loopback listener or weaken pairing require an explicit threat-model update.
