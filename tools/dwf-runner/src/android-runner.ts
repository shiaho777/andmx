/**
 * Device-neutral dynamic-workflow runner.
 *
 * The WebView entry point supplies an iframe realm; the Node probe supplies a
 * `vm` realm. Everything else — engine host, talking to the cell, persistence
 * hooks, actor/world-read bridging — is shared and testable off-device.
 */
import { InMemoryJournalStore } from "@zcode/dynamic-workflow/engine/journal-memory";
import { WorkflowError } from "@zcode/dynamic-workflow/engine/errors";
import { validate } from "@zcode/dynamic-workflow/schema/validate";
import type {
  ActorRef,
  ArtifactPublishRequest,
  ArtifactVersionRecord,
  AskMessage,
  AskSpec,
  Caps,
  InstanceRef,
  JournalStorePort,
  PersonaSpec,
  RunEvent,
  SessionRef,
  SubmitVerdict,
  WorkflowDriver,
  WorkflowReportSink,
  WorldReadOp,
} from "@zcode/dynamic-workflow/engine/types";
import { CELL_STREAMS_BOOTSTRAP } from "@zcode/dynamic-workflow-runtime/child-cell-streams";
import type { ChildPayload } from "@zcode/dynamic-workflow-runtime/protocol";
import { startCell, type CellPort } from "./cell.js";
import { applyRealmSanitizer } from "./realm-sanitize.js";
import type { CellEndpoint, CellRealm } from "./host.js";
import { startRun } from "./host.js";

declare const structuredClone: <T>(value: T) => T;

export interface RunnerBridge {
  saveJournal(runId: string, json: string): void;
  emit(runId: string, eventJson: string): void;
  createActor(runId: string, siteId: string, ordinal: number, personaJson: string): void;
  startAsk(runId: string, requestId: string, requestJson: string): void;
  continueAsk(requestId: string, verdictJson: string): void;
  cancelAsk(requestId: string): void;
  worldRead(requestId: string, op: string, argsJson: string): void;
  publishArtifact(requestId: string, requestJson: string): void;
  settled(runId: string, settlementJson: string): void;
  log(message: string): void;
}

export interface RunnerEnv {
  createRealm(): Promise<CellRealm>;
  bridge: RunnerBridge;
}

export interface StartConfig {
  runId: string;
  lowered: string;
  scriptText?: string;
  cwd: string;
  conversationId?: number;
  caps?: Caps;
  askSpecs?: Array<[string, AskSpec]>;
  sitePhases?: Array<[string, string]>;
  args?: Record<string, unknown>;
  scriptHash?: string;
  journalJson?: string;
  resumedFrom?: string;
  timeoutMs?: number;
}

interface PendingWorld {
  resolve: (value: unknown) => void;
  reject: (error: unknown) => void;
}

interface PendingArtifact {
  resolve: (value: ArtifactVersionRecord) => void;
  reject: (error: unknown) => void;
}

export class WorkflowRunner {
  private readonly runs = new Map<
    string,
    { driver: BridgeDriver; endpoint: DeviceEndpoint; engine: unknown }
  >();

  constructor(private readonly env: RunnerEnv) {
    if (typeof structuredClone === "undefined") {
      (globalThis as unknown as { structuredClone: <T>(value: T) => T }).structuredClone =
        <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
    }
  }

