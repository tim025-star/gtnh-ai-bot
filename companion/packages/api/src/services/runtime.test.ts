import { db } from "@companion/db";
import { goalRuns, runEvents, settings } from "@companion/db/schema/index";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MinecraftHttpError } from "./minecraft";
import { RuntimeService } from "./runtime";

const bridge = vi.hoisted(() => ({
	snapshot: vi.fn(),
	action: vi.fn(),
	actionStatus: vi.fn(),
	stop: vi.fn(),
}));
const codex = vi.hoisted(() => ({
	startThread: vi.fn(),
	ensureThread: vi.fn(),
	runDecision: vi.fn(),
}));
vi.mock("@companion/env/server", () => ({
	env: {
		DATABASE_URL: "file::memory:",
		APP_DATA_DIR: "../../../output/takeover/runtime-test",
	},
}));
vi.mock("./minecraft", async (importOriginal) => ({
	...(await importOriginal<typeof import("./minecraft")>()),
	MinecraftClient: class {
		snapshot = bridge.snapshot;
		action = bridge.action;
		actionStatus = bridge.actionStatus;
		stop = bridge.stop;
	},
}));
vi.mock("./codex", () => ({
	CodexAppServer: class {
		startThread = codex.startThread;
		ensureThread = codex.ensureThread;
		runDecision = codex.runDecision;
	},
}));

function deferred<T>() {
	let resolve!: (value: T) => void;
	const promise = new Promise<T>((done) => {
		resolve = done;
	});
	return { promise, resolve };
}
async function startGoal(runtime: RuntimeService, goal: string) {
	const run = await runtime.startGoal(goal);
	if (!run) throw new Error("Goal run was not persisted");
	return run;
}

const decision = {
	kind: "action",
	rationale: "Observed target",
	action: { type: "goto", x: 1, y: 64, z: 1 },
};
const tracked = {
	id: "12345678-abcd",
	type: "goto",
	status: "running",
	phase: "moving",
	message: "Moving",
	createdAt: 1,
	updatedAt: 1,
};

