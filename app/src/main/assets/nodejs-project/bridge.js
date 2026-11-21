'use strict';
// Kuheli bridge: launches an MCP server (REA) as a child process and exposes it to the
// app over a tiny local HTTP API.
//
//   GET  /health   -> { state, message, pid, node, uptime, tail }
//   POST /rpc      -> forwards one JSON-RPC request to the MCP server
//   POST /restart  -> relaunch the MCP server (re-reads the config file)
//   POST /stop     -> stop the MCP server
//
// Usage: node bridge.js <config.json>
// The same file runs inside the app (nodejs-mobile) or in Termux / on a desktop.

const http = require('http');
const fs = require('fs');
const { spawn } = require('child_process');

const cfgPath = process.argv[2];
let cfg = {};
let proc = null;
let nextId = 1;
const pending = new Map();
const st = { state: 'stopped', message: 'Not started', pid: null, startedAt: 0, tail: [] };

function loadCfg() {
  try { cfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8')); } catch (e) { cfg = cfg || {}; }
  cfg.port = cfg.port || 8765;
  cfg.command = cfg.command || 'npx';
  cfg.args = Array.isArray(cfg.args) ? cfg.args : ['-y', 'rea-agents@latest', 'mcp'];
  cfg.env = cfg.env || {};
  cfg.requestTimeoutMs = cfg.requestTimeoutMs || 600000;
  cfg.startTimeoutMs = cfg.startTimeoutMs || 180000;
}

function note(line) {
  st.tail.push(String(line).slice(0, 400));
  if (st.tail.length > 30) st.tail.shift();
}

function failAll(message) {
  for (const [, p] of pending) { clearTimeout(p.timer); p.reject(new Error(message)); }
  pending.clear();
}

function fail(message) {
  st.state = 'error';
  st.message = message;
  note(message);
  failAll(message);
}

function send(obj) {
  if (proc && proc.stdin && proc.stdin.writable) proc.stdin.write(JSON.stringify(obj) + '\n');
}

function call(method, params, timeoutMs) {
  return new Promise((resolve, reject) => {
    if (!proc) return reject(new Error('The MCP server is not running'));
    const id = nextId++;
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error('Timed out waiting for ' + method));
    }, timeoutMs || cfg.requestTimeoutMs);
    pending.set(id, { resolve, reject, timer });
    send({ jsonrpc: '2.0', id, method, params: params === undefined ? {} : params });
  });
}

function onLine(line) {
  line = line.trim();
  if (!line) return;
  let msg;
  try { msg = JSON.parse(line); } catch (e) { note('stdout: ' + line); return; }
  if (msg.id !== undefined && (msg.result !== undefined || msg.error !== undefined) && pending.has(msg.id)) {
    const p = pending.get(msg.id);
    pending.delete(msg.id);
    clearTimeout(p.timer);
    if (msg.error) p.reject(new Error(msg.error.message || 'MCP error'));
    else p.resolve(msg.result);
  } else if (msg.method && msg.id !== undefined) {
    // The server is asking us for something (roots, sampling...). We support none of it.
    send({ jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: 'Method not supported by the Kuheli bridge' } });
  }
}

function stop() {
  const p = proc;
  proc = null;
  if (p) { try { p.kill(); } catch (e) { /* already gone */ } }
  failAll('The MCP server was stopped');
  st.state = 'stopped';
  st.message = 'Stopped';
  st.pid = null;
}

