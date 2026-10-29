import { dirname, resolve } from "node:path";
import { cp, realpath } from "@toonflow/file";
import copyDocumentPackages from "./copyDocumentPackages";

const runtimeDirectory = resolve(import.meta.dirname, "..");
const outputDirectory = resolve(runtimeDirectory, "dist");
const result = await Bun.build({
  entrypoints: [resolve(runtimeDirectory, "src/index.ts")],
  outdir: outputDirectory,
  target: "bun",
  minify: true,
  external: ["@silvia-odwyer/photon-node", "@firecrawl/anydoc", "@firecrawl/anydoc-wasm"],
});
if (!result.success) throw new AggregateError(result.logs, "AgentRuntime 构建失败");
const agentDirectory = dirname(await realpath(Bun.resolveSync("@earendil-works/pi-coding-agent", runtimeDirectory)));
const photonDirectory = dirname(Bun.resolveSync("@silvia-odwyer/photon-node", agentDirectory));
await cp(photonDirectory, resolve(outputDirectory, "node_modules/@silvia-odwyer/photon-node"), { recursive: true, dereference: true });
await copyDocumentPackages(resolve(outputDirectory, "node_modules"));
