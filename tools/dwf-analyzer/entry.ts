// 唯一入口。这里**不重写任何分析逻辑**：每个方法都是对上游导出函数的直接调用，
// 区别只在把哪几个字段交出去。
//
// 为什么分这几档：
//   - `core` 是上游的内部铸造物（taint 事实 + 时序轨迹），唯一用途是被投影；
//     bundle 里投影已经做完了，把它搬过 WebView 桥要 350KB，而三个图加起来才 4KB。
//   - `compile` 只答「能不能提交」，SaveWorkflow 的审批 gate 只要这一个答案。
//   - `prepare` 是**执行前**那一档（见 `docs/execution-engine.md`「From script to sandbox input」）：
//     一次交出 lowered 函数体、每站点的 ask 规格、站点出生阶段，加上产物与模型清单。
//     有了它，宿主只剩「把 lowered 喂进沙箱」和「实现 driver port」两件事。
//
// 语义保真：返回值的字段名、类型、嵌套形状与上游同名类型逐字一致。
import {
  analyzeWorkflowScript,
  buildAskSpecs,
  collectSitePhases,
  collectSites,
  createWorkflowProgram,
  lowerWorkflowScript,
  synthesizeAskSchemas,
} from "@zcode/dynamic-workflow";

type AnalyzeResult = ReturnType<typeof analyzeWorkflowScript>;
type FullResult = AnalyzeResult & { core?: unknown };

/** 去掉 `core`：投影产物留下，投影的输入不留。 */
function withoutCore(result: AnalyzeResult): Omit<AnalyzeResult, "core"> {
  const { core: _core, ...rest } = result as FullResult;
  return rest as Omit<AnalyzeResult, "core">;
}

/**
 * 执行前的全部输入。
 *
 * 编译次数说明：上游 `createWorkflowProgram` 是三条管线的共享底座，但 `analyzeWorkflowScript`
 * 与 `lowerWorkflowScript` 各自建自己的 program——上游测试的装配（runtime 包的 `helpers.ts`）
 * 也是这么跑的。这里与它同形，不去手工合并：合并等于把「编译一次」的解释权收到本文件，
 * 而上游那个契约（`scriptText` + `lowered` 一起交给 harness）本来就归调用方。
 */
function prepare(script: string) {
  const workflow = createWorkflowProgram(script);
  const table = collectSites(workflow);
  const { schemas } = synthesizeAskSchemas(workflow, table);
  const askSpecs = buildAskSpecs(table, schemas);

  const analysis = analyzeWorkflowScript(script) as FullResult;
  const lowering = lowerWorkflowScript(script);

  if (!analysis.ok || lowering.ok === false || lowering.lowered === undefined) {
    return {
      diagnostics: lowering.ok ? analysis.diagnostics : lowering.diagnostics,
      ok: false,
    };
  }

  const core = analysis.core as Parameters<typeof collectSitePhases>[0];
  return {
    askSpecs: [...askSpecs.entries()],
    declaredArtifacts: analysis.declaredArtifacts,
    diagnostics: analysis.diagnostics,
    graph: analysis.graph,
    lowered: lowering.lowered,
    modelReferences: analysis.modelReferences,
    ok: true,
    sitePhases: [...collectSitePhases(core).entries()],
  };
}

const host = {
  /** 完整结果（含 core）。桥接代价高，只在需要重新投影时用。 */
  analyzeFull(script: string): AnalyzeResult {
    return analyzeWorkflowScript(script);
  },
  /** 分析结果，去掉内部铸造物 core。工具层用的就是这一档。 */
  analyze(script: string): Omit<AnalyzeResult, "core"> {
    return withoutCore(analyzeWorkflowScript(script));
  },
  /** 只答「编不编得过」：ok + diagnostics。 */
  compile(script: string): { ok: boolean; diagnostics: AnalyzeResult["diagnostics"] } {
    const result = analyzeWorkflowScript(script);
    return { diagnostics: result.diagnostics, ok: result.ok };
  },
  /** 降到沙箱输入（lowered 函数体）——执行链的第一步。 */
  lower(script: string) {
    return lowerWorkflowScript(script);
  },
  /** 执行前的全部输入：lowered + ask 规格 + 站点阶段 + 产物与模型清单。 */
  prepare,
};

(globalThis as unknown as { __DWF: typeof host }).__DWF = host;
