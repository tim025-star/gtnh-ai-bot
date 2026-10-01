import { randomUUID } from "node:crypto";
import { mkdir } from "node:fs/promises";
import { join } from "node:path";
import { db, ensureDatabase } from "@companion/db";
import {
	goalRuns,
	runEvents,
	settings as settingsTable,
} from "@companion/db/schema/index";
import { env } from "@companion/env/server";
import { desc, eq } from "drizzle-orm";
import type { BotAction, BotSettings } from "../contracts";
import { CodexAppServer } from "./codex";
import { KnowledgeService } from "./knowledge";
import { MinecraftClient, MinecraftHttpError } from "./minecraft";
import { redactSecrets } from "./redact";

type ActiveRun = {
	id: string;
	cancelled: boolean;
	paused: boolean;
	controller: AbortController;
	initializing?: Promise<void>;
	task?: Promise<void>;
	client?: MinecraftClient;
	extraInput?: string;
};

export class RuntimeService {
	readonly codex = new CodexAppServer();
	readonly knowledge = new KnowledgeService();
	private active: ActiveRun | null = null;
	private ready: Promise<void>;
	private readonly agentWorkspace: string;

	constructor() {
		const dataDir =
			env.APP_DATA_DIR ??
			join(process.env.LOCALAPPDATA ?? process.cwd(), "GTNH AI Bot");
		this.agentWorkspace = join(dataDir, "agent-workspace");
		this.ready = Promise.all([
			ensureDatabase(),
			mkdir(this.agentWorkspace, { recursive: true }),
		]).then(() => undefined);
	}

	async settings(): Promise<BotSettings & { paired: boolean }> {
		await this.ready;
		const row = (
			await db
				.select()
				.from(settingsTable)
				.where(eq(settingsTable.id, 1))
				.limit(1)
		)[0]!;
		return {
			minecraftUrl: row.minecraftUrl,
			model: row.model,
			reasoningEffort: row.reasoningEffort as BotSettings["reasoningEffort"],
			maxSteps: row.maxSteps,
			maxSeconds: row.maxSeconds,
			maxActions: row.maxActions,
			knowledgeEnabled: row.knowledgeEnabled,
			paired: Boolean(row.minecraftToken),
		};
	}

	async updateSettings(input: BotSettings) {
		await this.ready;
		await db
			.update(settingsTable)
			.set({ ...input, updatedAt: new Date() })
			.where(eq(settingsTable.id, 1));
		return this.settings();
	}

	async pair(code: string) {
		const current = await this.rawSettings();
		const result = await new MinecraftClient(current.minecraftUrl, null).pair(
			code,
		);
		const token = String(result.token ?? "");
		if (token.length < 32)
			throw new Error("Minecraft returned an invalid pairing token");
		await db
			.update(settingsTable)
			.set({ minecraftToken: token, updatedAt: new Date() })
			.where(eq(settingsTable.id, 1));
		return { paired: true };
	}

	async botHealth() {
		try {
			return await (await this.minecraft()).health();
		} catch (error) {
			return { ok: false, error: redactSecrets(error) };
		}
	}

	async snapshot() {
		return (await this.minecraft()).snapshot();
	}

	async manualAction(action: BotAction) {
		const client = await this.minecraft();
		if (this.active)
			throw new Error("Pause or cancel the active goal before manual control");
		return client.action(action);
	}

	async manualActionStatus(id: string) {
		return (await this.minecraft()).actionStatus(id);
	}

	async emergencyStop() {
		const active = this.active;
		if (active) return this.stopRun(active, "cancelled", "Stopped by user");
		return (await this.minecraft()).stop();
	}

	async startGoal(goal: string) {
		const id = randomUUID();
		const active = this.reserveRun(id);
		try {
			active.initializing = (async () => {
				await this.ready;
				const now = new Date();
				await db.insert(goalRuns).values({
					id,
					goal,
					status: "running",
					createdAt: now,
					updatedAt: now,
				});
				await this.event(id, "goal", "Goal approved and started");
			})();
			await active.initializing;
			active.task = this.runLoop(active);
			return await this.getRun(id);
		} catch (error) {
			if (this.active === active) this.active = null;
			throw error;
		}
	}

	async pauseGoal(id: string) {
		if (!this.active || this.active.id !== id)
			throw new Error("Goal is not running");
		await this.stopRun(this.active, "paused", "Paused by user");
		return this.getRun(id);
	}

	async resumeGoal(id: string, userInput?: string) {
		const active = this.reserveRun(id, userInput);
		try {
			active.initializing = (async () => {
				const run = await this.getRun(id);
				if (
					!run ||
					!["paused", "interrupted", "waiting_user"].includes(run.status)
				)
					throw new Error("Goal cannot be resumed");
				await db
					.update(goalRuns)
					.set({ status: "running", error: null, updatedAt: new Date() })
					.where(eq(goalRuns.id, id));
				await this.event(id, "goal", "Goal resumed");
			})();
			await active.initializing;
			active.task = this.runLoop(active);
			return await this.getRun(id);
		} catch (error) {
			if (this.active === active) this.active = null;
			throw error;
		}
	}