  async start(configJson: string): Promise<{ runId: string }> {
    const config = JSON.parse(configJson) as StartConfig;
    if (!config.runId || !config.lowered || !config.cwd) {
      throw new Error("runner: runId, lowered and cwd are required");
    }
    const journal = config.journalJson
      ? PortableJournal.fromJSON(JSON.parse(config.journalJson), this.env.bridge)
      : new PortableJournal(this.env.bridge);
    const driver = new BridgeDriver(
      config.runId,
      journal,
      this.env.bridge,
      config.conversationId ?? 0,
    );
    const endpoint = new DeviceEndpoint();
    const realm = await this.env.createRealm();
    endpoint.bindClose(() => realm.close?.());
    applyRealmSanitizer(realm);
    const payload: ChildPayload = {
      lowered: config.lowered,
      ...(config.args === undefined ? {} : { args: config.args }),
    };
    const handle = startRun({
      runId: config.runId,
      endpoint,
      makeDriver: (sink) => {
        driver.attach(sink);
        return driver;
      },
      caps: config.caps ?? { maxConcurrency: 4 },
      askSpecs: new Map(config.askSpecs ?? []),
      sitePhases: new Map(config.sitePhases ?? []),
      validate: (schema, value) => validate(schema as never, value),
      ...(config.scriptText === undefined ? {} : { scriptText: config.scriptText }),
      ...(config.args === undefined ? {} : { args: config.args }),
      ...(config.scriptHash === undefined ? {} : { scriptHash: config.scriptHash }),
      ...(config.resumedFrom === undefined ? {} : { resumedFrom: config.resumedFrom }),
      ...(config.timeoutMs === undefined ? {} : { timeoutMs: config.timeoutMs }),
      cwd: config.cwd,
    });
    if (!payload.lowered) throw new Error("runner: lowered script is empty");

    let hostToCell: ((line: string) => void) | undefined;
    const cellPort: CellPort = {
      post: (line) => endpoint.receiveFromCell(line),
      onLine: (handler) => {
        hostToCell = handler;
        endpoint.bindCell((line) => hostToCell?.(line));
      },
    };
    const cellDone = startCell({
      realm,
      port: cellPort,
      payload,
      streamsBootstrap: CELL_STREAMS_BOOTSTRAP,
    });
    endpoint.bindCell((line) => hostToCell?.(line));
    cellDone.then(
      () => endpoint.finish(),
      (error: unknown) => endpoint.fail(error),
    );

    this.runs.set(config.runId, { driver, endpoint, engine: handle.engine });
    void handle.settled.then((settlement) => {
      try {
        this.env.bridge.settled(config.runId, JSON.stringify(settlement));
      } finally {
        try {
          this.env.bridge.saveJournal(config.runId, JSON.stringify(journal.toJSON()));
        } catch {
          // Persistence failures must not hide the settlement.
        }
        endpoint.close();
        this.runs.delete(config.runId);
      }
    });
    return { runId: config.runId };
  }

  askResult(requestId: string, resultJson: string): void {
    this.driverForRequest(requestId)?.askResult(requestId, resultJson);
  }

  worldResult(requestId: string, ok: boolean, payloadJson: string, error?: string): void {
    this.driverForRequest(requestId)?.worldResult(requestId, ok, payloadJson, error);
  }

  artifactResult(requestId: string, ok: boolean, payloadJson: string, error?: string): void {
    this.driverForRequest(requestId)?.artifactResult(requestId, ok, payloadJson, error);
  }

  fillHole(runId: string, fillJson: string): { ok: boolean; reason?: string } {
    const run = this.runs.get(runId);
    if (run === undefined) return { ok: false, reason: "not_running" };
    const raw = JSON.parse(fillJson) as {
      siteId: string;
      code: string;
      script: { text: string; hash: string };
      askSpecs?: Array<[string, AskSpec]>;
      sitePhases?: Array<[string, string]>;
      phaseNames?: string[];
      holes?: number[];
      filledBy?: string;
    };
    const engine = run.engine as {
      fillHole(fill: unknown): { ok: boolean; reason?: string };
    };
    return engine.fillHole({
      ...raw,
      askSpecs: new Map(raw.askSpecs ?? []),
      sitePhases: new Map(raw.sitePhases ?? []),
    });
  }

  openHoles(runId: string): unknown[] {
    const run = this.runs.get(runId);
    if (run === undefined) return [];
    const engine = run.engine as { openHoles(): unknown[] };
    return engine.openHoles();
  }

