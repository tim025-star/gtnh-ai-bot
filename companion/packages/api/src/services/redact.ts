const SECRET_PATTERNS = [
	/sk-[A-Za-z0-9_-]{12,}/g,
	/Bearer\s+[A-Za-z0-9._~-]+/gi,
	/("?(?:apiKey|token|minecraftToken)"?\s*[:=]\s*")([^"]+)(")/gi,
];

export function redactSecrets(value: unknown): string {
	let text = value instanceof Error ? value.message : String(value ?? "");
	for (const pattern of SECRET_PATTERNS) {
		text = text.replace(pattern, (...parts: string[]) =>
			parts.length >= 4 ? `${parts[1]}[REDACTED]${parts[3]}` : "[REDACTED]",
		);
	}
	return text.slice(0, 2_000);
}
