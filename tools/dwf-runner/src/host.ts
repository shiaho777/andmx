/**
 * 引擎宿主：把「一条 NDJSON 端点」接到上游 `WorkflowEngine` 上。
 *
 * ## 它替换的是 `harness.ts` 的哪一半
 *
 * 上游 `runWorkflowScript` = 建引擎 + 写入口文件 + `spawn` 子进程 + `bridge`。其中
 * `bridge` / `handleChildMessage` / `handleRequest` 三段（约 145 行）**与传输无关**：它们只
 * 消费 `ChildMessage`、调引擎、回 `ResponseMessage`。本文件就是那三段，加上结算收敛。
 *
 * 被替换的是进程那半：`spawn`、stdio、`kill`、`close` 事件。设备上这些由 WebView 生命周期
 * 取代，node 上由 `loopback.ts` 用 vm realm 顶替——两端喂给本文件的都是同一种端点。
 *
 * ## 刻意保留的上游语义（每一条都有代价，删不得）
 *
 * - **single-writer finalize**：无论从引擎结算、超时、abort 还是沙箱死亡触达，第一个到达者胜出。
 * - **create-actor 同步处理**：FIFO 保证任何引用该句柄的 ask 到达前映射已就绪。
 * - **事件同步分派**：`report` 与 `declare-artifact` 的到达顺序就是 journal 顺序，异步化会让
 *   一条打了标签的 report 落到它的声明之前。
 * - **宿主侧故障 → stopped(interrupted) 而不是 failed**：可 resume；脚本抛错才是 failed。
 * - **协议损坏大声拒绝**：一行不是合法 JSON 就停下，不猜、不跳过。
 *
 * ## 与上游的一处必然差异
 *
 * 上游靠子进程的 `close` 事件判「沙箱没了但引擎还没结算」。`close` 在该进程 stdio 排空之后
 * 才触发，所以收到的行一定先于它到达。设备上没有进程可等，所以这里在端点 `done` 之后**再等
 * 一个宏任务**才判定死亡——否则 `complete` 那一行还在路上就会被误判成崩溃。
 */
import { WorkflowEngine } from "@zcode/dynamic-workflow/engine/engine";
import { WorkflowError } from "@zcode/dynamic-workflow/engine/errors";
import type {
  ActorId,
  AskSpec,
  Caps,
  ImportedRunCache,
  RunSettlement,
  ValidateFn,
  WorkflowDriver,
  WorkflowReportSink,
} from "@zcode/dynamic-workflow/engine/types";
import type { ChildMessage, ResponseMessage } from "../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow-runtime/src/protocol.js";

/** 宿主对沙箱说的话：一条 NDJSON 行。 */
export interface CellEndpoint {
  /** 发一行给沙箱（不含换行）。 */
  send(line: string): void;
  /** 收沙箱发来的行（不含换行）。 */
  onLine(handler: (line: string) => void): void;
  /** 沙箱终结（正常退出或异常）。 */
  readonly done: Promise<void>;
  /** 主动销毁沙箱。 */
  close(): void;
}

/** 上游 `DriverFactory`：先建 sink（引擎的向上回报面），交给工厂造 driver，再以该 driver 建引擎。 */
export type DriverFactory = (sink: WorkflowReportSink) => WorkflowDriver;

export interface StartRunOptions {
  runId: string;
  endpoint: CellEndpoint;
  makeDriver: DriverFactory;
  caps: Caps;
  askSpecs: ReadonlyMap<string, AskSpec>;
  validate: ValidateFn;
  sitePhases?: ReadonlyMap<string, string>;
  scriptText?: string;
  name?: string;
  args?: Record<string, unknown>;
  scriptHash?: string;
  parentSessionId?: string;
  resumedFrom?: string;
  importedCache?: ImportedRunCache;
  cwd: string;
  /**
   * journal **不在这里**：它挂在 `driver.journal` 上（上游 `EngineConfig` 没有这个字段）。
   * 所以「journal 落在哪一侧」这件事由 driver 的实现决定——设备侧就是桥到 Room 的那个
   * `JournalStorePort`，node 侧就是 `InMemoryJournalStore`。
   */
  timeoutMs?: number;
  signal?: AbortSignal;
  /** 活体控制面（改并发上界、补留白）。 */
  bindControl?(engine: WorkflowEngine): void;
}

export interface RunHandle {
  /** run 结算后兑现——与上游 `runWorkflowScript` 的返回值同一形状。 */
  settled: Promise<RunSettlement>;
  /** 活体引擎（控制面用）。引擎结算后调用是 no-op。 */
  engine: WorkflowEngine;
}