function start() {
  stop();
  loadCfg();
  st.state = 'starting';
  st.message = 'Launching ' + cfg.command + ' ' + cfg.args.join(' ');
  st.tail = [];
  st.startedAt = Date.now();

  let child;
  try {
    child = spawn(cfg.command, cfg.args, {
      env: Object.assign({}, process.env, cfg.env),
      cwd: cfg.cwd || undefined,
      stdio: ['pipe', 'pipe', 'pipe'],
    });
  } catch (e) {
    fail('This runtime cannot start child processes (' + e.message + '). Run the bridge in Termux instead.');
    return;
  }
  proc = child;
  st.pid = child.pid || null;

  let buf = '';
  child.stdout.on('data', (d) => {
    buf += d.toString('utf8');
    let i;
    while ((i = buf.indexOf('\n')) >= 0) { onLine(buf.slice(0, i)); buf = buf.slice(i + 1); }
  });
  let ebuf = '';
  child.stderr.on('data', (d) => {
    ebuf += d.toString('utf8');
    let i;
    while ((i = ebuf.indexOf('\n')) >= 0) { note(ebuf.slice(0, i)); ebuf = ebuf.slice(i + 1); }
  });
  child.on('error', (e) => {
    if (proc === child) { proc = null; fail('Could not launch "' + cfg.command + '": ' + e.message); }
  });
  child.on('exit', (code, sig) => {
    if (proc === child) {
      proc = null;
      fail('The MCP server exited (code ' + code + (sig ? ', signal ' + sig : '') + ')');
    }
  });

  call('initialize', {
    protocolVersion: '2024-11-05',
    capabilities: {},
    clientInfo: { name: 'kuheli-bridge', version: '1.0.0' },
  }, cfg.startTimeoutMs).then((res) => {
    if (proc !== child) return;
    send({ jsonrpc: '2.0', method: 'notifications/initialized' });
    st.state = 'ready';
    st.message = 'Connected to ' + ((res && res.serverInfo && res.serverInfo.name) || 'MCP server');
  }).catch((e) => {
    if (proc === child) { fail('Handshake failed: ' + e.message); try { child.kill(); } catch (x) { /* ignore */ } }
  });
}

function reply(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

function health() {
  return {
    state: st.state, message: st.message, pid: st.pid, node: process.version,
    uptime: st.startedAt ? Math.round((Date.now() - st.startedAt) / 1000) : 0,
    tail: st.tail.slice(-8),
  };
}

function readBody(req, cb) {
  const chunks = [];
  let size = 0;
  req.on('data', (c) => { size += c.length; if (size < 10 * 1024 * 1024) chunks.push(c); });
  req.on('end', () => cb(Buffer.concat(chunks).toString('utf8')));
}

const server = http.createServer((req, res) => {
  const url = (req.url || '/').split('?')[0];
  if (req.method === 'GET' && url === '/health') return reply(res, 200, health());
  if (req.method === 'POST' && url === '/restart') { start(); return reply(res, 200, health()); }
  if (req.method === 'POST' && url === '/stop') { stop(); return reply(res, 200, health()); }
  if (req.method === 'POST' && url === '/rpc') {
    return readBody(req, (text) => {
      let rq;
      try { rq = JSON.parse(text); } catch (e) { return reply(res, 400, { error: { message: 'Invalid JSON' } }); }
      if (st.state !== 'ready') {
        return reply(res, 503, { jsonrpc: '2.0', id: rq.id, error: { code: -32000, message: 'REA bridge is ' + st.state + ': ' + st.message } });
      }
      if (String(rq.method || '').startsWith('notifications/')) {
        send({ jsonrpc: '2.0', method: rq.method, params: rq.params });
        return reply(res, 200, { jsonrpc: '2.0', id: rq.id, result: {} });
      }
      call(rq.method, rq.params).then(
        (result) => reply(res, 200, { jsonrpc: '2.0', id: rq.id, result }),
        (err) => reply(res, 200, { jsonrpc: '2.0', id: rq.id, error: { code: -32000, message: err.message } }));
    });
  }
  reply(res, 404, { error: { message: 'Not found' } });
});

server.on('error', (e) => { st.state = 'error'; st.message = 'Bridge server error: ' + e.message; });

loadCfg();
server.listen(cfg.port, '127.0.0.1', () => { note('Bridge listening on 127.0.0.1:' + cfg.port); start(); });
process.on('uncaughtException', (e) => { note('uncaught: ' + e.message); });
