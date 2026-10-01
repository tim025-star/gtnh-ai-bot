import { z } from "zod";

const coordinate = z.number().int().min(-30_000_000).max(30_000_000);
const side = z.number().int().min(0).max(5);

export const botActionSchema = z.discriminatedUnion("type", [
	z.object({
		type: z.literal("goto"),
		x: coordinate,
		y: z.number().int().min(0).max(255),
		z: coordinate,
	}),
	z.object({
		type: z.literal("follow"),
		player: z.string().trim().min(1).max(32),
	}),
	z.object({
		type: z.literal("break"),
		x: coordinate,
		y: z.number().int().min(0).max(255),
		z: coordinate,
	}),
	z.object({
		type: z.literal("use"),
		x: coordinate,
		y: z.number().int().min(0).max(255),
		z: coordinate,
		side,
	}),
	z.object({
		type: z.literal("place"),
		x: coordinate,
		y: z.number().int().min(0).max(255),
		z: coordinate,
		side,
	}),
	z.object({
		type: z.literal("craft"),
		item: z.string().trim().min(1).max(160),
		count: z.number().int().min(1).max(64).default(1),
	}),
	z.object({
		type: z.literal("selectItem"),
		item: z.string().trim().min(1).max(160),
	}),
	z.object({
		type: z.literal("diagnose"),
		query: z.string().trim().max(160).default(""),
	}),
]);

export type BotAction = z.infer<typeof botActionSchema>;

export const minecraftTrackedActionSchema = z.object({
	id: z.string().min(8).max(100),
	type: z.string().min(1).max(40),
	status: z.enum(["accepted", "running", "completed", "failed", "cancelled"]),
	phase: z.string().max(80),
	message: z.string().max(500),
	createdAt: z.number().int().nonnegative(),
	updatedAt: z.number().int().nonnegative(),
});

export const minecraftActionResponseSchema = z.object({
	ok: z.literal(true),
	action: minecraftTrackedActionSchema,
});

export type MinecraftTrackedAction = z.infer<
	typeof minecraftTrackedActionSchema
>;

export const agentDecisionSchema = z.discriminatedUnion("kind", [
	z.object({
		kind: z.literal("action"),
		rationale: z.string().max(500),
		action: botActionSchema,
	}),
	z.object({
		kind: z.literal("askUser"),
		question: z.string().min(1).max(500),
	}),
	z.object({
		kind: z.literal("complete"),
		summary: z.string().min(1).max(1000),
	}),
	z.object({ kind: z.literal("fail"), error: z.string().min(1).max(1000) }),
]);

export type AgentDecision = z.infer<typeof agentDecisionSchema>;

const agentDecisionWireSchema = z.object({
	kind: z.enum(["action", "askUser", "complete", "fail"]),
	rationale: z.string().max(500),
	actionType: z.enum([
		"goto",
		"follow",
		"break",
		"use",
		"place",
		"craft",
		"selectItem",
		"diagnose",
		"none",
	]),
	x: coordinate,
	y: z.number().int().min(0).max(255),
	z: coordinate,
	player: z.string().max(32),
	side,
	item: z.string().max(160),
	count: z.number().int().min(1).max(64),
	query: z.string().max(160),
	question: z.string().max(500),
	summary: z.string().max(1000),
	error: z.string().max(1000),
});

export type AgentDecisionWire = z.infer<typeof agentDecisionWireSchema>;

// Codex structured outputs require a strict object schema and reject the
// `oneOf` emitted for Zod discriminated unions. The wire format is deliberately
// flat; parseAgentDecision restores and validates the richer union before use.
export const agentDecisionJsonSchema = z.toJSONSchema(agentDecisionWireSchema);

export function parseAgentDecision(value: unknown): AgentDecision {
	const wire = agentDecisionWireSchema.parse(value);
	switch (wire.kind) {
		case "askUser":
			return agentDecisionSchema.parse({
				kind: wire.kind,
				question: wire.question,
			});
		case "complete":
			return agentDecisionSchema.parse({
				kind: wire.kind,
				summary: wire.summary,
			});
		case "fail":
			return agentDecisionSchema.parse({ kind: wire.kind, error: wire.error });
		case "action": {
			let action: unknown;
			switch (wire.actionType) {
				case "goto":
				case "break":
					action = {
						type: wire.actionType,
						x: wire.x,
						y: wire.y,
						z: wire.z,
					};
					break;
				case "follow":
					action = { type: wire.actionType, player: wire.player };
					break;
				case "use":
				case "place":
					action = {
						type: wire.actionType,
						x: wire.x,
						y: wire.y,
						z: wire.z,
						side: wire.side,
					};
					break;
				case "craft":
					action = {
						type: wire.actionType,
						item: wire.item,
						count: wire.count,
					};
					break;
				case "selectItem":
					action = { type: wire.actionType, item: wire.item };
					break;
				case "diagnose":
					action = { type: wire.actionType, query: wire.query };
					break;
				case "none":
					throw new Error("Action decision did not include an action type");
			}
			return agentDecisionSchema.parse({
				kind: wire.kind,
				rationale: wire.rationale,
				action,
			});
		}
	}
}

export const settingsSchema = z.object({
	minecraftUrl: z
		.url()
		.refine(
			(value) => new URL(value).hostname === "127.0.0.1",
			"Minecraft must use 127.0.0.1",
		),
	model: z.string().trim().max(100).nullable(),
	reasoningEffort: z.enum(["low", "medium", "high", "xhigh"]),
	maxSteps: z.number().int().min(1).max(100),
	maxSeconds: z.number().int().min(10).max(3600),
	maxActions: z.number().int().min(1).max(500),
	knowledgeEnabled: z.boolean(),
});

export type BotSettings = z.infer<typeof settingsSchema>;
