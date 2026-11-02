/**
 * cell realm 的净化：把「内核之外还能唤醒它的东西」全部抹掉。
 *
 * ## 为什么这一步不是可选的
 *
 * 上游的 cell 是 `vm.createContext` 出来的裸上下文——**天然**没有定时器、没有 fetch、没有
 * DOM、没有父窗口。停滞检测（`__checkStalled`）成立的前提正是这个：
 *
 *   > 每条 response 投递后、以及脚本起步后，各在 setImmediate 上问一次「还有事可做吗」。
 *   > setImmediate 排在本轮全部微任务之后，而 cell 内没有定时器与 I/O，所以「无在飞请求且
 *   > 未完成」在这一刻成立就永远成立。
 *
 * iframe 默认全都有。一旦留着，一个 `setTimeout` 就能让脚本在「看起来停滞」之后再醒来，
 * 停滞检测会提前宣告失败——而且只在特定时序下出现，是最难查的那类坏掉。
 *
 * ## 抹掉的第二类东西：逃逸通道
 *
 * `parent` / `top` / `opener` / `postMessage` 必须断掉。留着它们，脚本可以绕过 `__send`
 * 直接对宿主说话——而 `__send` 是唯一被记录、被落 journal 的出口。绕过它等于绕过整个 journal。
 *
 * ## 与 BOOTSTRAP 的分工
 *
 * 运行期禁令（`Date.now` / 无参 `new Date()` / `Math.random`）由 BOOTSTRAP 自己做，它要在
 * 同一个 realm 里替换 `Date` 与 `Math.random`。本文件只管「内核之外的东西」，两者不重叠。
 */
export const CELL_REALM_SANITIZER = String.raw`(function () {
  "use strict";
  var names = [
    "setTimeout", "setInterval", "setImmediate",
    "clearTimeout", "clearInterval", "clearImmediate",
    "requestAnimationFrame", "cancelAnimationFrame",
    "requestIdleCallback", "cancelIdleCallback",
    "fetch", "XMLHttpRequest", "WebSocket", "EventSource",
    "Worker", "SharedWorker", "ServiceWorker", "importScripts",
    "postMessage", "addEventListener", "removeEventListener", "dispatchEvent",
    "localStorage", "sessionStorage", "indexedDB", "caches", "navigator",
    "console", "alert", "confirm", "prompt", "open", "close"
  ];
  for (var i = 0; i < names.length; i++) {
    var name = names[i];
    try { delete globalThis[name]; } catch (e) { /* 浏览器里有些是 non-configurable */ }
    try { globalThis[name] = undefined; } catch (e) { /* 只读属性，放弃 */ }
  }
  // 与父窗口的联系必须断开：脚本能对宿主说话的唯一通道是 __send。
  var severed = ["parent", "top", "opener", "frames", "self"];
  for (var j = 0; j < severed.length; j++) {
    try {
      Object.defineProperty(globalThis, severed[j], {
        configurable: false,
        enumerable: false,
        value: undefined,
        writable: false
      });
    } catch (e) { /* 已经不可重定义时无妨 */ }
  }
})();`;

/** 在 realm 里执行净化。必须在 `startCell` 之前调用。 */
export function applyRealmSanitizer(realm: {
  evaluate(code: string, filename: string): unknown;
}): void {
  realm.evaluate(CELL_REALM_SANITIZER, "cell-realm-sanitize.js");
}
