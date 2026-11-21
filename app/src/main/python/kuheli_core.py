"""Kuheli core: provider routing, streaming chat, tool loop and an MCP client.

Standard library only, so it runs unchanged on Android (Chaquopy) and on a desktop
for testing. Everything the Java side needs is exposed as plain functions that take
and return JSON strings.
"""
import json
import re
import threading
import time
import urllib.error
import urllib.request

APP_UA = "Kuheli/1.0"
STREAM_TIMEOUT = 120
LOCAL_SKILL_TOOL = "kuheli_use_skill"

_cancel = threading.Event()
_tools_cache = {"url": None, "at": 0.0, "tools": []}


class ApiError(Exception):
    """A failure that should be shown to the user as-is."""


def cancel():
    _cancel.set()


# --------------------------------------------------------------------------- helpers

def _parse_headers(extra):
    if isinstance(extra, dict):
        return {str(k): str(v) for k, v in extra.items()}
    out = {}
    for line in str(extra or "").splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            if key.strip():
                out[key.strip()] = value.strip()
    return out


def _headers(p, accept="application/json"):
    h = {"Content-Type": "application/json", "Accept": accept, "User-Agent": APP_UA}
    key = (p.get("api_key") or "").strip()
    if key:
        h["Authorization"] = "Bearer " + key
    h.update(_parse_headers(p.get("headers")))
    return h


def _endpoint(p, path):
    base = (p.get("base_url") or "").strip().rstrip("/")
    if not base:
        raise ApiError("This provider has no base URL.")
    return base + path


def _error_text(body):
    try:
        obj = json.loads(body)
        if isinstance(obj, list) and obj:
            obj = obj[0]
        err = obj.get("error", obj) if isinstance(obj, dict) else obj
        if isinstance(err, dict):
            return str(err.get("message") or err)
        return str(err)
    except Exception:
        return body.strip()[:400]


def _http_error(e):
    return ApiError("HTTP %d: %s" % (e.code, _error_text(e.read().decode("utf-8", "replace"))))