  cancel(runId: string): void {
    const run = this.runs.get(runId);
    run?.endpoint.close();
    this.runs.delete(runId);
  }

  stop(runId: string, reason: string, supersededBy?: string): boolean {
    const run = this.runs.get(runId);
    if (run === undefined) return false;
    const engine = run.engine as {
      stop(reason: string, error?: unknown, supersededBy?: string): void;
    };
    engine.stop(reason, undefined, supersededBy);
    return true;
  }

  private driverForRequest(requestId: string): BridgeDriver | undefined {
    for (const run of this.runs.values()) {
      if (run.driver.owns(requestId)) return run.driver;
    }
    return undefined;
  }
}

class DeviceEndpoint implements CellEndpoint {
  private fromCell: ((line: string) => void) | undefined;
  private fromCellBuffer: string[] = [];
  private toCell: ((line: string) => void) | undefined;
  private toCellBuffer: string[] = [];
  private closeCell: (() => void) | undefined;
  private closed = false;
  private resolveDone!: () => void;
  private rejectDone!: (error: unknown) => void;
  readonly done: Promise<void>;

  constructor() {
    this.done = new Promise<void>((resolve, reject) => {
      this.resolveDone = resolve;
      this.rejectDone = reject;
    });
    // The runner may settle before the cell is even started on a sync failure;
    // an unhandled rejection would then crash the page.
    void this.done.catch(() => undefined);
  }

  bindCell(send: (line: string) => void): void {
    this.toCell = send;
    const buffered = this.toCellBuffer;
    this.toCellBuffer = [];
    buffered.forEach((line) => send(line));
  }

  bindClose(close: () => void): void {
    this.closeCell = close;
  }

  send(line: string): void {
    if (this.closed) return;
    const target = this.toCell;
    if (target === undefined) {
      this.toCellBuffer.push(line);
    } else {
      target(line);
    }
  }

  onLine(handler: (line: string) => void): void {
    this.fromCell = handler;
    const buffered = this.fromCellBuffer;
    this.fromCellBuffer = [];
    buffered.forEach((line) => handler(line));
  }

  receiveFromCell(line: string): void {
    if (this.closed) return;
    const target = this.fromCell;
    if (target === undefined) {
      this.fromCellBuffer.push(line);
    } else {
      target(line);
    }
  }

  finish(): void {
    if (!this.closed) this.resolveDone();
  }

  fail(error: unknown): void {
    if (!this.closed) this.rejectDone(error);
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    try {
      this.closeCell?.();
    } finally {
      this.resolveDone();
    }
  }
}

class PortableJournal extends InMemoryJournalStore {
  private runId = "";

  constructor(private readonly bridge: RunnerBridge) {
    super();
  }

  static fromJSON(data: unknown, bridge: RunnerBridge): PortableJournal {
    const journal = new PortableJournal(bridge);
    const obj = data as {
      runs?: unknown[];
      actors?: Array<[string, Array<[string, unknown]>]>;
      nodes?: Array<[string, Array<[string, unknown]>]>;
      events?: Array<[string, unknown[]]>;
    };
    const self = journal as unknown as {
      runs: Map<string, unknown>;
      actors: Map<string, Map<string, unknown>>;
      nodes: Map<string, Map<string, unknown>>;
      events: Map<string, unknown[]>;
    };
    for (const run of obj.runs ?? []) {
      const record = run as { runId?: string };
      if (record.runId !== undefined) self.runs.set(record.runId, record);
    }
    for (const [runId, entries] of obj.actors ?? []) {
      self.actors.set(runId, new Map(entries));
    }
    for (const [runId, entries] of obj.nodes ?? []) {
      self.nodes.set(runId, new Map(entries));
    }
    for (const [runId, entries] of obj.events ?? []) {
      self.events.set(runId, entries);
    }
    journal.runId = (obj.runs?.[0] as { runId?: string } | undefined)?.runId ?? "";
    return journal;
  }

