import { randomUUID } from "node:crypto";
import {
	type BotAction,
	botActionSchema,
	minecraftActionResponseSchema,
} from "../contracts";

const MAX_RESPONSE_BYTES = 256 * 1024;

export class MinecraftHttpError extends Error {
	constructor(
		readonly status: number,
		message: string,
	) {
		super(message);
	}
}

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

	snapshot(signal?: AbortSignal) {
		return this.request("GET", "/v1/snapshot", undefined, true, signal);
	}

	async action(
		action: BotAction,
		actionId = randomUUID(),
		signal?: AbortSignal,
		expectedControlRevision?: number,
	) {
		const response = await this.request(
			"POST",
			"/v1/actions",
			{
				actionId,
				action: {
					...botActionSchema.parse(action),
					...(expectedControlRevision === undefined
						? {}
						: { expectedControlRevision }),
				},
			},
			true,
			signal,
		);
		return minecraftActionResponseSchema.parse(response);
	}

	async actionStatus(actionId: string, signal?: AbortSignal) {
		if (!/^[A-Za-z0-9-]{8,100}$/.test(actionId))
			throw new Error("Invalid Minecraft action ID");
		const response = await this.request(
			"GET",
			`/v1/actions/${encodeURIComponent(actionId)}`,
			undefined,
			true,
			signal,
		);
		return minecraftActionResponseSchema.parse(response);
	}

	stop() {
		return this.request("POST", "/v1/stop", {});
	}

	private async request(
		method: "GET" | "POST",
		path: string,
		body?: unknown,
		authenticated = true,
		signal?: AbortSignal,
	) {
		if (authenticated && !this.token)
			throw new Error("Minecraft is not paired");
		const controller = new AbortController();
		const timeout = setTimeout(() => controller.abort(), 10_000);
		try {
			const response = await fetch(`${this.baseUrl}${path}`, {
				method,
				signal: signal
					? AbortSignal.any([signal, controller.signal])
					: controller.signal,
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
				throw new MinecraftHttpError(
					response.status,
					String(payload.error ?? `Minecraft HTTP ${response.status}`),
				);
			return payload as Record<string, unknown>;
		} finally {
			clearTimeout(timeout);
		}
	}
}
