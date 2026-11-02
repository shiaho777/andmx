/**
 * node 侧的 `CellEndpoint`：用 `startCell` + 一条异步通道拼出来。
 *
 * 它的存在是为了让 `host.ts` 能在这里被跑起来做对照。设备侧会有一份同构的实现，把
 * `send` / `onLine` 接到 iframe 的 `postMessage` 上——两端喂给宿主的都是同一形状。
 *
 * 两个方向各有一处「等一拍」是刻意的：管道与 `postMessage` 都是异步的，同步直调会造出上游
 * 生产路径上不存在的重入（父进程在 cell 的调用栈里回话）。
 */
import type { ChildPayload } from "@zcode/dynamic-workflow-runtime";
import { startCell, type CellPort } from "./cell.js";
import type { CellEndpoint } from "./host.js";
import { nodeRealm } from "./node-realm.js";

export interface LoopbackOptions {
  payload: ChildPayload;
  streamsBootstrap: string;
}

/** 排到下一轮宏任务，模拟管道 / postMessage 的迟到。 */
function later(fn: () => void): void {
  setTimeout(fn, 0);
}

export function loopbackCell(options: LoopbackOptions): CellEndpoint {
  let toCell: (line: string) => void = () => {};
  let deliver: ((line: string) => void) | undefined;
  // 宿主注册 onLine 之前沙箱可能已经发过话（`create-actor` 就是同步发的），先攒着。
  const pending: string[] = [];
  let closed = false;

  const cellPort: CellPort = {
    onLine: (handler) => {
      toCell = handler;
    },
    post: (line) => {
      if (closed) return;
      later(() => {
        if (closed) return;
        if (deliver === undefined) pending.push(line);
        else deliver(line);
      });
    },
  };

  const done = startCell({
    payload: options.payload,
    port: cellPort,
    realm: nodeRealm(),
    streamsBootstrap: options.streamsBootstrap,
  });

  return {
    close: () => {
      closed = true;
    },
    done,
    onLine: (handler) => {
      deliver = handler;
      while (pending.length > 0) {
        const line = pending.shift();
        if (line !== undefined) handler(line);
      }
    },
    send: (line) => {
      if (closed) return;
      later(() => {
        if (!closed) toCell(line);
      });
    },
  };
}
