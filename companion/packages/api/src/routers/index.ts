import { z } from "zod";
import { botActionSchema, settingsSchema } from "../contracts";
import { publicProcedure, router } from "../index";
import { runtime } from "../services/runtime";

const idInput = z.object({ id: z.uuid() });

export const appRouter = router({
	system: router({
		status: publicProcedure.query(async () => ({
			ok: true,
			version: "0.1.0",
			minecraft: await runtime.botHealth(),
			openai: await runtime.codex
				.account()
				.catch((error) => ({ account: null, error: String(error) })),
		})),
	}),
	openaiAuth: router({
		status: publicProcedure.query(() => runtime.codex.account()),
		models: publicProcedure.query(() => runtime.codex.models()),
		loginChatGpt: publicProcedure.mutation(() => runtime.codex.loginChatGpt()),
		loginApiKey: publicProcedure
			.input(z.object({ apiKey: z.string().min(20).max(500) }))
			.mutation(({ input }) => runtime.codex.loginApiKey(input.apiKey)),
		cancelLogin: publicProcedure
			.input(z.object({ loginId: z.string().min(1).max(200) }))
			.mutation(({ input }) => runtime.codex.cancelLogin(input.loginId)),
		logout: publicProcedure.mutation(() => runtime.codex.logout()),
	}),
	bot: router({
		pair: publicProcedure
			.input(z.object({ code: z.string().regex(/^\d{6}$/) }))
			.mutation(({ input }) => runtime.pair(input.code)),
		health: publicProcedure.query(() => runtime.botHealth()),
		snapshot: publicProcedure.query(() => runtime.snapshot()),
		action: publicProcedure
			.input(botActionSchema)
			.mutation(({ input }) => runtime.manualAction(input)),
		stop: publicProcedure.mutation(() => runtime.emergencyStop()),
	}),
	goals: router({
		start: publicProcedure
			.input(z.object({ goal: z.string().trim().min(3).max(1_000) }))
			.mutation(({ input }) => runtime.startGoal(input.goal)),
		current: publicProcedure.query(() => runtime.currentRun()),
		list: publicProcedure.query(() => runtime.listRuns()),
		get: publicProcedure
			.input(idInput)
			.query(({ input }) => runtime.getRun(input.id)),
		pause: publicProcedure
			.input(idInput)
			.mutation(({ input }) => runtime.pauseGoal(input.id)),
		resume: publicProcedure
			.input(
				idInput.extend({ userInput: z.string().trim().max(500).optional() }),
			)
			.mutation(({ input }) => runtime.resumeGoal(input.id, input.userInput)),
		cancel: publicProcedure
			.input(idInput)
			.mutation(({ input }) => runtime.cancelGoal(input.id)),
	}),
	settings: router({
		get: publicProcedure.query(() => runtime.settings()),
		update: publicProcedure
			.input(settingsSchema)
			.mutation(({ input }) => runtime.updateSettings(input)),
	}),
	knowledge: router({
		search: publicProcedure
			.input(
				z.object({
					query: z.string().trim().min(1).max(160),
					refresh: z.boolean().default(false),
				}),
			)
			.query(({ input }) =>
				runtime.knowledge.search(input.query, input.refresh),
			),
	}),
});

export type AppRouter = typeof appRouter;
