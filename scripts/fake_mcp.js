'use strict';
// A tiny stand-in MCP server used only by scripts/selftest.py.
let buf = '';
process.stdin.on('data', (d) => {
  buf += d.toString('utf8');
  let i;
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i).trim();
    buf = buf.slice(i + 1);
    if (!line) continue;
    const m = JSON.parse(line);
    if (m.method === 'initialize') {
      out({ jsonrpc: '2.0', id: m.id, result: { protocolVersion: '2024-11-05', capabilities: { tools: {} }, serverInfo: { name: 'fake-rea' } } });
    } else if (m.method === 'tools/list') {
      out({ jsonrpc: '2.0', id: m.id, result: { tools: [{ name: 'echo.tool', description: 'Echo text back', inputSchema: { type: 'object', properties: { text: { type: 'string' } }, additionalProperties: false } }] } });
    } else if (m.method === 'tools/call') {
      out({ jsonrpc: '2.0', id: m.id, result: { content: [{ type: 'text', text: 'echo:' + ((m.params.arguments || {}).text || '') }] } });
    }
  }
});
function out(o) { process.stdout.write(JSON.stringify(o) + '\n'); }