/** 启动一次 run。同步建引擎（journal 行随之落库），返回句柄。 */
export function startRun(options: StartRunOptions): RunHandle {
  // 引擎与 driver 互为输入：driver 需要 sink，sink 需要引擎。用**每次 run 一个**的盒子打破
  // 这个环——不是模块级单例，否则两个并发 run 会互相覆盖。
  const box: { engine?: WorkflowEngine } = {};
  const driver = options.makeDriver(makeSink((method, args) => {
    const engine = box.engine;
    if (engine === undefined) throw new Error(`sink.${method} arrived before the engine was built`);
    return (engine as unknown as Record<string, (...inner: unknown[]) => unknown>)[method](...args);
  }));

  const engine = new WorkflowEngine({
    askSpecs: options.askSpecs,
    caps: options.caps,
    cwd: options.cwd,
    driver,
    runId: options.runId,
    validate: options.validate,
    ...(options.args === undefined ? {} : { args: options.args }),
    ...(options.name === undefined ? {} : { name: options.name }),
    ...(options.parentSessionId === undefined ? {} : { parentSessionId: options.parentSessionId }),
    ...(options.resumedFrom === undefined ? {} : { resumedFrom: options.resumedFrom }),
    ...(options.importedCache === undefined ? {} : { importedCache: options.importedCache }),
    ...(options.scriptHash === undefined ? {} : { scriptHash: options.scriptHash }),
    ...(options.scriptText === undefined ? {} : { scriptText: options.scriptText }),
    ...(options.sitePhases === undefined ? {} : { sitePhases: options.sitePhases }),
  });
  box.engine = engine;
  options.bindControl?.(engine);

  return { engine, settled: bridge(options, engine) };
}

/**
 * 造一个把调用转发到 `engine[method](...args)` 的 sink。
 *
 * 逐个方法转发而不是 `new Proxy`：sink 的成员是**枚举出来的契约**，漏一个就会在运行期变成
 * 「某个观察静默丢失」，而 Proxy 会让漏掉的成员看起来正常工作。
 */
function makeSink(forward: (method: string, args: unknown[]) => unknown): WorkflowReportSink {
  const methods = [
    "askSubmitAttempted",
    "askTurnEnded",
    "askProgress",
    "askStats",
    "askFailed",
    "stopRun",
    "askWaiting",
    "askExecuting",
    "askMutating",
    "concurrencyChanged",
    "runStalled",
  ] as const;
  const sink: Record<string, (...args: unknown[]) => unknown> = {};
  for (const method of methods) {
    sink[method] = (...args: unknown[]) => forward(method, args);
  }
  return sink as unknown as WorkflowReportSink;
}