  override createRun(record: Parameters<InMemoryJournalStore["createRun"]>[0]): void {
    super.createRun(record);
    this.runId = record.runId;
    this.persist();
  }

  override updateRunStatus(
    runId: string,
    status: Parameters<InMemoryJournalStore["updateRunStatus"]>[1],
    settlement?: Parameters<InMemoryJournalStore["updateRunStatus"]>[2],
  ): void {
    super.updateRunStatus(runId, status, settlement);
    this.persist();
  }

  override updateRunUsage(runId: string, spentTokens: number): void {
    super.updateRunUsage(runId, spentTokens);
    this.persist();
  }

  override updateRunCaps(runId: string, caps: Caps): void {
    super.updateRunCaps(runId, caps);
    this.persist();
  }

  override updateRunScript(runId: string, scriptText: string, scriptHash: string): void {
    super.updateRunScript(runId, scriptText, scriptHash);
    this.persist();
  }

  override putActor(record: Parameters<InMemoryJournalStore["putActor"]>[0]): void {
    super.putActor(record);
    this.persist();
  }

  override putNode(record: Parameters<InMemoryJournalStore["putNode"]>[0]): void {
    super.putNode(record);
    this.persist();
  }

  override appendEvent(
    runId: string,
    event: RunEvent,
  ): ReturnType<InMemoryJournalStore["appendEvent"]> {
    const stored = super.appendEvent(runId, event);
    this.persist();
    return stored;
  }

  toJSON(): unknown {
    const self = this as unknown as {
      runs: Map<string, unknown>;
      actors: Map<string, Map<string, unknown>>;
      nodes: Map<string, Map<string, unknown>>;
      events: Map<string, unknown[]>;
    };
    return {
      runs: [...self.runs.values()],
      actors: [...self.actors.entries()].map(([runId, entries]) => [runId, [...entries.entries()]]),
      nodes: [...self.nodes.entries()].map(([runId, entries]) => [runId, [...entries.entries()]]),
      events: [...self.events.entries()],
    };
  }

  private persist(): void {
    if (!this.runId) return;
    try {
      this.bridge.saveJournal(this.runId, JSON.stringify(this.toJSON()));
    } catch {
      // Journal bridge failure is reported by the host on the next mutation.
    }
  }
}

class BridgeDriver implements WorkflowDriver {
  private sink: WorkflowReportSink | undefined;
  private seq = 0;
  private readonly requests = new Map<string, InstanceRef>();
  private readonly instanceRequests = new Map<string, string>();
  private readonly worlds = new Map<string, PendingWorld>();
  private readonly artifacts = new Map<string, PendingArtifact>();

  constructor(
    private readonly runId: string,
    readonly journal: JournalStorePort,
    private readonly bridge: RunnerBridge,
    private readonly conversationId: number,
  ) {}

  attach(sink: WorkflowReportSink): void {
    this.sink = sink;
  }

  owns(requestId: string): boolean {
    return this.requests.has(requestId) ||
      this.worlds.has(requestId) ||
      this.artifacts.has(requestId) ||
      this.instanceRequests.has(requestId);
  }

  createActorSession(actor: ActorRef, persona: PersonaSpec): Promise<SessionRef> {
    this.bridge.createActor(this.runId, actor.siteId, actor.ordinal, JSON.stringify(persona));
    return Promise.resolve({ id: `session:${this.runId}:${actor.siteId}@${actor.ordinal}` });
  }

  startAsk(session: SessionRef, instance: InstanceRef, message: AskMessage): void {
    const requestId = `${this.runId}:ask:${++this.seq}`;
    this.requests.set(requestId, instance);
    this.instanceRequests.set(`${instance.siteId}@${instance.ordinal}`, requestId);
    this.bridge.startAsk(
      this.runId,
      requestId,
      JSON.stringify({
        sessionId: session.id,
        siteId: instance.siteId,
        ordinal: instance.ordinal,
        instructions: message.instructions,
        typed: message.typed,
        schema: message.schema,
        conversationId: this.conversationId,
      }),
    );
  }

