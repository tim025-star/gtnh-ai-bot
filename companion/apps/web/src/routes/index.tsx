import { useMutation, useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import {
	Bot,
	CircleStop,
	KeyRound,
	LogIn,
	Pause,
	Play,
	RefreshCw,
	Send,
	Settings2,
	ShieldCheck,
} from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { queryClient, trpc } from "@/utils/trpc";

export const Route = createFileRoute("/")({ component: Dashboard });

const field =
	"w-full rounded-lg border border-white/10 bg-black/20 px-3 py-2 text-sm outline-none focus:border-emerald-400/60";
const button =
	"inline-flex items-center justify-center gap-2 rounded-lg px-3 py-2 text-sm font-semibold transition disabled:cursor-not-allowed disabled:opacity-40";

function StatusPill({
	ok,
	children,
}: {
	ok: boolean;
	children: React.ReactNode;
}) {
	return (
		<span
			className={`inline-flex items-center gap-2 rounded-full border px-3 py-1 text-xs ${ok ? "border-emerald-400/30 bg-emerald-400/10 text-emerald-200" : "border-amber-400/30 bg-amber-400/10 text-amber-200"}`}
		>
			<span
				className={`h-2 w-2 rounded-full ${ok ? "bg-emerald-400" : "bg-amber-400"}`}
			/>
			{children}
		</span>
	);
}

function Panel({
	title,
	subtitle,
	children,
}: {
	title: string;
	subtitle: string;
	children: React.ReactNode;
}) {
	return (
		<section className="rounded-2xl border border-white/10 bg-[#111a17]/90 p-5 shadow-2xl shadow-black/20">
			<div className="mb-4">
				<h2 className="font-semibold text-lg text-white">{title}</h2>
				<p className="text-slate-400 text-sm">{subtitle}</p>
			</div>
			{children}
		</section>
	);
}

function useActionMutation(
	options: any,
	success: string,
	refresh: () => Promise<unknown>,
	clear?: () => void,
): any {
	return useMutation({
		...options,
		onSuccess: async () => {
			clear?.();
			toast.success(success);
			await refresh();
		},
	});
}

function Dashboard() {
	const status = useQuery({
		...trpc.system.status.queryOptions(),
		refetchInterval: 5_000,
	});
	const settings = useQuery(trpc.settings.get.queryOptions());
	const current = useQuery({
		...trpc.goals.current.queryOptions(),
		refetchInterval: 1_000,
	});
	const history = useQuery(trpc.goals.list.queryOptions());
	const models = useQuery({
		...trpc.openaiAuth.models.queryOptions(),
		enabled: Boolean((status.data as any)?.openai?.account),
	});
	const [pairCode, setPairCode] = useState("");
	const [apiKey, setApiKey] = useState("");
	const [goal, setGoal] = useState("");
	const [actionType, setActionType] = useState("goto");
	const [actionArgs, setActionArgs] = useState({
		x: "0",
		y: "64",
		z: "0",
		side: "1",
		player: "",
		item: "",
		count: "1",
	});
	const [draft, setDraft] = useState<any>(null);

	useEffect(() => {
		if (settings.data && !draft) setDraft(settings.data);
	}, [settings.data, draft]);
	const refresh = () =>
		Promise.all([
			queryClient.invalidateQueries(),
			current.refetch(),
			history.refetch(),
		]);
	const pair = useActionMutation(
		trpc.bot.pair.mutationOptions(),
		"Minecraft paired",
		refresh,
		() => setPairCode(""),
	);
	const chatLogin = useActionMutation(
		trpc.openaiAuth.loginChatGpt.mutationOptions(),
		"Login opened",
		refresh,
	);
	const keyLogin = useActionMutation(
		trpc.openaiAuth.loginApiKey.mutationOptions(),
		"API key accepted",
		refresh,
		() => setApiKey(""),
	);
	const logout = useActionMutation(
		trpc.openaiAuth.logout.mutationOptions(),
		"Signed out",
		refresh,
	);
	const startGoal = useActionMutation(
		trpc.goals.start.mutationOptions(),
		"Goal started",
		refresh,
		() => setGoal(""),
	);
	const pauseGoal = useActionMutation(
		trpc.goals.pause.mutationOptions(),
		"Goal paused",
		refresh,
	);
	const resumeGoal = useActionMutation(
		trpc.goals.resume.mutationOptions(),
		"Goal resumed",
		refresh,
	);
	const cancelGoal = useActionMutation(
		trpc.goals.cancel.mutationOptions(),
		"Goal cancelled",
		refresh,
	);
	const stop = useActionMutation(
		trpc.bot.stop.mutationOptions(),
		"Emergency stop sent",
		refresh,
	);
	const manual = useActionMutation(
		trpc.bot.action.mutationOptions(),
		"Action accepted",
		refresh,
	);
	const saveSettings = useActionMutation(
		trpc.settings.update.mutationOptions(),
		"Settings saved",
		refresh,
	);

	const openChatLogin = async () => {
		const result: any = await chatLogin.mutateAsync(undefined as any);
		if (result?.authUrl)
			window.open(result.authUrl, "_blank", "noopener,noreferrer");
	};
	const sendManual = () => {
		const xyz = {
			x: Number(actionArgs.x),
			y: Number(actionArgs.y),
			z: Number(actionArgs.z),
		};
		const action: any =
			actionType === "goto" || actionType === "break"
				? { type: actionType, ...xyz }
				: actionType === "use" || actionType === "place"
					? { type: actionType, ...xyz, side: Number(actionArgs.side) }
					: actionType === "follow"
						? { type: "follow", player: actionArgs.player }
						: actionType === "craft"
							? {
									type: "craft",
									item: actionArgs.item,
									count: Number(actionArgs.count),
								}
							: actionType === "selectItem"
								? { type: "selectItem", item: actionArgs.item }
								: { type: "diagnose", query: actionArgs.item };
		manual.mutate(action);
	};

	const openaiAccount = (status.data as any)?.openai?.account;
	const minecraftOk = Boolean((status.data as any)?.minecraft?.ok);
	const run: any = current.data;
	const active = run && run.status === "running";

	return (
		<main className="min-h-screen bg-[#07100d] text-slate-100">
			<div className="mx-auto max-w-7xl px-4 py-8 lg:px-8">
				<header className="mb-8 flex flex-col justify-between gap-5 border-white/10 border-b pb-6 md:flex-row md:items-end">
					<div>
						<div className="mb-2 flex items-center gap-3 text-emerald-300">
							<Bot className="h-7 w-7" />
							<span className="font-bold text-xs uppercase tracking-[0.28em]">
								Local control companion
							</span>
						</div>
						<h1 className="font-semibold text-4xl text-white tracking-tight">
							GTNH AI Bot
						</h1>
						<p className="mt-2 max-w-2xl text-slate-400">
							Plan through Codex. Validate in TypeScript. Execute only inside
							Minecraft.
						</p>
					</div>
					<div className="flex flex-wrap gap-2">
						<StatusPill ok={Boolean(openaiAccount)}>
							OpenAI {openaiAccount ? openaiAccount.type : "signed out"}
						</StatusPill>
						<StatusPill ok={minecraftOk}>
							Minecraft {minecraftOk ? "connected" : "offline"}
						</StatusPill>
					</div>
				</header>

				<div className="grid gap-5 xl:grid-cols-[1.2fr_0.8fr]">
					<div className="grid content-start gap-5">
						<Panel
							title="Control"
							subtitle="Submitting a goal approves autonomous actions within the limits shown in Settings."
						>
							<textarea
								className={`${field} min-h-28 resize-y`}
								value={goal}
								onChange={(e) => setGoal(e.target.value)}
								placeholder="Craft an LV lathe, using nearby storage and machines."
							/>
							<div className="mt-3 flex gap-2">
								<button
									type="button"
									className={`${button} bg-emerald-400 text-emerald-950 hover:bg-emerald-300`}
									disabled={active || goal.trim().length < 3}
									onClick={() => startGoal.mutate({ goal })}
								>
									<Send className="h-4 w-4" />
									Start goal
								</button>
								<button
									type="button"
									className={`${button} border border-red-400/30 bg-red-400/10 text-red-200`}
									onClick={() => stop.mutate(undefined as any)}
								>
									<CircleStop className="h-4 w-4" />
									Emergency stop
								</button>
							</div>
						</Panel>

						<Panel
							title="Current run"
							subtitle="One active goal at a time. Progress is reported from real state, never simulated."
						>
							{!run ? (
								<p className="text-slate-500 text-sm">No runs yet.</p>
							) : (
								<div className="space-y-4">
									<div className="flex flex-wrap items-center justify-between gap-3">
										<div>
											<p className="font-medium text-white">{run.goal}</p>
											<p className="text-slate-400 text-sm">
												{run.status} · {run.stepCount} model steps ·{" "}
												{run.actionCount} actions
											</p>
										</div>
										<div className="flex gap-2">
											{active && (
												<button
													type="button"
													className={`${button} bg-amber-400/15 text-amber-200`}
													onClick={() => pauseGoal.mutate({ id: run.id })}
												>
													<Pause className="h-4 w-4" />
													Pause
												</button>
											)}
											{["paused", "interrupted", "waiting_user"].includes(
												run.status,
											) && (
												<button
													type="button"
													className={`${button} bg-emerald-400/15 text-emerald-200`}
													onClick={() => resumeGoal.mutate({ id: run.id })}
												>
													<Play className="h-4 w-4" />
													Resume
												</button>
											)}
											<button
												type="button"
												className={`${button} bg-red-400/10 text-red-200`}
												onClick={() => cancelGoal.mutate({ id: run.id })}
											>
												Cancel
											</button>
										</div>
									</div>
									<div className="max-h-72 space-y-2 overflow-auto rounded-xl bg-black/20 p-3">
										{run.events?.map((event: any) => (
											<div
												key={event.id}
												className="border-emerald-400/30 border-l-2 pl-3 text-sm"
											>
												<span className="mr-2 text-emerald-300 text-xs uppercase">
													{event.type}
												</span>
												<span className="text-slate-300">{event.message}</span>
											</div>
										))}
									</div>
								</div>
							)}
						</Panel>

						<Panel
							title="Manual controls"
							subtitle="Typed actions use the same validation and Minecraft controller as autonomous goals."
						>
							<div className="grid gap-3 md:grid-cols-4">
								<select
									className={field}
									value={actionType}
									onChange={(e) => setActionType(e.target.value)}
								>
									{[
										"goto",
										"follow",
										"break",
										"use",
										"place",
										"craft",
										"selectItem",
										"diagnose",
									].map((type) => (
										<option key={type}>{type}</option>
									))}
								</select>
								{["goto", "break", "use", "place"].includes(actionType) && (
									<>
										<input
											className={field}
											value={actionArgs.x}
											onChange={(e) =>
												setActionArgs({ ...actionArgs, x: e.target.value })
											}
											placeholder="X"
										/>
										<input
											className={field}
											value={actionArgs.y}
											onChange={(e) =>
												setActionArgs({ ...actionArgs, y: e.target.value })
											}
											placeholder="Y"
										/>
										<input
											className={field}
											value={actionArgs.z}
											onChange={(e) =>
												setActionArgs({ ...actionArgs, z: e.target.value })
											}
											placeholder="Z"
										/>
									</>
								)}
								{["use", "place"].includes(actionType) && (
									<input
										className={field}
										value={actionArgs.side}
										onChange={(e) =>
											setActionArgs({ ...actionArgs, side: e.target.value })
										}
										placeholder="Side 0–5"
									/>
								)}
								{actionType === "follow" && (
									<input
										className={`${field} md:col-span-3`}
										value={actionArgs.player}
										onChange={(e) =>
											setActionArgs({ ...actionArgs, player: e.target.value })
										}
										placeholder="Player name"
									/>
								)}
								{["craft", "selectItem", "diagnose"].includes(actionType) && (
									<input
										className={`${field} md:col-span-2`}
										value={actionArgs.item}
										onChange={(e) =>
											setActionArgs({ ...actionArgs, item: e.target.value })
										}
										placeholder="Item or diagnostic query"
									/>
								)}
								{actionType === "craft" && (
									<input
										className={field}
										value={actionArgs.count}
										onChange={(e) =>
											setActionArgs({ ...actionArgs, count: e.target.value })
										}
										placeholder="Count"
									/>
								)}
							</div>
							<button
								type="button"
								className={`${button} mt-3 bg-white/10 text-white hover:bg-white/15`}
								disabled={active}
								onClick={sendManual}
							>
								Send action
							</button>
						</Panel>
					</div>

					<div className="grid content-start gap-5">
						<Panel
							title="Setup"
							subtitle="Credentials are handled by the official local Codex app-server."
						>
							<div className="space-y-4">
								<div className="rounded-xl border border-white/10 p-3">
									<div className="mb-3 flex items-center gap-2 font-medium">
										<ShieldCheck className="h-4 w-4 text-emerald-300" />
										Minecraft pairing
									</div>
									<p className="mb-3 text-slate-400 text-xs">
										Run <code>/gtnhbot pair</code> in game, then enter the
										one-use code.
									</p>
									<div className="flex gap-2">
										<input
											className={field}
											maxLength={6}
											value={pairCode}
											onChange={(e) =>
												setPairCode(e.target.value.replace(/\D/g, ""))
											}
											placeholder="000000"
										/>
										<button
											type="button"
											className={`${button} bg-white/10`}
											disabled={pairCode.length !== 6}
											onClick={() => pair.mutate({ code: pairCode })}
										>
											Pair
										</button>
									</div>
								</div>
								<div className="rounded-xl border border-white/10 p-3">
									<div className="mb-3 flex items-center gap-2 font-medium">
										<LogIn className="h-4 w-4 text-emerald-300" />
										OpenAI access
									</div>
									{openaiAccount ? (
										<div className="flex items-center justify-between">
											<p className="text-slate-300 text-sm">
												Signed in via {openaiAccount.type}
												{openaiAccount.planType
													? ` · ${openaiAccount.planType}`
													: ""}
											</p>
											<button
												type="button"
												className={`${button} bg-white/10`}
												onClick={() => logout.mutate(undefined as any)}
											>
												Sign out
											</button>
										</div>
									) : (
										<>
											<button
												type="button"
												className={`${button} w-full bg-emerald-400 text-emerald-950`}
												onClick={openChatLogin}
											>
												<LogIn className="h-4 w-4" />
												Sign in with ChatGPT
											</button>
											<div className="my-3 text-center text-slate-500 text-xs">
												or use Platform billing
											</div>
											<form
												className="flex gap-2"
												onSubmit={(event) => {
													event.preventDefault();
													keyLogin.mutate({ apiKey });
												}}
											>
												<input
													type="password"
													autoComplete="off"
													className={field}
													value={apiKey}
													onChange={(e) => setApiKey(e.target.value)}
													placeholder="OpenAI API key"
												/>
												<button
													type="submit"
													className={`${button} bg-white/10`}
													disabled={apiKey.length < 20}
												>
													<KeyRound className="h-4 w-4" />
													Use key
												</button>
											</form>
										</>
									)}
								</div>
							</div>
						</Panel>

						<Panel
							title="Settings"
							subtitle="Hard limits are enforced by the companion, independently of model output."
						>
							{!draft ? (
								<p className="text-slate-500 text-sm">Loading settings…</p>
							) : (
								<div className="grid gap-3">
									<label className="text-slate-400 text-xs">
										Model
										<select
											className={`${field} mt-1`}
											value={draft.model ?? ""}
											onChange={(e) =>
												setDraft({ ...draft, model: e.target.value || null })
											}
										>
											<option value="">Codex default</option>
											{((models.data as any)?.data ?? []).map((model: any) => (
												<option key={model.id} value={model.id}>
													{model.displayName ?? model.id}
												</option>
											))}
										</select>
									</label>
									<div className="grid grid-cols-3 gap-2">
										<label className="text-slate-400 text-xs">
											Steps
											<input
												className={`${field} mt-1`}
												type="number"
												value={draft.maxSteps}
												onChange={(e) =>
													setDraft({
														...draft,
														maxSteps: Number(e.target.value),
													})
												}
											/>
										</label>
										<label className="text-slate-400 text-xs">
											Seconds
											<input
												className={`${field} mt-1`}
												type="number"
												value={draft.maxSeconds}
												onChange={(e) =>
													setDraft({
														...draft,
														maxSeconds: Number(e.target.value),
													})
												}
											/>
										</label>
										<label className="text-slate-400 text-xs">
											Actions
											<input
												className={`${field} mt-1`}
												type="number"
												value={draft.maxActions}
												onChange={(e) =>
													setDraft({
														...draft,
														maxActions: Number(e.target.value),
													})
												}
											/>
										</label>
									</div>
									<label className="flex items-center gap-2 text-slate-300 text-sm">
										<input
											type="checkbox"
											checked={draft.knowledgeEnabled}
											onChange={(e) =>
												setDraft({
													...draft,
													knowledgeEnabled: e.target.checked,
												})
											}
										/>
										Use sourced GTNH knowledge
									</label>
									<button
										type="button"
										className={`${button} bg-white/10`}
										onClick={() =>
											saveSettings.mutate({
												minecraftUrl: draft.minecraftUrl,
												model: draft.model,
												reasoningEffort: draft.reasoningEffort,
												maxSteps: draft.maxSteps,
												maxSeconds: draft.maxSeconds,
												maxActions: draft.maxActions,
												knowledgeEnabled: draft.knowledgeEnabled,
											})
										}
									>
										<Settings2 className="h-4 w-4" />
										Save settings
									</button>
								</div>
							)}
						</Panel>

						<Panel
							title="Run history"
							subtitle="Recent goals and truthful terminal states stored locally."
						>
							<div className="space-y-2">
								{history.data?.length ? (
									history.data.map((item: any) => (
										<div
											key={item.id}
											className="flex items-center justify-between rounded-lg bg-black/20 px-3 py-2 text-sm"
										>
											<span className="max-w-[70%] truncate text-slate-300">
												{item.goal}
											</span>
											<span className="text-slate-500 text-xs uppercase">
												{item.status}
											</span>
										</div>
									))
								) : (
									<p className="text-slate-500 text-sm">No history yet.</p>
								)}
							</div>
							<button
								type="button"
								className={`${button} mt-3 text-slate-400`}
								onClick={() => refresh()}
							>
								<RefreshCw className="h-4 w-4" />
								Refresh
							</button>
						</Panel>
					</div>
				</div>
			</div>
		</main>
	);
}
