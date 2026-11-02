// 把上游 dynamic-workflow 的静态分析器打成一个 WebView 能直接执行的 IIFE。
//
// 这不是「用 Kotlin 重写分析器」的替代品规避——这里跑的就是上游那份代码：
// `analyzeWorkflowScript` 与它依赖的 TypeScript 编译器原样进包，没有一层改写。
//
// 产物落在 app/src/main/assets/dwf/ 下：
//   analyzer.js    单个 IIFE，挂 globalThis.__DWF
//   analyzer.html  一行脚本标签的宿主页（WebView 用 file:// 加载 assets 比注入 4MB 源码快）
//
// 重跑：node tools/dwf-analyzer/build.mjs
import { build } from "esbuild";
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, statSync, symlinkSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "../..");
const dwfPkg = resolve(repo, "ZCode-main/apps/zcode-cli/packages/dynamic-workflow");
const dwfSrc = resolve(dwfPkg, "src");
const assetDir = resolve(repo, "app/src/main/assets/dwf");

/**
 * 自举。`ZCode-main/` 是带自己 `.git` 的上游快照、被主仓 gitignore，所以干净环境下
 * 它可能整份缺席，`libs.generated.ts` 也可能在上游重爬时被冲掉。这里把两样都补齐，
 * 缺东西时给出**下一步该跑什么**，而不是让 esbuild 抛一句模块找不到。
 */
function prepare() {
  if (!existsSync(dwfSrc)) {
    throw new Error(
      `找不到上游快照 ${dwfSrc}。建议：把 zai-org/ZCode 克隆到 ${resolve(repo, "ZCode-main")}。`,
    );
  }

  // TypeScript 标准库闭包：上游 `pnpm build` 也会跑这个生成器，产物是 gitignore 的。
  const libsPath = resolve(dwfSrc, "compiler/libs.generated.ts");
  if (!existsSync(libsPath)) {
    // 生成器用 createRequire 从包根解析 typescript，所以 node_modules 得在上游树上可见。
    const upstreamModules = resolve(repo, "ZCode-main/node_modules");
    if (!existsSync(upstreamModules)) {
      symlinkSync(resolve(here, "node_modules"), upstreamModules, "dir");
    }
    process.stdout.write("dwf-analyzer: 生成 libs.generated.ts（TypeScript 标准库闭包）\n");
    execFileSync(process.execPath, [resolve(dwfPkg, "scripts/generate-libs.mjs")], {
      stdio: "inherit",
    });
  }
}

prepare();

// TypeScript 的 sys 层在 node 下会 require 这些模块。我们用自造的虚拟 host，
// 那些代码路径永远走不到，所以按 external 留成 require 调用即可——WebView 里
// require 未定义，一旦真被调用会立刻抛错，而不是静默降级。
const NODE_BUILTINS = [
  "assert", "buffer", "child_process", "crypto", "fs", "http", "https", "inspector",
  "module", "net", "os", "path", "perf_hooks", "readline", "stream", "tty", "url",
  "util", "v8", "worker_threads", "zlib",
];

mkdirSync(assetDir, { recursive: true });

await build({
  alias: { "@zcode/dynamic-workflow": resolve(dwfSrc, "index.ts") },
  banner: {
    // tsc 在浏览器里跑时 process 是 undefined；只补它读的那几个字段，
    // 不伪造 versions.node，免得它误判成 node 环境去摸 fs。
    js:
      "var process=globalThis.process||{env:{NODE_ENV:'production'},platform:'browser',versions:{},argv:[],cwd:function(){return'/'},exit:function(){}};",
  },
  bundle: true,
  define: { "process.env.NODE_ENV": '"production"' },
  entryPoints: [resolve(here, "entry.ts")],
  external: NODE_BUILTINS.flatMap((m) => [m, `node:${m}`]),
  format: "iife",
  globalName: "__DWF_BUNDLE",
  logLevel: "info",
  minify: true,
  outfile: resolve(assetDir, "analyzer.js"),
  platform: "browser",
  sourcemap: false,
  target: ["es2020"],
});

writeFileSync(
  resolve(assetDir, "analyzer.html"),
  [
    "<!doctype html>",
    '<meta charset="utf-8">',
    "<title>andmx dwf analyzer</title>",
    '<script src="analyzer.js"></script>',
    "",
  ].join("\n"),
);

const bytes = statSync(resolve(assetDir, "analyzer.js")).size;
process.stdout.write(
  `dwf-analyzer: analyzer.js ${(bytes / 1024 / 1024).toFixed(2)} MiB -> ${assetDir}\n`,
);
