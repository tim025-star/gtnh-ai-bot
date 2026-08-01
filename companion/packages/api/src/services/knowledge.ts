import { createHash } from "node:crypto";
import { db } from "@companion/db";
import { knowledgeCache } from "@companion/db/schema/index";
import { desc, eq } from "drizzle-orm";

const ALLOWED_HOSTS = new Set(["gtnh.miraheze.org", "forum.gtnewhorizons.com"]);
const MAX_BYTES = 512 * 1024;

type KnowledgeResult = { title: string; sourceUrl: string; summary: string };

export function safeKnowledgeUrl(value: string) {
	const url = new URL(value);
	if (url.protocol !== "https:" || !ALLOWED_HOSTS.has(url.hostname))
		throw new Error("Knowledge source is not allowlisted");
	return url;
}

async function fetchJson(urlValue: string) {
	const url = safeKnowledgeUrl(urlValue);
	const controller = new AbortController();
	const timeout = setTimeout(() => controller.abort(), 8_000);
	try {
		const response = await fetch(url, {
			signal: controller.signal,
			headers: { "User-Agent": "GTNH-AI-Bot/1.0" },
		});
		if (!response.ok) throw new Error(`Knowledge HTTP ${response.status}`);
		const text = await response.text();
		if (Buffer.byteLength(text) > MAX_BYTES)
			throw new Error("Knowledge response exceeded limit");
		return JSON.parse(text) as any;
	} finally {
		clearTimeout(timeout);
	}
}

export class KnowledgeService {
	async search(query: string, refresh = false): Promise<KnowledgeResult[]> {
		const normalized = query.trim().slice(0, 160);
		if (!normalized) return [];
		const key = createHash("sha256")
			.update(normalized.toLowerCase())
			.digest("hex");
		if (!refresh) {
			const cached = await db
				.select()
				.from(knowledgeCache)
				.where(eq(knowledgeCache.key, key))
				.orderBy(desc(knowledgeCache.fetchedAt))
				.limit(1);
			if (
				cached[0] &&
				Date.now() - cached[0].fetchedAt.getTime() < 86_400_000
			) {
				return [
					{
						title: cached[0].title,
						sourceUrl: cached[0].sourceUrl,
						summary: cached[0].summary,
					},
				];
			}
		}
		const api = `https://gtnh.miraheze.org/w/api.php?action=query&list=search&srsearch=${encodeURIComponent(normalized)}&srlimit=5&format=json&origin=*`;
		const data = await fetchJson(api);
		const first = data?.query?.search?.[0];
		if (!first) return [];
		const title = String(first.title).slice(0, 200);
		const summary = String(first.snippet ?? "")
			.replace(/<[^>]+>/g, " ")
			.replace(/\s+/g, " ")
			.trim()
			.slice(0, 1_500);
		const sourceUrl = `https://gtnh.miraheze.org/wiki/${encodeURIComponent(title.replace(/ /g, "_"))}`;
		await db
			.insert(knowledgeCache)
			.values({
				key,
				query: normalized,
				sourceUrl,
				title,
				summary,
				fetchedAt: new Date(),
			})
			.onConflictDoUpdate({
				target: knowledgeCache.key,
				set: {
					query: normalized,
					sourceUrl,
					title,
					summary,
					fetchedAt: new Date(),
				},
			});
		return [{ title, sourceUrl, summary }];
	}
}
