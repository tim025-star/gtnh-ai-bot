import { createContext } from "@companion/api/context";
import { appRouter } from "@companion/api/routers/index";
import { env } from "@companion/env/server";
import { trpcServer } from "@hono/trpc-server";
import { Hono } from "hono";
import { bodyLimit } from "hono/body-limit";
import { logger } from "hono/logger";
import { secureHeaders } from "hono/secure-headers";

const app = new Hono();

app.use(
	logger((message) =>
		console.log(
			message.replace(/(sk-[A-Za-z0-9_-]{12,}|Bearer\s+\S+)/g, "[REDACTED]"),
		),
	),
);
app.use(secureHeaders());
app.use(
	bodyLimit({
		maxSize: 64 * 1024,
		onError: (c) => c.json({ error: "Request body too large" }, 413),
	}),
);
app.use("/*", async (c, next) => {
	const url = new URL(c.req.url);
	if (!new Set(["127.0.0.1", "localhost", "[::1]"]).has(url.hostname))
		return c.json({ error: "Loopback access only" }, 403);
	const origin = c.req.header("Origin");
	const allowed = new Set([env.WEB_ORIGIN, `http://${env.HOST}:${env.PORT}`]);
	if (origin && !allowed.has(origin))
		return c.json({ error: "Origin denied" }, 403);
	c.header(
		"Access-Control-Allow-Origin",
		origin && allowed.has(origin) ? origin : env.WEB_ORIGIN,
	);
	c.header("Vary", "Origin");
	c.header("Access-Control-Allow-Methods", "GET, POST");
	c.header("Access-Control-Allow-Headers", "Content-Type");
	if (c.req.method === "OPTIONS") return c.body(null, 204);
	await next();
});

app.use(
	"/trpc/*",
	trpcServer({
		router: appRouter,
		createContext: (_opts, context) => {
			return createContext({ context });
		},
	}),
);

app.get("/health", (c) => {
	return c.json({ name: "GTNH AI Bot Companion", ok: true });
});

import { resolve } from "node:path";
import { serve } from "@hono/node-server";
import { serveStatic } from "@hono/node-server/serve-static";

if (env.NODE_ENV === "production") {
	const webRoot = resolve(process.cwd(), "web");
	app.use("/*", serveStatic({ root: webRoot }));
	app.get("*", serveStatic({ root: webRoot, path: "index.html" }));
}

serve(
	{
		fetch: app.fetch,
		hostname: env.HOST,
		port: env.PORT,
	},
	(info) => {
		console.log(
			`GTNH AI Bot Companion is running on http://${env.HOST}:${info.port}`,
		);
	},
);
