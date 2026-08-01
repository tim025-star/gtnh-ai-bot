import { env } from "@companion/env/server";
import { createClient } from "@libsql/client";
import { drizzle } from "drizzle-orm/libsql";

import * as schema from "./schema";

export function createDb() {
	const client = createClient({
		url: env.DATABASE_URL,
	});

	return { client, orm: drizzle({ client, schema }) };
}

const instance = createDb();
export const dbClient = instance.client;
export const db = instance.orm;

export async function ensureDatabase() {
	const statements = [
		`CREATE TABLE IF NOT EXISTS settings (id INTEGER PRIMARY KEY DEFAULT 1, minecraft_url TEXT NOT NULL DEFAULT 'http://127.0.0.1:8246', minecraft_token TEXT, model TEXT, reasoning_effort TEXT NOT NULL DEFAULT 'medium', max_steps INTEGER NOT NULL DEFAULT 40, max_seconds INTEGER NOT NULL DEFAULT 180, max_actions INTEGER NOT NULL DEFAULT 160, knowledge_enabled INTEGER NOT NULL DEFAULT 1, updated_at INTEGER NOT NULL)`,
		"CREATE TABLE IF NOT EXISTS goal_runs (id TEXT PRIMARY KEY, goal TEXT NOT NULL, status TEXT NOT NULL, codex_thread_id TEXT, step_count INTEGER NOT NULL DEFAULT 0, action_count INTEGER NOT NULL DEFAULT 0, error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
		"CREATE TABLE IF NOT EXISTS run_events (id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES goal_runs(id) ON DELETE CASCADE, type TEXT NOT NULL, message TEXT NOT NULL, payload TEXT, created_at INTEGER NOT NULL)",
		"CREATE TABLE IF NOT EXISTS knowledge_cache (key TEXT PRIMARY KEY, query TEXT NOT NULL, source_url TEXT NOT NULL, title TEXT NOT NULL, summary TEXT NOT NULL, fetched_at INTEGER NOT NULL)",
	];
	for (const sql of statements) await dbClient.execute(sql);
	await dbClient.execute({
		sql: "INSERT OR IGNORE INTO settings (id, updated_at) VALUES (1, ?)",
		args: [Date.now()],
	});
	await dbClient.execute(
		"UPDATE goal_runs SET status = 'interrupted', updated_at = unixepoch() * 1000 WHERE status IN ('running', 'pausing')",
	);
}