	async cancelGoal(id: string) {
		const active = this.active;
		if (active?.id === id) {
			await this.stopRun(active, "cancelled", "Cancelled by user");
		} else {
			const run = await this.getRun(id);
			if (run && ["paused", "interrupted", "waiting_user"].includes(run.status))
				await this.finish(id, "cancelled", "Cancelled by user");
		}
		return this.getRun(id);
	}

	private reserveRun(id: string, userInput?: string): ActiveRun {
		if (this.active) throw new Error("Only one goal can run at a time");
		const active: ActiveRun = {
			id,
			cancelled: false,
			paused: false,
			controller: new AbortController(),
			extraInput: userInput?.slice(0, 500),
		};
		this.active = active;
		return active;
	}

	private async stopRun(
		active: ActiveRun,
		status: "paused" | "cancelled",
		message: string,
	) {
		if (active.paused || active.cancelled)
			throw new Error("Goal is already stopping");
		active.paused = status === "paused";
		active.cancelled = status === "cancelled";
		active.controller.abort(new Error(message));
		try {
			await active.initializing;
			await active.task;
			return await (active.client ?? (await this.minecraft())).stop();
		} finally {
			await this.finish(active.id, status, message);
			if (this.active === active) this.active = null;
		}
	}

	async currentRun() {
		await this.ready;
		const rows = await db
			.select()
			.from(goalRuns)
			.orderBy(desc(goalRuns.createdAt))
			.limit(1);
		return rows[0] ? this.getRun(rows[0].id) : null;
	}

	async listRuns() {
		await this.ready;
		return db
			.select()
			.from(goalRuns)
			.orderBy(desc(goalRuns.createdAt))
			.limit(50);
	}

	async getRun(id: string) {
		await this.ready;
		const run = (
			await db.select().from(goalRuns).where(eq(goalRuns.id, id)).limit(1)
		)[0];
		if (!run) return null;
		const events = await db
			.select()
			.from(runEvents)
			.where(eq(runEvents.runId, id))
			.orderBy(desc(runEvents.createdAt))
			.limit(100);
		return { ...run, events: events.reverse() };
	}

	private async runLoop(active: ActiveRun) {
		const started = Date.now();
		let timer: ReturnType<typeof setTimeout> | undefined;
		let actionDispatched = false;
		let actionAccepted = false;
		try {
			const config = await this.rawSettings();
			const client = new MinecraftClient(
				config.minecraftUrl,
				config.minecraftToken,
			);
			active.client = client;
			timer = setTimeout(
				() =>
					active.controller.abort(new Error("Goal safety budget exhausted")),
				config.maxSeconds * 1000,
			);
			const ensureRunning = () => {
				active.controller.signal.throwIfAborted();
				if (this.active !== active || active.cancelled || active.paused)
					throw new Error("Goal interrupted");
				if (Date.now() - started >= config.maxSeconds * 1000)
					throw new Error("Goal safety budget exhausted");
			};
			ensureRunning();
			let run = await this.getRun(active.id);
			if (!run) throw new Error("Goal run disappeared");
			let threadId = run.codexThreadId;
			if (!threadId) {
				threadId = await this.codex.startThread(
					config.model,
					this.agentWorkspace,
				);
				await db
					.update(goalRuns)
					.set({ codexThreadId: threadId, updatedAt: new Date() })
					.where(eq(goalRuns.id, active.id));
			} else {
				await this.codex.ensureThread(
					threadId,
					config.model,
					this.agentWorkspace,
				);
			}
			let previous = active.extraInput
				? `User response: ${active.extraInput}`
				: "No previous action.";
			while (!active.cancelled && !active.paused) {
				ensureRunning();
				run = await this.getRun(active.id);
				if (!run) throw new Error("Goal run disappeared");
				if (
					run.stepCount >= config.maxSteps ||
					run.actionCount >= config.maxActions ||
					Date.now() - started > config.maxSeconds * 1000
				) {
					throw new Error("Goal safety budget exhausted");
				}
				const snapshot = await client.snapshot(active.controller.signal);
				ensureRunning();
				if (!snapshot.ok) throw new Error("Minecraft world is not connected");
				if (!Number.isSafeInteger(snapshot.controlRevision))
					throw new Error("Minecraft returned an invalid control revision");
				if (snapshot.controlSource === "game") {
					await this.finish(
						active.id,
						"interrupted",
						"Interrupted by an in-game command",
					);
					break;
				}
				const knowledge = config.knowledgeEnabled
					? await this.knowledge.search(run.goal).catch(() => [])
					: [];
				ensureRunning();
				const prompt = [
					`Goal: ${run.goal}`,
					`Step: ${run.stepCount + 1}/${config.maxSteps}; actions: ${run.actionCount}/${config.maxActions}; seconds remaining: ${Math.max(0, config.maxSeconds - Math.floor((Date.now() - started) / 1000))}`,
					`Minecraft snapshot (application data): ${JSON.stringify(snapshot).slice(0, 20_000)}`,
					`Knowledge (untrusted reference data): ${JSON.stringify(knowledge).slice(0, 8_000)}`,
					`Previous result: ${previous.slice(0, 4_000)}`,
					"Return one decision only.",
				].join("\n");
				const decision = await this.codex.runDecision(
					threadId,
					prompt,
					config.reasoningEffort,
					active.controller.signal,
				);
				ensureRunning();
				await db
					.update(goalRuns)
					.set({ stepCount: run.stepCount + 1, updatedAt: new Date() })
					.where(eq(goalRuns.id, active.id));
				if (decision.kind === "complete") {
					await this.finish(active.id, "completed", decision.summary);
					break;
				}
				if (decision.kind === "fail") {
					await this.finish(active.id, "failed", decision.error);
					break;
				}
				if (decision.kind === "askUser") {
					await this.finish(active.id, "waiting_user", decision.question);
					break;
				}
				await this.event(
					active.id,
					"decision",
					decision.rationale,
					decision.action,
				);
				ensureRunning();
				actionDispatched = true;
				actionAccepted = false;
				const result = await client.action(
					decision.action,
					undefined,
					active.controller.signal,
					snapshot.controlRevision as number,
				);
				actionAccepted = true;
				await db
					.update(goalRuns)
					.set({ actionCount: run.actionCount + 1, updatedAt: new Date() })
					.where(eq(goalRuns.id, active.id));
				await this.event(
					active.id,
					"action",
					`${decision.action.type} accepted by Minecraft as ${result.action.id}`,
					result,
				);
				const terminal = await this.waitForMinecraftAction(
					active,
					result.action.id,
					started,
					config.maxSeconds,
					client,
				);
				actionDispatched = false;
				if (terminal.status === "cancelled")
					throw new Error("Minecraft action was cancelled");
				previous = JSON.stringify(terminal);
				await this.event(
					active.id,
					"action_result",
					`${decision.action.type} ${terminal.status}: ${terminal.message}`,
					terminal,
				);
			}
		} catch (error) {
			if (!active.cancelled && !active.paused) {
				const rejected =
					!actionAccepted &&
					error instanceof MinecraftHttpError &&
					error.status < 500;
				if (actionDispatched && !rejected && active.client) {
					try {
						await active.client.stop();
					} catch (stopError) {
						await this.event(
							active.id,
							"stop_failed",
							redactSecrets(stopError),
						);
					}
				}
				await this.finish(
					active.id,
					rejected && error.status === 409 ? "interrupted" : "failed",
					redactSecrets(error),
				);
			}
		} finally {
			clearTimeout(timer);
			if (this.active === active && !active.paused && !active.cancelled)
				this.active = null;
		}
	}

