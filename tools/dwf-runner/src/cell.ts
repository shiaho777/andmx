/**
 * cell：在「一个 realm + 一条异步消息通道」上驱动上游的 `childMain`。
 *
 * ## 为什么复用 `childMain`，而不是照着它再写一份
 *
 * `childMain` 里装着 cell 的全部语义：`__host` 的每个 facade shim、`__deliver` 的应答投递、
 * `__execute` 的终结、以及那条**运行期禁令**（`Date.now` / 无参 `new Date()` / `Math.random`）。
 * 照抄一份就是 fork，上游改一次就漂一次。所以这里只替换它依赖的三样平台设施：
 *
 *   - `vm.createContext` / `runInContext` —— 换成一个 realm（设备上是 iframe 的 window）；
 *   - `readline` —— 换成一条异步消息通道；
 *   - `stdout` —— 同上，反方向。
 *
 * ## 两条平台前提（两边都必须满足）
 *
 * 1. **外层 realm 要有 `setImmediate`。** `childMain` 的停滞检测直接引用这个裸标识符
 *    （它排在本轮全部微任务之后，而 cell 内没有别的唤醒源，于是「无在飞请求且未完成」一旦
 *    成立就永远成立）。设备侧需在调用前补一个 `setTimeout(fn, 0)` 的同义实现——同样是宏任务，
 *    同样排在全部微任务之后。
 * 2. **cell realm 里不能有别的唤醒源。** 上游靠「裸 vm context 没有定时器与 I/O」成立；
 *    iframe 默认有 `setTimeout`/`fetch`/`XMLHttpRequest`，构造 realm 的一方必须先抹掉，
 *    否则停滞检测失真。本文件不做这一步——它属于 realm 的构造方。
 *
 * ## 同步性去了哪里
 *
 * `childMain` 在 `createContext` 之后**通过 sandbox 对象**读 `__deliver` / `__execute` /
 * `__checkStalled`。在 node 的 vm 里那个对象是 context 全局的活视图；这里改成一组访问器，
 * 读写都落到 realm 全局上——效果相同，且不需要平台支持任何对象代理。
 */
import { childMain, type ChildPayload } from "@zcode/dynamic-workflow-runtime";

/** 一个求值 realm。设备侧是 iframe 的 window，node 侧是 `vm` 的 context。 */
export interface CellRealm {
  /** 该 realm 的全局对象。 */
  readonly global: Record<string, unknown>;
  /** 在**该 realm 里**求值一段脚本，返回其完成值。 */
  evaluate(code: string, filename: string): unknown;
}

/** 与 cell 之间的双向异步通道。行是 NDJSON，不含换行。 */
export interface CellPort {
  post(line: string): void;
  onLine(handler: (line: string) => void): void;
}

/** `childMain` 会从 sandbox 对象上读回的那些全局。 */
const LINKED_KEYS = ["__deliver", "__execute", "__checkStalled", "__send", "__argsJson"] as const;

type CellVm = Parameters<typeof childMain>[0]["vm"];

/** 上游 `ChildVmModule` 的窄面，绑到一个 realm 上。 */
function createVm(realm: CellRealm): CellVm {
  const target = realm.global;
  return {
    createContext(sandbox, _options) {
      // 入站属性（`__argsJson`、`__send`）落到 realm 全局，与 node 的 contextify 同效。
      for (const key of Object.keys(sandbox)) {
        target[key] = (sandbox as Record<string, unknown>)[key];
      }
      // 出站属性反向连回去：childMain 之后是通过 sandbox 对象读它们的。
      for (const key of LINKED_KEYS) {
        Object.defineProperty(sandbox, key, {
          configurable: true,
          get: () => target[key],
          set: (value: unknown) => {
            target[key] = value;
          },
        });
      }
      return target;
    },
    runInContext(code, _sandbox, options) {
      return realm.evaluate(code, options?.filename ?? "cell.js");
    },
  };
}

export interface CellOptions {
  realm: CellRealm;
  port: CellPort;
  payload: ChildPayload;
  /** cell 的第二段引导脚本（上游 `CELL_STREAMS_BOOTSTRAP`）。 */
  streamsBootstrap: string;
}

/**
 * 启动 cell。返回的 promise 在脚本执行终结后兑现——与上游 `childMain` 同一契约。
 *
 * 调用前请确认外层 realm 有 `setImmediate`（见文件头「平台前提」）。
 */
export function startCell(options: CellOptions): Promise<void> {
  const { realm, port, payload, streamsBootstrap } = options;
  let closed = false;

  return childMain({
    createInterface: () => ({
      close: () => {
        closed = true;
      },
      on: (event: "line", listener: (line: string) => void) => {
        if (event !== "line") return undefined;
        port.onLine((line) => {
          // 空行按上游同规丢弃——NDJSON 的帧之间允许有空行。
          if (closed || line.trim() === "") return;
          listener(line);
        });
        return undefined;
      },
    }),
    // childMain 只把 stdin 交给 createInterface；这里不持有真实流。
    stdin: {} as never,
    stdout: {
      write: (chunk: string) => {
        port.post(chunk.endsWith("\n") ? chunk.slice(0, -1) : chunk);
        return true;
      },
    },
    payload,
    streamsBootstrap,
    vm: createVm(realm),
  });
}
