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
import { MinecraftClient } from "./minecraft";
import { redactSecrets } from "./redact";

type ActiveRun = {
	id: string;
	cancelled: boolean;
	paused: boolean;
	extraInput?: string;
};

export class RuntimeService {
	readonly codex = new CodexAppServer();
	readonly knowledge = new KnowledgeService();
	private active: ActiveRun | null = null;
	private ready: Promise<void>;
	private readonly dataDir: string;

	constructor() {
		this.dataDir =
			env.APP_DATA_DIR ??
			join(process.env.LOCALAPPDATA ?? process.cwd(), "GTNH AI Bot");
		this.ready = Promise.all([
			ensureDatabase(),
			mkdir(this.dataDir, { recursive: true }),
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
		if (this.active)
			throw new Error("Pause or cancel the active goal before manual control");
		return (await this.minecraft()).action(action);
	}

	async emergencyStop() {
		if (this.active) this.active.cancelled = true;
		const result = await (await this.minecraft()).stop();
		if (this.active)
			await this.finish(this.active.id, "cancelled", "Stopped by user");
		this.active = null;
		return result;
	}

	async startGoal(goal: string) {
		await this.ready;
		if (this.active) throw new Error("Only one goal can run at a time");
		const id = randomUUID();
		const now = new Date();
		await db
			.insert(goalRuns)
			.values({ id, goal, status: "running", createdAt: now, updatedAt: now });
		this.active = { id, cancelled: false, paused: false };
		await this.event(id, "goal", "Goal approved and started");
		void this.runLoop(this.active);
		return this.getRun(id);
	}

	async pauseGoal(id: string) {
		if (!this.active || this.active.id !== id)
			throw new Error("Goal is not running");
		this.active.paused = true;
		await (await this.minecraft()).stop();
		await this.finish(id, "paused", "Paused by user");
		this.active = null;
		return this.getRun(id);
	}

	async resumeGoal(id: string, userInput?: string) {
		if (this.active) throw new Error("Another goal is active");
		const run = await this.getRun(id);
		if (!run || !["paused", "interrupted", "waiting_user"].includes(run.status))
			throw new Error("Goal cannot be resumed");
		await db
			.update(goalRuns)
			.set({ status: "running", error: null, updatedAt: new Date() })
			.where(eq(goalRuns.id, id));
		this.active = {
			id,
			cancelled: false,
			paused: false,
			extraInput: userInput?.slice(0, 500),
		};
		await this.event(id, "goal", "Goal resumed");
		void this.runLoop(this.active);
		return this.getRun(id);
	}

	async cancelGoal(id: string) {
		if (this.active?.id === id) this.active.cancelled = true;
		try {
			await (await this.minecraft()).stop();
		} catch {}
		await this.finish(id, "cancelled", "Cancelled by user");
		if (this.active?.id === id) this.active = null;
		return this.getRun(id);
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
		try {
			const config = await this.rawSettings();
			let run = await this.getRun(active.id);
			if (!run) throw new Error("Goal run disappeared");
			let threadId = run.codexThreadId;
			if (!threadId) {
				threadId = await this.codex.startThread(config.model, this.dataDir);
				await db
					.update(goalRuns)
					.set({ codexThreadId: threadId, updatedAt: new Date() })
					.where(eq(goalRuns.id, active.id));
			}
			let previous = active.extraInput
				? `User response: ${active.extraInput}`
				: "No previous action.";
			while (!active.cancelled && !active.paused) {
				run = await this.getRun(active.id);
				if (!run) throw new Error("Goal run disappeared");
				if (
					run.stepCount >= config.maxSteps ||
					run.actionCount >= config.maxActions ||
					Date.now() - started > config.maxSeconds * 1000
				) {
					throw new Error("Goal safety budget exhausted");
				}
				const snapshot = await (await this.minecraft()).snapshot();
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
				);
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
				const result = await (await this.minecraft()).action(decision.action);
				await db
					.update(goalRuns)
					.set({ actionCount: run.actionCount + 1, updatedAt: new Date() })
					.where(eq(goalRuns.id, active.id));
				previous = JSON.stringify(result);
				await this.event(
					active.id,
					"action",
					`${decision.action.type} accepted by Minecraft`,
					result,
				);
				await new Promise((resolve) => setTimeout(resolve, 750));
			}
		} catch (error) {
			if (!active.cancelled && !active.paused)
				await this.finish(active.id, "failed", redactSecrets(error));
		} finally {
			if (this.active === active) this.active = null;
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