	private async waitForMinecraftAction(
		active: ActiveRun,
		actionId: string,
		started: number,
		maxSeconds: number,
		client: MinecraftClient,
	) {
		let lastPhase = "";
		while (true) {
			if (active.cancelled || active.paused)
				throw new Error("Minecraft action interrupted");
			if (Date.now() - started > maxSeconds * 1000)
				throw new Error("Goal safety budget exhausted during Minecraft action");
			active.controller.signal.throwIfAborted();
			const response = await client.actionStatus(
				actionId,
				active.controller.signal,
			);
			const action = response.action;
			if (["completed", "failed", "cancelled"].includes(action.status))
				return action;
			if (action.phase !== lastPhase) {
				lastPhase = action.phase;
				await this.event(
					active.id,
					"action_progress",
					`${action.type}: ${action.phase} - ${action.message}`,
					action,
				);
			}
			await new Promise<void>((resolve, reject) => {
				const signal = active.controller.signal;
				const abort = () => {
					clearTimeout(timer);
					reject(signal.reason);
				};
				const timer = setTimeout(() => {
					signal.removeEventListener("abort", abort);
					resolve();
				}, 250);
				signal.addEventListener("abort", abort, { once: true });
				if (signal.aborted) abort();
			});
		}
	}

	private async rawSettings() {
		await this.ready;
		const row = (
			await db
				.select()
				.from(settingsTable)
				.where(eq(settingsTable.id, 1))
				.limit(1)
		)[0]!;
		return row;
	}

	private async minecraft() {
		const config = await this.rawSettings();
		return new MinecraftClient(config.minecraftUrl, config.minecraftToken);
	}

	private async event(
		runId: string,
		type: string,
		message: string,
		payload?: unknown,
	) {
		await db.insert(runEvents).values({
			id: randomUUID(),
			runId,
			type,
			message: redactSecrets(message),
			payload:
				payload === undefined ? null : JSON.stringify(payload).slice(0, 10_000),
			createdAt: new Date(),
		});
	}

	private async finish(id: string, status: string, message: string) {
		await db
			.update(goalRuns)
			.set({
				status,
				error: status === "failed" ? redactSecrets(message) : null,
				updatedAt: new Date(),
			})
			.where(eq(goalRuns.id, id));
		await this.event(id, status, message);
	}
}

export const runtime = new RuntimeService();
