// Browser bundle stub: the dynamic-workflow facade registry imports typescript
// for compile-time symbol helpers, but the runtime engine only calls the
// registry's pure op predicates. Keeping the real compiler in the WebView
// bundle costs ~10 MiB per run; stub it instead.
const ts: Record<string, unknown> = new Proxy({}, { get: () => undefined });
export default ts;