def _http_json(p, method, path, payload=None, timeout=30):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(_endpoint(p, path), data=data, headers=_headers(p), method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        raise _http_error(e)
    except urllib.error.URLError as e:
        raise ApiError("Network error: %s" % (e.reason,))
    except OSError as e:
        raise ApiError("Network error: %s" % e)
    except ValueError:
        raise ApiError("The server replied with something that is not JSON.")


# --------------------------------------------------------------------------- providers

def list_models(provider_json):
    p = json.loads(provider_json)
    try:
        data = _http_json(p, "GET", "/models")
        items = data.get("data") if isinstance(data, dict) else data
        ids = []
        for it in items or []:
            mid = it.get("id") if isinstance(it, dict) else str(it)
            if mid:
                ids.append(re.sub(r"^models/", "", str(mid)))
        ids = sorted(set(ids))[:600]
        return json.dumps({"ok": True, "models": ids})
    except ApiError as e:
        return json.dumps({"ok": False, "message": str(e), "models": []})


def test_connection(provider_json):
    p = json.loads(provider_json)
    listed = json.loads(list_models(provider_json))
    if listed["ok"]:
        return json.dumps({"ok": True, "message": "Connected. %d models available." % len(listed["models"]),
                           "models": listed["models"]})
    if not p.get("model"):
        return json.dumps({"ok": False, "message": listed["message"], "models": []})
    try:  # Some servers have no /models endpoint; a one-token chat is a fair second test.
        _http_json(p, "POST", "/chat/completions",
                   {"model": p["model"], "max_tokens": 1, "stream": False,
                    "messages": [{"role": "user", "content": "ping"}]}, timeout=45)
        return json.dumps({"ok": True, "message": "Connected (chat request succeeded).", "models": []})
    except ApiError as e:
        return json.dumps({"ok": False, "message": str(e), "models": []})


# --------------------------------------------------------------------------- streaming

def _merge_tool_delta(calls, delta_calls):
    for tc in delta_calls or []:
        idx = tc.get("index", 0)
        slot = calls.setdefault(idx, {"id": "", "name": "", "arguments": ""})
        if tc.get("id"):
            slot["id"] = tc["id"]
        fn = tc.get("function") or {}
        if fn.get("name"):
            slot["name"] += fn["name"]
        if fn.get("arguments"):
            slot["arguments"] += fn["arguments"]


def _finish_calls(calls):
    out = []
    for i, key in enumerate(sorted(calls)):
        c = calls[key]
        if c["name"]:
            out.append({"id": c["id"] or "call_%d_%d" % (int(time.time()), i),
                        "name": c["name"], "arguments": c["arguments"] or "{}"})
    return out


def _complete(p, payload, on_token, stream=True):
    """One chat-completions request. Returns {content, tool_calls, usage, cancelled}."""
    payload = dict(payload)
    payload["stream"] = bool(stream)
    req = urllib.request.Request(
        _endpoint(p, "/chat/completions"), data=json.dumps(payload).encode("utf-8"),
        headers=_headers(p, "text/event-stream" if stream else "application/json"), method="POST")
    try:
        resp = urllib.request.urlopen(req, timeout=STREAM_TIMEOUT)
    except urllib.error.HTTPError as e:
        raise _http_error(e)
    except urllib.error.URLError as e:
        raise ApiError("Network error: %s" % (e.reason,))
    except OSError as e:
        raise ApiError("Network error: %s" % e)

    text, calls, usage = [], {}, {}
    try:
        ctype = (resp.headers.get("Content-Type") or "").lower()
        if "event-stream" not in ctype:  # the server ignored stream=true, or stream was off
            try:
                obj = json.loads(resp.read().decode("utf-8", "replace"))
            except ValueError:
                raise ApiError("The server replied with something that is not JSON.")
            if isinstance(obj, dict) and obj.get("error"):
                raise ApiError(_error_text(json.dumps(obj)))
            msg = ((obj.get("choices") or [{}])[0]).get("message") or {}
            content = msg.get("content") or ""
            if content:
                text.append(content)
                on_token(content)
            for i, tc in enumerate(msg.get("tool_calls") or []):
                fn = tc.get("function") or {}
                calls[i] = {"id": tc.get("id") or "", "name": fn.get("name") or "",
                            "arguments": fn.get("arguments") or "{}"}
            usage = obj.get("usage") or {}
        else:
            for raw in resp:
                if _cancel.is_set():
                    break
                line = raw.decode("utf-8", "replace").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                try:
                    obj = json.loads(data)
                except ValueError:
                    continue
                if isinstance(obj, dict) and obj.get("error"):
                    raise ApiError(_error_text(json.dumps(obj)))
                if obj.get("usage"):
                    usage = obj["usage"]
                for choice in obj.get("choices") or []:
                    delta = choice.get("delta") or {}
                    piece = delta.get("content")
                    if isinstance(piece, str) and piece:
                        text.append(piece)
                        on_token(piece)
                    _merge_tool_delta(calls, delta.get("tool_calls"))
    finally:
        resp.close()

    cancelled = _cancel.is_set()
    return {"content": "".join(text), "tool_calls": [] if cancelled else _finish_calls(calls),
            "usage": usage, "cancelled": cancelled}


# --------------------------------------------------------------------------- MCP bridge

def _bridge_url(url):
    return (url or "").strip().rstrip("/")


def _rpc(url, method, params=None, timeout=60):
    body = {"jsonrpc": "2.0", "id": int(time.time() * 1000) % 1000000000, "method": method}
    if params is not None:
        body["params"] = params
    req = urllib.request.Request(_bridge_url(url) + "/rpc", data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json", "User-Agent": APP_UA},
                                 method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            obj = json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        raise ApiError("REA bridge: " + _error_text(e.read().decode("utf-8", "replace")))
    except urllib.error.URLError:
        raise ApiError("Cannot reach the REA bridge at %s" % _bridge_url(url))
    except OSError as e:
        raise ApiError("REA bridge error: %s" % e)
    except ValueError:
        raise ApiError("The REA bridge returned invalid JSON.")
    if isinstance(obj, dict) and obj.get("error"):
        err = obj["error"]
        raise ApiError("REA: %s" % (err.get("message") if isinstance(err, dict) else err))
    return obj.get("result") if isinstance(obj, dict) else None


def _list_mcp_tools(url, use_cache=True):
    now = time.time()
    if use_cache and _tools_cache["url"] == url and now - _tools_cache["at"] < 60:
        return _tools_cache["tools"]
    tools, cursor = [], None
    for _ in range(20):
        res = _rpc(url, "tools/list", {"cursor": cursor} if cursor else {}, timeout=60) or {}
        tools.extend(res.get("tools") or [])
        cursor = res.get("nextCursor")
        if not cursor:
            break
    _tools_cache.update({"url": url, "at": now, "tools": tools})
    return tools


def mcp_tools(bridge_url):
    try:
        tools = _list_mcp_tools(bridge_url, use_cache=False)
        return json.dumps({"ok": True, "tools": [
            {"name": t.get("name", ""), "description": (t.get("description") or "")[:300]} for t in tools]})
    except ApiError as e:
        return json.dumps({"ok": False, "message": str(e), "tools": []})


def bridge_request(bridge_url, path, post=False):
    """Health check or control call against the Node bridge. Returns a JSON string."""
    req = urllib.request.Request(_bridge_url(bridge_url) + path, data=b"" if post else None,
                                 headers={"User-Agent": APP_UA}, method="POST" if post else "GET")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            obj = json.loads(r.read().decode("utf-8", "replace"))
        obj["reachable"] = True
        return json.dumps(obj)
    except (urllib.error.URLError, OSError, ValueError) as e:
        return json.dumps({"reachable": False, "state": "unreachable",
                           "message": "Bridge is not running (%s)" % getattr(e, "reason", e)})


def bridge_health(bridge_url):
    return bridge_request(bridge_url, "/health")


def bridge_restart(bridge_url):
    _tools_cache["at"] = 0.0
    return bridge_request(bridge_url, "/restart", post=True)


def bridge_stop(bridge_url):
    _tools_cache["at"] = 0.0
    return bridge_request(bridge_url, "/stop", post=True)


def _flatten_result(res, limit):
    if not isinstance(res, dict):
        text = json.dumps(res)
    else:
        parts = []
        for c in res.get("content") or []:
            if c.get("type") == "text":
                parts.append(c.get("text", ""))
            else:
                parts.append("[%s content omitted]" % c.get("type", "data"))
        if not parts and res.get("structuredContent") is not None:
            parts.append(json.dumps(res["structuredContent"]))
        text = "\n".join(parts) or "(empty result)"
        if res.get("isError"):
            text = "Tool error: " + text
    if limit and len(text) > limit:
        text = text[:limit] + "\n...[truncated %d characters]" % (len(text) - limit)
    return text


def _clean_schema(node):
    if isinstance(node, dict):
        return {k: _clean_schema(v) for k, v in node.items() if k not in ("$schema", "additionalProperties")}
    if isinstance(node, list):
        return [_clean_schema(v) for v in node]
    return node


# --------------------------------------------------------------------------- the agent loop

def _enabled_skills(cfg):
    return [s for s in cfg.get("skills") or [] if s.get("enabled", True) and s.get("name")]


def _build_messages(cfg):
    parts = []
    if cfg.get("system"):
        parts.append(cfg["system"])
    skills = _enabled_skills(cfg)
    if skills:
        listing = "\n".join("- %s: %s" % (s["name"], s.get("description", "")) for s in skills)
        parts.append("You have skills, which are saved instruction sets. When one fits the task, call "
                     "%s with its name to load the full instructions, then follow them.\n"
                     "Available skills:\n%s" % (LOCAL_SKILL_TOOL, listing))
    slash = cfg.get("slash_skill")
    if slash:
        for s in skills:
            if s["name"] == slash:
                parts.append("The user explicitly invoked the skill \"%s\". Follow it:\n%s" % (slash, s.get("body", "")))
    msgs = []
    if parts:
        msgs.append({"role": "system", "content": "\n\n".join(parts)})
    for m in cfg.get("messages") or []:
        if m.get("role") in ("user", "assistant", "system") and (m.get("content") or "").strip():
            msgs.append({"role": m["role"], "content": m["content"]})
    return msgs


def _collect_tools(cfg, cb):
    tools, name_map = [], {}
    if _enabled_skills(cfg):
        tools.append({"type": "function", "function": {
            "name": LOCAL_SKILL_TOOL, "description": "Load the full instructions of a saved skill by name.",
            "parameters": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}}})
    url = cfg.get("bridge_url")
    if cfg.get("tools_enabled") and url:
        try:
            raw = _list_mcp_tools(url)
        except ApiError as e:
            cb.onStatus("REA tools unavailable (%s). Continuing without tools." % e)
            raw = []
        terms = [t.strip().lower() for t in (cfg.get("tool_filter") or "").split(",") if t.strip()]
        if terms:
            raw = [t for t in raw if any(term in t.get("name", "").lower() for term in terms)]
        cap = int(cfg.get("max_tools") or 64)
        for t in raw[:cap]:
            safe = re.sub(r"[^A-Za-z0-9_-]", "_", t.get("name", ""))[:64]
            if not safe or safe in name_map or safe == LOCAL_SKILL_TOOL:
                continue
            name_map[safe] = t["name"]
            schema = _clean_schema(t.get("inputSchema") or {"type": "object", "properties": {}})
            schema.setdefault("type", "object")
            tools.append({"type": "function", "function": {
                "name": safe, "description": (t.get("description") or "")[:1000], "parameters": schema}})
        if len(raw) > cap:
            cb.onStatus("%d REA tools found; sending the first %d. Use the tool filter in Settings to choose."
                        % (len(raw), cap))
    return tools, name_map


def _call_with_fallback(providers, cfg, messages, tools, cb, emit):
    last = None
    for i, p in enumerate(providers):
        emitted = []

        def on_token(t, _e=emitted):
            _e.append(1)
            emit(t)

        payload = {"model": p.get("model") or "", "messages": messages}
        if cfg.get("temperature") is not None:
            payload["temperature"] = cfg["temperature"]
        if int(cfg.get("max_tokens") or 0) > 0:
            payload["max_tokens"] = int(cfg["max_tokens"])
        if tools:
            payload["tools"] = tools
        try:
            return _complete(p, payload, on_token, stream=cfg.get("stream", True))
        except ApiError as e:
            last = e
            if emitted or i == len(providers) - 1:
                raise
            cb.onStatus("%s failed (%s). Trying %s." % (p.get("name", "provider"), e,
                                                         providers[i + 1].get("name", "the next provider")))
    raise last or ApiError("No provider is configured.")


def run_chat(config_json, cb):
    """Blocks until the answer (and any tool calls) are finished. Reports through `cb`."""
    _cancel.clear()
    try:
        _run(json.loads(config_json), cb)
    except ApiError as e:
        cb.onError(str(e))
    except Exception as e:  # keep the app alive whatever happens in here
        cb.onError("Unexpected error: %s: %s" % (type(e).__name__, e))


def _run(cfg, cb):
    providers = [cfg["provider"]] + (cfg.get("fallbacks") or [])
    messages = _build_messages(cfg)
    tools, name_map = _collect_tools(cfg, cb)
    skills = {s["name"]: s.get("body", "") for s in _enabled_skills(cfg)}
    limit = int(cfg.get("tool_result_limit") or 20000)
    max_steps = max(1, int(cfg.get("max_steps") or 8))
    approve_all = False
    full, usage, tool_log, steps, cancelled = [], {"prompt": 0, "completion": 0}, [], 0, False

    def emit(t):
        full.append(t)
        cb.onToken(t)

    while steps < max_steps:
        steps += 1
        if full and not "".join(full).endswith("\n"):
            emit("\n\n")
        res = _call_with_fallback(providers, cfg, messages, tools, cb, emit)
        usage["prompt"] += int(res["usage"].get("prompt_tokens") or 0)
        usage["completion"] += int(res["usage"].get("completion_tokens") or 0)
        if res["cancelled"]:
            cancelled = True
            break
        if not res["tool_calls"]:
            break

        messages.append({"role": "assistant", "content": res["content"] or None, "tool_calls": [
            {"id": c["id"], "type": "function", "function": {"name": c["name"], "arguments": c["arguments"]}}
            for c in res["tool_calls"]]})
        for call in res["tool_calls"]:
            if _cancel.is_set():
                cancelled = True
                result = "Cancelled by the user."
            else:
                result, approve_all = _execute(call, name_map, skills, cfg, cb, limit, approve_all, tool_log)
            messages.append({"role": "tool", "tool_call_id": call["id"], "content": result})
        if cancelled:
            break
    else:
        emit("\n\n(Stopped after %d tool steps.)" % max_steps)

    cb.onDone(json.dumps({"content": "".join(full), "usage": usage, "steps": steps,
                          "tools": tool_log, "cancelled": cancelled}))


def _execute(call, name_map, skills, cfg, cb, limit, approve_all, tool_log):
    name, raw_args = call["name"], call["arguments"] or "{}"
    cb.onToolCall(name, raw_args[:2000])
    try:
        args = json.loads(raw_args) if raw_args.strip() else {}
        if not isinstance(args, dict):
            raise ValueError("arguments must be an object")
    except ValueError:
        result = "Error: the tool arguments were not valid JSON."
        cb.onToolResult(name, result)
        tool_log.append("%s -> invalid arguments" % name)
        return result, approve_all

    if name == LOCAL_SKILL_TOOL:
        body = skills.get(str(args.get("name", "")))
        result = body if body else "Unknown skill. Available: " + ", ".join(sorted(skills))
    elif name not in name_map:
        result = "Unknown tool: %s" % name
    else:
        decision = 1
        if cfg.get("require_approval") and not approve_all:
            decision = int(cb.approve(name, raw_args[:2000]))
            if decision == 2:
                approve_all = True
        if decision == 0:
            result = "The user declined this tool call."
        else:
            try:
                res = _rpc(cfg["bridge_url"], "tools/call", {"name": name_map[name], "arguments": args}, timeout=900)
                result = _flatten_result(res, limit)
            except ApiError as e:
                result = "Error: %s" % e
    cb.onToolResult(name, result[:600])
    tool_log.append("%s -> %s" % (name, result.replace("\n", " ")[:120]))
    return result, approve_all