describe("goal action lifecycle", () => {
	let runtime: RuntimeService;
	beforeEach(async () => {
		vi.restoreAllMocks();
		vi.clearAllMocks();
		runtime = new RuntimeService();
		await runtime.settings();
		await db.delete(runEvents);
		await db.delete(goalRuns);
		await db.update(settings).set({
			knowledgeEnabled: false,
			minecraftToken: "test-pairing-token",
			maxSteps: 40,
			maxSeconds: 180,
		});
		codex.startThread.mockResolvedValue("thread-one");
		codex.ensureThread.mockResolvedValue(undefined);
		codex.runDecision.mockResolvedValue({ kind: "complete", summary: "Done" });
		bridge.snapshot.mockResolvedValue({
			ok: true,
			controlSource: "idle",
			controlRevision: 0,
		});
		bridge.action.mockResolvedValue({ ok: true, action: tracked });
		bridge.actionStatus.mockResolvedValue({
			ok: true,
			action: { ...tracked, status: "completed" },
		});
		bridge.stop.mockResolvedValue({ ok: true });
	});

	it("reserves one goal even when start requests arrive together", async () => {
		const pending = deferred<unknown>();
		codex.runDecision.mockReturnValue(pending.promise);
		const results = await Promise.allSettled([
			runtime.startGoal("First"),
			runtime.startGoal("Second"),
		]);
		expect(
			results.filter((result) => result.status === "fulfilled"),
		).toHaveLength(1);
		pending.resolve({ kind: "complete", summary: "Done" });
		await vi.waitFor(async () =>
			expect((await runtime.currentRun())?.status).toBe("completed"),
		);
	});

	it("does not send a decision returned after cancellation", async () => {
		const pending = deferred<unknown>();
		codex.runDecision.mockReturnValue(pending.promise);
		const run = await startGoal(runtime, "Move");
		await vi.waitFor(() => expect(codex.runDecision).toHaveBeenCalled());
		const cancelling = runtime.cancelGoal(run.id);
		pending.resolve(decision);
		await cancelling;
		expect(bridge.action).not.toHaveBeenCalled();
		expect((await runtime.getRun(run.id))?.status).toBe("cancelled");
	});

	it("stops a goal that is still being initialized", async () => {
		const starting = runtime.startGoal("Move");
		await runtime.emergencyStop();
		const run = await starting;
		expect(run).not.toBeNull();
		expect((await runtime.currentRun())?.status).toBe("cancelled");
		expect(codex.runDecision).not.toHaveBeenCalled();
		expect(bridge.stop).toHaveBeenCalled();
	});

	it("waits for Minecraft completion before the next model step", async () => {
		const pending = deferred<unknown>();
		codex.runDecision
			.mockResolvedValueOnce(decision)
			.mockResolvedValue({ kind: "complete", summary: "Done" });
		bridge.actionStatus.mockReturnValueOnce(pending.promise);
		const run = await startGoal(runtime, "Move");
		await vi.waitFor(() => expect(bridge.actionStatus).toHaveBeenCalled());
		expect(codex.runDecision).toHaveBeenCalledTimes(1);
		pending.resolve({
			ok: true,
			action: { ...tracked, status: "completed", message: "Arrived" },
		});
		await vi.waitFor(async () =>
			expect((await runtime.getRun(run.id))?.status).toBe("completed"),
		);
		expect(codex.runDecision.mock.calls[1]?.[1]).toContain("Arrived");
	});

	it("rechecks the time budget when a model decision arrives", async () => {
		await db.update(settings).set({ maxSeconds: 1 });
		const pending = deferred<unknown>();
		codex.runDecision.mockReturnValue(pending.promise);
		const run = await startGoal(runtime, "Move");
		await vi.waitFor(() => expect(codex.runDecision).toHaveBeenCalled());
		const expired = Date.now() + 5_000;
		vi.spyOn(Date, "now").mockReturnValue(expired);
		pending.resolve(decision);
		await vi.waitFor(async () =>
			expect((await runtime.getRun(run.id))?.status).toBe("failed"),
		);
		expect(bridge.action).not.toHaveBeenCalled();
	});

	it("stops the game action when status polling fails", async () => {
		codex.runDecision.mockResolvedValue(decision);
		bridge.actionStatus.mockRejectedValue(new Error("Bridge disconnected"));
		const run = await startGoal(runtime, "Move");
		await vi.waitFor(async () =>
			expect((await runtime.getRun(run.id))?.status).toBe("failed"),
		);
		expect(bridge.stop).toHaveBeenCalled();
	});

	it("does not stop a different active goal when cancelling history", async () => {
		const old = await startGoal(runtime, "First");
		await vi.waitFor(async () =>
			expect((await runtime.getRun(old.id))?.status).toBe("completed"),
		);
		const pending = deferred<unknown>();
		codex.runDecision.mockReturnValue(pending.promise);
		const active = await startGoal(runtime, "Second");
		await runtime.cancelGoal(old.id);
		expect(bridge.stop).not.toHaveBeenCalled();
		pending.resolve({ kind: "complete", summary: "Done" });
		await vi.waitFor(async () =>
			expect((await runtime.getRun(active.id))?.status).toBe("completed"),
		);
	});

	it("preserves in-game control when Minecraft rejects a stale decision", async () => {
		codex.runDecision.mockResolvedValue(decision);
		bridge.action.mockRejectedValue(
			new MinecraftHttpError(409, "Minecraft control changed while planning"),
		);
		const run = await startGoal(runtime, "Move");
		await vi.waitFor(async () =>
			expect((await runtime.getRun(run.id))?.status).toBe("interrupted"),
		);
		expect(bridge.stop).not.toHaveBeenCalled();
	});
});
