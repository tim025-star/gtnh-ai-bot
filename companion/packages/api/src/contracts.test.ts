import { describe, expect, it } from "vitest";
import {
	agentDecisionJsonSchema,
	agentDecisionSchema,
	botActionSchema,
	minecraftActionResponseSchema,
	parseAgentDecision,
	settingsSchema,
} from "./contracts";
import { safeKnowledgeUrl } from "./services/knowledge";
import { redactSecrets } from "./services/redact";

describe("public contracts", () => {
	it("accepts bounded actions", () => {
		expect(
			botActionSchema.parse({ type: "goto", x: 10, y: 64, z: -10 }),
		).toEqual({ type: "goto", x: 10, y: 64, z: -10 });
		expect(() =>
			botActionSchema.parse({ type: "place", x: 0, y: 500, z: 0, side: 9 }),
		).toThrow();
	});

	it("validates tracked Minecraft action lifecycle responses", () => {
		expect(
			minecraftActionResponseSchema.parse({
				ok: true,
				action: {
					id: "12345678-abcd",
					type: "craft",
					status: "running",
					phase: "planning",
					message: "Resolving recipe dependencies",
					createdAt: 1,
					updatedAt: 2,
				},
			}).action.phase,
		).toBe("planning");
		expect(() =>
			minecraftActionResponseSchema.parse({
				ok: true,
				action: { id: "short", status: "done" },
			}),
		).toThrow();
	});

	it("rejects malformed model decisions", () => {
		expect(
			agentDecisionSchema.parse({ kind: "complete", summary: "Finished" }).kind,
		).toBe("complete");
		expect(() =>
			agentDecisionSchema.parse({
				kind: "action",
				action: { type: "deleteWorld" },
			}),
		).toThrow();
	});

	it("uses a Codex-compatible decision output schema", () => {
		expect(agentDecisionJsonSchema.type).toBe("object");
		expect(JSON.stringify(agentDecisionJsonSchema)).not.toContain('"oneOf"');
		expect(JSON.stringify(agentDecisionJsonSchema)).not.toContain('"anyOf"');
	});

	it("restores and validates decisions from the flat wire format", () => {
		const base = {
			kind: "action" as const,
			rationale: "Move to the inspected location",
			actionType: "goto" as const,
			x: 10,
			y: 64,
			z: -10,
			player: "",
			side: 0,
			item: "",
			count: 1,
			query: "",
			question: "",
			summary: "",
			error: "",
		};
		expect(parseAgentDecision(base)).toEqual({
			kind: "action",
			rationale: base.rationale,
			action: { type: "goto", x: 10, y: 64, z: -10 },
		});
		expect(() => parseAgentDecision({ ...base, actionType: "none" })).toThrow(
			"did not include an action type",
		);
	});

	it("requires loopback Minecraft URLs", () => {
		const base = {
			model: null,
			reasoningEffort: "medium",
			maxSteps: 40,
			maxSeconds: 180,
			maxActions: 160,
			knowledgeEnabled: true,
		};
		expect(
			settingsSchema.parse({ ...base, minecraftUrl: "http://127.0.0.1:8246" })
				.minecraftUrl,
		).toContain("127.0.0.1");
		expect(() =>
			settingsSchema.parse({ ...base, minecraftUrl: "http://example.com" }),
		).toThrow();
	});

	it("redacts API keys and bearer tokens", () => {
		const output = redactSecrets(
			`${["sk", "abcdefghijklmnopqrstuvwxyz"].join("-")} Bearer abc.def.ghi`,
		);
		expect(output).not.toContain("abcdefghijklmnopqrstuvwxyz");
		expect(output).not.toContain("abc.def.ghi");
	});

	it("allows only HTTPS GTNH knowledge hosts", () => {
		expect(
			safeKnowledgeUrl("https://gtnh.miraheze.org/wiki/Main_Page").hostname,
		).toBe("gtnh.miraheze.org");
		expect(() =>
			safeKnowledgeUrl("http://gtnh.miraheze.org/wiki/Test"),
		).toThrow();
		expect(() => safeKnowledgeUrl("https://example.com/wiki/Test")).toThrow();
	});
});