  respondToSubmit(instance: InstanceRef, verdict: SubmitVerdict): void {
    const key = `${instance.siteId}@${instance.ordinal}`;
    const requestId = this.instanceRequests.get(key);
    if (requestId === undefined) return;
    if (verdict.kind === "accept") {
      this.instanceRequests.delete(key);
      this.requests.delete(requestId);
      return;
    }
    this.bridge.continueAsk(requestId, JSON.stringify(verdict));
  }

  cancelAsk(instance: InstanceRef): void {
    const key = `${instance.siteId}@${instance.ordinal}`;
    const requestId = this.instanceRequests.get(key);
    if (requestId === undefined) return;
    this.instanceRequests.delete(key);
    this.requests.delete(requestId);
    this.bridge.cancelAsk(requestId);
  }

  executeWorldRead(op: WorldReadOp, args: unknown[]): Promise<unknown> {
    const requestId = `${this.runId}:world:${++this.seq}`;
    return new Promise<unknown>((resolve, reject) => {
      this.worlds.set(requestId, { resolve, reject });
      this.bridge.worldRead(requestId, op, JSON.stringify(args));
    });
  }

  executeArtifactPublish(request: ArtifactPublishRequest): Promise<ArtifactVersionRecord> {
    const requestId = `${this.runId}:artifact:${++this.seq}`;
    return new Promise<ArtifactVersionRecord>((resolve, reject) => {
      this.artifacts.set(requestId, { resolve, reject });
      this.bridge.publishArtifact(requestId, JSON.stringify({ runId: this.runId, request }));
    });
  }

  emit(event: RunEvent): void {
    this.bridge.emit(this.runId, JSON.stringify(event));
  }

  askResult(requestId: string, resultJson: string): void {
    const instance = this.requests.get(requestId);
    if (instance === undefined || this.sink === undefined) return;
    const result = JSON.parse(resultJson) as {
      kind?: string;
      value?: unknown;
      error?: string;
      stats?: unknown;
    };
    if (result.stats !== undefined) {
      this.sink.askStats(instance, result.stats as never);
    }
    try {
      switch (result.kind) {
        case "submit":
          // Keep the mapping: if validation rejects, respondToSubmit reuses it.
          this.sink.askSubmitAttempted(instance, result.value);
          return;
        case "fail":
          this.requests.delete(requestId);
          this.instanceRequests.delete(`${instance.siteId}@${instance.ordinal}`);
          this.sink.askFailed(instance, new WorkflowError("DriverError", result.error ?? "agent failed"));
          return;
        default:
          this.requests.delete(requestId);
          this.instanceRequests.delete(`${instance.siteId}@${instance.ordinal}`);
          this.sink.askTurnEnded(instance, typeof result.value === "string" ? result.value : "");
      }
    } catch (error) {
      this.bridge.log(`askResult sink threw: ${String(error)}`);
    }
  }

  worldResult(requestId: string, ok: boolean, payloadJson: string, error?: string): void {
    const pending = this.worlds.get(requestId);
    if (pending === undefined) return;
    this.worlds.delete(requestId);
    if (ok) {
      pending.resolve(payloadJson === "" ? undefined : JSON.parse(payloadJson));
    } else {
      pending.reject(new WorkflowError("DriverError", error ?? "world read failed"));
    }
  }

  artifactResult(requestId: string, ok: boolean, payloadJson: string, error?: string): void {
    const pending = this.artifacts.get(requestId);
    if (pending === undefined) return;
    this.artifacts.delete(requestId);
    if (ok) {
      pending.resolve(JSON.parse(payloadJson) as ArtifactVersionRecord);
    } else {
      pending.reject(new WorkflowError("DriverError", error ?? "artifact publish failed"));
    }
  }
}
