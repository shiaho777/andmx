// 把探针连同上游执行管线打成 node 可执行的 CJS。
//
// 刻意**不 minify**：`child-source.ts` 的 `childMain` 经 `toString()` 内嵌进沙箱入口文件运行，
// minify 会破坏它里面那句「只允许 import 类型、不得引用模块作用域绑定」的约束。
// 这条限制对将来打进 WebView 的那份 bundle 同样成立。
import { build } from "esbuild";
import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "../..");
const cliPackages = resolve(repo, "ZCode-main/apps/zcode-cli/packages");

if (!existsSync(resolve(here, "node_modules"))) {
  throw new Error(
    "缺少 node_modules（它是指向 ../dwf-analyzer/node_modules 的符号链接）。"
      + "先跑：cd tools/dwf-analyzer && npm install",
  );
}
if (!existsSync(resolve(repo, "app/src/main/assets/dwf/analyzer.js"))) {
  throw new Error("缺少 app/src/main/assets/dwf/analyzer.js。先跑：node tools/dwf-analyzer/build.mjs");
}

await build({
  alias: {
    "@zcode/dynamic-workflow": resolve(cliPackages, "dynamic-workflow/src/index.ts"),
    "@zcode/dynamic-workflow/engine/engine": resolve(cliPackages, "dynamic-workflow/src/engine/engine.ts"),
    "@zcode/dynamic-workflow/engine/errors": resolve(cliPackages, "dynamic-workflow/src/engine/errors.ts"),
    "@zcode/dynamic-workflow/engine/types": resolve(cliPackages, "dynamic-workflow/src/engine/types.ts"),
    "@zcode/dynamic-workflow/engine/journal-memory": resolve(cliPackages, "dynamic-workflow/src/engine/journal-memory.ts"),
    "@zcode/dynamic-workflow/schema/validate": resolve(cliPackages, "dynamic-workflow/src/schema/validate.ts"),
    "@zcode/dynamic-workflow-runtime": resolve(cliPackages, "dynamic-workflow-runtime/src/index.ts"),
    "@zcode/dynamic-workflow-runtime/child-cell-streams": resolve(
      cliPackages,
      "dynamic-workflow-runtime/src/child-cell-streams.ts",
    ),
    "@zcode/dynamic-workflow-runtime/child-source": resolve(
      cliPackages,
      "dynamic-workflow-runtime/src/child-source.ts",
    ),
    "@zcode/dynamic-workflow-runtime/protocol": resolve(
      cliPackages,
      "dynamic-workflow-runtime/src/protocol.ts",
    ),
  },
  bundle: true,
  entryNames: "[name]",
  entryPoints: [
    resolve(here, "probe.ts"),
    resolve(here, "cell-probe.ts"),
    resolve(here, "host-probe.ts"),
    resolve(here, "runner-probe.ts"),
  ],
  // CJS 而不是 ESM：TypeScript 的 `getNodeSystem()` 里是 `require("fs")`，esbuild 在 ESM 输出里
  // 会把它降级成一个抛错的 `__require` 桩。WebView 那份 bundle 用 IIFE，永远走不到那条路
  // （虚拟 host，不碰 sys），所以两边不冲突。
  format: "cjs",
  keepNames: true,
  logLevel: "info",
  outdir: resolve(here, "dist"),
  outExtension: { ".js": ".cjs" },
  platform: "node",
  sourcemap: false,
  target: ["node22"],
});

// WebView/iframe runner: IIFE, no Node built-ins, no minify (childMain is
// stringified by upstream and must stay self-contained).
await build({
  alias: {
    "typescript": resolve(here, "src/typescript-stub.ts"),
    "@zcode/dynamic-workflow": resolve(cliPackages, "dynamic-workflow/src/index.ts"),
    "@zcode/dynamic-workflow/engine/engine": resolve(cliPackages, "dynamic-workflow/src/engine/engine.ts"),
    "@zcode/dynamic-workflow/engine/errors": resolve(cliPackages, "dynamic-workflow/src/engine/errors.ts"),
    "@zcode/dynamic-workflow/engine/types": resolve(cliPackages, "dynamic-workflow/src/engine/types.ts"),
    "@zcode/dynamic-workflow/engine/journal-memory": resolve(cliPackages, "dynamic-workflow/src/engine/journal-memory.ts"),
    "@zcode/dynamic-workflow/schema/validate": resolve(cliPackages, "dynamic-workflow/src/schema/validate.ts"),
    "@zcode/dynamic-workflow-runtime": resolve(cliPackages, "dynamic-workflow-runtime/src/index.ts"),
    "@zcode/dynamic-workflow-runtime/child-cell-streams": resolve(
      cliPackages,
      "dynamic-workflow-runtime/src/child-cell-streams.ts",
    ),
    "@zcode/dynamic-workflow-runtime/child-source": resolve(
      cliPackages,
      "dynamic-workflow-runtime/src/child-source.ts",
    ),
    "@zcode/dynamic-workflow-runtime/protocol": resolve(
      cliPackages,
      "dynamic-workflow-runtime/src/protocol.ts",
    ),
  },
  bundle: true,
  entryPoints: [resolve(here, "src/android-entry.ts")],
  format: "iife",
  keepNames: true,
  logLevel: "info",
  outfile: resolve(repo, "app/src/main/assets/dwf/runner.js"),
  platform: "browser",
  sourcemap: false,
  target: ["es2020"],
});
