/**
 * node 侧的 realm 构造。**只给对照测试用**——设备侧对应物是 iframe 的 window。
 *
 * 单独成文件是为了让 `cell.ts` 保持平台中立：它不该为了一个测试把 `node:vm` 拉进设备打包。
 */
import vm from "node:vm";
import type { CellRealm } from "./cell.js";

/**
 * 造一个真隔离的 realm。
 *
 * 刻意用 `vm.createContext` 而不是「一个大对象」：对照测试要回答的是「把 realm 换成别的东西
 * 之后，cell 的行为是否一模一样」，如果参照物本身不是真 realm，这个对照就没有意义。
 */
export function nodeRealm(): CellRealm {
  const sandbox: Record<string, unknown> = {};
  const contextified = vm.createContext(sandbox, { name: "cell-realm" }) as Record<string, unknown>;
  return {
    global: contextified,
    evaluate: (code, filename) => vm.runInContext(code, contextified, { filename }),
  };
}
