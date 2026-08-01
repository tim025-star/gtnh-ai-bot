# Security policy

## Supported version

Only the latest release is supported while the project is pre-1.0.

## Report a vulnerability

Do not open a public issue for authentication bypasses, unsafe game-action execution, credential exposure, or remote-access vulnerabilities. Contact the maintainer privately through GitHub Security Advisories after the repository is published.

Include reproduction steps, affected version, impact, and any suggested mitigation. Please allow reasonable time for investigation before disclosure.

## Security boundaries

- The dashboard and Minecraft API are loopback-only and are not designed for LAN or internet exposure.
- Minecraft actions require a paired bearer token and are revalidated inside the mod.
- OpenAI credentials are owned and stored by Codex, not by this application's database.
- A submitted goal authorizes only the displayed action set and configured safety budgets.

Treat any change to host/origin checks, pairing, action schemas, body limits, or Minecraft-thread handoff as security-sensitive.
