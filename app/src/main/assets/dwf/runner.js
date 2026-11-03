(() => {
  var __defProp = Object.defineProperty;
  var __defNormalProp = (obj, key2, value) => key2 in obj ? __defProp(obj, key2, { enumerable: true, configurable: true, writable: true, value }) : obj[key2] = value;
  var __name = (target, value) => __defProp(target, "name", { value, configurable: true });
  var __publicField = (obj, key2, value) => __defNormalProp(obj, typeof key2 !== "symbol" ? key2 + "" : key2, value);

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/journal-memory.ts
  function clone(value) {
    return structuredClone(value);
  }
  __name(clone, "clone");
  function nodeCopy(node, withResult) {
    const copy = clone(node);
    if (!withResult) delete copy.result;
    return copy;
  }
  __name(nodeCopy, "nodeCopy");
  function key(siteId, ordinal) {
    return `${siteId}@${ordinal}`;
  }
  __name(key, "key");
  var _InMemoryJournalStore = class _InMemoryJournalStore {
    constructor() {
      // 以 runId 分桶，贴合"一个 store 可承载多个 run"的存储语义。
      __publicField(this, "runs", /* @__PURE__ */ new Map());
      __publicField(this, "actors", /* @__PURE__ */ new Map());
      __publicField(this, "nodes", /* @__PURE__ */ new Map());
      __publicField(this, "events", /* @__PURE__ */ new Map());
    }
    createRun(record) {
      if (this.runs.has(record.runId)) {
        throw new Error(`journal: run ${record.runId} already exists`);
      }
      this.runs.set(record.runId, clone(record));
      this.actors.set(record.runId, /* @__PURE__ */ new Map());
      this.nodes.set(record.runId, /* @__PURE__ */ new Map());
      this.events.set(record.runId, []);
    }
    getRun(runId) {
      const r = this.runs.get(runId);
      return r === void 0 ? void 0 : clone(r);
    }
    updateRunStatus(runId, status, settlement) {
      const r = this.runs.get(runId);
      if (r === void 0) throw new Error(`journal: unknown run ${runId}`);
      r.status = status;
      if (status === "pending" || status === "running") {
        delete r.failure;
        delete r.result;
        delete r.stopReason;
        delete r.supersededBy;
        return;
      }
      if (settlement?.stopReason === void 0) delete r.stopReason;
      else r.stopReason = settlement.stopReason;
      if (settlement?.supersededBy === void 0) delete r.supersededBy;
      else r.supersededBy = settlement.supersededBy;
      if (settlement?.failure === void 0) delete r.failure;
      else r.failure = clone(settlement.failure);
      if (settlement?.result !== void 0) r.result = clone(settlement.result);
    }
    updateRunUsage(runId, spentTokens) {
      const r = this.runs.get(runId);
      if (r === void 0) throw new Error(`journal: unknown run ${runId}`);
      r.spentTokens = spentTokens;
    }
    updateRunCaps(runId, caps) {
      const r = this.runs.get(runId);
      if (r === void 0) throw new Error(`journal: unknown run ${runId}`);
      r.caps = clone(caps);
    }
    updateRunScript(runId, scriptText, scriptHash) {
      const r = this.runs.get(runId);
      if (r === void 0) throw new Error(`journal: unknown run ${runId}`);
      r.scriptText = scriptText;
      r.scriptHash = scriptHash;
    }
    putActor(record) {
      const bucket = this.requireActorBucket(record.runId);
      bucket.set(key(record.siteId, record.ordinal), clone(record));
    }
    getActor(runId, siteId, ordinal) {
      const a = this.actors.get(runId)?.get(key(siteId, ordinal));
      return a === void 0 ? void 0 : clone(a);
    }
    listActors(runId, opts) {
      const bucket = this.actors.get(runId);
      if (bucket === void 0) return [];
      const matched = [...bucket.values()].filter(
        (actor) => opts.name === void 0 || actor.name === opts.name
      );
      return matched.map((actor) => {
        const copy = clone(actor);
        if (!opts.withPersona) delete copy.persona;
        return copy;
      });
    }
    putNode(record) {
      const bucket = this.requireNodeBucket(record.runId);
      bucket.set(key(record.siteId, record.ordinal), clone(record));
    }
    getNode(runId, siteId, ordinal, opts) {
      const n = this.nodes.get(runId)?.get(key(siteId, ordinal));
      return n === void 0 ? void 0 : nodeCopy(n, opts?.withResult ?? true);
    }
    listNodes(runId, opts) {
      const bucket = this.nodes.get(runId);
      if (bucket === void 0) return [];
      const kinds = opts.kinds;
      const matched = [...bucket.values()].filter(
        (node) => kinds === "all" || kinds.includes(node.kind)
      );
      const limited = opts.limit === void 0 ? matched : matched.slice(0, Math.max(0, opts.limit));
      const budgeted = opts.maxResultBytes === void 0 ? limited : withinBytes(limited, opts.maxResultBytes);
      return budgeted.map((node) => nodeCopy(node, opts.withResult));
    }
    countNodes(runId, kind) {
      const bucket = this.nodes.get(runId);
      if (bucket === void 0) return 0;
      let count = 0;
      for (const node of bucket.values()) if (node.kind === kind) count += 1;
      return count;
    }
    sumResultBytes(runId, kind) {
      const bucket = this.nodes.get(runId);
      if (bucket === void 0) return 0;
      let bytes = 0;
      for (const node of bucket.values()) if (node.kind === kind) bytes += resultBytes(node);
      return bytes;
    }
    appendEvent(runId, event) {
      const list = this.events.get(runId);
      if (list === void 0) throw new Error(`journal: unknown run ${runId}`);
      const stored = {
        sequence: list.length,
        event: clone(event),
        timeCreated: Date.now()
      };
      list.push(stored);
      return clone(stored);
    }
    listEvents(runId, opts) {
      const list = this.events.get(runId);
      if (list === void 0) return [];
      const reportLimit = opts.reportItems === "all" ? void 0 : opts.reportItems.limit;
      const stripped = /* @__PURE__ */ new Set();
      if (reportLimit !== void 0) {
        let rank = 0;
        for (const stored of list) {
          if (stored.event.type !== "report") continue;
          rank += 1;
          if (rank > reportLimit) stripped.add(stored.sequence);
        }
      }
      const types = opts.types;
      const after = opts.afterSequence;
      const filtered = list.filter(
        (e) => (types === "all" || types.includes(e.event.type)) && (after === void 0 || e.sequence > after)
      );
      const limited = opts.limit === void 0 ? filtered : filtered.slice(0, Math.max(0, opts.limit));
      return limited.map((stored) => {
        const copy = clone(stored);
        if (stripped.has(stored.sequence)) delete copy.event.item;
        return copy;
      });
    }
    requireActorBucket(runId) {
      const bucket = this.actors.get(runId);
      if (bucket === void 0) throw new Error(`journal: unknown run ${runId}`);
      return bucket;
    }
    requireNodeBucket(runId) {
      const bucket = this.nodes.get(runId);
      if (bucket === void 0) throw new Error(`journal: unknown run ${runId}`);
      return bucket;
    }
  };
  __name(_InMemoryJournalStore, "InMemoryJournalStore");
  var InMemoryJournalStore = _InMemoryJournalStore;
  function resultBytes(node) {
    if (node.result === void 0) return 0;
    const text = JSON.stringify(node.result);
    return text === void 0 ? 0 : new TextEncoder().encode(text).length;
  }
  __name(resultBytes, "resultBytes");
  function withinBytes(nodes, maxResultBytes) {
    const kept = [];
    let total = 0;
    for (const node of nodes) {
      const bytes = resultBytes(node);
      if (kept.length > 0 && total + bytes > maxResultBytes) break;
      kept.push(node);
      total += bytes;
    }
    return kept;
  }
  __name(withinBytes, "withinBytes");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/errors.ts
  var _WorkflowError = class _WorkflowError extends Error {
    constructor(code, message, extra) {
      super(message);
      __publicField(this, "code");
      __publicField(this, "violations");
      __publicField(this, "finalText");
      __publicField(this, "mismatch");
      __publicField(this, "providerStop");
      this.name = "WorkflowError";
      this.code = code;
      if (extra?.violations !== void 0) this.violations = extra.violations;
      if (extra?.finalText !== void 0) this.finalText = extra.finalText;
      if (extra?.mismatch !== void 0) this.mismatch = extra.mismatch;
      if (extra?.providerStop !== void 0) this.providerStop = extra.providerStop;
      if (extra?.cause !== void 0) this.cause = extra.cause;
    }
    /** 转为可序列化形态落 journal。 */
    toJSON() {
      const json = { code: this.code, message: this.message };
      if (this.violations !== void 0) json.violations = this.violations;
      if (this.finalText !== void 0) json.finalText = this.finalText;
      if (this.mismatch !== void 0) json.mismatch = this.mismatch;
      if (this.providerStop !== void 0) json.providerStop = this.providerStop;
      return json;
    }
    /** 从 journal 记录重建（replay 命中失败节点时用）。 */
    static fromJSON(json) {
      return new _WorkflowError(json.code, json.message, {
        violations: json.violations,
        finalText: json.finalText,
        mismatch: json.mismatch,
        providerStop: json.providerStop
      });
    }
  };
  __name(_WorkflowError, "WorkflowError");
  var WorkflowError = _WorkflowError;

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/schema/validate.ts
  function validate(schema, value) {
    const violations = [];
    check(schema, value, "$", schema, violations);
    return violations;
  }
  __name(validate, "validate");
  function check(schema, value, path, root, out) {
    if (schema.$ref !== void 0) {
      const resolved = resolveRef(root, schema.$ref);
      if (resolved === void 0) {
        out.push({ expected: `schema ${schema.$ref}`, got: "unresolved $ref", path });
        return;
      }
      check(resolved, value, path, root, out);
      return;
    }
    if ("const" in schema) {
      if (!deepEqual(value, schema.const)) {
        out.push({ expected: describeValue(schema.const), got: describeValue(value), path });
      }
      return;
    }
    if (schema.enum !== void 0) {
      if (!schema.enum.some((candidate) => deepEqual(value, candidate))) {
        out.push({ expected: `one of ${schema.enum.map((v) => describeValue(v)).join(", ")}`, got: describeValue(value), path });
      }
      return;
    }
    if (schema.anyOf !== void 0) {
      const matched = schema.anyOf.some((branch) => {
        const trial = [];
        check(branch, value, path, root, trial);
        return trial.length === 0;
      });
      if (!matched) {
        out.push({ expected: `one of ${schema.anyOf.length} variants`, got: describeValue(value), path });
      }
      return;
    }
    if (schema.type !== void 0 && !typeMatches(schema.type, value)) {
      out.push({ expected: typeName(schema.type), got: describeValue(value), path });
      return;
    }
    if (schema.type === "object") checkObject(schema, value, path, root, out);
    if (schema.type === "array") checkArray(schema, value, path, root, out);
    if (typeof value === "string") checkString(schema, value, path, out);
    if (typeof value === "number") checkNumber(schema, value, path, out);
  }
  __name(check, "check");
  function checkObject(schema, value, path, root, out) {
    for (const key2 of schema.required ?? []) {
      if (!Object.prototype.hasOwnProperty.call(value, key2)) {
        out.push({ expected: "present", got: "missing", path: `${path}.${key2}` });
      }
    }
    const properties = schema.properties ?? {};
    for (const [key2, propValue] of Object.entries(value)) {
      const propSchema = properties[key2];
      if (propSchema !== void 0) {
        check(propSchema, propValue, `${path}.${key2}`, root, out);
        continue;
      }
      const additional = schema.additionalProperties;
      if (additional === false) {
        out.push({ expected: "no additional property", got: describeValue(propValue), path: `${path}.${key2}` });
      } else if (additional !== void 0 && additional !== true) {
        check(additional, propValue, `${path}.${key2}`, root, out);
      }
    }
  }
  __name(checkObject, "checkObject");
  function checkArray(schema, value, path, root, out) {
    if (schema.minItems !== void 0 && value.length < schema.minItems) {
      out.push({ expected: `at least ${schema.minItems} items`, got: `array(${value.length})`, path });
    }
    if (schema.maxItems !== void 0 && value.length > schema.maxItems) {
      out.push({ expected: `at most ${schema.maxItems} items`, got: `array(${value.length})`, path });
    }
    if (schema.prefixItems !== void 0) {
      schema.prefixItems.forEach((itemSchema, index) => {
        if (index < value.length) check(itemSchema, value[index], `${path}[${index}]`, root, out);
      });
      if (schema.items !== void 0) {
        for (let index = schema.prefixItems.length; index < value.length; index += 1) {
          check(schema.items, value[index], `${path}[${index}]`, root, out);
        }
      }
      return;
    }
    if (schema.items !== void 0) {
      value.forEach((item, index) => check(schema.items, item, `${path}[${index}]`, root, out));
    }
  }
  __name(checkArray, "checkArray");
  function checkString(schema, value, path, out) {
    if (schema.minLength !== void 0 && value.length < schema.minLength) {
      out.push({ expected: `string length >= ${schema.minLength}`, got: `length ${value.length}`, path });
    }
    if (schema.maxLength !== void 0 && value.length > schema.maxLength) {
      out.push({ expected: `string length <= ${schema.maxLength}`, got: `length ${value.length}`, path });
    }
    if (schema.pattern !== void 0 && !new RegExp(schema.pattern).test(value)) {
      out.push({ expected: `match /${schema.pattern}/`, got: describeValue(value), path });
    }
  }
  __name(checkString, "checkString");
  function checkNumber(schema, value, path, out) {
    if (schema.type === "integer" && !Number.isInteger(value)) {
      out.push({ expected: "integer", got: `number ${value}`, path });
    }
    if (schema.minimum !== void 0 && value < schema.minimum) {
      out.push({ expected: `>= ${schema.minimum}`, got: `number ${value}`, path });
    }
    if (schema.maximum !== void 0 && value > schema.maximum) {
      out.push({ expected: `<= ${schema.maximum}`, got: `number ${value}`, path });
    }
    if (schema.exclusiveMinimum !== void 0 && value <= schema.exclusiveMinimum) {
      out.push({ expected: `> ${schema.exclusiveMinimum}`, got: `number ${value}`, path });
    }
    if (schema.exclusiveMaximum !== void 0 && value >= schema.exclusiveMaximum) {
      out.push({ expected: `< ${schema.exclusiveMaximum}`, got: `number ${value}`, path });
    }
  }
  __name(checkNumber, "checkNumber");
  function typeMatches(type, value) {
    const types = Array.isArray(type) ? type : [type];
    return types.some((candidate) => matchesOne(candidate, value));
  }
  __name(typeMatches, "typeMatches");
  function matchesOne(type, value) {
    switch (type) {
      case "string":
        return typeof value === "string";
      case "number":
        return typeof value === "number" && Number.isFinite(value);
      case "integer":
        return typeof value === "number" && Number.isFinite(value);
      case "boolean":
        return typeof value === "boolean";
      case "null":
        return value === null;
      case "array":
        return Array.isArray(value);
      case "object":
        return typeof value === "object" && value !== null && !Array.isArray(value);
    }
  }
  __name(matchesOne, "matchesOne");
  function typeName(type) {
    return Array.isArray(type) ? type.join(" | ") : type;
  }
  __name(typeName, "typeName");
  function resolveRef(root, ref) {
    const match = /^#\/\$defs\/(.+)$/.exec(ref);
    if (match === null) return void 0;
    return root.$defs?.[match[1]];
  }
  __name(resolveRef, "resolveRef");
  function deepEqual(a, b) {
    if (a === b) return true;
    if (a === null || b === null) return false;
    if (Array.isArray(a) && Array.isArray(b)) {
      return a.length === b.length && a.every((item, index) => deepEqual(item, b[index]));
    }
    if (typeof a === "object" && typeof b === "object") {
      const aKeys = Object.keys(a);
      const bKeys = Object.keys(b);
      if (aKeys.length !== bKeys.length) return false;
      return aKeys.every(
        (key2) => Object.prototype.hasOwnProperty.call(b, key2) && deepEqual(a[key2], b[key2])
      );
    }
    return false;
  }
  __name(deepEqual, "deepEqual");
  function describeValue(value) {
    if (value === null) return "null";
    if (Array.isArray(value)) return `array(${value.length})`;
    switch (typeof value) {
      case "string": {
        const shown = value.length > 20 ? `${value.slice(0, 20)}\u2026` : value;
        return `string ${JSON.stringify(shown)}`;
      }
      case "number":
        return `number ${value}`;
      case "boolean":
        return `boolean ${value}`;
      case "object":
        return "object";
      case "undefined":
        return "undefined";
      default:
        return typeof value;
    }
  }
  __name(describeValue, "describeValue");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow-runtime/src/child-cell-streams.ts
  var CELL_STREAMS_BOOTSTRAP = String.raw`
"use strict";

// —— 流水线原语：channel / future ——
var __channels = [];
var __parkedReceivers = 0;
var __CHANNEL_COMPACT_MIN = 1024;
var __END = Object.freeze({ value: undefined, done: true });

function __channel(name) {
  var label = name === undefined || name === null ? "channel #" + (__channels.length + 1) : String(name);
  var state = { label: label, items: [], head: 0, waiters: [], waitHead: 0, closed: false };
  __channels.push(state);

  function compact() {
    if (state.head >= __CHANNEL_COMPACT_MIN && state.head * 2 >= state.items.length) {
      state.items = state.items.slice(state.head);
      state.head = 0;
    }
    if (state.waitHead >= __CHANNEL_COMPACT_MIN && state.waitHead * 2 >= state.waiters.length) {
      state.waiters = state.waiters.slice(state.waitHead);
      state.waitHead = 0;
    }
  }
  function takeWaiter() {
    if (state.waitHead >= state.waiters.length) return undefined;
    var waiter = state.waiters[state.waitHead];
    state.waiters[state.waitHead] = undefined;
    state.waitHead += 1;
    __parkedReceivers -= 1;
    compact();
    return waiter;
  }
  function next() {
    if (state.head < state.items.length) {
      var item = state.items[state.head];
      state.items[state.head] = undefined;
      state.head += 1;
      compact();
      return Promise.resolve({ value: item, done: false });
    }
    if (state.closed) return Promise.resolve(__END);
    return new Promise(function (resolve) {
      state.waiters.push(resolve);
      __parkedReceivers += 1;
    });
  }
  var channel = {
    send: function (item) {
      if (state.closed) {
        var err = new Error("Channel \"" + state.label + "\" is closed: send() after close(). The stage that produces into a channel owns its close; close it in a finally once every producer has finished.");
        err.name = "ChannelClosed";
        err.code = "ChannelClosed";
        throw err;
      }
      var waiter = takeWaiter();
      if (waiter !== undefined) waiter({ value: item, done: false });
      else state.items.push(item);
    },
    close: function () {
      if (state.closed) return;
      state.closed = true;
      for (var waiter = takeWaiter(); waiter !== undefined; waiter = takeWaiter()) waiter(__END);
    },
  };
  channel[Symbol.asyncIterator] = function () {
    var iterator = {
      next: next,
      return: function () {
        return Promise.resolve(__END);
      },
    };
    iterator[Symbol.asyncIterator] = function () {
      return iterator;
    };
    return iterator;
  };
  return channel;
}

function __future(body) {
  // 立即调用、原样返回其 promise：future 是 async IIFE 的命名形态。同步抛出转成拒绝，
  // 让「一个 stage 的失败」永远走 join 那一条路，而不是从 future() 的调用点同步逃出。
  try {
    var result = body();
    return result instanceof Promise ? result : Promise.resolve(result);
  } catch (e) {
    return Promise.reject(e);
  }
}

// —— 停滞检测（execution-engine.md「The vm cell」）——
// cell 没有定时器、没有 I/O：能唤醒挂起脚本的只有 host response。于是「没有在飞请求、微任务
// 已排空、脚本未完成」就是「永远不会再有任何事发生」。外层 realm 在每条 response 投递之后与
// __execute 起步之后各调一次（setImmediate，在微任务之后），命中即以 error-complete 结束 run，
// 而不是让 run 永远挂着。有接收者停在 channel 上时报 ChannelDeadlock 并点名通道；否则报
// ScriptStalled（一个永不兑现的 promise）。
globalThis.__checkStalled = function () {
  if (__settled || __pending.size > 0) return false;
  var waiting = [];
  for (var i = 0; i < __channels.length; i++) {
    var ch = __channels[i];
    var count = ch.waiters.length - ch.waitHead;
    if (count > 0) waiting.push(count + " on \"" + ch.label + "\"");
  }
  var err;
  if (waiting.length > 0) {
    err = new Error(
      "Deadlock: " + __parkedReceivers + " receiver(s) waiting (" + waiting.join(", ") +
        ") with no request in flight, so nothing can ever send again. Close each channel from the stage that produces into it (ch.close() in a finally) once its producers are done."
    );
    err.name = "ChannelDeadlock";
    err.code = "ChannelDeadlock";
  } else {
    err = new Error(
      "Stalled: the script is suspended with no request in flight and no channel receiver waiting, so nothing can ever resolve what it awaits (a promise that is never settled)."
    );
    err.name = "ScriptStalled";
    err.code = "ScriptStalled";
  }
  __complete(false, err);
  return true;
};

globalThis.__host.channel = __channel;
globalThis.__host.future = __future;
`;

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow-runtime/src/child-source.ts
  function childMain(deps) {
    const BOOTSTRAP = String.raw`
"use strict";

var __nextLocal = 0;
var __nextReq = 0;
var __pending = new Map();
// 本世已补全的留白：站点 id → 体的文本。同一站点再次到达（循环）时直接求值，不再过线。
var __fills = new Map();

function __emit(obj) {
  __send(JSON.stringify(obj));
}

function __createActor(siteId, name, persona) {
  // 同步造 child-local 句柄并即发即忘 create-actor；父进程据 stdio FIFO 在后续 ask 前完成映射。
  var localId = "local#" + (++__nextLocal);
  __emit({ kind: "create-actor", localId: localId, siteId: siteId, name: name, persona: persona });
  return localId;
}

function __ask(siteId, actor, instructions) {
  var id = "r" + (++__nextReq);
  return new Promise(function (resolve, reject) {
    __pending.set(id, { resolve: resolve, reject: reject });
    __emit({ kind: "request", id: id, type: "ask", siteId: siteId, actor: actor, instructions: instructions });
  });
}

function __worldRead(siteId, op, args) {
  // args 是 lowering 打包好的位置实参数组；本 shim 原样透传，不看 op、不校验元数（归 driver）。
  var id = "r" + (++__nextReq);
  return new Promise(function (resolve, reject) {
    __pending.set(id, { resolve: resolve, reject: reject });
    __emit({ kind: "request", id: id, type: "world-read", siteId: siteId, op: op, args: args });
  });
}

function __evaluateFill(evaluate, code) {
  // evaluate 是 lowering 在站点处发出的 (__src) => eval(__src)：直接 eval，闭包住作者代码在
  // 那一点的全部绑定（__host 在内），求出 (async () => { … }) 这个函数；调它，结果即留白的值。
  // 每次到达都新造一个 evaluate，循环里的留白因此看见各轮自己的绑定。求值失败（语法错）与体的
  // 拒绝都在这条 promise 上冒出，落在站点处，脚本可以 catch。
  return Promise.resolve().then(function () {
    return evaluate(code)();
  });
}

function __hole(siteId, name, prompt, evaluate, body) {
  // 已补全（体就在脚本里）：直接调体，不过线。同步抛出转成拒绝，与 future 同规。
  if (body !== undefined) {
    try {
      return Promise.resolve(body());
    } catch (e) {
      return Promise.reject(e);
    }
  }
  var remembered = __fills.get(siteId);
  if (remembered !== undefined) return __evaluateFill(evaluate, remembered);
  var id = "r" + (++__nextReq);
  return new Promise(function (resolve, reject) {
    __pending.set(id, { resolve: resolve, reject: reject });
    __emit({ kind: "request", id: id, type: "hole", siteId: siteId, name: name, prompt: prompt });
  }).then(function (answer) {
    __fills.set(siteId, answer.code);
    return __evaluateFill(evaluate, answer.code);
  });
}

function __log(message) {
  __emit({ kind: "event", type: "log", message: String(message) });
}

function __enterPhase(name) {
  // 阶段标记：无站点、不落 journal，只让引擎发一条事件——所以与 log 同走事件通道。
  __emit({ kind: "event", type: "phase-entered", name: String(name) });
}

function __publishArtifact(siteId, op, args) {
  // 内容产物是效应：脚本 await 它、要能 catch 它的拒绝，所以与 ask / world-read 同走
  // request/response。args 是 lowering 打包的位置实参（[id, path|content, opts]），原样透传。
  var id = "r" + (++__nextReq);
  return new Promise(function (resolve, reject) {
    __pending.set(id, { resolve: resolve, reject: reject });
    __emit({ kind: "request", id: id, type: "publish-artifact", siteId: siteId, artifactOp: op, args: args });
  });
}

function __declareArtifact(siteId, op, args) {
  // 预置产物是声明：同步返回 void，没有可等的东西，所以走事件通道。它与 report 共用同一条
  // FIFO——一条打了标签的 report 必须晚于它的声明到达父进程，而顺序正是由这一点保证的。
  __emit({ kind: "event", type: "declare-artifact", siteId: siteId, op: op, args: args });
}

function __report(siteId, item, artifactId) {
  // 即发即忘：脚本从不 await 它，所以走 event 通道而不是 request/response。父进程按 siteId ×
  // ordinal 落一行 journal，故本条消息的**到达顺序**是承重的（stdio FIFO 保证它）。
  // item 里若有环，这里的 JSON.stringify 会抛错——就在 report() 的调用点上，脚本要么 catch
  // 要么让 run 失败。两者都比静默发出一条残缺的 item 好；父进程另有同样的护栏。
  // artifactId 缺席时 JSON.stringify 直接把这个键丢掉，线上因此就是"没有标签"。
  __emit({ kind: "event", type: "report", siteId: siteId, item: item, artifactId: artifactId });
}

// —— 运行实参（Boundary A 的 args）——
// 与预算一样在 context **内**用 JSON.parse 构造，所以脚本拿到的是 context-native 对象，
// 外层 realm 的 intrinsics 一点都不掺进来（本文件顶部的跨 realm 收敛约束）。
// ⚠ Object.freeze 是**浅**冻结：嵌套对象未冻结，脚本仍能改动 args.foo.bar。v1 接受这个
// 界限——它挡的是"手滑重新赋值 args"，不是一个安全边界（实参本来就是调用方给的）。
var __args = Object.freeze(JSON.parse(__argsJson));

globalThis.__host = {
  args: __args,
  createActor: __createActor,
  ask: __ask,
  worldRead: __worldRead,
  publishArtifact: __publishArtifact,
  declareArtifact: __declareArtifact,
  report: __report,
  log: __log,
  enterPhase: __enterPhase,
  hole: __hole,
};

// —— 入站 response 消费（由外层 realm 以行字符串调用）——
globalThis.__deliver = function (line) {
  var msg = JSON.parse(line);
  if (msg.kind !== "response") return;
  var waiter = __pending.get(msg.id);
  if (!waiter) return;
  __pending.delete(msg.id);
  if (msg.ok) {
    waiter.resolve(msg.value);
    return;
  }
  // 拒绝以 context-native Error 过界，带上 code/violations/finalText 供脚本 try/catch 结构化处理。
  var e = msg.error || {};
  var err = new Error(e.message || "workflow host error");
  if (e.name) err.name = e.name;
  if (e.code !== undefined) err.code = e.code;
  if (e.violations !== undefined) err.violations = e.violations;
  if (e.finalText !== undefined) err.finalText = e.finalText;
  waiter.reject(err);
};

// run 是否已发出 complete。停滞检测（deps.streamsBootstrap 里的 __checkStalled）也读写它。
var __settled = false;

function __complete(ok, payload) {
  // 幂等：停滞检测已经以 error-complete 结束 run 之后，脚本（理论上）再完成也不再发第二条。
  if (__settled) return;
  __settled = true;
  if (ok) {
    __emit({ kind: "complete", ok: true, value: payload });
    return;
  }
  var wire;
  if (payload instanceof Error) {
    wire = {
      name: payload.name,
      message: payload.message,
      stack: payload.stack,
      code: payload.code,
      violations: payload.violations,
      finalText: payload.finalText,
    };
  } else {
    wire = { name: "Error", message: String(payload) };
  }
  __emit({ kind: "complete", ok: false, error: wire });
}

// 外层 realm 调用它跑脚本；始终 resolve（错误已转成 complete 消息），便于外层收尾（关 stdin）。
globalThis.__execute = async function (runFn) {
  try {
    var value = await runFn(globalThis.__host);
    __complete(true, value);
  } catch (e) {
    __complete(false, e);
  }
};

// —— 运行期禁令（belt；编译诊断是 suspenders）：Date.now / argless new Date() / Math.random ——
var __NativeDate = Date;
class __WorkflowDate extends __NativeDate {
  constructor() {
    if (arguments.length === 0) throw new Error("argless new Date() is disabled in workflows");
    super(...arguments);
  }
  static now() {
    throw new Error("Date.now() is disabled in workflows");
  }
  static parse(value) {
    return __NativeDate.parse(value);
  }
  static UTC() {
    return __NativeDate.UTC.apply(__NativeDate, arguments);
  }
}
globalThis.Date = __WorkflowDate;
Math.random = function () {
  throw new Error("Math.random() is disabled in workflows");
};
`;
    const payload = deps.payload;
    const sandbox = {
      __argsJson: JSON.stringify(payload.args ?? {})
    };
    sandbox.__send = (line) => {
      deps.stdout.write(`${line}
`);
    };
    deps.vm.createContext(sandbox, { name: "workflow-sandbox" });
    deps.vm.runInContext(BOOTSTRAP, sandbox, { filename: "workflow-bootstrap.js" });
    deps.vm.runInContext(deps.streamsBootstrap, sandbox, { filename: "workflow-streams.js" });
    let runFn;
    try {
      runFn = deps.vm.runInContext(`(async (__host) => {
${payload.lowered}
})`, sandbox, {
        filename: "workflow-script.js"
      });
    } catch (error) {
      const err = error;
      deps.stdout.write(
        `${JSON.stringify({
          kind: "complete",
          ok: false,
          error: {
            name: err?.name ?? "SyntaxError",
            message: err?.message ?? String(error),
            stack: err?.stack
          }
        })}
`
      );
      return Promise.resolve();
    }
    const reader = deps.createInterface({ input: deps.stdin });
    reader.on("line", (line) => {
      if (!line.trim()) return;
      sandbox.__deliver(line);
      setImmediate(() => {
        if (sandbox.__checkStalled()) reader.close();
      });
    });
    const execution = sandbox.__execute(runFn).then(
      () => reader.close(),
      () => reader.close()
    );
    setImmediate(() => {
      if (sandbox.__checkStalled()) reader.close();
    });
    return execution;
  }
  __name(childMain, "childMain");

  // src/cell.ts
  var LINKED_KEYS = ["__deliver", "__execute", "__checkStalled", "__send", "__argsJson"];
  function createVm(realm) {
    const target = realm.global;
    return {
      createContext(sandbox, _options) {
        for (const key2 of Object.keys(sandbox)) {
          target[key2] = sandbox[key2];
        }
        for (const key2 of LINKED_KEYS) {
          Object.defineProperty(sandbox, key2, {
            configurable: true,
            get: /* @__PURE__ */ __name(() => target[key2], "get"),
            set: /* @__PURE__ */ __name((value) => {
              target[key2] = value;
            }, "set")
          });
        }
        return target;
      },
      runInContext(code, _sandbox, options) {
        return realm.evaluate(code, options?.filename ?? "cell.js");
      }
    };
  }
  __name(createVm, "createVm");
  function startCell(options) {
    const { realm, port, payload, streamsBootstrap } = options;
    let closed = false;
    return childMain({
      createInterface: /* @__PURE__ */ __name(() => ({
        close: /* @__PURE__ */ __name(() => {
          closed = true;
        }, "close"),
        on: /* @__PURE__ */ __name((event, listener) => {
          if (event !== "line") return void 0;
          port.onLine((line) => {
            if (closed || line.trim() === "") return;
            listener(line);
          });
          return void 0;
        }, "on")
      }), "createInterface"),
      // childMain 只把 stdin 交给 createInterface；这里不持有真实流。
      stdin: {},
      stdout: {
        write: /* @__PURE__ */ __name((chunk) => {
          port.post(chunk.endsWith("\n") ? chunk.slice(0, -1) : chunk);
          return true;
        }, "write")
      },
      payload,
      streamsBootstrap,
      vm: createVm(realm)
    });
  }
  __name(startCell, "startCell");

  // src/realm-sanitize.ts
  var CELL_REALM_SANITIZER = String.raw`(function () {
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
  function applyRealmSanitizer(realm) {
    realm.evaluate(CELL_REALM_SANITIZER, "cell-realm-sanitize.js");
  }
  __name(applyRealmSanitizer, "applyRealmSanitizer");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/hash.ts
  function canonicalJson(value) {
    if (value === null || typeof value !== "object") {
      const s = JSON.stringify(value);
      return s === void 0 ? "null" : s;
    }
    if (Array.isArray(value)) {
      return `[${value.map((v) => canonicalJson(v)).join(",")}]`;
    }
    const obj = value;
    const keys = Object.keys(obj).sort();
    const parts = [];
    for (const key2 of keys) {
      const v = obj[key2];
      if (v === void 0) continue;
      parts.push(`${JSON.stringify(key2)}:${canonicalJson(v)}`);
    }
    return `{${parts.join(",")}}`;
  }
  __name(canonicalJson, "canonicalJson");
  function fnv1a(input) {
    let hash = 2166136261;
    for (let i = 0; i < input.length; i++) {
      hash ^= input.charCodeAt(i) & 255;
      hash ^= input.charCodeAt(i) >> 8 & 255;
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(16).padStart(8, "0");
  }
  __name(fnv1a, "fnv1a");
  function inputHash(value) {
    return fnv1a(canonicalJson(value));
  }
  __name(inputHash, "inputHash");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/persona.ts
  function normalizePersona(name, persona) {
    const base = {};
    if (typeof persona === "string") {
      base.system = persona;
    } else if (persona !== null && typeof persona === "object") {
      if (typeof persona.name === "string") base.name = persona.name;
      if (typeof persona.system === "string") base.system = persona.system;
      if (typeof persona.model === "string") base.model = persona.model;
    }
    if (base.name === void 0 && name !== void 0) base.name = name;
    return base;
  }
  __name(normalizePersona, "normalizePersona");
  function personaIdentity(persona) {
    const { model: _model, ...identity } = persona;
    void _model;
    return identity;
  }
  __name(personaIdentity, "personaIdentity");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/imported-cache.ts
  var _ImportedActorState = class _ImportedActorState {
    constructor(candidate) {
      __publicField(this, "candidate", candidate);
      /** 已消费的导入条目数 = 下一个可命中的 actorSeq（也是转录截断边界的下标 + 1）。 */
      __publicField(this, "consumed", 0);
      __publicField(this, "diverged", false);
      /**
       * 「续跑前驱在飞 ask」的那个 seq（没有续跑即缺席）。至多一个：续跑本身就是分歧，
       * 分歧单调，所以第二次不会再有。
       */
      __publicField(this, "carriedSeq");
    }
    /**
     * fresh ask 在 seq 上问缓存：命中返回条目（并推进游标），否则置分歧并返回 undefined。
     *
     * 两种不命中同等处理：哈希不符（指令变了）与 `seq >= entries.length`（新脚本在此 actor 上
     * 扩了新 ask）。分歧的**级联**是免费且动态的——上游 ask 转 live 拿到新结果，下游用它插值出的
     * 指令哈希必变，于是下游自动分歧，不需要任何显式传播。
     *
     * 本方法只在导入缓存**开着**时被调用（关门后走 {@link takeIfPure}），所以未命中时可以谈续跑。
     */
    take(seq, hash) {
      if (this.diverged) return void 0;
      const entry = this.candidate.entries[seq];
      if (entry === void 0 || entry.inputHash !== hash) {
        this.diverge(seq, hash, true);
        return void 0;
      }
      this.consumed = seq + 1;
      return entry;
    }
    /**
     * 缓存关闭后的问法：只有**纯**条目（前驱记下 `worldToolCalls === 0`）还能命中。纯 ask 只依赖指令与
     * 转录前缀——两者都在哈希链里——与工作区无关，所以关门不影响它的答案。碰过外部世界的条目即便同哈希
     * 也不给：它读过的工作区可能已被改写；这个 ask 转 live，actor 从此分歧（转录不再与前驱一致，
     * 后缀条目不可复活）。没有 stats、或 stats 里没有这个键的老条目按「碰过」处理（保守）。
     *
     * 关门后同样不续跑在飞 ask：那半场转录里全是前驱对**旧工作区**的观察，与带工具的条目同理。
     */
    takeIfPure(seq, hash) {
      if (this.diverged) return void 0;
      const entry = this.candidate.entries[seq];
      if (entry === void 0 || entry.inputHash !== hash || entry.stats?.worldToolCalls !== 0) {
        this.diverge(seq, hash, false);
        return void 0;
      }
      this.consumed = seq + 1;
      return entry;
    }
    /**
     * 该 seq 的 ask 的答案是否**读自前驱的转录**：是则返回持有它的会话，否则 undefined。
     *
     * 成立当且仅当此刻仍未分歧、且游标已越过该 seq——分歧单调、准入按 seq 升序，所以这恰好是
     * 「这一条被导入消费了」。两条路径各在判定之后立刻问：fresh ask 在 {@link take} 命中之后，
     * 修订 run 的 resume 在 {@link reconcileRecorded} 之后（结算次序闸可能把事件推迟到同一个
     * actor 更后的行已经置了分歧的时候，那时再问就会把一条导入行说成本 run 自己跑的）。
     */
    sourceSessionAt(seq) {
      return !this.diverged && seq < this.consumed ? this.candidate.transcriptSourceSessionId : void 0;
    }
    /** 该 seq 的 ask 是否续跑了前驱的在飞 ask（调度器据此决定 stats 记不记 worldToolCalls）。 */
    carriedAt(seq) {
      return this.carriedSeq === seq;
    }
    /**
     * 据一行**已记录**的 ask 重推分歧状态（修订 run 崩溃后 resume 时用）。
     *
     * 分歧状态不落库，而修订 run 的 resume 会把导入缓存整表重建。若不据 journal 行重推分歧点，
     * 一个在原次执行中已于 seq k 分歧的 actor，其 seq k+n 的 fresh ask 可能恰好撞上导入条目的
     * 哈希而被**错误导入**——那等于把一段与本 run 实际转录无关的历史塞回来。逐 seq 拿记录行的
     * inputHash 与导入条目比对，恰好重建了原次执行当时的判定（准入按 seq 升序，hold 规则保证
     * 这一点），因此这个重推是精确的，不是保守近似。`wasLive` 补上哈希看不见的那一种 live
     * （缓存关闭后带工具的 ask，见 scheduler 的 tryImportedSettle）。
     *
     * `queuedBeforeClose` 是续跑判定的那一半事实：续跑与该 ask 的 `node-queued` 在准入的同一个
     * 同步片里发生，所以「当时门开着」等价于「这条 node-queued 早于第一条 import-cache-closed」
     * （见 engine-world.ts 的 recoverImportClosure）。必须精确——错判成续跑会让 seedActorTranscript
     * 往一个**已经分歧**的会话里多抄一段前驱消息（driver 的幂等判据只看目标够不够长）。
     */
    reconcileRecorded(seq, recordedHash, wasLive, queuedBeforeClose) {
      if (this.diverged) return;
      if (wasLive) {
        this.diverge(seq, recordedHash, queuedBeforeClose);
        return;
      }
      const entry = this.candidate.entries[seq];
      if (entry === void 0 || entry.inputHash !== recordedHash) {
        this.diverged = true;
        return;
      }
      this.consumed = Math.max(this.consumed, seq + 1);
    }
    /**
     * 分歧 actor 的会话种子：源会话 + 复制多少条消息 + 承袭的模型 pin。
     *
     * 边界取**最后一条被消费**的导入条目的记账值：在 seq k 分歧意味着 0..k-1 的问答都已按缓存
     * 结算，新会话要接着的正是那 k 次完整交换之后的位置（含它们的 repair / nudge 轮）。
     * 一条也没消费就没有种子——全新会话、全新模型解析、不带 pin：pin 是为「转录接续下不静默
     * 换模型」存在的，没有接续就没有它的用武之地。
     *
     * **续跑是例外**：边界改取 `inFlight.messageBoundary`（前驱整个会话），因此一条都没消费也有
     * 种子——那正是「扇出第一轮在飞时被修订」的形状。
     */
    seed() {
      const inFlight = this.candidate.inFlight;
      if (this.carriedSeq !== void 0 && inFlight !== void 0) {
        return this.seedAt(inFlight.messageBoundary);
      }
      if (this.consumed === 0) return void 0;
      const last = this.candidate.entries[this.consumed - 1];
      if (last === void 0) return void 0;
      return this.seedAt(last.messageBoundary);
    }
    /**
     * 置分歧，并在此顺带判定这次未命中是不是**续跑前驱的在飞 ask**。
     *
     * 续跑的四个条件缺一不可：此前未分歧（否则本 actor 的转录早已不是前驱那条）、`seq` 恰在
     * 前缀之后（在飞 ask 的位置）、整个前缀都已消费、指令哈希与在飞 ask 相符。`cacheOpen`
     * 是第五个：关门之后那半场转录只是对旧工作区的观察，不比缓存的 world 读取更可信。
     */
    diverge(seq, hash, cacheOpen) {
      const inFlight = this.candidate.inFlight;
      if (cacheOpen && inFlight !== void 0 && seq === this.candidate.entries.length && this.consumed === seq && inFlight.inputHash === hash) {
        this.carriedSeq = seq;
      }
      this.diverged = true;
    }
    seedAt(messageCount) {
      const seed = {
        sourceSessionId: this.candidate.transcriptSourceSessionId,
        messageCount
      };
      if (this.candidate.resolvedModel !== void 0) seed.resolvedModel = this.candidate.resolvedModel;
      return seed;
    }
  };
  __name(_ImportedActorState, "ImportedActorState");
  var ImportedActorState = _ImportedActorState;
  var _ImportedWorldQueue = class _ImportedWorldQueue {
    constructor(world) {
      __publicField(this, "world", world);
      __publicField(this, "cursors", /* @__PURE__ */ new Map());
    }
    /** 取该内容哈希的下一条记录，耗尽或从未记录即 undefined（调用方转 live）。 */
    take(hash) {
      const queue = this.world.get(hash);
      if (queue === void 0) return void 0;
      const cursor = this.cursors.get(hash) ?? 0;
      const entry = queue[cursor];
      if (entry === void 0) return void 0;
      this.cursors.set(hash, cursor + 1);
      return entry;
    }
  };
  __name(_ImportedWorldQueue, "ImportedWorldQueue");
  var ImportedWorldQueue = _ImportedWorldQueue;
  function matchImportedActor(cache, spec) {
    if (cache === void 0) return void 0;
    const name = spec.name;
    if (name === void 0 || name === "") return void 0;
    const candidate = cache.actors.get(name);
    if (candidate === void 0) return void 0;
    if (canonicalJson(personaIdentity(spec)) !== canonicalJson(personaIdentity(candidate.persona))) {
      return void 0;
    }
    return new ImportedActorState(candidate);
  }
  __name(matchImportedActor, "matchImportedActor");
  function importedAskRecord(runId, instance, actor, seq, hash, entry) {
    const record = {
      runId,
      siteId: instance.siteId,
      ordinal: instance.ordinal,
      kind: "ask",
      actorSiteId: actor.siteId,
      actorOrdinal: actor.ordinal,
      actorSeq: seq,
      inputHash: hash,
      status: "completed",
      result: entry.result,
      messageBoundary: entry.messageBoundary
    };
    if (entry.stats !== void 0) record.stats = entry.stats;
    return record;
  }
  __name(importedAskRecord, "importedAskRecord");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/ask-observation-types.ts
  var INSTRUCTIONS_HEAD_MAX_CHARS = 240;

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/types.ts
  function refToString(ref) {
    return `${ref.siteId}@${ref.ordinal}`;
  }
  __name(refToString, "refToString");
  var WORLD_READ_INPUT_MAX_BYTES = 4096;
  var REPAIR_ATTEMPTS = 3;
  var NUDGE_ATTEMPTS = 1;

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/cached-ask-facts.ts
  function cachedAskFacts(actor, seq, instructions, imported) {
    const instructionsHead = headOfInstructions(instructions);
    const sourceSessionId = imported?.sourceSessionAt(seq);
    return {
      kind: "ask",
      actor,
      actorSeq: seq,
      ...instructionsHead === void 0 ? {} : { instructionsHead },
      ...sourceSessionId === void 0 ? {} : { sourceSessionId }
    };
  }
  __name(cachedAskFacts, "cachedAskFacts");
  function headOfInstructions(instructions) {
    const trimmed = instructions.trim();
    if (trimmed.length === 0) return void 0;
    return trimmed.length <= INSTRUCTIONS_HEAD_MAX_CHARS ? trimmed : trimmed.slice(0, INSTRUCTIONS_HEAD_MAX_CHARS);
  }
  __name(headOfInstructions, "headOfInstructions");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/hash-mismatch.ts
  function hashMismatch(instance, expected, got) {
    return new WorkflowError(
      "InputHashMismatch",
      `Replay hit at ${refToString(instance)} but inputHash differs (expected ${expected}, got ${got}): the script is not deterministic, so the journal cannot be replayed.`,
      // 结构化 mismatch 与 ScriptHashMismatch 对齐：两个哈希不一致错误共用同一个字段，
      // 读端不必再从 message 文本里抠哈希。
      { mismatch: { expected, got } }
    );
  }
  __name(hashMismatch, "hashMismatch");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/scheduler-types.ts
  function defer() {
    let resolve;
    let reject;
    const promise = new Promise((res, rej) => {
      resolve = res;
      reject = rej;
    });
    return { promise, resolve, reject };
  }
  __name(defer, "defer");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/scheduler-submit.ts
  function handleSubmitAttempted(seam, instance, payload) {
    const node = seam.liveNode(instance);
    if (node === void 0 || node.settled) return;
    if (!node.spec.typed) return;
    const { value, violations } = normalizeSubmit(seam.host, node.spec.schema, payload);
    if (violations.length === 0) {
      seam.host.driver.respondToSubmit(instance, { kind: "accept" });
      seam.settleOk(node, value);
      return;
    }
    if (node.repairsRemaining > 0) {
      node.repairsRemaining--;
      const attempt = REPAIR_ATTEMPTS - node.repairsRemaining;
      seam.host.driver.respondToSubmit(instance, { kind: "reject", violations });
      seam.host.record({ type: "node-repairing", instance, attempt, violations });
      return;
    }
    seam.host.driver.cancelAsk(instance);
    seam.settleFailed(
      node,
      new WorkflowError(
        "ValidationFailed",
        "submit_result failed schema validation repeatedly and the repair budget is exhausted.",
        { violations }
      )
    );
  }
  __name(handleSubmitAttempted, "handleSubmitAttempted");
  function normalizeSubmit(host, schema, payload) {
    const direct = host.validate(schema, payload);
    if (direct.length === 0) return { value: payload, violations: [] };
    if (typeof payload === "string") {
      try {
        const parsed = JSON.parse(payload);
        const after = host.validate(schema, parsed);
        if (after.length === 0) return { value: parsed, violations: [] };
        return { value: parsed, violations: after };
      } catch {
      }
    }
    return { value: payload, violations: direct };
  }
  __name(normalizeSubmit, "normalizeSubmit");
  function handleTurnEnded(seam, instance, finalText) {
    const node = seam.liveNode(instance);
    if (node === void 0 || node.settled) return;
    if (!node.spec.typed) {
      seam.settleOk(node, finalText);
      return;
    }
    if (node.nudgesRemaining > 0) {
      node.nudgesRemaining--;
      seam.host.driver.respondToSubmit(instance, { kind: "nudge" });
      seam.host.record({ type: "node-nudged", instance });
      return;
    }
    seam.host.driver.cancelAsk(instance);
    seam.settleFailed(
      node,
      new WorkflowError(
        "ResultNotSubmitted",
        "The typed ask ended without a submit_result call, so there is no result.",
        { finalText }
      )
    );
  }
  __name(handleTurnEnded, "handleTurnEnded");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/scheduler.ts
  var _AskScheduler = class _AskScheduler {
    constructor(host) {
      __publicField(this, "host", host);
      __publicField(this, "actors", /* @__PURE__ */ new Map());
      __publicField(this, "actorOrder", []);
      __publicField(this, "liveNodes", /* @__PURE__ */ new Map());
      /**
       * 「转录早于本次派发」的 ask 实例（`siteId@ordinal`）：续跑前驱在飞 ask 的，以及 resume 时
       * 据 running 行重新派发的。它们落 journal 的 stats 要抹掉 `worldToolCalls`，见 {@link journaledStats}。
       * 结算后不清理——迟到的 stats 回填还要查它。
       */
      __publicField(this, "priorTranscriptAsks", /* @__PURE__ */ new Set());
      /**
       * 每个 actor 在 journal 里已记录的 ask 行数（`siteId@ordinal` → 行数），由引擎的 resume 分支用它
       * 已经读好的 ask 行一次建好（{@link seedRecordedAsks}）。修复原因：registerActor 原先每建一个
       * actor 就把整张节点表读一遍再过滤——宽扇出下是 O(actor × 行) 次解码，而且解的是每条 ask 的回答
       * 与每条 report item。一次建表是对的：actor 总在它的第一次 ask 之前注册，所以注册那一刻 journal
       * 里属于它的 ask 行只可能来自前几世；全新 run 的表为空。
       */
      __publicField(this, "recordedAsks", /* @__PURE__ */ new Map());
      __publicField(this, "activeAsks", 0);
      /** scheduler-submit.ts 的自由函数经它查 live 节点、按结果结算（见 {@link SubmitSeam}）。 */
      __publicField(this, "submitSeam");
      this.submitSeam = {
        host,
        liveNode: /* @__PURE__ */ __name((instance) => this.liveNodes.get(refToString(instance)), "liveNode"),
        settleOk: /* @__PURE__ */ __name((node, artifact) => this.settleOk(node, artifact), "settleOk"),
        settleFailed: /* @__PURE__ */ __name((node, error) => this.settleFailed(node, error), "settleFailed")
      };
    }
    get journal() {
      return this.host.driver.journal;
    }
    /** resume 时喂入本 run 已落库的 ask 行（可不带结果），建好 {@link recordedAsks}。 */
    seedRecordedAsks(askRows) {
      for (const row of askRows) {
        if (row.kind !== "ask" || row.actorSiteId === void 0 || row.actorOrdinal === void 0) {
          continue;
        }
        const actorKey = refToString({ siteId: row.actorSiteId, ordinal: row.actorOrdinal });
        this.recordedAsks.set(actorKey, (this.recordedAsks.get(actorKey) ?? 0) + 1);
      }
    }
    hasActor(id) {
      return this.actors.has(id);
    }
    /**
     * 注册一个 actor（创建站点 × 序号）。`imported` 是 amend-resume 的导入候选：引擎已在
     * createActor 里按有效名 + 规范化 persona 匹配过，调度器只负责消费它。
     */
    registerActor(ref, id, name, persona, imported) {
      const recordedCount = this.recordedAsks.get(refToString(ref)) ?? 0;
      const actor = {
        ref,
        id,
        persona,
        name,
        recordedCount,
        nextAdmitSeq: 0,
        pendingRecorded: /* @__PURE__ */ new Map(),
        pendingLive: [],
        liveQueue: [],
        ...imported === void 0 ? {} : { imported }
      };
      this.actors.set(id, actor);
      this.actorOrder.push(actor);
      return actor;
    }
    /** 受理一次 ask：完结命中短路（走 hold 规则），running 命中或未命中则 live 派发。 */
    admitAsk(siteId, actorId, instructions, spec) {
      const actor = this.actors.get(actorId);
      const ordinal = this.host.nextOrdinal(siteId);
      const instance = { siteId, ordinal };
      const hash = inputHash(instructions);
      const deferred = defer();
      const recorded = this.journal.getNode(this.host.runId, siteId, ordinal);
      if (recorded !== void 0) {
        if (recorded.inputHash !== hash) {
          const err = hashMismatch(instance, recorded.inputHash, hash);
          this.host.failRun(err);
          return Promise.reject(err);
        }
        const seq = recorded.actorSeq ?? 0;
        const reconcile = /* @__PURE__ */ __name(() => actor.imported?.reconcileRecorded(
          seq,
          recorded.inputHash,
          this.host.wasLiveBeforeResume(instance),
          this.host.wasQueuedBeforeImportClose(instance)
        ), "reconcile");
        if (recorded.status === "running") {
          actor.pendingRecorded.set(seq, () => {
            reconcile();
            this.admitLive(instance, actor, seq, instructions, hash, spec, deferred, true);
          });
        } else {
          actor.pendingRecorded.set(seq, () => {
            reconcile();
            this.releaseCachedAsk(instance, recorded, deferred, cachedAskFacts(actor.ref, seq, instructions, actor.imported));
          });
        }
        this.drainAdmission(actor);
        return deferred.promise;
      }
      actor.pendingLive.push(() => {
        const seq = actor.nextAdmitSeq++;
        if (this.tryImportedSettle(instance, actor, seq, hash, instructions, deferred)) return;
        const carried = actor.imported?.carriedAt(seq) === true;
        this.admitLive(instance, actor, seq, instructions, hash, spec, deferred, carried);
      });
      this.drainAdmission(actor);
      this.pumpAll();
      return deferred.promise;
    }
    /**
     * 把一个 ask 作为 live 节点准入：建节点、准入即落 running 记录、入队并记事件。
     *
     * `priorTranscript` 为真表示这一条不是从空转录开跑的（续跑前驱在飞 ask，或 resume 据 running
     * 行重新派发）——它只影响 stats 怎么落库，见 {@link journaledStats}。
     */
    admitLive(instance, actor, seq, instructions, hash, spec, deferred, priorTranscript = false) {
      if (priorTranscript) this.priorTranscriptAsks.add(refToString(instance));
      const instructionsHead = headOfInstructions(instructions);
      const node = {
        instance,
        actor,
        actorSeq: seq,
        instructions,
        hash,
        spec,
        deferred,
        repairsRemaining: REPAIR_ATTEMPTS,
        nudgesRemaining: NUDGE_ATTEMPTS,
        settled: false,
        dispatched: false,
        ...instructionsHead === void 0 ? {} : { instructionsHead }
      };
      this.liveNodes.set(refToString(instance), node);
      this.journal.putNode({
        runId: this.host.runId,
        siteId: instance.siteId,
        ordinal: instance.ordinal,
        kind: "ask",
        actorSiteId: actor.ref.siteId,
        actorOrdinal: actor.ref.ordinal,
        actorSeq: seq,
        inputHash: hash,
        status: "running"
      });
      actor.liveQueue.push(node);
      this.host.record({
        type: "node-queued",
        instance,
        kind: "ask",
        actor: actor.ref,
        actorSeq: seq,
        ...instructionsHead === void 0 ? {} : { instructionsHead }
      });
    }
    /**
     * fresh ask 准入时问一次导入缓存（amend-resume）。命中即 **cached settle** 并返回 true
     * （调用方不再 live）；判定与分歧记账全在 {@link ImportedActorState}。
     *
     * 命中写一行**真** dwf_node，只发 `node-settled cached:true`——与 replay 命中的
     * {@link releaseCachedAsk} 同一副姿态：不发 node-queued / node-dispatched，
     * 不入 liveQueue，不建会话，不占 `actor.current`。那条结算因此就是出生事件，重发出生事实，
     * 并点名前驱里持有这段交换的会话（{@link cachedAskFacts}）。
     */
    tryImportedSettle(instance, actor, seq, hash, instructions, deferred) {
      const entry = this.host.importCacheClosed() ? actor.imported?.takeIfPure(seq, hash) : actor.imported?.take(seq, hash);
      if (entry === void 0) return false;
      this.journal.putNode(importedAskRecord(this.host.runId, instance, actor.ref, seq, hash, entry));
      const facts = cachedAskFacts(actor.ref, seq, instructions, actor.imported);
      this.host.record({ type: "node-settled", instance, outcome: "ok", cached: true, ...facts });
      deferred.resolve(entry.result);
      return true;
    }
    // ——————————————————————————————— 向上回报 ———————————————————————————————
    /** submit_result 到达：方法体在 scheduler-submit.ts 的 handleSubmitAttempted。 */
    submitAttempted(instance, payload) {
      handleSubmitAttempted(this.submitSeam, instance, payload);
    }
    /** turn 结束（无 submit）：方法体在 scheduler-submit.ts 的 handleTurnEnded。 */
    turnEnded(instance, finalText) {
      handleTurnEnded(this.submitSeam, instance, finalText);
    }
    noteStats(instance, stats) {
      const node = this.liveNodes.get(refToString(instance));
      if (node !== void 0) {
        node.lastStats = stats;
        return;
      }
      const recorded = this.journal.getNode(this.host.runId, instance.siteId, instance.ordinal);
      if (recorded === void 0) return;
      this.journal.putNode({ ...recorded, stats: this.journaledStats(instance, stats) });
    }
    /**
     * 落 journal 的 stats：**转录早于本次派发**的 ask 抹掉 `worldToolCalls`。
     *
     * 根因：driver 的工具计数器是内存态、每次 ask 起跑时清零，所以接着一段既有转录跑的 ask 只数
     * 得到自己这几轮，转录里原有的工具调用一个也数不到。少报的后果不是账目不准（那是 tokens 的
     * 事），而是**纯度判据被污染**：一个报 0 的 ask 会被后来的修订当成纯条目，在关门之后仍从缓存
     * 结算——而它其实碰过工作区。缺席这个键本就表示「碰过」（保守读法），所以抹掉才是诚实记录。
     * tokens / toolCalls / turns 照记：它们是用量，不是纯度声明。
     */
    journaledStats(instance, stats) {
      if (!this.priorTranscriptAsks.has(refToString(instance))) return stats;
      const { worldToolCalls: _unreliable, ...rest } = stats;
      return rest;
    }
    failed(instance, error) {
      const node = this.liveNodes.get(refToString(instance));
      if (node === void 0 || node.settled) return;
      this.settleFailed(node, error);
    }
    /** 该实例是否仍是在飞（已准入、未结算）的 live ask——限流观察事件只对这样的节点有意义。 */
    isLive(instance) {
      const node = this.liveNodes.get(refToString(instance));
      return node !== void 0 && !node.settled;
    }
    /** 在飞 ask 所属子代理的有效名（关门事件里点名用）；不在飞即 undefined。 */
    liveActorName(instance) {
      const node = this.liveNodes.get(refToString(instance));
      return node === void 0 || node.settled ? void 0 : node.actor.persona.name;
    }
    /** 中止所有在飞 ask：run 取消/失败时用。emitCancelled 为真时补发 node-settled(cancelled)。 */
    abortInFlight(error, emitCancelled) {
      for (const node of this.liveNodes.values()) {
        if (node.settled) continue;
        node.settled = true;
        if (node.dispatched) this.host.driver.cancelAsk(node.instance);
        if (emitCancelled) {
          this.host.record({ type: "node-settled", instance: node.instance, outcome: "cancelled" });
        }
        node.deferred.reject(error);
      }
      this.liveNodes.clear();
      this.activeAsks = 0;
    }
    // ——————————————————————————————— 内部：准入 / 派发 ———————————————————————————————
    drainAdmission(actor) {
      let progressed = true;
      while (progressed) {
        progressed = false;
        const release = actor.pendingRecorded.get(actor.nextAdmitSeq);
        if (release !== void 0) {
          actor.pendingRecorded.delete(actor.nextAdmitSeq);
          actor.nextAdmitSeq++;
          release();
          progressed = true;
          continue;
        }
        if (actor.nextAdmitSeq >= actor.recordedCount && actor.pendingLive.length > 0) {
          const admit = actor.pendingLive.shift();
          admit();
          progressed = true;
        }
      }
      this.pumpActor(actor);
    }
    /**
     * 命中记录的 ask：按 journal 记下的**结算次序**释放（docs/execution-engine.md
     * 「Replaying the settle order」）。hold 规则定的是同一个 actor 内的准入次序，跨 actor 的
     * 完成次序要这一道闸才回得来——扇出分支在 await 之后的每一次 journal 调用都按它编号。
     */
    releaseCachedAsk(instance, recorded, deferred, facts) {
      this.host.holdForReplay(instance, () => this.settleCachedAsk(instance, recorded, deferred, facts));
    }
    settleCachedAsk(instance, recorded, deferred, facts) {
      if (recorded.status === "completed") {
        this.host.record({ type: "node-settled", instance, outcome: "ok", cached: true, ...facts });
        deferred.resolve(recorded.result);
      } else {
        this.host.record({ type: "node-settled", instance, outcome: "failed", cached: true, error: recorded.error, ...facts });
        deferred.reject(WorkflowError.fromJSON(recorded.error));
      }
    }
    /**
     * 重扫每个 actor 的待派发队列。公开面**只为一个调用方**：引擎在抬高本 run 的并发上界之后
     * 调它（docs/dynamic-workflow/concurrency.md「Two bounds on a run」）。上界本身是
     * {@link pumpActor} 每次派发前现读的，但没有任何别的事件会触发一次重扫——排队的 ask
     * 否则要一直等到下一次结算才动，而「抬高之后立刻多跑几个」正是这条命令买的东西。
     */
    pumpAll() {
      for (const actor of this.actorOrder) this.pumpActor(actor);
    }
    pumpActor(actor) {
      if (this.host.isRunSettled()) return;
      if (actor.current !== void 0) return;
      if (actor.liveQueue.length === 0) return;
      if (this.activeAsks >= this.host.caps.maxConcurrency) return;
      const node = actor.liveQueue.shift();
      actor.current = node;
      this.activeAsks++;
      void this.dispatch(node);
    }
    async dispatch(node) {
      let session;
      try {
        session = await this.ensureSession(node.actor);
      } catch (cause) {
        if (this.host.isRunSettled() || node.settled) return;
        this.settleFailed(
          node,
          new WorkflowError(
            "DriverError",
            `Failed to create the subagent session: ${describeCause(cause)}`,
            { cause }
          )
        );
        return;
      }
      if (this.host.isRunSettled() || node.settled) return;
      this.host.record({
        type: "node-dispatched",
        instance: node.instance,
        kind: "ask",
        actor: node.actor.ref,
        ...node.actor.name === void 0 ? {} : { actorName: node.actor.name },
        ...node.actor.persona.model === void 0 ? {} : { actorPersonaModel: node.actor.persona.model },
        ...node.instructionsHead === void 0 ? {} : { instructionsHead: node.instructionsHead }
      });
      node.dispatched = true;
      const message = {
        instructions: node.instructions,
        typed: node.spec.typed,
        schema: node.spec.schema
      };
      this.host.driver.startAsk(session, node.instance, message);
    }
    ensureSession(actor) {
      if (actor.sessionPromise !== void 0) return actor.sessionPromise;
      const seed = actor.imported?.seed();
      const promise = this.host.driver.createActorSession(actor.ref, actor.persona, seed).then((session) => {
        actor.session = session;
        const resolvedModel = this.journal.getActor(
          this.host.runId,
          actor.ref.siteId,
          actor.ref.ordinal
        )?.resolvedModel;
        this.journal.putActor({
          runId: this.host.runId,
          siteId: actor.ref.siteId,
          ordinal: actor.ref.ordinal,
          name: actor.name,
          persona: actor.persona,
          sessionId: session.id,
          resolvedModel
        });
        return session;
      });
      actor.sessionPromise = promise;
      return promise;
    }
    // ——————————————————————————————— 内部：结算 ———————————————————————————————
    settleOk(node, artifact) {
      if (node.settled) return;
      node.settled = true;
      this.journal.putNode(this.nodeRecordFor(node, { status: "completed", result: artifact }));
      this.host.record({ type: "node-settled", instance: node.instance, outcome: "ok" });
      this.finishLiveNode(node);
      node.deferred.resolve(artifact);
    }
    settleFailed(node, error) {
      if (node.settled) return;
      node.settled = true;
      this.journal.putNode(this.nodeRecordFor(node, { status: "failed", error: error.toJSON() }));
      this.host.record({ type: "node-settled", instance: node.instance, outcome: "failed", error: error.toJSON() });
      this.finishLiveNode(node);
      node.deferred.reject(error);
    }
    finishLiveNode(node) {
      this.liveNodes.delete(refToString(node.instance));
      if (node.actor.current === node) {
        node.actor.current = void 0;
        this.activeAsks--;
      }
      this.pumpAll();
    }
    nodeRecordFor(node, outcome) {
      const record = {
        runId: this.host.runId,
        siteId: node.instance.siteId,
        ordinal: node.instance.ordinal,
        kind: "ask",
        actorSiteId: node.actor.ref.siteId,
        actorOrdinal: node.actor.ref.ordinal,
        actorSeq: node.actorSeq,
        inputHash: node.hash,
        status: outcome.status
      };
      if (outcome.status === "completed") record.result = outcome.result;
      else record.error = outcome.error;
      if (node.lastStats !== void 0)
        record.stats = this.journaledStats(node.instance, node.lastStats);
      return record;
    }
  };
  __name(_AskScheduler, "AskScheduler");
  var AskScheduler = _AskScheduler;
  function describeCause(cause) {
    const text = cause instanceof Error ? cause.message : String(cause);
    const trimmed = text.trim();
    if (trimmed.length === 0) return "unknown error";
    return trimmed.length > 300 ? `${trimmed.slice(0, 300)}\u2026` : trimmed;
  }
  __name(describeCause, "describeCause");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/facade/artifact-caps.ts
  var ARTIFACT_CAPS = {
    /** 一个 run 内最多能有几个不同的产物 id。 */
    maxArtifactsPerRun: 32,
    /** 单个 id 最多能发布几版（内容成员每次成功发布 = 一版）。 */
    maxVersionsPerArtifact: 16,
    /** `artifact.file` 的最大字节数（与 PROTOCOL_V4_LIMITS.attachmentMaxBytes 同值）。 */
    maxFileBytes: 20 * 1024 * 1024,
    /** `artifact.markdown` 的最大字节数（UTF-8）。 */
    maxMarkdownBytes: 256 * 1024,
    /** `opts.title` 的最大字符数。 */
    maxTitleLength: 120,
    /** `opts.description` 的最大字符数。 */
    maxDescriptionLength: 500,
    /** 预置 spec 规范化 JSON 后的最大字节数。 */
    maxSpecSerializedBytes: 8 * 1024,
    /** 产物 id 的最大字符数。 */
    maxIdLength: 64
  };

  // src/typescript-stub.ts
  var ts = new Proxy({}, { get: /* @__PURE__ */ __name(() => void 0, "get") });

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/facade/dts-model.ts
  var FACADE_MODEL_DECLARATIONS = String.raw`/** A model declared with model(). */
declare class ModelRef { private constructor(); private readonly modelRef: never }

/**
 * Declare a model subagents may run on: "modelId" or "providerId/modelId", optional "$level".
 * String literal only (checked at launch); choose among ModelRefs at run time.
 */
declare function model(id: string): ModelRef;
`;

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/facade/dts-stream.ts
  var FACADE_STREAM_SEGMENT = String.raw`
/**
 * A stream of typed items between two stages: one stage send()s, another drains it with
 * for await. Unbounded, first-in-first-out, each item to exactly one receiver.
 */
declare interface Channel<T> extends AsyncIterable<T> {
  /** Enqueue one item; synchronous, never waits. Throws ChannelClosed after close(). */
  send(item: T): void;
  /**
   * End the stream: receivers get the remaining items, then their loops exit. Idempotent.
   * A channel nobody closes fails the run as ChannelDeadlock, naming it, once nothing in
   * flight could send to it again.
   */
  close(): void;
}

/**
 * Create a channel. The name labels the deadlock message; it is not an identity. A receiver
 * on an empty, open channel waits until a send or the close.
 */
declare function channel<T>(name?: string): Channel<T>;

/**
 * Run an async block as a concurrent stage. It starts immediately, not when awaited, and
 * returns its promise.
 */
declare function future<T>(body: () => Promise<T>): Promise<T>;
`;

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/facade/dts-hole.ts
  var FACADE_HOLE_SEGMENT = String.raw`
/**
 * A typed gap: code you write later, once an earlier step's findings are in. When the run
 * reaches an unfilled hole, that branch waits while you write the body (FillWorkflowHole);
 * the rest of the run keeps going. prompt is the message you will receive, so interpolate
 * the values the decision needs. The body runs in the hole's place, sees the bindings
 * declared before it, and returns the hole's value. The filled form passes the body last.
 */
declare function hole<T>(name: string, prompt?: string): Promise<T>;
declare function hole<T>(name: string, body: () => Promise<T>): Promise<T>;
declare function hole<T>(name: string, prompt: string, body: () => Promise<T>): Promise<T>;
`;

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/facade/dts.ts
  var FACADE_ACTOR_SEGMENT = String.raw`
/** The pending result of one ask. Await it, or pass it to Promise.all with others. */
declare interface Node<T> extends PromiseLike<T> {}

${FACADE_MODEL_DECLARATIONS}
/** Persona of a subagent, fixed when agent() creates it. Every subagent has the same working tools. */
declare interface AgentPersona {
  /** System prompt describing the actor's role. */
  system?: string;
  /** Model id literal or ModelRef; omitted = the run's subagent model. */
  model?: ModelRef | string;
}

/**
 * A subagent: one conversation that keeps its context across asks. Asks issued while it is
 * busy wait their turn, first in, first out.
 */
declare interface Agent {
  /**
   * Give the subagent one task. With a type argument, the answer is a value of type T,
   * validated against a schema built from it: T is an interface you define in this script
   * with a plain "interface" declaration (no "declare" modifier). Without one, the answer is
   * the subagent's final reply text.
   */
  ask<T = string>(instructions: string): Node<T>;
}

/**
 * Create a subagent. Every call starts a new, empty conversation; to share one, share the
 * returned value. A non-empty name is an identity: unique within the run (a literal duplicate
 * is a compile error, a computed one fails the run) and the key AmendWorkflow matches cached
 * work by. Unnamed subagents are allowed and never reuse cached work.
 */
declare function agent(name?: string, persona?: string | AgentPersona): Agent;
`;
  var FACADE_LOG_SEGMENT = String.raw`
/** Emit a progress message to the user. */
declare function log(message: string): void;
`;
  var FACADE_REPORT_SEGMENT = String.raw`
/**
 * Publish one intermediate result while the run is going; returns nothing. Items are
 * recorded: a resumed run never shows one twice, and they reach the completion notification
 * even when the run fails. The item must be JSON-serializable (plain objects, arrays, strings,
 * numbers, booleans, null); functions, class instances, Date and promises are compile
 * errors. Caps fail the whole run, not the call: 65,536 items per run, 1 MiB per serialized
 * item, 1 GiB per run.
 *
 * artifactId routes the item to a dashboard: a compile-time literal naming a preset
 * (artifact.chart / table / metrics / board) whose declaration has executed by then. The
 * item becomes one point, row, tile value or card there and still lands in the run's
 * Results. A tag naming nothing, or a file/markdown artifact, fails the run.
 */
declare function report(item: unknown, artifactId?: string): void;
`;
  var FACADE_ARTIFACT_SEGMENT = String.raw`
/** A published artifact version: the id it was published under, and which version this call minted. */
declare interface ArtifactRef { id: string; version: number }
/** Card metadata every artifact kind accepts. */
declare interface ArtifactOptions {
  /** Shown as the card title; defaults to the id. In the user's language. */
  title?: string;
  /** A sentence or two, shown beside the title when this artifact is the run's primary. */
  description?: string;
  /** The run's deliverable: the card and the run pane lead with it. At most one id per run; once set it stays set for later versions. */
  primary?: boolean;
}
declare interface ArtifactFileOptions extends ArtifactOptions {
  /** Overrides the type sniffed from the extension ("application/pdf", "text/html", …). */
  contentType?: string;
}
/** One value taken from a reported item: a dot path into the item ("timing.after"). */
declare interface ArtifactField { field: string; label?: string; unit?: string }
declare interface ChartSpec extends ArtifactOptions {
  type?: "line" | "bar" | "scatter";        // default "line"
  x: ArtifactField;
  y: ArtifactField | ArtifactField[];        // several = several series
  scale?: "linear" | "log";                  // y axis, default "linear"
  /** A reference value drawn as a horizontal rule, taken from the first item that has the field. */
  baseline?: ArtifactField;
}
declare interface TableSpec extends ArtifactOptions {
  columns: ArtifactField[];
  /** Field that identifies a row; a later item with the same key replaces the row. Absent = append-only. */
  key?: string;
}
declare interface MetricsSpec extends ArtifactOptions {
  /** Each tile shows the value from the latest item that has the field. */
  metrics: ArtifactField[];
}
declare interface BoardSpec extends ArtifactOptions {
  /** Field identifying a card; a later item with the same key moves/updates the card. */
  key: string;
  /** Field holding the card's column. */
  status: string;
  /** Column order. Items whose status is not listed land in a trailing "other" column. */
  columns: string[];
  /** Field for the card title (default: the key) and extra fields shown on the card. */
  cardTitle?: string;
  detail?: ArtifactField[];
}
/**
 * Publish what the user keeps: cards beside the run, kept after it ends. Every id is a
 * compile-time string literal (non-empty, at most 64 characters of [A-Za-z0-9_.-]) and is
 * used with one member only.
 * - CONTENT (file, markdown) are effects: async, resolve to an ArtifactRef, and reject
 *   catchably (missing file, not a file, outside the workspace, over the cap, or no artifact
 *   store in this host).
 *   Bytes are copied at publish time; republishing an id mints the next version and keeps
 *   the old ones.
 * - PRESET (chart, table, metrics, board) are declarations: synchronous and void, drawn from
 *   the items tagged report(item, "<id>"). Declare each once, at the top level; an identical
 *   re-declaration is a no-op, a different or malformed spec fails the whole run.
 * Caps: 32 ids per run, 16 versions per id, 20 MiB per file, 256 KB per markdown, 120
 * characters of title and 500 of description.
 */
declare const artifact: {
  /** Publish a workspace file, path resolved as in files.read(). The type comes from the extension unless opts.contentType sets it. */
  file(id: string, path: string, opts?: ArtifactFileOptions): Promise<ArtifactRef>;
  /** Publish markdown text the script composed. */
  markdown(id: string, content: string, opts?: ArtifactOptions): Promise<ArtifactRef>;
  /** Declare a chart fed by report(item, id): each tagged item is one point. */
  chart(id: string, spec: ChartSpec): void;
  /** Declare a table fed by report(item, id): each tagged item is one row. */
  table(id: string, spec: TableSpec): void;
  /** Declare a metric tile row fed by report(item, id): each tile shows the newest value it has. */
  metrics(id: string, spec: MetricsSpec): void;
  /** Declare a board fed by report(item, id): each tagged item is a card, placed by its status field. */
  board(id: string, spec: BoardSpec): void;
};
`;
  var FACADE_PHASE_SEGMENT = String.raw`
/**
 * Mark the start of a phase: a named group of the steps that follow, drawn as one node on
 * the graph the user approves. Presentation only: it starts, waits for and returns nothing.
 * Required. The name is a non-empty compile-time literal and the call a standalone
 * statement. A marker covers every step from it to the end of the block it stands in
 * (nested blocks and inlined helpers included); two markers with the same name are one
 * phase. Every phase contains at least one ask or world.run.
 */
declare function phase(name: string): void;
`;
  var FACADE_WORLD_SEGMENT = String.raw`
/** One matching line found by files.grep. */
declare interface GrepMatch {
  /** Workspace-relative path of the file the match was found in. */
  path: string;
  /** One-based line number of the match. */
  line: number;
  /** The full text of the matching line. */
  text: string;
}

/**
 * Read-only views of the workspace. Each result is recorded, so a resumed run gets the same
 * answer back instead of reading again. There is no write: writing is a subagent's task.
 */
declare const files: {
  /** Workspace-relative paths matching a glob, sorted. Over 2000 files the call rejects. */
  glob(pattern: string): Promise<string[]>;
  /** Read one workspace file as UTF-8 text. Size-capped. */
  read(path: string): Promise<string>;
  /**
   * Search contents with a ripgrep-compatible regex, optionally narrowed by a glob over
   * paths ("*.ts", "src/**"); one entry per matching line. Over 2000 matches or 256KB of
   * results the call rejects.
   */
  grep(pattern: string, glob?: string): Promise<GrepMatch[]>;
};

/** The working tree's status, as reported by git.status(). */
declare interface GitStatus {
  /** Current branch name; absent when HEAD is detached. */
  branch?: string;
  /** True when nothing is staged, modified, or untracked. */
  clean: boolean;
  /** Workspace-relative paths staged for the next commit. */
  staged: string[];
  /** Workspace-relative paths modified in the working tree but not staged. */
  unstaged: string[];
  /** Workspace-relative paths git does not track (honouring .gitignore). */
  untracked: string[];
}

/** One commit, as reported by git.log(). */
declare interface GitCommit {
  /** Full commit hash. */
  hash: string;
  /** First line of the commit message. */
  subject: string;
  /** Author name. */
  author: string;
  /** Author date, ISO 8601. */
  date: string;
}

/**
 * Read-only git views, recorded like files.* so a resumed run gets the same answer back; no
 * call can write. A base names a single ref (no ".." ranges). Paths are workspace-relative, and
 * changes outside the workspace are not reported; git.log alone is repository-wide. Caps
 * reject rather than truncate: diff at 512KB, log at 100 commits. Outside a git repository,
 * or without git, every call rejects catchably.
 */
declare const git: {
  /**
   * Workspace-relative paths that changed. With no base: files modified against HEAD, plus
   * untracked files. With a base ref: tracked files whose current state differs from that
   * ref, uncommitted edits included, untracked files not.
   */
  changedFiles(base?: string): Promise<string[]>;
  /**
   * Unified diff against base (default HEAD). Covers the whole workspace unless you
   * narrow it to one workspace-relative path.
   */
  diff(base?: string, path?: string): Promise<string>;
  /** The current working-tree status, for the workspace. */
  status(): Promise<GitStatus>;
  /** The most recent commits, newest first. Default 20, maximum 100. */
  log(count?: number): Promise<GitCommit[]>;
};
`;
  var FACADE_WORLD_RUN_SEGMENT = String.raw`
/** The outcome of one world.run command, including nonzero exits. */
declare interface WorldRunResult {
  /** The process exit code. Nonzero is a normal, returned outcome — branch on it. */
  exitCode: number;
  /** Captured stdout (UTF-8). Capped at 256KB; over the cap the call rejects. */
  stdout: string;
  /** Captured stderr (UTF-8). Same cap and rejection semantics as stdout. */
  stderr: string;
}

/**
 * Run a command. Each call runs once and its result is recorded: a resumed run reuses the
 * recorded result instead of running the command again. A completed process RESOLVES,
 * nonzero exit included; the promise rejects (catchably) only on spawn failure or timeout
 * (default 300000ms, timeoutMs overrides, no upper cap). cmd is a compile-time string
 * literal, shown to the user at confirmation. Fixed argv, never a shell: no pipes,
 * redirection or expansion. cwd is the workspace. Node builtins are reachable as
 * world.run("node", ["-e", code]).
 */
declare const world: {
  run(cmd: string, args?: string[], opts?: { timeoutMs?: number }): Promise<WorldRunResult>;
};
`;
  var FACADE_ARGS_SEGMENT = String.raw`
/**
 * The run's arguments. For a saved workflow they are validated against its declared
 * arguments, with defaults filled in, before the run starts; an inline script or a snippet
 * gets {}. Always defined; the values are unknown, so narrow them (String(args.target), a
 * typeof guard).
 */
declare const args: Readonly<Record<string, unknown>>;
`;
  var FACADE_DTS = FACADE_ACTOR_SEGMENT + FACADE_ARGS_SEGMENT + FACADE_LOG_SEGMENT + FACADE_REPORT_SEGMENT + FACADE_ARTIFACT_SEGMENT + FACADE_PHASE_SEGMENT + // 留白段只进完整 facade（docs/dynamic-workflow/authoring.md「Holes」）：片段没有 run，也就
  // 没有可等主代理补全的东西，snippet 里的 `hole(...)` 得到 TS2304，教删除。
  FACADE_HOLE_SEGMENT + FACADE_WORLD_SEGMENT + FACADE_WORLD_RUN_SEGMENT + FACADE_STREAM_SEGMENT;
  var SNIPPET_FACADE_DTS = FACADE_ARGS_SEGMENT + FACADE_LOG_SEGMENT + FACADE_WORLD_SEGMENT + FACADE_WORLD_RUN_SEGMENT + FACADE_STREAM_SEGMENT;

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/facade/registry.ts
  var WORLD_READ_REGISTRY = [
    { container: "files", member: "glob", op: "glob" },
    { container: "files", member: "read", op: "read" },
    { container: "files", member: "grep", op: "grep" },
    { container: "git", member: "changedFiles", op: "git-changed-files" },
    { container: "git", member: "diff", op: "git-diff" },
    { container: "git", member: "status", op: "git-status" },
    { container: "git", member: "log", op: "git-log" },
    // world.run：journal 化命令执行。同一张表、同一套
    // 机制——「读」与「效应」的差别在授权面（编译期字面量 cmd + 确认窗）与 journal 的
    // 节点种类（world-run），不在站点身份。
    { container: "world", member: "run", op: "run" }
  ];
  var ARTIFACT_REGISTRY = [
    { container: "artifact", member: "file", op: "file", family: "content" },
    { container: "artifact", member: "markdown", op: "markdown", family: "content" },
    { container: "artifact", member: "chart", op: "chart", family: "preset" },
    { container: "artifact", member: "table", op: "table", family: "preset" },
    { container: "artifact", member: "metrics", op: "metrics", family: "preset" },
    { container: "artifact", member: "board", op: "board", family: "preset" }
  ];
  function isArtifactPresetOp(op) {
    return ARTIFACT_REGISTRY.some((row) => row.op === op && row.family === "preset");
  }
  __name(isArtifactPresetOp, "isArtifactPresetOp");
  var ASK_MEMBER = { container: "Agent", member: "ask" };
  var SITE_MEMBER_NAMES = /* @__PURE__ */ new Set([
    ASK_MEMBER.member,
    ...WORLD_READ_REGISTRY.map((row) => row.member),
    ...ARTIFACT_REGISTRY.map((row) => row.member)
  ]);

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/journal-read-types.ts
  var NODE_KIND_SET = {
    ask: true,
    "world-read": true,
    "world-run": true,
    report: true,
    artifact: true
  };
  var NODE_KINDS = Object.keys(NODE_KIND_SET);
  var NON_REPORT_NODE_KINDS = NODE_KINDS.filter(
    (kind) => kind !== "report"
  );

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/replay-order.ts
  var REPLAY_WORK_KINDS = NODE_KINDS.filter(
    (kind) => kind !== "report" && kind !== "artifact"
  );
  var SETTLE_EVENT_TYPES = [
    "node-settled",
    "artifact-published",
    "artifact-failed"
  ];
  function readReplayRows(journal, runId) {
    return {
      workRows: journal.listNodes(runId, { kinds: REPLAY_WORK_KINDS, withResult: false }),
      artifactRows: journal.listNodes(runId, { kinds: ["artifact"], withResult: true })
    };
  }
  __name(readReplayRows, "readReplayRows");
  var _ReplaySettleOrder = class _ReplaySettleOrder {
    constructor(order = []) {
      __publicField(this, "order", order);
      /** 实例键 → 它在首生结算次序里的位置。 */
      __publicField(this, "position");
      /** 已释放到哪一位（次序表的游标）。 */
      __publicField(this, "cursor", 0);
      /** 已到达、正在等自己那一位的释放动作。 */
      __publicField(this, "parked", /* @__PURE__ */ new Map());
      /** 已轮到、排在微任务里等着投递的释放动作（FIFO，见 {@link enqueue}）。 */
      __publicField(this, "queue", []);
      __publicField(this, "flushing", false);
      /** run 结算后闸门永久打开（见 {@link open}）。 */
      __publicField(this, "opened", false);
      const position = /* @__PURE__ */ new Map();
      order.forEach((key2, index) => position.set(key2, index));
      this.position = position;
    }
    static empty() {
      return new _ReplaySettleOrder([]);
    }
    /** 首生的结算次序（诊断与测试用）。 */
    recorded() {
      return this.order;
    }
    /**
     * 受次序约束地释放一次 replay 命中。表里没有这个实例（全新调用、旧 journal）、闸门已打开、
     * 或它的位次已被越过时立即执行；否则挂起，等轮到它。
     */
    hold(instance, release) {
      const key2 = refToString(instance);
      const at = this.position.get(key2);
      if (this.opened || at === void 0 || at < this.cursor) {
        release();
        return;
      }
      this.parked.set(key2, release);
      this.pump();
    }
    /**
     * 投递永远晚于**产生这个 promise 的那次调用**：释放动作先进队列，一个微任务之后才执行。
     *
     * 少了这一跳，次序就是反的：`Promise.all` 里每条分支都在同一个同步片里发起自己的调用，
     * 队首那条的释放会**在它自己的 `ask()` 里**同步发生——那时它的 `await` 还没挂上去，于是
     * 它的续体排在「早先已挂上、刚被这次 pump 兑现」的那条后面。微任务这一跳让所有 `await`
     * 先挂稳，投递次序因此就是释放次序。这不是时钟：跳的是确定的一步，队列是 FIFO。
     */
    enqueue(release) {
      this.queue.push(release);
      if (this.flushing) return;
      this.flushing = true;
      queueMicrotask(() => this.flush());
    }
    /** 把排好的投递按 FIFO 执行完（队列空即空跑，故重复调用安全）。 */
    flush() {
      this.flushing = false;
      const batch = this.queue;
      this.queue = [];
      for (const release of batch) release();
    }
    /**
     * run 结算：闸门永久打开，挂起的按记录次序放完。
     *
     * 不放的后果是脚本那侧的 promise 永远不兑现——在 harness 里沙箱马上就被关掉，但在同进程
     * 跑脚本的装配（引擎测试、`EvalWorkflowSnippet`）里那就是一次挂死。结算之后释放是安全的：
     * 引擎已 markSettled，每条 host 路径都以 `isRunSettled()` 开头。
     */
    open() {
      if (this.opened) return;
      this.opened = true;
      this.flush();
      for (let i = this.cursor; i < this.order.length; i++) {
        const key2 = this.order[i];
        if (key2 === void 0) continue;
        const release = this.parked.get(key2);
        if (release === void 0) continue;
        this.parked.delete(key2);
        release();
      }
      this.cursor = this.order.length;
      const leftovers = [...this.parked.values()];
      this.parked.clear();
      for (const release of leftovers) release();
    }
    /** 游标能往前走多少就走多少：队首已到达即释放，然后看下一位。 */
    pump() {
      while (this.cursor < this.order.length) {
        const key2 = this.order[this.cursor];
        if (key2 === void 0) break;
        const release = this.parked.get(key2);
        if (release === void 0) break;
        this.parked.delete(key2);
        this.cursor++;
        this.enqueue(release);
      }
    }
  };
  __name(_ReplaySettleOrder, "ReplaySettleOrder");
  var ReplaySettleOrder = _ReplaySettleOrder;
  function recoverSettleOrder(journal, runId, nodes = Object.values(readReplayRows(journal, runId)).flat()) {
    const claimable = /* @__PURE__ */ new Set();
    for (const node of nodes) {
      if (node.status !== "completed" && node.status !== "failed") continue;
      if (!claimsOnReplay(node)) continue;
      claimable.add(refToString(node));
    }
    if (claimable.size === 0) return ReplaySettleOrder.empty();
    const order = [];
    const seen = /* @__PURE__ */ new Set();
    for (const { event } of journal.listEvents(runId, {
      types: SETTLE_EVENT_TYPES,
      reportItems: { limit: 0 }
    })) {
      const key2 = settledInstanceKey(event);
      if (key2 === void 0 || seen.has(key2) || !claimable.has(key2)) continue;
      seen.add(key2);
      order.push(key2);
    }
    return new ReplaySettleOrder(order);
  }
  __name(recoverSettleOrder, "recoverSettleOrder");
  function settledInstanceKey(event) {
    if (event.type === "node-settled" || event.type === "artifact-published" || event.type === "artifact-failed") {
      return refToString(event.instance);
    }
    return void 0;
  }
  __name(settledInstanceKey, "settledInstanceKey");
  function claimsOnReplay(node) {
    if (node.kind === "report") return false;
    if (node.kind !== "artifact") return true;
    if (node.status === "failed") return true;
    const kind = node.result?.kind;
    return typeof kind === "string" && !isArtifactPresetOp(kind);
  }
  __name(claimsOnReplay, "claimsOnReplay");
  function heldResolution(hold, instance, settle) {
    return new Promise((resolve, reject) => {
      hold(instance, () => {
        try {
          resolve(settle());
        } catch (cause) {
          reject(cause);
        }
      });
    });
  }
  __name(heldResolution, "heldResolution");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/engine-artifacts-primary.ts
  function primaryArtifactId(state) {
    for (const [id, idState] of state.artifacts) if (idState.primary) return id;
    return void 0;
  }
  __name(primaryArtifactId, "primaryArtifactId");
  function primaryConflict(state, id, primary) {
    if (!primary) return void 0;
    const holder = primaryArtifactId(state);
    return holder === void 0 || holder === id ? void 0 : holder;
  }
  __name(primaryConflict, "primaryConflict");
  function primaryConflictMessage(id, holder, fix, instance) {
    const where = instance === void 0 ? "" : ` (at ${refToString(instance)})`;
    return `Cannot mark "${id}" as primary${where}: "${holder}" is already this run's primary artifact. A run has one deliverable; ${fix}, or publish it as a new version of "${holder}".`;
  }
  __name(primaryConflictMessage, "primaryConflictMessage");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/artifact-spec.ts
  function validateArtifactSpec(op, spec) {
    if (!isPlainObject(spec)) return `${op} spec must be an object literal, got ${describe(spec)}`;
    const common = checkOptions(spec);
    if (common !== void 0) return common;
    const shape = checkShape(op, spec);
    if (shape !== void 0) return shape;
    const bytes = utf8ByteLength(canonicalJson(spec));
    if (bytes > ARTIFACT_CAPS.maxSpecSerializedBytes) {
      return `${op} spec is ${bytes} bytes serialized, over the ${ARTIFACT_CAPS.maxSpecSerializedBytes}-byte limit`;
    }
    return void 0;
  }
  __name(validateArtifactSpec, "validateArtifactSpec");
  function checkShape(op, spec) {
    if (op === "chart") {
      const x = checkField(spec.x, "x");
      if (x !== void 0) return x;
      const y = spec.y;
      if (Array.isArray(y)) {
        if (y.length === 0) return "chart spec y is an empty array; give it at least one series";
        for (const [index, entry] of y.entries()) {
          const problem = checkField(entry, `y[${index}]`);
          if (problem !== void 0) return problem;
        }
      } else {
        const problem = checkField(y, "y");
        if (problem !== void 0) return problem;
      }
      if (spec.type !== void 0 && !["line", "bar", "scatter"].includes(String(spec.type))) {
        return `chart spec type must be "line", "bar" or "scatter", got ${describe(spec.type)}`;
      }
      if (spec.scale !== void 0 && !["linear", "log"].includes(String(spec.scale))) {
        return `chart spec scale must be "linear" or "log", got ${describe(spec.scale)}`;
      }
      if (spec.baseline !== void 0) return checkField(spec.baseline, "baseline");
      return void 0;
    }
    if (op === "table") {
      const columns = checkFieldList(spec.columns, "columns");
      if (columns !== void 0) return columns;
      if (spec.key !== void 0 && !isNonEmptyString(spec.key)) {
        return "table spec key must be a non-empty string (the field that identifies a row)";
      }
      return void 0;
    }
    if (op === "metrics") return checkFieldList(spec.metrics, "metrics");
    if (!isNonEmptyString(spec.key)) return "board spec key must be a non-empty string (the field that identifies a card)";
    if (!isNonEmptyString(spec.status)) {
      return "board spec status must be a non-empty string (the field that picks a card's column)";
    }
    if (!Array.isArray(spec.columns) || spec.columns.length === 0) {
      return "board spec columns must be a non-empty array of strings (the column order)";
    }
    for (const [index, column] of spec.columns.entries()) {
      if (!isNonEmptyString(column)) return `board spec columns[${index}] must be a non-empty string`;
    }
    if (spec.cardTitle !== void 0 && !isNonEmptyString(spec.cardTitle)) {
      return "board spec cardTitle must be a non-empty string (the field used as the card title)";
    }
    if (spec.detail !== void 0) return checkFieldList(spec.detail, "detail");
    return void 0;
  }
  __name(checkShape, "checkShape");
  function checkOptions(spec) {
    if (spec.title !== void 0) {
      if (typeof spec.title !== "string") return `spec title must be a string, got ${describe(spec.title)}`;
      if (spec.title.length > ARTIFACT_CAPS.maxTitleLength) {
        return `spec title is ${spec.title.length} characters, over the ${ARTIFACT_CAPS.maxTitleLength} limit`;
      }
    }
    if (spec.description !== void 0) {
      if (typeof spec.description !== "string") {
        return `spec description must be a string, got ${describe(spec.description)}`;
      }
      if (spec.description.length > ARTIFACT_CAPS.maxDescriptionLength) {
        return `spec description is ${spec.description.length} characters, over the ${ARTIFACT_CAPS.maxDescriptionLength} limit`;
      }
    }
    if (spec.primary !== void 0 && typeof spec.primary !== "boolean") {
      return `spec primary must be a boolean, got ${describe(spec.primary)}`;
    }
    return void 0;
  }
  __name(checkOptions, "checkOptions");
  function checkFieldList(value, where) {
    if (!Array.isArray(value) || value.length === 0) {
      return `spec ${where} must be a non-empty array of fields ({ field: "\u2026" })`;
    }
    for (const [index, entry] of value.entries()) {
      const problem = checkField(entry, `${where}[${index}]`);
      if (problem !== void 0) return problem;
    }
    return void 0;
  }
  __name(checkFieldList, "checkFieldList");
  function checkField(value, where) {
    if (!isPlainObject(value)) return `spec ${where} must be { field: "\u2026" }, got ${describe(value)}`;
    if (!isNonEmptyString(value.field)) {
      return `spec ${where}.field must be a non-empty string (a dot path into the item, e.g. "timing.after")`;
    }
    for (const key2 of ["label", "unit"]) {
      if (value[key2] !== void 0 && typeof value[key2] !== "string") {
        return `spec ${where}.${key2} must be a string, got ${describe(value[key2])}`;
      }
    }
    return void 0;
  }
  __name(checkField, "checkField");
  function isPlainObject(value) {
    return typeof value === "object" && value !== null && !Array.isArray(value);
  }
  __name(isPlainObject, "isPlainObject");
  function isNonEmptyString(value) {
    return typeof value === "string" && value.trim() !== "";
  }
  __name(isNonEmptyString, "isNonEmptyString");
  function describe(value) {
    if (value === null) return "null";
    if (Array.isArray(value)) return `array (${value.length} items)`;
    if (typeof value === "object") return "object";
    if (typeof value === "string") return JSON.stringify(value.slice(0, 40));
    return String(value);
  }
  __name(describe, "describe");
  function utf8ByteLength(value) {
    return new TextEncoder().encode(value).length;
  }
  __name(utf8ByteLength, "utf8ByteLength");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/engine-artifacts.ts
  function publishContentArtifact(state, siteId, op, args) {
    if (state.isRunSettled()) return Promise.reject(state.runError());
    const ordinal = state.nextOrdinal(siteId);
    const instance = { siteId, ordinal };
    const id = args[0];
    const payload = args[1];
    if (typeof id !== "string" || id === "") {
      return Promise.reject(
        new WorkflowError(
          "DriverError",
          `artifact.${op}: id must be a non-empty string (at ${refToString(instance)}).`
        )
      );
    }
    if (typeof payload !== "string") {
      return Promise.reject(
        new WorkflowError(
          "DriverError",
          op === "file" ? `artifact.file: path must be a string (at ${refToString(instance)}).` : `artifact.markdown: content must be a string (at ${refToString(instance)}).`
        )
      );
    }
    const primaryOption = args[2]?.primary;
    if (primaryOption !== void 0 && typeof primaryOption !== "boolean") {
      return Promise.reject(
        new WorkflowError(
          "DriverError",
          `artifact.${op}: opts.primary must be a boolean (at ${refToString(instance)}).`
        )
      );
    }
    const hash = inputHash({ args, id, op });
    const recorded = state.journal.getNode(state.runId, siteId, ordinal);
    if (recorded !== void 0) {
      if (recorded.inputHash !== hash) {
        const err = hashMismatch(instance, recorded.inputHash, hash);
        state.failRun(err);
        return Promise.reject(err);
      }
      if (recorded.status === "completed") {
        const record = recorded.result;
        return heldResolution(state.holdForReplay, instance, () => ({
          id: record.id,
          version: record.version
        }));
      }
      if (recorded.status === "failed") {
        return heldResolution(state.holdForReplay, instance, () => {
          throw WorkflowError.fromJSON(recorded.error);
        });
      }
    }
    const idState = state.artifacts.get(id);
    const primary = primaryOption === true || idState?.primary === true;
    const admission = admitArtifact(state, id, op, primary);
    if (admission !== void 0) {
      return Promise.reject(settleArtifactFailure(state, instance, hash, { id, op }, admission));
    }
    const version = (idState?.versions ?? 0) + 1;
    const publish = state.driver.executeArtifactPublish;
    if (publish === void 0) {
      return Promise.reject(
        settleArtifactFailure(
          state,
          instance,
          hash,
          { id, op },
          new WorkflowError(
            "ArtifactStoreUnavailable",
            `Cannot publish "${id}": this host has no artifact store (at ${refToString(instance)}).`
          )
        )
      );
    }
    state.journal.putNode({
      runId: state.runId,
      siteId,
      ordinal,
      kind: "artifact",
      inputHash: hash,
      status: "running",
      artifactId: id
    });
    const request = {
      runId: state.runId,
      siteId,
      ordinal,
      op,
      id,
      version,
      ...op === "file" ? { path: payload } : { content: payload },
      ...args[2] === void 0 ? {} : { opts: args[2] }
    };
    return publish.call(state.driver, request).then(
      (record) => settleArtifactPublish(state, instance, hash, { id, op, version, primary }, record),
      (cause) => {
        const err = cause instanceof WorkflowError ? cause : new WorkflowError("DriverError", `Artifact publish failed: ${op} "${id}".`, {
          cause
        });
        throw settleArtifactFailure(state, instance, hash, { id, op }, err);
      }
    );
  }
  __name(publishContentArtifact, "publishContentArtifact");
  function declarePresetArtifact(state, siteId, op, args) {
    if (state.isRunSettled()) return;
    const ordinal = state.nextOrdinal(siteId);
    const instance = { siteId, ordinal };
    const id = args[0];
    if (typeof id !== "string" || id === "") {
      state.failRun(
        new WorkflowError(
          "DriverError",
          `artifact.${op}: id must be a non-empty string (at ${refToString(instance)}).`
        )
      );
      return;
    }
    const spec = args[1];
    const hash = inputHash({ args, id, op });
    const recorded = state.journal.getNode(state.runId, siteId, ordinal);
    if (recorded !== void 0) {
      if (recorded.inputHash !== hash) {
        state.failRun(hashMismatch(instance, recorded.inputHash, hash));
        return;
      }
      return;
    }
    const problem = validateArtifactSpec(op, spec);
    if (problem !== void 0) {
      state.failRun(
        new WorkflowError(
          "ArtifactSpecInvalid",
          `Artifact "${id}" has an invalid spec (at ${refToString(instance)}): ${problem}`
        )
      );
      return;
    }
    const canonical = canonicalJson(spec);
    const idState = state.artifacts.get(id);
    if (idState !== void 0) {
      if (idState.kind !== op) {
        state.failRun(
          new WorkflowError(
            "ArtifactKindMismatch",
            `Artifact id "${id}" is already a ${idState.kind} and cannot be declared as a ${op} (at ${refToString(instance)}). An id belongs to one artifact kind for the whole run; use a different id.`
          )
        );
        return;
      }
      if (idState.spec === canonical) return;
      state.failRun(
        new WorkflowError(
          "ArtifactRedeclared",
          `Artifact "${id}" was already declared with a different spec (at ${refToString(instance)}). The spec for one id must stay identical (an identical re-declaration is a no-op); declare it once at the top of the script.`
        )
      );
      return;
    }
    const wantsPrimary = spec.primary === true;
    const holder = wantsPrimary ? primaryArtifactId(state) : void 0;
    if (holder !== void 0 && holder !== id) {
      state.failRun(
        new WorkflowError(
          "ArtifactPrimaryConflict",
          primaryConflictMessage(id, holder, `declare "${id}" without primary`, instance)
        )
      );
      return;
    }
    if (state.artifacts.size >= ARTIFACT_CAPS.maxArtifactsPerRun) {
      state.failRun(
        new WorkflowError(
          "ArtifactCapExceeded",
          `Cannot declare "${id}": this run already has ${ARTIFACT_CAPS.maxArtifactsPerRun} artifact ids, the maximum. Reuse an existing id or publish fewer artifacts.`
        )
      );
      return;
    }
    const record = presetRecord(id, op, spec);
    state.artifacts.set(id, {
      kind: op,
      spec: canonical,
      versions: 1,
      ...wantsPrimary ? { primary: true } : {}
    });
    state.journal.putNode({
      runId: state.runId,
      siteId,
      ordinal,
      kind: "artifact",
      inputHash: hash,
      status: "completed",
      result: record,
      artifactId: id
    });
    state.record({ type: "artifact-published", instance, artifact: record });
  }
  __name(declarePresetArtifact, "declarePresetArtifact");
  function admitArtifact(state, id, op, primary) {
    const idState = state.artifacts.get(id);
    if (idState !== void 0 && idState.kind !== op) {
      return new WorkflowError(
        "ArtifactKindMismatch",
        `Artifact id "${id}" is already a ${idState.kind} and cannot be published as a ${op}. An id belongs to one artifact kind for the whole run (publishing the same id again creates a new version, and a version cannot change what it is); use a different id.`
      );
    }
    const holder = primaryConflict(state, id, primary);
    if (holder !== void 0) {
      return new WorkflowError(
        "ArtifactPrimaryConflict",
        primaryConflictMessage(id, holder, `publish "${id}" without primary`, void 0)
      );
    }
    if (idState === void 0 && state.artifacts.size >= ARTIFACT_CAPS.maxArtifactsPerRun) {
      return new WorkflowError(
        "ArtifactCapExceeded",
        `Cannot publish "${id}": this run already has ${ARTIFACT_CAPS.maxArtifactsPerRun} artifact ids, the maximum. Publish a new version of an existing id instead.`
      );
    }
    if (idState !== void 0 && idState.versions >= ARTIFACT_CAPS.maxVersionsPerArtifact) {
      return new WorkflowError(
        "ArtifactVersionCapExceeded",
        `Artifact "${id}" already has ${idState.versions} versions, the maximum of ${ARTIFACT_CAPS.maxVersionsPerArtifact}. Publish the final content in fewer versions.`
      );
    }
    return void 0;
  }
  __name(admitArtifact, "admitArtifact");
  function settleArtifactPublish(state, instance, hash, issued, record) {
    if (state.isRunSettled()) throw state.runError();
    const holder = primaryConflict(state, issued.id, issued.primary);
    if (holder !== void 0) {
      throw settleArtifactFailure(
        state,
        instance,
        hash,
        issued,
        new WorkflowError(
          "ArtifactPrimaryConflict",
          primaryConflictMessage(issued.id, holder, `publish "${issued.id}" without primary`, instance)
        )
      );
    }
    const stored = issued.primary ? { ...record, primary: true } : record;
    state.journal.putNode({
      runId: state.runId,
      siteId: instance.siteId,
      ordinal: instance.ordinal,
      kind: "artifact",
      inputHash: hash,
      status: "completed",
      result: stored,
      artifactId: issued.id
    });
    state.artifacts.set(issued.id, {
      kind: issued.op,
      versions: issued.version,
      ...issued.primary ? { primary: true } : {}
    });
    state.record({ type: "artifact-published", instance, artifact: stored });
    return { id: issued.id, version: issued.version };
  }
  __name(settleArtifactPublish, "settleArtifactPublish");
  function settleArtifactFailure(state, instance, hash, issued, error) {
    const json = error.toJSON();
    state.journal.putNode({
      runId: state.runId,
      siteId: instance.siteId,
      ordinal: instance.ordinal,
      kind: "artifact",
      inputHash: hash,
      status: "failed",
      error: json,
      artifactId: issued.id
    });
    state.record({
      type: "artifact-failed",
      instance,
      id: issued.id,
      op: issued.op,
      error: json
    });
    return error;
  }
  __name(settleArtifactFailure, "settleArtifactFailure");
  function rememberArtifactRow(state, id, result) {
    if (id === void 0 || id === "") return;
    const record = result ?? void 0;
    if (record?.kind === void 0) return;
    const existing = state.artifacts.get(id);
    const spec = record.spec === void 0 ? existing?.spec : canonicalJson(record.spec);
    const primary = record.primary === true || existing?.primary === true;
    state.artifacts.set(id, {
      kind: record.kind,
      ...spec === void 0 ? {} : { spec },
      versions: (existing?.versions ?? 0) + 1,
      ...primary ? { primary: true } : {}
    });
  }
  __name(rememberArtifactRow, "rememberArtifactRow");
  function isDeclaredPreset(state, id) {
    const idState = state.artifacts.get(id);
    return idState !== void 0 && isArtifactPresetOp(idState.kind);
  }
  __name(isDeclaredPreset, "isDeclaredPreset");
  function declaredPresetIds(state) {
    return [...state.artifacts.entries()].filter(([, idState]) => isArtifactPresetOp(idState.kind)).map(([id]) => id).sort();
  }
  __name(declaredPresetIds, "declaredPresetIds");
  function presetRecord(id, op, spec) {
    const options = spec ?? {};
    return {
      id,
      kind: op,
      version: 1,
      ...typeof options.title === "string" ? { title: options.title } : {},
      ...typeof options.description === "string" ? { description: options.description } : {},
      spec,
      ...options.primary === true ? { primary: true } : {}
    };
  }
  __name(presetRecord, "presetRecord");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/facade/report-caps.ts
  var REPORT_CAPS = {
    /**
     * 一个 run 内 `report` 的最大条数。原为 256：那是节点上限（100）还在时按「与之同宽」定的，
     * 节点上限删掉之后，一次「每个任务报一条」的扇出就能合理地撞上它，而撞上的代价是整个 run
     * 失败。条数本身不贵：没有读者会把超出展示界的 item 读进内存（docs/execution-engine.md
     * 「Reading the journal」），磁盘上的总量由 {@link REPORT_CAPS.maxBytesPerRun} 管。
     */
    maxItemsPerRun: 65536,
    /**
     * 单条 item 序列化后的最大字节数。原为 32 KiB；1 MiB 的界来自一条 item 要能放进一个协议
     * 消息（单帧上限 16 MiB，看板与事件日志一页 4 MiB 且至少带一条），并给读取整条 item 的
     * 读者留出余量——冷回放与 GetWorkflowRun 一次最多读 64 条，即至多 64 MiB 的瞬时内存。
     * 更大的产出该走 `artifact.file`（上限 20 MiB，按块读取）。
     */
    maxItemSerializedBytes: 1024 * 1024,
    /**
     * 一个 run 内全部 item 的序列化字节总数上限。没有它，65,536 条 × 1 MiB 就是 64 GiB 的
     * item（磁盘上 128 GiB）。resume 时由 `sumResultBytes` 从 journal 恢复，跨 resume 连续。
     */
    maxBytesPerRun: 1024 * 1024 * 1024
  };

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/engine-report.ts
  function publishReport(state, siteId, item, artifactId) {
    if (state.isRunSettled()) return;
    const ordinal = state.nextOrdinal(siteId);
    const instance = { siteId, ordinal };
    const serialized = probeReportItem(state, instance, item);
    if (serialized === void 0) return;
    const hash = inputHash(item);
    const recorded = state.journal.getNode(state.runId, siteId, ordinal, { withResult: false });
    if (recorded !== void 0) {
      if (recorded.inputHash !== hash) {
        state.failRun(hashMismatch(instance, recorded.inputHash, hash));
        return;
      }
      return;
    }
    if (artifactId !== void 0 && !isDeclaredPreset(state, artifactId)) {
      state.failRun(
        new WorkflowError(
          "ArtifactUndeclared",
          `report() tag "${artifactId}" is not a declared preset artifact (at ${refToString(instance)}). Declare it once at the top of the script, e.g. artifact.chart("${artifactId}", \u2026), before tagging reports with it. Declared presets: ${declaredPresetIds(state).join(", ") || "(none)"}.`
        )
      );
      return;
    }
    if (!reserveReport(state, instance, serialized)) return;
    state.journal.putNode({
      runId: state.runId,
      siteId,
      ordinal,
      kind: "report",
      inputHash: hash,
      status: "completed",
      result: item,
      // 打了标签的 report 行是「看板 = journal 的投影」这条不变式的落点：一个看板的每个点
      // 就是一行 kind = report ∧ artifact_id = 该 id。
      ...artifactId === void 0 ? {} : { artifactId }
    });
    state.record({
      type: "report",
      instance,
      item,
      ...artifactId === void 0 ? {} : { artifactId }
    });
  }
  __name(publishReport, "publishReport");
  function probeReportItem(state, instance, item) {
    let serialized;
    try {
      serialized = JSON.stringify(item);
    } catch (cause) {
      state.failRun(
        new WorkflowError(
          "DriverError",
          `report() item cannot be serialized to JSON (a cycle or a bigint) at ${refToString(instance)}. Report a plain JSON value.`,
          { cause }
        )
      );
      return void 0;
    }
    if (serialized === void 0) {
      state.failRun(
        new WorkflowError(
          "DriverError",
          `report() item is not a JSON value (undefined, a function or a symbol) at ${refToString(instance)}. Report a plain JSON value.`
        )
      );
      return void 0;
    }
    return serialized;
  }
  __name(probeReportItem, "probeReportItem");
  function reserveReport(state, instance, serialized) {
    const bytes = utf8ByteLength2(serialized);
    if (bytes > REPORT_CAPS.maxItemSerializedBytes) {
      state.failRun(
        new WorkflowError(
          "ReportCapExceeded",
          `report() item at ${refToString(instance)} is ${bytes} bytes, over the ${REPORT_CAPS.maxItemSerializedBytes}-byte limit. Report a summary instead.`
        )
      );
      return false;
    }
    const tally = state.reportTally();
    if (tally.count >= REPORT_CAPS.maxItemsPerRun) {
      state.failRun(
        new WorkflowError(
          "ReportCapExceeded",
          `This run already reported ${REPORT_CAPS.maxItemsPerRun} items, the maximum. Report findings, not chatter.`
        )
      );
      return false;
    }
    if (tally.bytes + bytes > REPORT_CAPS.maxBytesPerRun) {
      state.failRun(
        new WorkflowError(
          "ReportCapExceeded",
          `report() item at ${refToString(instance)} would take this run's reports to ${tally.bytes + bytes} bytes, over the ${REPORT_CAPS.maxBytesPerRun}-byte limit per run. Report summaries, and publish large results with artifact.file.`
        )
      );
      return false;
    }
    state.countReport(bytes);
    return true;
  }
  __name(reserveReport, "reserveReport");
  function utf8ByteLength2(value) {
    return new TextEncoder().encode(value).length;
  }
  __name(utf8ByteLength2, "utf8ByteLength");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/world-read-input.ts
  var TRUNCATED_ARG_MAX_CHARS = 400;
  var MAX_ARGS_KEPT = 8;
  function utf8Length(text) {
    return new TextEncoder().encode(text).length;
  }
  __name(utf8Length, "utf8Length");
  function previewArg(value) {
    let text;
    if (typeof value === "string") text = value;
    else {
      try {
        text = JSON.stringify(value) ?? String(value);
      } catch {
        text = String(value);
      }
    }
    return text.length > TRUNCATED_ARG_MAX_CHARS ? `${text.slice(0, TRUNCATED_ARG_MAX_CHARS)}\u2026` : text;
  }
  __name(previewArg, "previewArg");
  function boundWorldReadInput(op, args) {
    const plain = { op, args: [...args] };
    try {
      const text = JSON.stringify(plain);
      if (text !== void 0 && utf8Length(text) <= WORLD_READ_INPUT_MAX_BYTES) return plain;
    } catch {
    }
    return {
      op,
      args: args.slice(0, MAX_ARGS_KEPT).map(previewArg),
      truncated: true
    };
  }
  __name(boundWorldReadInput, "boundWorldReadInput");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/engine-world.ts
  function readWorld(state, siteId, op, args) {
    if (state.isRunSettled()) return Promise.reject(state.runError());
    const ordinal = state.nextOrdinal(siteId);
    const instance = { siteId, ordinal };
    const hash = inputHash({ op, args });
    const input = boundWorldReadInput(op, args);
    const recorded = state.journal.getNode(state.runId, siteId, ordinal);
    if (recorded !== void 0) {
      if (recorded.inputHash !== hash) {
        const err = hashMismatch(instance, recorded.inputHash, hash);
        state.failRun(err);
        return Promise.reject(err);
      }
      if (recorded.status === "completed") {
        return heldResolution(state.holdForReplay, instance, () => {
          state.record({ type: "node-settled", instance, outcome: "ok", cached: true });
          return recorded.result;
        });
      }
      if (recorded.status === "failed") {
        return heldResolution(state.holdForReplay, instance, () => {
          state.record({
            type: "node-settled",
            instance,
            outcome: "failed",
            cached: true,
            error: recorded.error
          });
          throw WorkflowError.fromJSON(recorded.error);
        });
      }
    }
    const kind = op === "run" ? "world-run" : "world-read";
    const imported = state.importClosed() ? void 0 : state.importedWorld.take(hash);
    if (imported !== void 0) {
      state.journal.putNode({
        runId: state.runId,
        siteId,
        ordinal,
        kind,
        inputHash: hash,
        input,
        status: "completed",
        result: imported.result
      });
      state.record({ type: "node-settled", instance, outcome: "ok", cached: true });
      return Promise.resolve(imported.result);
    }
    if (state.isRunSettled()) return Promise.reject(state.runError());
    if (kind === "world-run") closeImportCache(state, instance, "world-run");
    state.journal.putNode({
      runId: state.runId,
      siteId,
      ordinal,
      kind,
      inputHash: hash,
      input,
      status: "running"
    });
    state.record({ type: "node-queued", instance, kind });
    state.record({ type: "node-dispatched", instance });
    return state.driver.executeWorldRead(op, args).then(
      (value) => settleWorldRead(state, instance, kind, hash, input, { status: "completed", result: value }),
      (cause) => {
        const err = cause instanceof WorkflowError ? cause : new WorkflowError("DriverError", `World read failed: ${op} ${canonicalJson(args)}.`, {
          cause
        });
        return settleWorldRead(state, instance, kind, hash, input, {
          status: "failed",
          error: err.toJSON()
        });
      }
    );
  }
  __name(readWorld, "readWorld");
  function settleWorldRead(state, instance, kind, hash, input, outcome) {
    if (state.isRunSettled()) throw state.runError();
    state.journal.putNode({
      runId: state.runId,
      siteId: instance.siteId,
      ordinal: instance.ordinal,
      kind,
      inputHash: hash,
      input,
      status: outcome.status,
      ...outcome.status === "completed" ? { result: outcome.result } : { error: outcome.error }
    });
    if (outcome.status === "completed") {
      state.record({ type: "node-settled", instance, outcome: "ok" });
      return outcome.result;
    }
    state.record({ type: "node-settled", instance, outcome: "failed", error: outcome.error });
    throw WorkflowError.fromJSON(outcome.error);
  }
  __name(settleWorldRead, "settleWorldRead");
  function closeImportCache(state, instance, cause, actorName) {
    if (state.importClosed()) return;
    state.closeImport();
    if (state.importedCache === void 0) return;
    state.record({
      type: "import-cache-closed",
      instance,
      cause,
      ...actorName === void 0 || actorName === "" ? {} : { actorName }
    });
  }
  __name(closeImportCache, "closeImportCache");
  function recoverImportClosure(journal, runId) {
    const live = /* @__PURE__ */ new Set();
    const queuedBeforeClose = /* @__PURE__ */ new Set();
    let closed = false;
    for (const { event } of journal.listEvents(runId, {
      types: ["node-queued", "import-cache-closed"],
      reportItems: { limit: 0 }
    })) {
      if (event.type === "import-cache-closed") closed = true;
      if (event.type !== "node-queued" || event.kind !== "ask") continue;
      const key2 = refToString(event.instance);
      live.add(key2);
      if (!closed) queuedBeforeClose.add(key2);
    }
    return { live, queuedBeforeClose, closed };
  }
  __name(recoverImportClosure, "recoverImportClosure");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/engine-resume.ts
  function restoreFromJournal(state, scheduler) {
    const { workRows, artifactRows } = readReplayRows(state.journal, state.runId);
    const replaySettleOrder = recoverSettleOrder(state.journal, state.runId, [
      ...workRows,
      ...artifactRows
    ]);
    scheduler.seedRecordedAsks(workRows);
    for (const node of artifactRows) {
      if (node.status !== "completed") continue;
      rememberArtifactRow(state, node.artifactId, node.result);
    }
    return {
      replaySettleOrder,
      reportTally: {
        count: state.journal.countNodes(state.runId, "report"),
        bytes: state.journal.sumResultBytes(state.runId, "report")
      }
    };
  }
  __name(restoreFromJournal, "restoreFromJournal");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/engine-settlement.ts
  function settleCompleted(state, artifact) {
    if (state.isRunSettled()) return;
    state.markSettled();
    state.abortInFlight(
      new WorkflowError("Cancelled", "Run completed; in-flight subagent tasks were abandoned."),
      true
    );
    finishRun(state, "completed", { result: artifact });
    state.resolveSettled({ status: "completed", artifact });
  }
  __name(settleCompleted, "settleCompleted");
  function settleStopped(state, reason, error, supersededBy) {
    if (state.isRunSettled()) return;
    state.markSettled();
    state.abortInFlight(new WorkflowError("Cancelled", "Run stopped."), true);
    finishRun(state, "stopped", {
      stopReason: reason,
      ...supersededBy === void 0 ? {} : { supersededBy },
      ...error === void 0 ? {} : { failure: error.toJSON() }
    });
    state.resolveSettled({
      status: "stopped",
      reason,
      ...supersededBy === void 0 ? {} : { supersededBy },
      ...error === void 0 ? {} : { error }
    });
  }
  __name(settleStopped, "settleStopped");
  function settleFailed(state, error) {
    if (state.isRunSettled()) return;
    state.markSettled(error);
    state.abortInFlight(error, false);
    finishRun(state, "errored", { failure: error.toJSON() });
    state.resolveSettled({ status: "errored", error });
  }
  __name(settleFailed, "settleFailed");
  function finishRun(state, status, settlement) {
    state.journal.updateRunStatus(state.runId, status, settlement);
    state.record({
      type: "run-settled",
      status,
      ...settlement?.stopReason === void 0 ? {} : { stopReason: settlement.stopReason },
      ...settlement?.supersededBy === void 0 ? {} : { supersededBy: settlement.supersededBy },
      ...settlement?.failure === void 0 ? {} : { error: settlement.failure }
    });
    state.driver.dispose?.();
  }
  __name(finishRun, "finishRun");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/engine-holes.ts
  var HOLE_PROMPT_MAX_CHARS = 4e3;
  var ELLIPSIS = "\u2026";
  var _HoleRegistry = class _HoleRegistry {
    constructor(now) {
      __publicField(this, "now", now);
      /** 站点 → 停在它下面的分支（一个站点可以同时停着好几个序号）。 */
      __publicField(this, "parked", /* @__PURE__ */ new Map());
      /** 站点 → 本世记住的补全（后到的 `hole` 直接作答、不停驻）。 */
      __publicField(this, "filled", /* @__PURE__ */ new Map());
    }
    /** 站点已补全时返回记住的代码，否则 undefined。 */
    remembered(siteId) {
      return this.filled.get(siteId)?.code;
    }
    /** 把一个分支停在站点下，返回它的 promise。 */
    park(instance, name) {
      return new Promise((resolve, reject) => {
        const list = this.parked.get(instance.siteId) ?? [];
        list.push({ instance, name, since: this.now(), resolve, reject });
        this.parked.set(instance.siteId, list);
      });
    }
    /** 这个站点是否有停驻的分支或已记住的补全（`fillHole` 的 not_waiting 判据的反面）。 */
    isWaitingOrFilled(siteId) {
      return (this.parked.get(siteId)?.length ?? 0) > 0 || this.filled.has(siteId);
    }
    /** 现在的时刻（epoch 毫秒）：`hole-filled` 的 `filledAt` 与记忆里的同一个瞬间。 */
    clock() {
      return this.now();
    }
    /** 记住补全并放行站点下的每一个分支。 */
    release(siteId, code, filledAt, filledBy) {
      this.filled.set(siteId, { code, filledAt, ...filledBy === void 0 ? {} : { filledBy } });
      const list = this.parked.get(siteId) ?? [];
      this.parked.delete(siteId);
      for (const entry of list) entry.resolve({ code });
    }
    /** run 结算：停驻的分支一律以结算错误拒绝（与 ask 的取消同姿态）；补全记忆保留。 */
    rejectAll(error) {
      const lists = [...this.parked.values()];
      this.parked.clear();
      for (const list of lists) for (const entry of list) entry.reject(error);
    }
    open() {
      const out = [];
      for (const list of this.parked.values()) {
        for (const entry of list) {
          out.push({
            siteId: entry.instance.siteId,
            ordinal: entry.instance.ordinal,
            name: entry.name,
            since: entry.since
          });
        }
      }
      return out;
    }
    filledList() {
      return [...this.filled.entries()].map(([siteId, fill]) => ({
        siteId,
        filledAt: fill.filledAt,
        ...fill.filledBy === void 0 ? {} : { filledBy: fill.filledBy }
      }));
    }
  };
  __name(_HoleRegistry, "HoleRegistry");
  var HoleRegistry = _HoleRegistry;
  function boundHolePrompt(prompt) {
    if (prompt.length <= HOLE_PROMPT_MAX_CHARS) return prompt;
    return prompt.slice(0, HOLE_PROMPT_MAX_CHARS - ELLIPSIS.length) + ELLIPSIS;
  }
  __name(boundHolePrompt, "boundHolePrompt");
  function reachHole(state, holes, siteId, name, prompt) {
    if (state.isRunSettled()) return Promise.reject(state.runError());
    const ordinal = state.nextOrdinal(siteId);
    const remembered = holes.remembered(siteId);
    if (remembered !== void 0) return Promise.resolve({ code: remembered });
    const instance = { siteId, ordinal };
    const promise = holes.park(instance, name);
    state.record({
      type: "hole-reached",
      instance,
      name,
      ...prompt === void 0 ? {} : { prompt: boundHolePrompt(prompt) }
    });
    return promise;
  }
  __name(reachHole, "reachHole");
  function fillHole(state, holes, fill, swapTables) {
    if (state.isRunSettled()) return { ok: false, reason: "settled" };
    if (!holes.isWaitingOrFilled(fill.siteId)) return { ok: false, reason: "not_waiting" };
    swapTables(fill.askSpecs, fill.sitePhases);
    state.journal.updateRunScript(state.runId, fill.script.text, fill.script.hash);
    const filledAt = holes.clock();
    state.record({
      type: "hole-filled",
      siteId: fill.siteId,
      filledAt,
      ...fill.filledBy === void 0 ? {} : { filledBy: fill.filledBy },
      phaseNames: fill.phaseNames,
      ...fill.holes === void 0 ? {} : { holes: fill.holes },
      ...fill.scriptPath === void 0 ? {} : { scriptPath: fill.scriptPath }
    });
    holes.release(fill.siteId, fill.code, filledAt, fill.filledBy);
    return { ok: true };
  }
  __name(fillHole, "fillHole");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/engine-launch.ts
  function runLaunchedEvent(launch, origin) {
    const {
      inputId,
      phaseNames,
      subagentModel,
      subagentPermissionMode,
      modelBindings,
      scriptPath,
      phaseAlongside,
      holes
    } = launch;
    return {
      type: "run-launched",
      inputId,
      ...origin.toolCallId === void 0 ? {} : { toolCallId: origin.toolCallId },
      ...origin.parentSessionId === void 0 ? {} : { parentSessionId: origin.parentSessionId },
      ...phaseNames === void 0 ? {} : { phaseNames },
      ...subagentModel === void 0 ? {} : { subagentModel },
      ...subagentPermissionMode === void 0 ? {} : { subagentPermissionMode },
      ...modelBindings === void 0 ? {} : { modelBindings },
      ...scriptPath === void 0 ? {} : { scriptPath },
      ...phaseAlongside === void 0 ? {} : { phaseAlongside },
      ...holes === void 0 ? {} : { holes }
    };
  }
  __name(runLaunchedEvent, "runLaunchedEvent");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/engine-phase-stamp.ts
  function stampBirthPhase(event, instancePhases) {
    if (event.type === "actor-created") {
      const phaseName = instancePhases.get(refToString(event.actor));
      return phaseName === void 0 ? event : { ...event, phaseName };
    }
    if (event.type === "node-queued") {
      const phaseName = instancePhases.get(refToString(event.instance));
      return phaseName === void 0 ? event : { ...event, phaseName };
    }
    if (event.type === "node-dispatched" && event.actor !== void 0) {
      const phaseName = instancePhases.get(refToString(event.instance));
      const actorPhaseName = instancePhases.get(refToString(event.actor));
      if (phaseName === void 0 && actorPhaseName === void 0) return event;
      return {
        ...event,
        ...phaseName === void 0 ? {} : { phaseName },
        ...actorPhaseName === void 0 ? {} : { actorPhaseName }
      };
    }
    if (event.type === "node-settled" && event.cached === true) {
      const phaseName = instancePhases.get(refToString(event.instance));
      return phaseName === void 0 ? event : { ...event, phaseName };
    }
    if (event.type === "hole-reached") {
      const phaseName = instancePhases.get(refToString(event.instance));
      return phaseName === void 0 ? event : { ...event, phaseName };
    }
    return event;
  }
  __name(stampBirthPhase, "stampBirthPhase");
  function enrichProviderStopPhase(error, instancePhases) {
    const details = error.providerStop;
    if (details === void 0 || details.subagent === void 0 || details.phase !== void 0) {
      return error;
    }
    const phase = instancePhases.get(details.subagent);
    if (phase === void 0) return error;
    return new WorkflowError(error.code, error.message, {
      providerStop: { ...details, phase },
      cause: error.cause
    });
  }
  __name(enrichProviderStopPhase, "enrichProviderStopPhase");

  // ../../ZCode-main/apps/zcode-cli/packages/dynamic-workflow/src/engine/engine.ts
  var _WorkflowEngine = class _WorkflowEngine {
    constructor(config) {
      __publicField(this, "runId");
      __publicField(this, "driver");
      __publicField(this, "journal");
      /**
       * 本 run 的并发上界。**可变**（见 {@link setMaxConcurrency}）：一次只改 `max_concurrency`
       * 的修订就地作用在活着的 run 上。整份替换而不是原地改字段——已记进事件与 journal 的那几份
       * caps 必须保持它们被记下时的样子（调用方递进来的那个对象同理不被回写）。
       */
      __publicField(this, "caps");
      /**
       * 每 ask 站点的静态规格。**可变**（见 {@link fillHole}）：一次补全把它整份换成有效脚本的表
       * （原表的超集）。整份替换而不是原地改，与 caps 同一条纪律。
       */
      __publicField(this, "askSpecs");
      __publicField(this, "validate");
      __publicField(this, "scheduler");
      /** 每站点执行序号计数器（ask / world-read / actor 共用，站点 id 互不相同）。 */
      __publicField(this, "ordinals", /* @__PURE__ */ new Map());
      /**
       * 阶段进入次数，按名字。**不与**
       * `ordinals` 共表：阶段名是作者的任意字符串，一个恰好叫 `report#1` 的阶段不能挪动那个
       * 站点的节点序号（site-id stability），反之亦然。
       */
      __publicField(this, "phaseOrdinals", /* @__PURE__ */ new Map());
      /**
       * 控制流当前所在的阶段名（`enterPhase` 维护；第一个标记之前为 undefined）。只在铸造点被
       * 读取——见 {@link nextOrdinal}。
       */
      __publicField(this, "currentPhase");
      /** 站点 → 词法出生阶段（见 {@link EngineConfig.sitePhases}）。可变，与 `askSpecs` 同一条路换表。 */
      __publicField(this, "sitePhases");
      /** 留白的停驻表与本世的补全记忆（engine-holes.ts）。 */
      __publicField(this, "holes");
      /**
       * 实例（`siteId@ordinal`）→ 它**出生时**的阶段名。写在铸造点、读在 {@link record} 的打戳漏斗：静态因果图按阶段拷贝
       * 站点，运行时实例必须带同一个坐标，否则一条车道上的实例会被那个站点的每一份阶段拷贝
       * 同时认领（本 bug 的形状：五个阶段各 20 个子代理，五张卡各显示 100 个）。
       */
      __publicField(this, "instancePhases", /* @__PURE__ */ new Map());
      /**
       * 本 run 内已被认领的**非空有效 actor 名** → 认领它的 actor（用于失败信息里指认前一处）。
       * 见 {@link createActor} 的查重与 replay 安全性说明。
       */
      __publicField(this, "actorNames", /* @__PURE__ */ new Map());
      /** amend-resume 的导入缓存（纯数据，缺席即本次不是修订续跑）。 */
      __publicField(this, "importedCache");
      /** world 导入队列的消费游标（第 n 次出现对第 n 条）。 */
      __publicField(this, "importedWorld");
      /**
       * 导入缓存是否已**关闭**：第一笔写入之前置真、永不重开
       * ——某个 live 子代理即将执行改写工具（driver 上报 askMutating），或一条 world.run live 执行。
       * 关闭后 world 节点与带工具的 ask 不再问导入表（哈希只比对文本，比不出工作区已被改写）；
       * 纯 ask（toolCalls 0）照常命中。resume 时从 `import-cache-closed` 事件恢复，见 {@link recoverImportClosure}。
       */
      __publicField(this, "importClosed", false);
      /**
       * replay 的结算次序闸（docs/execution-engine.md「Replaying the settle order」）：命中缓存的
       * 结算按 journal 记下的**首生结算次序**释放，而不是按准入次序——join 之后的每一个站点序号
       * 都由那个次序决定。非 resume 恒为空闸（人人放行）。
       */
      __publicField(this, "replaySettleOrder", ReplaySettleOrder.empty());
      /** resume 时从事件恢复的「崩溃前曾 live 的 ask 实例」（`siteId@ordinal`）；非 resume 为空。 */
      __publicField(this, "liveAskInstances", /* @__PURE__ */ new Set());
      /** resume 时从事件次序恢复的「准入早于关门的 ask 实例」；续跑在飞 ask 的判定据此复原。 */
      __publicField(this, "queuedBeforeImportClose", /* @__PURE__ */ new Set());
      /**
       * 本 run 已发布的报告条数与 item 字节数（REPORT_CAPS 两个 run 级上限的计数器）。resume 时从
       * journal 的 kind:"report" 行恢复——上限是 run 级的，跨 resume 必须连续计数，否则一个反复
       * resume 的 run 可以无限报告。
       */
      __publicField(this, "reportTally", { count: 0, bytes: 0 });
      /**
       * 本 run 每个**用户面产物** id 的状态。与 `reportTally`
       * 同一条恢复法：resume 时从 journal 的 `kind: "artifact"` 行重建，此后在内存里维护——
       * 上限（32 个 id / 每 id 16 版）与版本号都是 run 级的事实，跨 resume 必须连续，否则一个
       * 反复 resume 的 run 可以无限发布。
       *
       * ⚠ 术语：artifact = 用户面产物，不是 `RunSettlement.artifact`（顶层返回值）。
       */
      __publicField(this, "artifacts", /* @__PURE__ */ new Map());
      /** 累计 token 用量（观察面：只记账、只广播，永远不会让 run 失败）。 */
      __publicField(this, "spentTokens", 0);
      __publicField(this, "runSettled", false);
      __publicField(this, "runFailure");
      __publicField(this, "settledDeferred");
      /** 兄弟模块自由函数读写私有状态的接缝（构造函数里用箭头闭包装配，见 engine-state.ts）。 */
      __publicField(this, "state");
      this.runId = config.runId;
      this.driver = config.driver;
      this.journal = config.driver.journal;
      this.caps = { maxConcurrency: config.caps.maxConcurrency };
      this.askSpecs = config.askSpecs;
      this.validate = config.validate;
      this.sitePhases = config.sitePhases ?? /* @__PURE__ */ new Map();
      this.holes = new HoleRegistry(config.now ?? Date.now);
      this.importedCache = config.importedCache;
      this.importedWorld = new ImportedWorldQueue(config.importedCache?.world ?? /* @__PURE__ */ new Map());
      let resolve;
      const promise = new Promise((res) => {
        resolve = res;
      });
      this.settledDeferred = { promise, resolve };
      this.state = {
        runId: this.runId,
        driver: this.driver,
        journal: this.journal,
        artifacts: this.artifacts,
        importedCache: this.importedCache,
        importedWorld: this.importedWorld,
        isRunSettled: /* @__PURE__ */ __name(() => this.runSettled, "isRunSettled"),
        runError: /* @__PURE__ */ __name(() => this.runError(), "runError"),
        failRun: /* @__PURE__ */ __name((error) => this.failRun(error), "failRun"),
        record: /* @__PURE__ */ __name((event) => this.record(event), "record"),
        nextOrdinal: /* @__PURE__ */ __name((siteId) => this.nextOrdinal(siteId), "nextOrdinal"),
        holdForReplay: /* @__PURE__ */ __name((instance, release) => {
          this.replaySettleOrder.hold(instance, release);
        }, "holdForReplay"),
        reportTally: /* @__PURE__ */ __name(() => this.reportTally, "reportTally"),
        countReport: /* @__PURE__ */ __name((bytes) => {
          this.reportTally = {
            count: this.reportTally.count + 1,
            bytes: this.reportTally.bytes + bytes
          };
        }, "countReport"),
        importClosed: /* @__PURE__ */ __name(() => this.importClosed, "importClosed"),
        closeImport: /* @__PURE__ */ __name(() => {
          this.importClosed = true;
        }, "closeImport"),
        markSettled: /* @__PURE__ */ __name((failure) => {
          this.runSettled = true;
          if (failure !== void 0) this.runFailure = failure;
          this.replaySettleOrder.open();
        }, "markSettled"),
        abortInFlight: /* @__PURE__ */ __name((error, emitCancelled) => {
          this.scheduler.abortInFlight(error, emitCancelled);
          this.holes.rejectAll(error);
        }, "abortInFlight"),
        resolveSettled: /* @__PURE__ */ __name((settlement) => this.settledDeferred.resolve(settlement), "resolveSettled")
      };
      const readCaps = /* @__PURE__ */ __name(() => this.caps, "readCaps");
      const host = {
        runId: this.runId,
        get caps() {
          return readCaps();
        },
        driver: this.driver,
        validate: this.validate,
        nextOrdinal: /* @__PURE__ */ __name((siteId) => this.nextOrdinal(siteId), "nextOrdinal"),
        holdForReplay: /* @__PURE__ */ __name((instance, release) => {
          this.replaySettleOrder.hold(instance, release);
        }, "holdForReplay"),
        record: /* @__PURE__ */ __name((event) => this.record(event), "record"),
        isRunSettled: /* @__PURE__ */ __name(() => this.runSettled, "isRunSettled"),
        runError: /* @__PURE__ */ __name(() => this.runError(), "runError"),
        failRun: /* @__PURE__ */ __name((error) => this.failRun(error), "failRun"),
        importCacheClosed: /* @__PURE__ */ __name(() => this.importClosed, "importCacheClosed"),
        wasLiveBeforeResume: /* @__PURE__ */ __name((instance) => this.liveAskInstances.has(refToString(instance)), "wasLiveBeforeResume"),
        wasQueuedBeforeImportClose: /* @__PURE__ */ __name((instance) => this.queuedBeforeImportClose.has(refToString(instance)), "wasQueuedBeforeImportClose")
      };
      this.scheduler = new AskScheduler(host);
      const existing = this.journal.getRun(this.runId);
      if (existing === void 0) {
        const inherited = config.inheritedTokens ?? 0;
        this.spentTokens = Number.isFinite(inherited) ? Math.max(0, Math.floor(inherited)) : 0;
        this.journal.createRun({
          runId: this.runId,
          caps: this.caps,
          spentTokens: this.spentTokens,
          status: "running",
          // 元数据只在创建 run 时落库；保存传入的脚本与会话字段，供恢复和 script_hash 校验使用。
          // name 是后来加入同一条路的展示元数据。缺省的字段保持缺席（不落成 undefined 键）。
          ...config.scriptText === void 0 ? {} : { scriptText: config.scriptText },
          // 实参与 scriptText 同一条元数据路，且同样只在建 run 时写一次：resume 从这里读回
          // 重放，绝不接受调用方给的新实参。
          ...config.args === void 0 ? {} : { args: config.args },
          ...config.name === void 0 ? {} : { name: config.name },
          ...config.scriptHash === void 0 ? {} : { scriptHash: config.scriptHash },
          ...config.parentSessionId === void 0 ? {} : { parentSessionId: config.parentSessionId },
          ...config.cwd === void 0 ? {} : { cwd: config.cwd },
          ...config.toolCallId === void 0 ? {} : { toolCallId: config.toolCallId },
          // lineage 也只在建 run 时写一次：修订是 supersede（新 run），前驱行零触碰。
          ...config.resumedFrom === void 0 ? {} : { resumedFrom: config.resumedFrom }
        });
      } else {
        if (existing.scriptHash !== void 0 && config.scriptHash !== void 0 && existing.scriptHash !== config.scriptHash) {
          throw new WorkflowError(
            "ScriptHashMismatch",
            `Cannot resume run ${this.runId}: its script changed (recorded hash ${existing.scriptHash}, got ${config.scriptHash}). Resume needs the byte-identical script; to run a revised script, start a new run with resume_from instead.`,
            { mismatch: { expected: existing.scriptHash, got: config.scriptHash } }
          );
        }
        ({ replaySettleOrder: this.replaySettleOrder, reportTally: this.reportTally } = restoreFromJournal(this.state, this.scheduler));
        this.spentTokens = existing.spentTokens;
        if (this.importedCache !== void 0) {
          const recovered = recoverImportClosure(this.journal, this.runId);
          this.liveAskInstances = recovered.live;
          this.queuedBeforeImportClose = recovered.queuedBeforeClose;
          this.importClosed = recovered.closed;
        }
        this.journal.updateRunStatus(this.runId, "running");
      }
      this.record({ type: "run-started", runId: this.runId, caps: this.caps });
      if (existing === void 0 && config.launch !== void 0) {
        this.record(runLaunchedEvent(config.launch, config));
      }
      if (this.spentTokens > 0) this.record({ type: "usage-updated", spentTokens: this.spentTokens });
    }
    /** run 结算 promise：完成/失败/取消时兑现。 */
    get settled() {
      return this.settledDeferred.promise;
    }
    /** 当前 run 状态。 */
    status() {
      return this.journal.getRun(this.runId)?.status ?? "running";
    }
    // ——————————————————————————————— Boundary A ———————————————————————————————
    /**
     * 建一个 actor。**有效名**是 `normalizePersona` 之后的 `spec.name`（persona.name 压过 name
     * 实参），非空即必须在本 run 内唯一——重名让整个 run 大声失败（DuplicateActorName）。
     *
     * 为什么是 run 级失败而不是改名/警告：具名 actor 是 amend-resume 缓存导入的身份键，而任何
     * run 都是未来修订的潜在前驱，前驱里重名会让导入匹配歧义。
     * 规则因此对所有 run 生效，不只是修订 run。匿名（缺席或空串）从不查、任意多个合法——
     * 代价已裁决：没有缓存资格。
     *
     * **replay 安全**：查重表是纯内存的、随引擎实例而生。逐字节相同脚本的 resume 在一个**全新**
     * 引擎实例里从头重跑同一串 createActor 调用，每个名字因此恰好被登记一次，不会把上一轮
     * 自己的登记当成重名。这也是它不能落 journal 的理由——落库的表会在 resume 时与自己撞车。
     *
     * **导入缓存的附着也在这里**（amend-resume）：按有效名查 `importedCache.actors`，
     * persona 规范化后一致才把候选挂到调度器的 actor 态上。比对之所以在**运行期**而不是
     * 提交时静态比对两份脚本：名字与 persona 都是运行期值（`agent()` 的实参可以是动态表达式），
     * 静态比对是第二份真相，恰是本包处处要防的（判定见 imported-cache.ts 的 `matchImportedActor`）。
     */
    createActor(siteId, name, persona) {
      this.assertRunning("createActor");
      const ordinal = this.nextOrdinal(siteId);
      const ref = { siteId, ordinal };
      const id = refToString(ref);
      const spec = normalizePersona(name, persona);
      const effectiveName = spec.name;
      if (effectiveName !== void 0 && effectiveName !== "") {
        const claimed = this.actorNames.get(effectiveName);
        if (claimed !== void 0) {
          const err = new WorkflowError(
            "DuplicateActorName",
            `Subagent name "${effectiveName}" is used twice in this run (${claimed} and ${id}). A named subagent is the identity an amended re-run matches its cache by, so names must be unique; give this one its own name or drop the name to make it anonymous.`
          );
          this.failRun(err);
          throw err;
        }
        this.actorNames.set(effectiveName, id);
      }
      this.scheduler.registerActor(ref, id, name, spec, matchImportedActor(this.importedCache, spec));
      const existing = this.journal.getActor(this.runId, siteId, ordinal);
      this.journal.putActor({
        runId: this.runId,
        siteId,
        ordinal,
        name,
        persona: spec,
        sessionId: existing?.sessionId,
        resolvedModel: existing?.resolvedModel
      });
      this.record({ type: "actor-created", actor: ref, name, persona: spec });
      return id;
    }
    ask(siteId, actorId, instructions) {
      if (this.runSettled) return Promise.reject(this.runError());
      if (!this.scheduler.hasActor(actorId)) {
        const err = new WorkflowError("UnknownActor", `Unknown subagent handle: ${actorId}.`);
        this.failRun(err);
        return Promise.reject(err);
      }
      const spec = this.askSpecs.get(siteId);
      if (spec === void 0) {
        const err = new WorkflowError(
          "MissingAskSpec",
          `Ask site ${siteId} has no spec: askSpecs is incomplete (compile output mismatch).`
        );
        this.failRun(err);
        return Promise.reject(err);
      }
      return this.scheduler.admitAsk(siteId, actorId, instructions, spec);
    }
    /** world 节点（world-read / world-run）：方法体在 engine-world.ts 的 readWorld。 */
    worldRead(siteId, op, args) {
      return readWorld(this.state, siteId, op, args);
    }
    log(message) {
      if (this.runSettled) return;
      this.record({ type: "log", message });
    }
    /**
     * 控制流经过了一个 `phase("…")` 标记（Boundary A 的 `enterPhase`）。与 `log` 同一副姿态：同步、无返回值、
     * 结算后 no-op、**不落 journal 行**——标记不是站点，没有 `dwf_node` 可写。
     *
     * ordinal 按名字计数，用自己的表（{@link phaseOrdinals}）：阶段名与 site id 互不消耗对方的
     * 序号（site-id stability）。
     * **每次求值都发**：`for { phase("B"); ask }` 的第二轮就是 B 的第二次进入；若「与当前阶段
     * 同名即不发」，单阶段循环体的轮次就丢了。resume 重跑会把前缀再发一遍——这里刻意不去重
     * （去重需要引擎读事件表），reducer 以 `rounds = max(rounds, ordinal)` 单调归约。
     */
    enterPhase(name) {
      if (this.runSettled) return;
      const trimmed = name.trim();
      if (trimmed.length === 0) return;
      const ordinal = (this.phaseOrdinals.get(trimmed) ?? 0) + 1;
      this.phaseOrdinals.set(trimmed, ordinal);
      this.currentPhase = trimmed;
      this.record({ type: "phase-entered", name: trimmed, ordinal });
    }
    /** 脚本到达一处未补全的留白（Boundary A 的 `hole`）：方法体在 engine-holes.ts 的 reachHole。 */
    hole(siteId, name, prompt) {
      return reachHole(this.state, this.holes, siteId, name, prompt);
    }
    /**
     * 主代理补全一处留白（docs/execution-engine.md「The engine's part」）：一个同步步骤里换掉规格表
     * 与阶段表、写回有效脚本、记 `hole-filled`、记住代码、放行停驻的每一个分支。方法体在
     * engine-holes.ts 的 fillHole；两条 no-op（`not_waiting` / `settled`）不写不发。
     */
    fillHole(fill) {
      return fillHole(this.state, this.holes, fill, (askSpecs, sitePhases) => {
        this.askSpecs = askSpecs;
        this.sitePhases = sitePhases;
      });
    }
    /** 仍在等的留白（快照投影：事件之外还要问引擎，进程死过之后没有 promise 在等）。 */
    openHoles() {
      return this.holes.open();
    }
    /** 本世已补全的留白。 */
    filledHoles() {
      return this.holes.filledList();
    }
    /** 发布一条中间结果（Boundary A 的 `report`）：方法体在 engine-report.ts 的 publishReport。 */
    report(siteId, item, artifactId) {
      publishReport(this.state, siteId, item, artifactId);
    }
    /**
     * 发布一个**内容产物**（`artifact.file` / `artifact.markdown`）：方法体在 engine-artifacts.ts
     * 的 publishContentArtifact。
     */
    publishArtifact(siteId, op, args) {
      return publishContentArtifact(this.state, siteId, op, args);
    }
    /**
     * 声明一个**预置产物**（`artifact.chart` / `table` / `metrics` / `board`）：方法体在
     * engine-artifacts.ts 的 declarePresetArtifact。
     */
    declareArtifact(siteId, op, args) {
      declarePresetArtifact(this.state, siteId, op, args);
    }
    // —————————————————————— 外部驱动的 run 生命周期 ——————————————————————
    /** 沙箱脚本成功返回顶层 artifact，结算为 completed：方法体在 engine-settlement.ts 的 settleCompleted。 */
    complete(artifact) {
      settleCompleted(this.state, artifact);
    }
    /**
     * 外部停止：中止在飞 ask（deferred 以 Cancelled reject、补发 node-settled(cancelled)），run
     * 结算 `stopped(reason)`；已完结的 journal 条目保留（可 resume）。四个 reason 走同一条路：
     * `user` / `model`（cancel 入口传进来的 initiator）、`interrupted`（harness 的沙箱故障）、
     * `provider`（driver 经 `stopRun` 报上来的确定性模型侧错误）。`error` 只对后两者在场。
     * first-wins：已结算即忽略。方法体在 engine-settlement.ts 的 settleStopped。
     */
    stop(reason, error, supersededBy) {
      settleStopped(this.state, reason, error, supersededBy);
    }
    /**
     * 外部失败：complete 的失败对偶。harness 用它把子进程 `{complete, ok:false, error}`（脚本抛出）、
     * 子进程崩溃、墙钟超时、NDJSON 解析失败等都归为 run 失败，
     * 而非伪装成 cancel——否则 journal 的 failure 与调用方看到的结果会分叉。
     * 走内部 failRun 路径：first-wins（对 complete/cancel/内部失败）、driver 侧取消在飞 ask、
     * journal run 状态 failed + failure_json、发 run-failure 事件、settled 结算为 failed。
     */
    fail(error) {
      this.failRun(error);
    }
    /**
     * 就地改本 run **自己**的并发上界（docs/dynamic-workflow/concurrency.md「Two bounds on a run」）。
     * 一次只带 `max_concurrency` 的修订作用在活着的 run 上：同一个 runId、不铸后继、不 supersede、
     * 在飞 ask 一个不丢——这正是它与 AmendWorkflow 的全部差别，也是它存在的唯一理由。
     *
     * 返回**这次是否真的改了**。两条 no-op 都返回 false 且不写库、不发事件：run 已结算（宿主的
     * 存活判定与本调用之间的竞态——调用方据此回落到一次真正的 amend），以及新值与当前值相同。
     *
     * 改成时三件事在同一个同步步骤里发生，于是三者永远一致：整份换掉 caps（调度器现读）、写
     * `dwf_run.caps_max_concurrency`（resume 沿用行里的 caps，不落库就恢复成旧上界）、记一条
     * `run-caps-changed`。**抬高**还多一次 `pumpAll()`——上界是派发前现读的，但没有别的事件会
     * 触发重扫，排队的 ask 否则要等到下一次结算。调低不召回在飞 ask：它们照常跑完，上界只管
     * 「还能不能再放一个」。
     *
     * 钳到 `[1, 天花板]` 归调用方——天花板是宿主事实（机器核数），引擎既看不见也不该看见。
     * 这里只做落库归一，与构造函数里的 `inheritedTokens` 同一条纪律：这个数要落
     * `caps_max_concurrency`（integer not null），一个 NaN 会同时毒化列值与派发判据。
     */
    setMaxConcurrency(maxConcurrency) {
      if (this.runSettled) return false;
      if (!Number.isFinite(maxConcurrency)) return false;
      const previous = this.caps;
      const next = Math.max(1, Math.floor(maxConcurrency));
      if (next === previous.maxConcurrency) return false;
      const caps = { maxConcurrency: next };
      this.caps = caps;
      this.journal.updateRunCaps(this.runId, caps);
      this.record({ type: "run-caps-changed", runId: this.runId, caps, previous });
      if (next > previous.maxConcurrency) this.scheduler.pumpAll();
      return true;
    }
    // ——————————————————————————— Boundary B（向上回报）———————————————————————————
    askSubmitAttempted(instance, payload) {
      this.scheduler.submitAttempted(instance, payload);
    }
    askTurnEnded(instance, finalText) {
      this.scheduler.turnEnded(instance, finalText);
    }
    /**
     * ask 内进度观察：**纯透传**，
     * 与 askStats → usage-updated 同一副姿态——不改调度、不进 inputHash、replay 不比对它。
     * 与用量同理，结算后到达的迟到观察只丢事件（这里没有账要入），不给已完结的 run 长尾巴。
     */
    askProgress(instance, progress) {
      if (this.runSettled) return;
      this.record({ type: "node-progress", instance, ...progress });
    }
    askStats(instance, stats) {
      this.scheduler.noteStats(instance, stats);
      this.spentTokens += stats.tokens;
      this.journal.updateRunUsage(this.runId, this.spentTokens);
      if (this.runSettled) return;
      this.record({ type: "usage-updated", spentTokens: this.spentTokens });
    }
    askFailed(instance, error) {
      this.scheduler.failed(instance, error);
    }
    /**
     * 确定性的模型侧错误：不结算节点，
     * 整个 run 以 `stopped(provider)` 停下。run 已结算后到达的调用被 `stop` 的 first-wins 忽略。
     */
    stopRun(error) {
      this.stop("provider", enrichProviderStopPhase(error, this.instancePhases));
    }
    /** run 级停滞观察：只 record；run 已结算后到达的调用忽略。 */
    runStalled(info) {
      if (this.runSettled) return;
      this.record({ type: "run-stalled", ...info });
    }
    // 自适应并发的三个纯观察：引擎只 record，
    // 不据它们做任何决策。run 已结算或节点已结算后到达的调用被忽略——那是迟到的观察，
    // 落 journal 只会让一个已完结的 run 长出尾巴。
    askWaiting(instance, info) {
      if (this.runSettled || !this.scheduler.isLive(instance)) return;
      this.record({ type: "node-waiting", instance, ...info });
    }
    askExecuting(instance) {
      if (this.runSettled || !this.scheduler.isLive(instance)) return;
      this.record({ type: "node-executing", instance });
    }
    /**
     * 唯一一个引擎据以做决策的 driver 观察：在飞 ask 的子代理
     * 即将改写工作区 ⇒ 关导入缓存。迟到的观察（run 或节点已结算）忽略，与上面两个同一姿态。
     */
    askMutating(instance) {
      if (this.runSettled || !this.scheduler.isLive(instance)) return;
      closeImportCache(this.state, instance, "mutating-tool", this.scheduler.liveActorName(instance));
    }
    concurrencyChanged(change) {
      if (this.runSettled) return;
      this.record({ type: "concurrency-changed", ...change });
    }
    // ——————————————————————————————— 内部 ———————————————————————————————
    /** run 级失败（first-wins）：方法体在 engine-settlement.ts 的 settleFailed。 */
    failRun(error) {
      settleFailed(this.state, error);
    }
    /**
     * 站点序号的**唯一**铸造点（createActor / admitAsk 第一行 / worldRead / report / artifact 全
     * 同步经它），因此也是实例出生阶段的唯一记录点：这一行总在脚本调用的同一个 tick 里执行，
     * 而事件未必——ask 的 `node-queued` 会被 hold 规则推迟到下一个标记之后才发出。
     */
    nextOrdinal(siteId) {
      const next = (this.ordinals.get(siteId) ?? 0) + 1;
      this.ordinals.set(siteId, next);
      const phase = this.sitePhases.get(siteId) ?? this.currentPhase;
      if (phase !== void 0) this.instancePhases.set(refToString({ siteId, ordinal: next }), phase);
      return next;
    }
    assertRunning(op) {
      if (this.runSettled)
        throw this.runFailure ?? new WorkflowError("Cancelled", `Run already settled; ${op} is no longer accepted.`);
    }
    runError() {
      return this.runFailure ?? new WorkflowError("Cancelled", "Run already settled.");
    }
    /**
     * 每个事件既落 journal（dwf_event，持久）又经 driver.emit 扇出（Boundary C，实时）。
     *
     * 所有事件的唯一漏斗，出生阶段也就在这里补上（{@link stampBirthPhase}）。两条路线拿到的
     * 必须是**同一个对象**：bootstrap 的 `createJournalSequenceCapture` 按引用相等核对序号。
     */
    record(event) {
      const stamped = stampBirthPhase(event, this.instancePhases);
      this.journal.appendEvent(this.runId, stamped);
      this.driver.emit(stamped);
    }
  };
  __name(_WorkflowEngine, "WorkflowEngine");
  var WorkflowEngine = _WorkflowEngine;

  // src/host.ts
  function startRun(options) {
    const box = {};
    const driver = options.makeDriver(makeSink((method, args) => {
      const engine2 = box.engine;
      if (engine2 === void 0) throw new Error(`sink.${method} arrived before the engine was built`);
      return engine2[method](...args);
    }));
    const engine = new WorkflowEngine({
      askSpecs: options.askSpecs,
      caps: options.caps,
      cwd: options.cwd,
      driver,
      runId: options.runId,
      validate: options.validate,
      ...options.args === void 0 ? {} : { args: options.args },
      ...options.name === void 0 ? {} : { name: options.name },
      ...options.parentSessionId === void 0 ? {} : { parentSessionId: options.parentSessionId },
      ...options.resumedFrom === void 0 ? {} : { resumedFrom: options.resumedFrom },
      ...options.importedCache === void 0 ? {} : { importedCache: options.importedCache },
      ...options.scriptHash === void 0 ? {} : { scriptHash: options.scriptHash },
      ...options.scriptText === void 0 ? {} : { scriptText: options.scriptText },
      ...options.sitePhases === void 0 ? {} : { sitePhases: options.sitePhases }
    });
    box.engine = engine;
    options.bindControl?.(engine);
    return { engine, settled: bridge(options, engine) };
  }
  __name(startRun, "startRun");
  function makeSink(forward) {
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
      "runStalled"
    ];
    const sink = {};
    for (const method of methods) {
      sink[method] = (...args) => forward(method, args);
    }
    return sink;
  }
  __name(makeSink, "makeSink");
  function bridge(options, engine) {
    const { endpoint, runId, signal, timeoutMs } = options;
    const actorMap = /* @__PURE__ */ new Map();
    let finalized = false;
    let timer;
    let unbind;
    return new Promise((resolve) => {
      const finalize = /* @__PURE__ */ __name((settlement) => {
        if (finalized) return;
        finalized = true;
        if (timer !== void 0) clearTimeout(timer);
        unbind?.();
        endpoint.close();
        resolve(settlement);
      }, "finalize");
      const failRun = /* @__PURE__ */ __name((error) => {
        if (finalized) return;
        engine.fail(error);
      }, "failRun");
      const interruptRun = /* @__PURE__ */ __name((message, cause) => {
        if (finalized) return;
        engine.stop(
          "interrupted",
          new WorkflowError("Interrupted", message, cause === void 0 ? void 0 : { cause })
        );
      }, "interruptRun");
      const onAbort = /* @__PURE__ */ __name(() => {
        if (finalized) return;
        const reason = signal?.reason;
        const supersededBy = readSupersededBy(reason);
        if (supersededBy !== void 0) {
          engine.stop("superseded", void 0, supersededBy);
          return;
        }
        if (reason === "interrupted") {
          engine.stop(
            "interrupted",
            new WorkflowError(
              "Interrupted",
              `dynamic workflow run ${runId} was interrupted: the owning session closed before the run settled`
            )
          );
          return;
        }
        engine.stop(reason === "model" ? "model" : "user");
      }, "onAbort");
      void engine.settled.then(finalize);
      if (signal !== void 0) {
        if (signal.aborted) {
          onAbort();
        } else {
          signal.addEventListener("abort", onAbort, { once: true });
          unbind = /* @__PURE__ */ __name(() => signal.removeEventListener("abort", onAbort), "unbind");
        }
      }
      if (timeoutMs !== void 0) {
        timer = setTimeout(() => {
          interruptRun(`The workflow sandbox exceeded its wall-clock timeout of ${timeoutMs}ms`);
        }, timeoutMs);
        timer.unref?.();
      }
      const respond = /* @__PURE__ */ __name((message, ok, value, error) => {
        const response = {
          id: message.id,
          kind: "response",
          ok,
          ...ok ? { value } : { error: toWireError(error) }
        };
        endpoint.send(JSON.stringify(response));
      }, "respond");
      const handleRequest = /* @__PURE__ */ __name((message) => {
        let promise;
        if (message.type === "ask") {
          const actorId = actorMap.get(message.actor ?? "");
          if (actorId === void 0) {
            respond(
              message,
              false,
              void 0,
              new WorkflowError("UnknownActor", `Unknown subagent handle: ${message.actor}`)
            );
            return;
          }
          promise = engine.ask(message.siteId, actorId, message.instructions ?? "");
        } else if (message.type === "publish-artifact") {
          const op = message.artifactOp;
          if (op === void 0) {
            respond(
              message,
              false,
              void 0,
              new WorkflowError(
                "DriverError",
                `publish-artifact request is missing artifactOp (site ${message.siteId})`
              )
            );
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
          (cause) => {
            respond(message, false, void 0, cause instanceof WorkflowError ? cause : new WorkflowError(
              "DriverError",
              cause instanceof Error ? cause.message : String(cause),
              { cause }
            ));
          }
        );
      }, "handleRequest");
      const handleChildMessage = /* @__PURE__ */ __name((message) => {
        switch (message.kind) {
          case "create-actor": {
            try {
              const actorId = engine.createActor(
                message.siteId,
                message.name,
                message.persona
              );
              actorMap.set(message.localId, actorId);
            } catch (cause) {
              failRun(cause instanceof WorkflowError ? cause : new WorkflowError(
                "DriverError",
                `createActor failed at site ${message.siteId}`,
                { cause }
              ));
            }
            return;
          }
          case "event":
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
              failRun(new WorkflowError(
                "DriverError",
                error?.message ?? "The workflow script threw an error",
                { cause: error?.stack ?? error?.name }
              ));
            }
            return;
          case "request":
            handleRequest(message);
            return;
          default:
            return;
        }
      }, "handleChildMessage");
      endpoint.onLine((line) => {
        if (finalized) return;
        if (line.trim().length === 0) return;
        let message;
        try {
          message = JSON.parse(line);
        } catch (cause) {
          interruptRun(
            `The workflow sandbox emitted a line that is not valid NDJSON: ${line}`,
            cause
          );
          return;
        }
        handleChildMessage(message);
      });
      void endpoint.done.then(
        () => {
          setTimeout(() => {
            interruptRun("The workflow sandbox closed before the run settled");
          }, 0);
        },
        (error) => {
          setTimeout(() => {
            interruptRun(
              `The workflow sandbox failed: ${error instanceof Error ? error.message : String(error)}`,
              error
            );
          }, 0);
        }
      );
    });
  }
  __name(bridge, "bridge");
  function toWireError(error) {
    if (error === void 0) return { message: "unknown error", name: "Error" };
    const wire = {
      code: error.code,
      message: error.message,
      name: error.name
    };
    if (error.violations !== void 0) wire.violations = error.violations;
    if (error.finalText !== void 0) wire.finalText = error.finalText;
    return wire;
  }
  __name(toWireError, "toWireError");
  function readSupersededBy(reason) {
    if (typeof reason !== "object" || reason === null) return void 0;
    const value = reason.superseded;
    return typeof value === "string" && value.length > 0 ? value : void 0;
  }
  __name(readSupersededBy, "readSupersededBy");

  // src/android-runner.ts
  function importedCacheFromJournal(journalJson) {
    const data = JSON.parse(journalJson);
    const actors = (data.actors ?? []).flatMap(([, entries]) => entries.map(([, record]) => record));
    const nodes = (data.nodes ?? []).flatMap(([, entries]) => entries.map(([, record]) => record));
    const byName = /* @__PURE__ */ new Map();
    for (const actor of actors) {
      const name = actor.name;
      if (name === void 0 || name === "") continue;
      const bucket = byName.get(name);
      if (bucket === void 0) byName.set(name, [actor]);
      else bucket.push(actor);
    }
    const cache = /* @__PURE__ */ new Map();
    for (const [name, candidates] of byName) {
      if (candidates.length !== 1) continue;
      const actor = candidates[0];
      const asks = nodes.filter(
        (node) => node.kind === "ask" && node.actorSiteId === actor.siteId && node.actorOrdinal === actor.ordinal
      );
      const entries = [];
      for (let seq = 0; ; seq += 1) {
        const node = asks.find((candidate) => candidate.actorSeq === seq);
        if (node === void 0 || node.status !== "completed") break;
        entries.push({
          inputHash: node.inputHash,
          result: node.result,
          ...node.stats === void 0 ? {} : { stats: node.stats },
          messageBoundary: node.messageBoundary ?? 0
        });
      }
      if (entries.length === 0) continue;
      cache.set(name, {
        persona: actor.persona ?? { name },
        entries,
        transcriptSourceSessionId: actor.sessionId ?? "",
        ...actor.resolvedModel === void 0 ? {} : { resolvedModel: actor.resolvedModel }
      });
    }
    const world = /* @__PURE__ */ new Map();
    for (const node of nodes) {
      if (node.status !== "completed") continue;
      if (node.kind !== "world-read" && node.kind !== "world-run") continue;
      const queue = world.get(node.inputHash) ?? [];
      queue.push({ inputHash: node.inputHash, kind: node.kind, result: node.result });
      world.set(node.inputHash, queue);
    }
    if (cache.size === 0 && world.size === 0) return void 0;
    return { actors: cache, world };
  }
  __name(importedCacheFromJournal, "importedCacheFromJournal");
  var _WorkflowRunner = class _WorkflowRunner {
    constructor(env) {
      __publicField(this, "env", env);
      __publicField(this, "runs", /* @__PURE__ */ new Map());
      if (typeof structuredClone === "undefined") {
        globalThis.structuredClone = (value) => JSON.parse(JSON.stringify(value));
      }
    }
    async start(configJson) {
      const config = JSON.parse(configJson);
      if (!config.runId || !config.lowered || !config.cwd) {
        throw new Error("runner: runId, lowered and cwd are required");
      }
      const importedCache = config.importedJournal === void 0 ? void 0 : importedCacheFromJournal(config.importedJournal);
      const journal = config.journalJson ? PortableJournal.fromJSON(JSON.parse(config.journalJson), this.env.bridge) : new PortableJournal(this.env.bridge);
      const driver = new BridgeDriver(
        config.runId,
        journal,
        this.env.bridge,
        config.conversationId ?? 0
      );
      const endpoint = new DeviceEndpoint();
      const realm = await this.env.createRealm();
      endpoint.bindClose(() => realm.close?.());
      applyRealmSanitizer(realm);
      const payload = {
        lowered: config.lowered,
        ...config.args === void 0 ? {} : { args: config.args }
      };
      const handle = startRun({
        runId: config.runId,
        endpoint,
        makeDriver: /* @__PURE__ */ __name((sink) => {
          driver.attach(sink);
          return driver;
        }, "makeDriver"),
        caps: config.caps ?? { maxConcurrency: 4 },
        askSpecs: new Map(config.askSpecs ?? []),
        sitePhases: new Map(config.sitePhases ?? []),
        validate: /* @__PURE__ */ __name((schema, value) => validate(schema, value), "validate"),
        ...config.scriptText === void 0 ? {} : { scriptText: config.scriptText },
        ...config.args === void 0 ? {} : { args: config.args },
        ...config.scriptHash === void 0 ? {} : { scriptHash: config.scriptHash },
        ...config.resumedFrom === void 0 ? {} : { resumedFrom: config.resumedFrom },
        ...importedCache === void 0 ? {} : { importedCache },
        ...config.timeoutMs === void 0 ? {} : { timeoutMs: config.timeoutMs },
        cwd: config.cwd
      });
      if (!payload.lowered) throw new Error("runner: lowered script is empty");
      let hostToCell;
      const cellPort = {
        post: /* @__PURE__ */ __name((line) => endpoint.receiveFromCell(line), "post"),
        onLine: /* @__PURE__ */ __name((handler) => {
          hostToCell = handler;
          endpoint.bindCell((line) => hostToCell?.(line));
        }, "onLine")
      };
      const cellDone = startCell({
        realm,
        port: cellPort,
        payload,
        streamsBootstrap: CELL_STREAMS_BOOTSTRAP
      });
      endpoint.bindCell((line) => hostToCell?.(line));
      cellDone.then(
        () => endpoint.finish(),
        (error) => endpoint.fail(error)
      );
      this.runs.set(config.runId, { driver, endpoint, engine: handle.engine });
      void handle.settled.then((settlement) => {
        try {
          this.env.bridge.settled(config.runId, JSON.stringify(settlement));
        } finally {
          try {
            this.env.bridge.saveJournal(config.runId, JSON.stringify(journal.toJSON()));
          } catch {
          }
          endpoint.close();
          this.runs.delete(config.runId);
        }
      });
      return { runId: config.runId };
    }
    askResult(requestId, resultJson) {
      this.driverForRequest(requestId)?.askResult(requestId, resultJson);
    }
    worldResult(requestId, ok, payloadJson, error) {
      this.driverForRequest(requestId)?.worldResult(requestId, ok, payloadJson, error);
    }
    artifactResult(requestId, ok, payloadJson, error) {
      this.driverForRequest(requestId)?.artifactResult(requestId, ok, payloadJson, error);
    }
    fillHole(runId, fillJson) {
      const run = this.runs.get(runId);
      if (run === void 0) return { ok: false, reason: "not_running" };
      const raw = JSON.parse(fillJson);
      const engine = run.engine;
      return engine.fillHole({
        ...raw,
        askSpecs: new Map(raw.askSpecs ?? []),
        sitePhases: new Map(raw.sitePhases ?? [])
      });
    }
    openHoles(runId) {
      const run = this.runs.get(runId);
      if (run === void 0) return [];
      const engine = run.engine;
      return engine.openHoles();
    }
    markMutating(runId, siteId, ordinal) {
      const run = this.runs.get(runId);
      if (run === void 0) return false;
      const engine = run.engine;
      engine.askMutating({ siteId, ordinal });
      return true;
    }
    cancel(runId) {
      const run = this.runs.get(runId);
      run?.endpoint.close();
      this.runs.delete(runId);
    }
    stop(runId, reason, supersededBy) {
      const run = this.runs.get(runId);
      if (run === void 0) return false;
      const engine = run.engine;
      engine.stop(reason, void 0, supersededBy);
      return true;
    }
    driverForRequest(requestId) {
      for (const run of this.runs.values()) {
        if (run.driver.owns(requestId)) return run.driver;
      }
      return void 0;
    }
  };
  __name(_WorkflowRunner, "WorkflowRunner");
  var WorkflowRunner = _WorkflowRunner;
  var _DeviceEndpoint = class _DeviceEndpoint {
    constructor() {
      __publicField(this, "fromCell");
      __publicField(this, "fromCellBuffer", []);
      __publicField(this, "toCell");
      __publicField(this, "toCellBuffer", []);
      __publicField(this, "closeCell");
      __publicField(this, "closed", false);
      __publicField(this, "resolveDone");
      __publicField(this, "rejectDone");
      __publicField(this, "done");
      this.done = new Promise((resolve, reject) => {
        this.resolveDone = resolve;
        this.rejectDone = reject;
      });
      void this.done.catch(() => void 0);
    }
    bindCell(send) {
      this.toCell = send;
      const buffered = this.toCellBuffer;
      this.toCellBuffer = [];
      buffered.forEach((line) => send(line));
    }
    bindClose(close) {
      this.closeCell = close;
    }
    send(line) {
      if (this.closed) return;
      const target = this.toCell;
      if (target === void 0) {
        this.toCellBuffer.push(line);
      } else {
        target(line);
      }
    }
    onLine(handler) {
      this.fromCell = handler;
      const buffered = this.fromCellBuffer;
      this.fromCellBuffer = [];
      buffered.forEach((line) => handler(line));
    }
    receiveFromCell(line) {
      if (this.closed) return;
      const target = this.fromCell;
      if (target === void 0) {
        this.fromCellBuffer.push(line);
      } else {
        target(line);
      }
    }
    finish() {
      if (!this.closed) this.resolveDone();
    }
    fail(error) {
      if (!this.closed) this.rejectDone(error);
    }
    close() {
      if (this.closed) return;
      this.closed = true;
      try {
        this.closeCell?.();
      } finally {
        this.resolveDone();
      }
    }
  };
  __name(_DeviceEndpoint, "DeviceEndpoint");
  var DeviceEndpoint = _DeviceEndpoint;
  var _PortableJournal = class _PortableJournal extends InMemoryJournalStore {
    constructor(bridge2) {
      super();
      __publicField(this, "bridge", bridge2);
      __publicField(this, "runId", "");
    }
    static fromJSON(data, bridge2) {
      const journal = new _PortableJournal(bridge2);
      const obj = data;
      const self = journal;
      for (const run of obj.runs ?? []) {
        const record = run;
        if (record.runId !== void 0) self.runs.set(record.runId, record);
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
      journal.runId = obj.runs?.[0]?.runId ?? "";
      return journal;
    }
    createRun(record) {
      super.createRun(record);
      this.runId = record.runId;
      this.persist();
    }
    updateRunStatus(runId, status, settlement) {
      super.updateRunStatus(runId, status, settlement);
      this.persist();
    }
    updateRunUsage(runId, spentTokens) {
      super.updateRunUsage(runId, spentTokens);
      this.persist();
    }
    updateRunCaps(runId, caps) {
      super.updateRunCaps(runId, caps);
      this.persist();
    }
    updateRunScript(runId, scriptText, scriptHash) {
      super.updateRunScript(runId, scriptText, scriptHash);
      this.persist();
    }
    putActor(record) {
      super.putActor(record);
      this.persist();
    }
    putNode(record) {
      super.putNode(record);
      this.persist();
    }
    appendEvent(runId, event) {
      const stored = super.appendEvent(runId, event);
      this.persist();
      return stored;
    }
    toJSON() {
      const self = this;
      return {
        runs: [...self.runs.values()],
        actors: [...self.actors.entries()].map(([runId, entries]) => [runId, [...entries.entries()]]),
        nodes: [...self.nodes.entries()].map(([runId, entries]) => [runId, [...entries.entries()]]),
        events: [...self.events.entries()]
      };
    }
    persist() {
      if (!this.runId) return;
      try {
        this.bridge.saveJournal(this.runId, JSON.stringify(this.toJSON()));
      } catch {
      }
    }
  };
  __name(_PortableJournal, "PortableJournal");
  var PortableJournal = _PortableJournal;
  var _BridgeDriver = class _BridgeDriver {
    constructor(runId, journal, bridge2, conversationId) {
      __publicField(this, "runId", runId);
      __publicField(this, "journal", journal);
      __publicField(this, "bridge", bridge2);
      __publicField(this, "conversationId", conversationId);
      __publicField(this, "sink");
      __publicField(this, "seq", 0);
      __publicField(this, "requests", /* @__PURE__ */ new Map());
      __publicField(this, "instanceRequests", /* @__PURE__ */ new Map());
      __publicField(this, "worlds", /* @__PURE__ */ new Map());
      __publicField(this, "artifacts", /* @__PURE__ */ new Map());
    }
    attach(sink) {
      this.sink = sink;
    }
    owns(requestId) {
      return this.requests.has(requestId) || this.worlds.has(requestId) || this.artifacts.has(requestId) || this.instanceRequests.has(requestId);
    }
    createActorSession(actor, persona) {
      this.bridge.createActor(this.runId, actor.siteId, actor.ordinal, JSON.stringify(persona));
      return Promise.resolve({ id: `session:${this.runId}:${actor.siteId}@${actor.ordinal}` });
    }
    startAsk(session, instance, message) {
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
          conversationId: this.conversationId
        })
      );
    }
    respondToSubmit(instance, verdict) {
      const key2 = `${instance.siteId}@${instance.ordinal}`;
      const requestId = this.instanceRequests.get(key2);
      if (requestId === void 0) return;
      if (verdict.kind === "accept") {
        this.instanceRequests.delete(key2);
        this.requests.delete(requestId);
        return;
      }
      this.bridge.continueAsk(requestId, JSON.stringify(verdict));
    }
    cancelAsk(instance) {
      const key2 = `${instance.siteId}@${instance.ordinal}`;
      const requestId = this.instanceRequests.get(key2);
      if (requestId === void 0) return;
      this.instanceRequests.delete(key2);
      this.requests.delete(requestId);
      this.bridge.cancelAsk(requestId);
    }
    executeWorldRead(op, args) {
      const requestId = `${this.runId}:world:${++this.seq}`;
      return new Promise((resolve, reject) => {
        this.worlds.set(requestId, { resolve, reject });
        this.bridge.worldRead(requestId, op, JSON.stringify(args));
      });
    }
    executeArtifactPublish(request) {
      const requestId = `${this.runId}:artifact:${++this.seq}`;
      return new Promise((resolve, reject) => {
        this.artifacts.set(requestId, { resolve, reject });
        this.bridge.publishArtifact(requestId, JSON.stringify({ runId: this.runId, request }));
      });
    }
    emit(event) {
      this.bridge.emit(this.runId, JSON.stringify(event));
    }
    askResult(requestId, resultJson) {
      const instance = this.requests.get(requestId);
      if (instance === void 0 || this.sink === void 0) return;
      const result = JSON.parse(resultJson);
      if (result.stats !== void 0) {
        this.sink.askStats(instance, result.stats);
      }
      try {
        switch (result.kind) {
          case "submit":
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
    worldResult(requestId, ok, payloadJson, error) {
      const pending = this.worlds.get(requestId);
      if (pending === void 0) return;
      this.worlds.delete(requestId);
      if (ok) {
        pending.resolve(payloadJson === "" ? void 0 : JSON.parse(payloadJson));
      } else {
        pending.reject(new WorkflowError("DriverError", error ?? "world read failed"));
      }
    }
    artifactResult(requestId, ok, payloadJson, error) {
      const pending = this.artifacts.get(requestId);
      if (pending === void 0) return;
      this.artifacts.delete(requestId);
      if (ok) {
        pending.resolve(JSON.parse(payloadJson));
      } else {
        pending.reject(new WorkflowError("DriverError", error ?? "artifact publish failed"));
      }
    }
  };
  __name(_BridgeDriver, "BridgeDriver");
  var BridgeDriver = _BridgeDriver;

  // src/android-entry.ts
  function ensureSetImmediate() {
    const target = globalThis;
    if (target.setImmediate === void 0) {
      target.setImmediate = (fn) => setTimeout(fn, 0);
    }
  }
  __name(ensureSetImmediate, "ensureSetImmediate");
  async function createIframeRealm() {
    const iframe = document.createElement("iframe");
    iframe.setAttribute("aria-hidden", "true");
    iframe.style.display = "none";
    iframe.src = "about:blank";
    const loaded = new Promise((resolve, reject) => {
      iframe.onload = () => resolve();
      iframe.onerror = () => reject(new Error("workflow cell iframe failed to load"));
    });
    (document.body ?? document.documentElement).appendChild(iframe);
    await loaded;
    const win = iframe.contentWindow;
    if (win === null) throw new Error("workflow cell iframe has no contentWindow");
    return {
      global: win,
      evaluate: /* @__PURE__ */ __name((code) => {
        const stripped = code.replace(/^\s*(['"])use strict\1;?\s*/, "");
        return win.eval(stripped);
      }, "evaluate"),
      close: /* @__PURE__ */ __name(() => iframe.remove(), "close")
    };
  }
  __name(createIframeRealm, "createIframeRealm");
  ensureSetImmediate();
  var runner = new WorkflowRunner({ createRealm: createIframeRealm, bridge: __andmx });
  window.__DWF_RUNNER = {
    start: /* @__PURE__ */ __name((configJson) => runner.start(configJson), "start"),
    askResult: /* @__PURE__ */ __name((requestId, resultJson) => runner.askResult(requestId, resultJson), "askResult"),
    worldResult: /* @__PURE__ */ __name((requestId, ok, payloadJson, error) => runner.worldResult(requestId, ok, payloadJson, error), "worldResult"),
    artifactResult: /* @__PURE__ */ __name((requestId, ok, payloadJson, error) => runner.artifactResult(requestId, ok, payloadJson, error), "artifactResult"),
    fillHole: /* @__PURE__ */ __name((runId, fillJson) => runner.fillHole(runId, fillJson), "fillHole"),
    openHoles: /* @__PURE__ */ __name((runId) => runner.openHoles(runId), "openHoles"),
    markMutating: /* @__PURE__ */ __name((runId, siteId, ordinal) => runner.markMutating(runId, siteId, ordinal), "markMutating"),
    stop: /* @__PURE__ */ __name((runId, reason, supersededBy) => runner.stop(runId, reason, supersededBy), "stop"),
    cancel: /* @__PURE__ */ __name((runId) => runner.cancel(runId), "cancel")
  };
})();
