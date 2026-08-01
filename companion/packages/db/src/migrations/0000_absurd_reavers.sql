CREATE TABLE `goal_runs` (
	`id` text PRIMARY KEY NOT NULL,
	`goal` text NOT NULL,
	`status` text NOT NULL,
	`codex_thread_id` text,
	`step_count` integer DEFAULT 0 NOT NULL,
	`action_count` integer DEFAULT 0 NOT NULL,
	`error` text,
	`created_at` integer NOT NULL,
	`updated_at` integer NOT NULL
);
--> statement-breakpoint
CREATE TABLE `knowledge_cache` (
	`key` text PRIMARY KEY NOT NULL,
	`query` text NOT NULL,
	`source_url` text NOT NULL,
	`title` text NOT NULL,
	`summary` text NOT NULL,
	`fetched_at` integer NOT NULL
);
--> statement-breakpoint
CREATE TABLE `run_events` (
	`id` text PRIMARY KEY NOT NULL,
	`run_id` text NOT NULL,
	`type` text NOT NULL,
	`message` text NOT NULL,
	`payload` text,
	`created_at` integer NOT NULL,
	FOREIGN KEY (`run_id`) REFERENCES `goal_runs`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE TABLE `settings` (
	`id` integer PRIMARY KEY DEFAULT 1 NOT NULL,
	`minecraft_url` text DEFAULT 'http://127.0.0.1:8246' NOT NULL,
	`minecraft_token` text,
	`model` text,
	`reasoning_effort` text DEFAULT 'medium' NOT NULL,
	`max_steps` integer DEFAULT 40 NOT NULL,
	`max_seconds` integer DEFAULT 180 NOT NULL,
	`max_actions` integer DEFAULT 160 NOT NULL,
	`knowledge_enabled` integer DEFAULT true NOT NULL,
	`updated_at` integer NOT NULL
);
