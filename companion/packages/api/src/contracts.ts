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

export const agentDecisionJsonSchema = z.toJSONSchema(agentDecisionSchema);

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
