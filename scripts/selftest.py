"""End-to-end self-test for kuheli_core.py and bridge.js. No network, no Android needed.

Run:  python3 scripts/selftest.py
"""
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "app", "src", "main", "python"))
import kuheli_core as core  # noqa: E402


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def sse(handler, chunks):
    handler.send_response(200)
    handler.send_header("Content-Type", "text/event-stream")
    handler.end_headers()
    for c in chunks:
        handler.wfile.write(("data: " + json.dumps(c) + "\n\n").encode())
        handler.wfile.flush()
    handler.wfile.write(b"data: [DONE]\n\n")
    handler.wfile.flush()


def delta(content=None, tool_calls=None):
    d = {}
    if content is not None:
        d["content"] = content
    if tool_calls is not None:
        d["tool_calls"] = tool_calls
    return {"choices": [{"index": 0, "delta": d}]}


class MockProvider(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def reply_json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.headers.get("Authorization") != "Bearer good-key":
            return self.reply_json(401, {"error": {"message": "Incorrect API key"}})
        if self.path == "/v1/models":
            return self.reply_json(200, {"data": [{"id": "models/m-b"}, {"id": "m-a"}]})
        self.reply_json(404, {"error": {"message": "nope"}})

    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.headers.get("Authorization") != "Bearer good-key":
            return self.reply_json(401, {"error": {"message": "Incorrect API key"}})
        msgs = payload["messages"]
        tool_msg = [m for m in msgs if m["role"] == "tool"]
        if not payload.get("stream"):
            return self.reply_json(200, {"choices": [{"message": {"content": "plain reply"}}],
                                         "usage": {"prompt_tokens": 3, "completion_tokens": 2}})
        if tool_msg:
            return sse(self, [delta("Tool said: "), delta(tool_msg[-1]["content"]),
                              {"choices": [], "usage": {"prompt_tokens": 10, "completion_tokens": 5}}])
        if payload.get("tools"):
            name = payload["tools"][-1]["function"]["name"]
            return sse(self, [
                delta("Let me check. "),
                delta(tool_calls=[{"index": 0, "id": "c1", "function": {"name": name, "arguments": '{"te'}}]),
                delta(tool_calls=[{"index": 0, "function": {"arguments": 'xt":"hi"}'}}]),
            ])
        return sse(self, [delta("Hello "), delta("world")])


class FakeCb:
    def __init__(self, decision=1):
        self.tokens, self.calls, self.results, self.status = [], [], [], []
        self.done = self.error = None
        self.decision = decision
        self.asked = []

    def onToken(self, t): self.tokens.append(t)
    def onToolCall(self, n, a): self.calls.append((n, a))
    def onToolResult(self, n, r): self.results.append((n, r))
    def onStatus(self, s): self.status.append(s)
    def onDone(self, j): self.done = json.loads(j)
    def onError(self, m): self.error = m

    def approve(self, n, a):
        self.asked.append(n)
        return self.decision


failures = []


def check(name, cond, extra=""):
    print(("PASS  " if cond else "FAIL  ") + name + ("  " + str(extra) if (extra and not cond) else ""))
    if not cond:
        failures.append(name)


def cfg(provider, **kw):
    base = {"provider": provider, "messages": [{"role": "user", "content": "hi"}], "system": "be brief",
            "temperature": 0.5, "max_tokens": 0, "tools_enabled": False, "require_approval": False,
            "max_steps": 5, "stream": True, "skills": []}
    base.update(kw)
    return json.dumps(base)


def main():
    mport = free_port()
    srv = ThreadingHTTPServer(("127.0.0.1", mport), MockProvider)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    good = {"name": "Mock", "base_url": "http://127.0.0.1:%d/v1" % mport, "api_key": "good-key", "model": "m-a"}
    bad = dict(good, name="BadKey", api_key="wrong")
    dead = dict(good, name="Dead", base_url="http://127.0.0.1:%d/v1" % free_port())

    # 1. plain streaming
    cb = FakeCb()
    core.run_chat(cfg(good), cb)
    check("streams tokens", "".join(cb.tokens) == "Hello world" and cb.error is None, (cb.tokens, cb.error))
    check("onDone carries full text", cb.done and cb.done["content"] == "Hello world")

    # 2. non-streaming fallback path
    cb = FakeCb()
    core.run_chat(cfg(good, stream=False), cb)
    check("non-stream JSON reply", cb.done and cb.done["content"] == "plain reply" and cb.done["usage"]["prompt"] == 3,
          (cb.done, cb.error))

    # 3. auth error surfaces clearly
    cb = FakeCb()
    core.run_chat(cfg(bad), cb)
    check("401 becomes a readable error", cb.error and "HTTP 401" in cb.error and "Incorrect API key" in cb.error, cb.error)

    # 4. fallback provider
    cb = FakeCb()
    core.run_chat(cfg(dead, fallbacks=[good]), cb)
    check("falls back to the next provider", cb.done and cb.done["content"] == "Hello world" and cb.status, (cb.error, cb.status))

    # 5. models + connection test
    models = json.loads(core.list_models(json.dumps(good)))
    check("list_models strips models/ and sorts", models["ok"] and models["models"] == ["m-a", "m-b"], models)
    conn = json.loads(core.test_connection(json.dumps(bad)))
    check("test_connection reports failure", not conn["ok"] and "401" in conn["message"], conn)

    # 6. the real Node bridge with a fake MCP server, then a full tool loop through it
    bport = free_port()
    tmp = tempfile.mkdtemp()
    conf = os.path.join(tmp, "config.json")
    with open(conf, "w") as f:
        json.dump({"port": bport, "command": "node", "args": [os.path.join(ROOT, "scripts", "fake_mcp.js")]}, f)
    bridge = subprocess.Popen(["node", os.path.join(ROOT, "app/src/main/assets/nodejs-project/bridge.js"), conf],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    url = "http://127.0.0.1:%d" % bport
    try:
        state = {}
        for _ in range(50):
            state = json.loads(core.bridge_request(url, "/health"))
            if state.get("state") == "ready":
                break
            time.sleep(0.2)
        check("bridge reaches ready state", state.get("state") == "ready", state)

        tl = json.loads(core.mcp_tools(url))
        check("lists MCP tools through the bridge", tl["ok"] and tl["tools"][0]["name"] == "echo.tool", tl)

        cb = FakeCb(decision=1)
        core.run_chat(cfg(good, tools_enabled=True, bridge_url=url, require_approval=True), cb)
        text = "".join(cb.tokens)
        check("tool call sanitised, approved and executed",
              cb.calls and cb.calls[0][0] == "echo_tool" and cb.asked == ["echo_tool"], (cb.calls, cb.asked, cb.error))
        check("tool result reaches the model", "echo:hi" in text and cb.done and cb.done["steps"] == 2, (text, cb.error))

        cb = FakeCb(decision=0)
        core.run_chat(cfg(good, tools_enabled=True, bridge_url=url, require_approval=True), cb)
        check("declined tool call is reported, not run",
              cb.results and "declined" in cb.results[0][1] and "echo:hi" not in "".join(cb.tokens), (cb.results, cb.error))

        cb = FakeCb()
        core.run_chat(cfg(good, tools_enabled=True, bridge_url=url, tool_filter="nomatch"), cb)
        check("tool filter hides tools", cb.done and "".join(cb.tokens) == "Hello world", (cb.tokens, cb.error))

        cb = FakeCb()
        core.run_chat(cfg(good, tools_enabled=True, bridge_url="http://127.0.0.1:%d" % free_port()), cb)
        check("unreachable bridge degrades to plain chat", cb.done and cb.status and cb.error is None, (cb.status, cb.error))

        restart = json.loads(core.bridge_request(url, "/restart", post=True))
        check("bridge restart endpoint works", restart.get("reachable") is True, restart)
    finally:
        bridge.terminate()

    # 7. skills
    skills = [{"name": "demo", "description": "A demo skill", "body": "DEMO BODY", "enabled": True},
              {"name": "off", "description": "disabled", "body": "NO", "enabled": False}]
    msgs = core._build_messages(json.loads(cfg(good, skills=skills, slash_skill="demo")))
    check("skills are listed and the slash skill is injected",
          "demo: A demo skill" in msgs[0]["content"] and "DEMO BODY" in msgs[0]["content"] and "off:" not in msgs[0]["content"])
    cb = FakeCb()
    core.run_chat(cfg(good, skills=skills), cb)
    check("skill tool is offered to the model", cb.done is not None and cb.error is None, cb.error)

    srv.shutdown()
    print("\n%d check(s) failed" % len(failures) if failures else "\nAll checks passed")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
