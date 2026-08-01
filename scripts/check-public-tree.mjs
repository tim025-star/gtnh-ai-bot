import { readdir, readFile } from "node:fs/promises";
import { extname, join, relative } from "node:path";

const root = new URL("../", import.meta.url).pathname.replace(/^\/(.:)/, "$1");
const ignored = new Set([".git", ".gradle", ".vscode", "bin", "build", "run", "node_modules", "dist", "__pycache__"]);
const textExtensions = new Set([".java", ".ts", ".tsx", ".js", ".mjs", ".json", ".jsonc", ".md", ".kts", ".properties", ".toml", ".yml", ".yaml", ".xml"]);
const forbidden = [
  ["open", "claw"].join(""),
  ["api.openai.com", "v1", "responses"].join("/"),
  ["127.0.0.1", "8250"].join(":"),
  ["127.0.0.1", "8260"].join(":"),
  ["127.0.0.1", "8787"].join(":"),
];
const findings = [];

async function walk(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (ignored.has(entry.name)) continue;
    const path = join(directory, entry.name);
    if (entry.isDirectory()) await walk(path);
    else if (textExtensions.has(extname(entry.name)) || entry.name === "README") {
      const text = await readFile(path, "utf8");
      for (const term of forbidden) if (text.toLowerCase().includes(term.toLowerCase())) findings.push(`${relative(root, path)}: forbidden legacy reference`);
    }
  }
}

await walk(root);
if (findings.length) {
  console.error(findings.join("\n"));
  process.exit(1);
}
console.log("Public tree contains no legacy runtime references.");
