#!/usr/bin/env python3
"""
Minimal OpenAI-compatible streaming server for AndMX end-to-end testing.

Behaviour:
  - If the request's messages contain no tool result yet, stream a tool_call
    asking run_shell to execute a probe command.
  - Otherwise (a tool result is present), stream a final text answer that
    echoes what the tool returned.

Run: python3 tools/mock_llm_server.py  (listens on :8765)
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8791


def sse(obj):
    return f"data: {json.dumps(obj)}\n\n".encode()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        messages = body.get("messages", [])
        has_tool_result = any(m.get("role") == "tool" for m in messages)
        print(f"HIT {self.path} msgs={len(messages)} has_tool={has_tool_result}", flush=True)

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()

        if not has_tool_result:
            # Stream a tool call to run_shell.
            self.wfile.write(sse({
                "choices": [{"delta": {"role": "assistant", "tool_calls": [{
                    "index": 0, "id": "call_1",
                    "function": {"name": "run_shell",
                                 "arguments": json.dumps({"command": "echo HELLO_FROM_AGENT; uname -m"})},
                }]}}]
            }))
            self.wfile.write(sse({"choices": [{"delta": {}, "finish_reason": "tool_calls"}]}))
        else:
            tool_out = ""
            for m in messages:
                if m.get("role") == "tool":
                    tool_out = (m.get("content") or "")[:120].replace("\n", " ")
            answer = f"工具已在沙箱执行,输出:{tool_out}"
            for ch in [answer[i:i+8] for i in range(0, len(answer), 8)]:
                self.wfile.write(sse({"choices": [{"delta": {"content": ch}}]}))
            self.wfile.write(sse({"choices": [{"delta": {}, "finish_reason": "stop"}]}))

        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


if __name__ == "__main__":
    print(f"mock LLM on :{PORT}")
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
