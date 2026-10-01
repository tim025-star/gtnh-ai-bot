import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CodexAppServer } from "./codex";

vi.mock("@companion/env/server", () => ({
	env: {
		APP_DATA_DIR: "../../../output/takeover/codex-test",
		CODEX_PATH: fileURLToPath(
			new URL("../../test/fixtures/codex-app-server.mjs", import.meta.url),
		),
	},
}));

describe("Codex stdio lifecycle", () => {
	let server: CodexAppServer;
	afterEach(() => {
		// The fixture owns no credentials and makes no external requests.
		const child = (
			server as unknown as { process: { kill: () => void } | null }
		)?.process;
		child?.kill();
	});

	it("shares initialization between simultaneous status requests", async () => {
		server = new CodexAppServer();
		const [account, models] = await Promise.all([
			server.account(),
			server.models(),
		]);
		expect(account.pid).toBe(models.pid);
	});

	it("loads persisted threads and accepts early completion notifications", async () => {
		server = new CodexAppServer();
		await server.ensureThread("persisted-thread", null, process.cwd());
		expect(
			await server.runDecision("persisted-thread", "complete", "medium"),
		).toEqual({ kind: "complete", summary: "Done" });
	});

	it("interrupts an aborted turn and can plan again on the same thread", async () => {
		server = new CodexAppServer();
		const thread = await server.startThread(null, process.cwd());
		const controller = new AbortController();
		const pending = server.runDecision(
			thread,
			"wait",
			"medium",
			controller.signal,
		);
		const rejected = expect(pending).rejects.toThrow("Paused");
		await vi.waitFor(() =>
			expect(
				(server as unknown as { turnWaiters: Map<string, unknown> }).turnWaiters
					.size,
			).toBe(1),
		);
		controller.abort(new Error("Paused"));
		await rejected;
		expect(await server.runDecision(thread, "complete", "medium")).toEqual({
			kind: "complete",
			summary: "Done",
		});
	});
});
