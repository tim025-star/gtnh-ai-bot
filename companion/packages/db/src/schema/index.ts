import { integer, sqliteTable, text } from "drizzle-orm/sqlite-core";

export const settings = sqliteTable("settings", {
	id: integer("id").primaryKey().default(1),
	minecraftUrl: text("minecraft_url")
		.notNull()
		.default("http://127.0.0.1:8246"),
	minecraftToken: text("minecraft_token"),
	model: text("model"),
	reasoningEffort: text("reasoning_effort").notNull().default("medium"),
	maxSteps: integer("max_steps").notNull().default(40),
	maxSeconds: integer("max_seconds").notNull().default(180),
	maxActions: integer("max_actions").notNull().default(160),
	knowledgeEnabled: integer("knowledge_enabled", { mode: "boolean" })
		.notNull()
		.default(true),
	updatedAt: integer("updated_at", { mode: "timestamp_ms" }).notNull(),
});

export const goalRuns = sqliteTable("goal_runs", {
	id: text("id").primaryKey(),
	goal: text("goal").notNull(),
	status: text("status").notNull(),
	codexThreadId: text("codex_thread_id"),
	stepCount: integer("step_count").notNull().default(0),
	actionCount: integer("action_count").notNull().default(0),
	error: text("error"),
	createdAt: integer("created_at", { mode: "timestamp_ms" }).notNull(),
	updatedAt: integer("updated_at", { mode: "timestamp_ms" }).notNull(),
});

export const runEvents = sqliteTable("run_events", {
	id: text("id").primaryKey(),
	runId: text("run_id")
		.notNull()
		.references(() => goalRuns.id, { onDelete: "cascade" }),
	type: text("type").notNull(),
	message: text("message").notNull(),
	payload: text("payload"),
	createdAt: integer("created_at", { mode: "timestamp_ms" }).notNull(),
});

export const knowledgeCache = sqliteTable("knowledge_cache", {
	key: text("key").primaryKey(),
	query: text("query").notNull(),
	sourceUrl: text("source_url").notNull(),
	title: text("title").notNull(),
	summary: text("summary").notNull(),
	fetchedAt: integer("fetched_at", { mode: "timestamp_ms" }).notNull(),
});
