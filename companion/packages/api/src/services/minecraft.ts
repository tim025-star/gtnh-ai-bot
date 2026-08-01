import { randomUUID } from "node:crypto";
import { type BotAction, botActionSchema } from "../contracts";

const MAX_RESPONSE_BYTES = 256 * 1024;

export class MinecraftClient {
	constructor(
		private readonly baseUrl: string,
		private readonly token: string | null,
	) {
		const url = new URL(baseUrl);
		if (url.protocol !== "http:" || url.hostname !== "127.0.0.1") {
			throw new Error("Minecraft bridge URL must be loopback HTTP");
		}
	}

	health() {
		return this.request("GET", "/v1/health");
	}

	pair(code: string) {
		return this.request("POST", "/v1/pair", { code }, false);
	}

	snapshot() {
		return this.request("GET", "/v1/snapshot");
	}

	action(action: BotAction, actionId = randomUUID()) {
		return this.request("POST", "/v1/actions", {
			actionId,
			action: botActionSchema.parse(action),
		});
	}

	stop() {
		return this.request("POST", "/v1/stop", {});
	}

	private async request(
		method: "GET" | "POST",
		path: string,
		body?: unknown,
		authenticated = true,
	) {
		if (authenticated && !this.token)
			throw new Error("Minecraft is not paired");
		const controller = new AbortController();
		const timeout = setTimeout(() => controller.abort(), 10_000);
		try {
			const response = await fetch(`${this.baseUrl}${path}`, {
				method,
				signal: controller.signal,
				headers: {
					Accept: "application/json",
					...(body ? { "Content-Type": "application/json" } : {}),
					...(authenticated && this.token
						? { Authorization: `Bearer ${this.token}` }
						: {}),
				},
				body: body ? JSON.stringify(body) : undefined,
			});
			const text = await response.text();
			if (Buffer.byteLength(text) > MAX_RESPONSE_BYTES)
				throw new Error("Minecraft response exceeded limit");
			const payload = text ? JSON.parse(text) : {};
			if (!response.ok)
				throw new Error(payload.error ?? `Minecraft HTTP ${response.status}`);
			return payload as Record<string, unknown>;
		} finally {
			clearTimeout(timeout);
		}
	}
}
