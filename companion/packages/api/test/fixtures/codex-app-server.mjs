import { createInterface } from "node:readline";

let initialized = false;
let turnNumber = 0;
const threads = new Set();
const waiting = new Map();
const send = (message) => process.stdout.write(`${JSON.stringify(message)}\n`);
const wire = {
	kind: "complete",
	rationale: "",
	actionType: "none",
	x: 0,
	y: 64,
	z: 0,
	player: "",
	side: 0,
	item: "",
	count: 1,
	query: "",
	question: "",
	summary: "Done",
	error: "",
};
createInterface({ input: process.stdin }).on("line", (line) => {
	const { id, method, params } = JSON.parse(line);
	if (method === "initialize") {
		setTimeout(() => {
			initialized = true;
			send({ id, result: {} });
		}, 20);
		return;
	}
	if (id === undefined) return;
	if (!initialized) {
		send({ id, error: { message: "Not initialized" } });
		return;
	}
	if (method === "account/read") {
		send({ id, result: { account: null, pid: process.pid } });
		return;
	}
	if (method === "model/list") {
		send({ id, result: { data: [], pid: process.pid } });
		return;
	}
	if (method === "thread/start" || method === "thread/resume") {
		const threadId = params.threadId ?? "thread-one";
		threads.add(threadId);
		send({ id, result: { thread: { id: threadId } } });
		return;
	}
	if (method === "turn/start") {
		if (!threads.has(params.threadId)) {
			send({ id, error: { message: "Thread not loaded" } });
			return;
		}
		const turnId = `turn-${++turnNumber}`;
		if (params.input[0].text === "wait") {
			waiting.set(turnId, params.threadId);
			send({ id, result: { turn: { id: turnId } } });
			return;
		}
		// Exercise completion notifications arriving before turn/start responds.
		send({
			method: "item/completed",
			params: {
				threadId: params.threadId,
				turnId,
				item: { type: "agentMessage", text: JSON.stringify(wire) },
			},
		});
		send({
			method: "turn/completed",
			params: {
				threadId: params.threadId,
				turn: { id: turnId, status: "completed", error: null },
			},
		});
		send({ id, result: { turn: { id: turnId } } });
		return;
	}
	if (method === "turn/interrupt") {
		waiting.delete(params.turnId);
		send({ id, result: {} });
		send({
			method: "turn/completed",
			params: {
				threadId: params.threadId,
				turn: { id: params.turnId, status: "interrupted", error: null },
			},
		});
		return;
	}
	send({ id, result: {} });
});