function bridge(options: StartRunOptions, engine: WorkflowEngine): Promise<RunSettlement> {
  const { endpoint, runId, signal, timeoutMs } = options;

  // local#N（沙箱内句柄）→ engine ActorId。FIFO + 同步 create-actor 处理保证任何引用某句柄的
  // ask 到达前，这里已经有映射。
  const actorMap = new Map<string, ActorId>();
  let finalized = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let unbind: (() => void) | undefined;

  return new Promise<RunSettlement>((resolve) => {
    const finalize = (settlement: RunSettlement): void => {
      if (finalized) return;
      finalized = true;
      if (timer !== undefined) clearTimeout(timer);
      unbind?.();
      endpoint.close();
      resolve(settlement);
    };

    /** 脚本抛错 / 协议损坏：run 失败。裁决归引擎，journal 与调用方不分叉。 */
    const failRun = (error: WorkflowError): void => {
      if (finalized) return;
      engine.fail(error);
    };

    /** 宿主侧故障（沙箱崩溃 / 超时 / 协议损坏）：stopped(interrupted)，可 resume。 */
    const interruptRun = (message: string, cause?: unknown): void => {
      if (finalized) return;
      engine.stop(
        "interrupted",
        new WorkflowError("Interrupted", message, cause === undefined ? undefined : { cause }),
      );
    };

    const onAbort = (): void => {
      if (finalized) return;
      const reason: unknown = signal?.reason;
      const supersededBy = readSupersededBy(reason);
      if (supersededBy !== undefined) {
        engine.stop("superseded", undefined, supersededBy);
        return;
      }
      if (reason === "interrupted") {
        engine.stop(
          "interrupted",
          new WorkflowError(
            "Interrupted",
            `dynamic workflow run ${runId} was interrupted: the owning session closed before the run settled`,
          ),
        );
        return;
      }
      engine.stop(reason === "model" ? "model" : "user");
    };

    // 一切结算（complete / fail / stop / 引擎内部 cap 失败）都经 engine.settled 冒到这里。
    void engine.settled.then(finalize);

    if (signal !== undefined) {
      if (signal.aborted) {
        onAbort();
      } else {
        signal.addEventListener("abort", onAbort, { once: true });
        unbind = () => signal.removeEventListener("abort", onAbort);
      }
    }

    if (timeoutMs !== undefined) {
      timer = setTimeout(() => {
        interruptRun(`The workflow sandbox exceeded its wall-clock timeout of ${timeoutMs}ms`);
      }, timeoutMs);
      // node 上别让这个 timer 拦住进程退出；设备侧没有 unref。
      (timer as unknown as { unref?: () => void }).unref?.();
    }

    const respond = (message: Extract<ChildMessage, { kind: "request" }>, ok: boolean, value: unknown, error?: WorkflowError): void => {
      const response: ResponseMessage = {
        id: message.id,
        kind: "response",
        ok,
        ...(ok ? { value } : { error: toWireError(error) }),
      };
      endpoint.send(JSON.stringify(response));
    };

    const handleRequest = (message: Extract<ChildMessage, { kind: "request" }>): void => {
      let promise: Promise<unknown>;
      if (message.type === "ask") {
        const actorId = actorMap.get(message.actor ?? "");
        if (actorId === undefined) {
          // 映射缺失（FIFO 下理应不会发生）——归一成结构化拒绝，不静默。
          respond(message, false, undefined,
            new WorkflowError("UnknownActor", `Unknown subagent handle: ${message.actor}`));
          return;
        }
        promise = engine.ask(message.siteId, actorId, message.instructions ?? "");
      } else if (message.type === "publish-artifact") {
        const op = message.artifactOp;
        if (op === undefined) {
          // 缺 op 是接线错误。**不编默认值**：被当成 file 处理的 markdown 发布，错误会出现在
          // 离故障点很远的地方。
          respond(message, false, undefined,
            new WorkflowError("DriverError",
              `publish-artifact request is missing artifactOp (site ${message.siteId})`));
          return;
        }
        promise = engine.publishArtifact(message.siteId, op, message.args ?? []);
      } else if (message.type === "hole") {
        promise = engine.hole(message.siteId, message.name ?? "", message.prompt);
      } else {
        promise = engine.worldRead(message.siteId, message.op ?? "read", message.args ?? []);
      }

      promise.then(
        (value) => respond(message, true, value),
        (cause: unknown) => {
          respond(message, false, undefined, cause instanceof WorkflowError
            ? cause
            : new WorkflowError("DriverError",
              cause instanceof Error ? cause.message : String(cause), { cause }));
        },
      );
    };

    const handleChildMessage = (message: ChildMessage): void => {
      switch (message.kind) {
        case "create-actor": {
          try {
            const actorId = engine.createActor(
              message.siteId,
              message.name,
              message.persona as string | undefined,
            );
            actorMap.set(message.localId, actorId);
          } catch (cause) {
            failRun(cause instanceof WorkflowError
              ? cause
              : new WorkflowError("DriverError",
                `createActor failed at site ${message.siteId}`, { cause }));
          }
          return;
        }
        case "event":
          // 同步分派：事件通道的 FIFO 顺序对 report 与 declare-artifact 都是承重的。
          if (message.type === "report") {
            engine.report(message.siteId, message.item, message.artifactId);
          } else if (message.type === "declare-artifact") {
            engine.declareArtifact(message.siteId, message.op, message.args);
          } else if (message.type === "phase-entered") {
            engine.enterPhase(message.name);
          } else {
            engine.log(message.message);
          }
          return;
        case "complete":
          if (message.ok) {
            engine.complete(message.value);
          } else {
            const error = message.error;
            failRun(new WorkflowError("DriverError",
              error?.message ?? "The workflow script threw an error",
              { cause: error?.stack ?? error?.name }));
          }
          return;
        case "request":
          handleRequest(message);
          return;
        default:
          return;
      }
    };

    endpoint.onLine((line) => {
      if (finalized) return;
      if (line.trim().length === 0) return;
      let message: ChildMessage;
      try {
        message = JSON.parse(line) as ChildMessage;
      } catch (cause) {
        // 沙箱行 JSON 损坏：暴露而非吞掉。
        interruptRun(
          `The workflow sandbox emitted a line that is not valid NDJSON: ${line}`,
          cause,
        );
        return;
      }
      handleChildMessage(message);
    });

    // 沙箱没了但引擎未结算 → 崩溃/被杀而无 complete。与上游 `child.on("close")` 同一分支，
    // 只是要多等一个宏任务：`complete` 那一行可能还在异步通道里。
    void endpoint.done.then(
      () => {
        setTimeout(() => {
          interruptRun("The workflow sandbox closed before the run settled");
        }, 0);
      },
      (error: unknown) => {
        setTimeout(() => {
          interruptRun(
            `The workflow sandbox failed: ${error instanceof Error ? error.message : String(error)}`,
            error,
          );
        }, 0);
      },
    );
  });
}

/** WorkflowError → 线形态（保留 code/violations/finalText，供沙箱脚本 try/catch 结构化处理）。 */
function toWireError(error: WorkflowError | undefined): ResponseMessage["error"] {
  if (error === undefined) return { message: "unknown error", name: "Error" };
  const wire: NonNullable<ResponseMessage["error"]> = {
    code: error.code,
    message: error.message,
    name: error.name,
  };
  if (error.violations !== undefined) wire.violations = error.violations;
  if (error.finalText !== undefined) wire.finalText = error.finalText;
  return wire;
}

/** abort reason 是 `{ superseded: <runId> }` 时取出后继 id；其余形状返回 undefined。 */
function readSupersededBy(reason: unknown): string | undefined {
  if (typeof reason !== "object" || reason === null) return undefined;
  const value = (reason as { superseded?: unknown }).superseded;
  return typeof value === "string" && value.length > 0 ? value : undefined;
}
