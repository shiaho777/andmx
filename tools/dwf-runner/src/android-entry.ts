import { WorkflowRunner, type RunnerBridge } from "./android-runner.js";
import type { CellRealm } from "./host.js";

declare const __andmx: RunnerBridge;

declare global {
  interface Window {
    __DWF_RUNNER: {
      start(configJson: string): Promise<{ runId: string }>;
      askResult(requestId: string, resultJson: string): void;
      worldResult(requestId: string, ok: boolean, payloadJson: string, error?: string): void;
      artifactResult(requestId: string, ok: boolean, payloadJson: string, error?: string): void;
      cancel(runId: string): void;
    };
  }
}

function ensureSetImmediate(): void {
  const target = globalThis as unknown as {
    setImmediate?: (fn: () => void) => void;
  };
  if (target.setImmediate === undefined) {
    target.setImmediate = (fn: () => void) => setTimeout(fn, 0);
  }
}

async function createIframeRealm(): Promise<CellRealm> {
  const iframe = document.createElement("iframe");
  iframe.setAttribute("aria-hidden", "true");
  iframe.style.display = "none";
  iframe.src = "about:blank";
  const loaded = new Promise<void>((resolve, reject) => {
    iframe.onload = () => resolve();
    iframe.onerror = () => reject(new Error("workflow cell iframe failed to load"));
  });
  (document.body ?? document.documentElement).appendChild(iframe);
  await loaded;
  const win = iframe.contentWindow;
  if (win === null) throw new Error("workflow cell iframe has no contentWindow");
  return {
    global: win as unknown as Record<string, unknown>,
    evaluate: (code: string) => {
      // Direct `win.eval` keeps the script's strict-mode prologue, which would
      // make top-level `var` declarations eval-local instead of realm globals.
      // BOOTSTRAP uses those vars from a second script, so strip only the
      // leading directive (script payloads are ours, not user input).
      const stripped = code.replace(/^\s*(['"])use strict\1;?\s*/, "");
      return win.eval(stripped);
    },
    close: () => iframe.remove(),
  };
}

ensureSetImmediate();
const runner = new WorkflowRunner({ createRealm: createIframeRealm, bridge: __andmx });

window.__DWF_RUNNER = {
  start: (configJson) => runner.start(configJson),
  askResult: (requestId, resultJson) => runner.askResult(requestId, resultJson),
  worldResult: (requestId, ok, payloadJson, error) =>
    runner.worldResult(requestId, ok, payloadJson, error),
  artifactResult: (requestId, ok, payloadJson, error) =>
    runner.artifactResult(requestId, ok, payloadJson, error),
  cancel: (runId) => runner.cancel(runId),
};
