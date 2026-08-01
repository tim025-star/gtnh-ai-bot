import "dotenv/config";
import { createEnv } from "@t3-oss/env-core";
import { z } from "zod";

export const env = createEnv({
	server: {
		DATABASE_URL: z.string().min(1).default("file:./gtnh-ai-bot.db"),
		HOST: z.enum(["127.0.0.1", "localhost"]).default("127.0.0.1"),
		PORT: z.coerce.number().int().min(1024).max(65535).default(3000),
		WEB_ORIGIN: z.url().default("http://127.0.0.1:3001"),
		MINECRAFT_URL: z.url().default("http://127.0.0.1:8246"),
		CODEX_PATH: z.string().optional(),
		APP_DATA_DIR: z.string().optional(),
		NODE_ENV: z
			.enum(["development", "production", "test"])
			.default("development"),
	},
	runtimeEnv: process.env,
	skipValidation: !!process.env.SKIP_ENV_VALIDATION,
	emptyStringAsUndefined: true,
});
