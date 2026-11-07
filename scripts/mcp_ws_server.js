#!/usr/bin/env node
/**
 * 江山时序 MCP Server —— WebSocket 传输版
 *
 *   stdio 版（scripts/mcp_server.js）只能给桌面 MCP 客户端（Claude Desktop / Cursor）用；
 *   这个版本把同样的 MCP 协议挂到 WebSocket 上，Android App 可以直接连上来调用。
 *
 * 传输：RFC 6455 WebSocket（零依赖，用 Node 内置模块手写）
 * 协议：MCP JSON-RPC 2.0
 * 附加工具：ask_question —— 自然语言提问 → LLM 考据出年份与地点 → 调用 inspect_place /
 *           inspect_person → 返回解答 + 可在 App 内跳转的深链
 *
 * 用法：
 *   node scripts/mcp_ws_server.js                        # 默认 127.0.0.1:8798
 *   node scripts/mcp_ws_server.js --port 8798 --host 0.0.0.0
 *   LLM_BASE_URL=https://api.anthropic.com LLM_API_KEY=sk-xxx node scripts/mcp_ws_server.js
 *
 * 连接：ws://<host>:<port>/mcp
 */
'use strict';

const http = require('http');
const crypto = require('crypto');
const { createMcpRuntime, executeMcpTool, MCP_TOOLS } = require('./mcp_server');

const PORT = argValue('--port') || 8798;
const HOST = argValue('--host') || '127.0.0.1';

const LLM_BASE_URL =
  process.env.LLM_BASE_URL || process.env.ANTHROPIC_BASE_URL || '';
const LLM_API_KEY =
  process.env.LLM_API_KEY ||
  process.env.ANTHROPIC_AUTH_TOKEN ||
  process.env.ANTHROPIC_API_KEY ||
  '';
const LLM_MODEL = process.env.LLM_MODEL || process.env.ANTHROPIC_MODEL || '';

const ASK_QUESTION_TOOL = {
  name: 'ask_question',
  description:
    '回答关于中国历史、古典诗词、战役、历史人物或古地理的提问。' +
    '自然语言提问 → 考据出历史年份与地点/人物 → 查中华字典、政区沿革、地理坐标与地图深链。' +
    '例如「雪拥蓝关马不前是哪一年」「李白和杜甫在哪里相遇」「赤壁在今天的哪里」。',
  inputSchema: {
    type: 'object',
    properties: {
      question: { type: 'string', description: '自然语言问题，中文或英文。' },
      lang: {
        type: 'string',
        enum: ['zh', 'en'],
        description: '回答语言（默认 zh）。',
      },
    },
    required: ['question'],
  },
};

// ---------------------------------------------------------------- WebSocket

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

function wsAccept(key) {
  return crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
}

/**
 * 解析客户端发来的一个完整帧。
 * 返回 { opcode, payload, consumed } 或 null（数据不完整）。
 */
function parseFrame(buf) {
  if (buf.length < 2) return null;
  const opcode = buf[0] & 0x0f;
  const masked = (buf[1] & 0x80) !== 0;
  let len = buf[1] & 0x7f;
  let offset = 2;
  if (len === 126) {
    if (buf.length < 4) return null;
    len = buf.readUInt16BE(2);
    offset = 4;
  } else if (len === 127) {
    if (buf.length < 10) return null;
    len = Number(buf.readBigUInt64BE(2));
    offset = 10;
  }
  if (masked) {
    if (buf.length < offset + 4) return null;
    offset += 4;
  }
  if (buf.length < offset + len) return null;

  const payload = Buffer.from(buf.subarray(offset, offset + len));
  if (masked) {
    const mask = buf.subarray(offset - 4, offset);
    for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
  }
  return { opcode, payload, consumed: offset + len };
}

function buildFrame(opcode, payload) {
  const len = payload.length;
  const header = [0x80 | opcode];
  if (len < 126) {
    header.push(len);
  } else if (len <= 0xffff) {
    header.push(126, (len >> 8) & 0xff, len & 0xff);
  } else {
    header.push(127);
    let big = BigInt(len);
    for (let i = 7; i >= 0; i--) header.push(Number((big >> BigInt(i * 8)) & BigInt(0xff)));
  }
  return Buffer.concat([Buffer.from(header), payload]);
}

// ----------------------------------------------------------------------- LLM

const SYSTEM_ZH =
  '你是历史地理考据助手。用户会问一个关于中国历史、古典诗词、战役、历史人物或古地名的问题。' +
  '你的任务是把提问拆解成江山时序舆图工具可以执行的参数，并给出中文解答。' +
  '严格只输出 JSON，不要任何额外文字、不要 markdown 代码块。';

const SYSTEM_EN =
  'You are a historical geography research assistant. The user asks about Chinese history, ' +
  'classical poetry, battles, historical figures, or ancient places. Decompose the question into ' +
  'parameters the Jiangshan Atlas tools can execute, and answer in English. Output JSON only.';

const TOOL_SPEC_ZH =
  '江山时序舆图提供三个查询工具：\n' +
  '1) inspect_place(year, place) —— 查某年某地的政权、道/路/行省、郡州府、今省、两千年沿革与坐标\n' +
  '2) inspect_person(person) —— 查历史人物的生平行迹与坐标\n' +
  '3) search_atlas(query, limit) —— 在 1753 座古城、185 处诗词名胜/古战场、20 处名关、125+ 位历史人物、351 个年号、473 条编年大事中检索\n\n' +
  '请分析用户的提问，输出 JSON：\n' +
  '{"answer": "对用户提问的中文解答（含考据过程）",\n' +
  ' "tool": "inspect_place" | "inspect_person" | "search_atlas" | "none",\n' +
  ' "year": 整数或逗号分隔的整数串（公元前用负数，公元前 403 年写作 -403；题干只说朝代没有具体年份时取该朝代中间年份），\n' +
  ' "place": "地点名（中文/英文/拼音皆可，多处用逗号分隔）",\n' +
  ' "person": "人物名（多个用逗号分隔，最多 4 个）",\n' +
  ' "query": "检索关键词"}';

const TOOL_SPEC_EN =
  'The Jiangshan Atlas offers three tools:\n' +
  '1) inspect_place(year, place) — polity, macro-region, prefecture, modern province, 2400-year lore, coordinates\n' +
  '2) inspect_person(person) — spatio-temporal life trajectory of a figure\n' +
  '3) search_atlas(query, limit) — search across 1753 ancient cities, 185 landmarks, 20 passes, 125+ figures, 351 eras, 473 milestones\n\n' +
  'Decompose the user question into JSON:\n' +
  '{"answer": "the answer (in English)",\n' +
  ' "tool": "inspect_place" | "inspect_person" | "search_atlas" | "none",\n' +
  ' "year": integer or comma-separated integers (BCE negative, 403 BCE = -403),\n' +
  ' "place": "place name(s)",\n' +
  ' "person": "person name(s)",\n' +
  ' "query": "search keyword"}';

async function callLlm(question, lang) {
  if (!LLM_BASE_URL || !LLM_API_KEY) {
    throw new Error(
      '未配置 LLM：请设置 LLM_BASE_URL 与 LLM_API_KEY（或 ANTHROPIC_BASE_URL / ANTHROPIC_AUTH_TOKEN）'
    );
  }
  const wantZh = lang !== 'en';
  const base = LLM_BASE_URL.replace(/\/$/, '');
  const anthropic = /anthropic/i.test(base) || /messages/.test(process.env.LLM_TRANSPORT || '');
  const transport = process.env.LLM_TRANSPORT || (anthropic ? 'anthropic' : 'openai');

  if (transport === 'anthropic') {
    const body = JSON.stringify({
      model: LLM_MODEL || undefined,
      max_tokens: 2048,
      system: wantZh ? SYSTEM_ZH : SYSTEM_EN,
      messages: [
        {
          role: 'user',
          content: (wantZh ? TOOL_SPEC_ZH : TOOL_SPEC_EN) + '\n\n用户提问：' + question,
        },
      ],
    });
    const res = await fetch(`${base}/v1/messages`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-api-key': LLM_API_KEY,
        'anthropic-version': '2023-06-01',
        authorization: `Bearer ${LLM_API_KEY}`,
      },
      body,
    });
    if (!res.ok) throw new Error(`LLM 请求失败 HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
    const data = await res.json();
    const text =
      data && Array.isArray(data.content)
        ? data.content.filter((c) => c.type === 'text').map((c) => c.text).join('')
        : '';
    if (!text) throw new Error('LLM 返回为空');
    return extractJson(text);
  }

  // OpenAI 兼容
  const body = JSON.stringify({
    model: LLM_MODEL || undefined,
    temperature: 0,
    messages: [
      { role: 'system', content: wantZh ? SYSTEM_ZH : SYSTEM_EN },
      {
        role: 'user',
        content: (wantZh ? TOOL_SPEC_ZH : TOOL_SPEC_EN) + '\n\n用户提问：' + question,
      },
    ],
  });
  const url = base.endsWith('/v1') ? base + '/chat/completions' : base + '/v1/chat/completions';
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      authorization: `Bearer ${LLM_API_KEY}`,
    },
    body,
  });
  if (!res.ok) throw new Error(`LLM 请求失败 HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);

  const data = await res.json();
  const text = data && data.choices && data.choices[0] && data.choices[0].message
    ? data.choices[0].message.content
    : '';
  if (!text) throw new Error('LLM 返回为空');
  return extractJson(text);
}

function extractJson(text) {
  let cleaned = String(text).trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/i, '');
  const match = cleaned.match(/\{[\s\S]*\}/);
  if (match) cleaned = match[0];
  return JSON.parse(cleaned);
}

async function askQuestion(runtime, question, lang) {
  const parsed = await callLlm(question, lang);
  const tool = parsed.tool || 'none';
  let atlas = null;
  let note = '';

  if (tool === 'inspect_place' && parsed.place) {
    const year = parsed.year !== undefined && parsed.year !== null ? parsed.year : 0;
    atlas = executeMcpTool(runtime, 'inspect_place', { year, place: parsed.place, lang });
  } else if (tool === 'inspect_person' && parsed.person) {
    atlas = executeMcpTool(runtime, 'inspect_person', { person: parsed.person, lang });
  } else if (tool === 'search_atlas' && parsed.query) {
    atlas = executeMcpTool(runtime, 'search_atlas', { query: parsed.query, lang });
  } else if (tool && tool !== 'none') {
    note = `已识别意图 ${tool}，但缺少必要参数，请把年份与地点写得更明确。`;
  }

  return {
    question,
    lang,
    answer: parsed.answer || '(模型未给出解答)',
    year: parsed.year !== undefined ? parsed.year : undefined,
    place: parsed.place || undefined,
    person: parsed.person || undefined,
    atlas,
    note,
  };
}

// --------------------------------------------------------------------------- RPC

function respond(socket, msg, result) {
  socket.write(
    buildFrame(0x1, Buffer.from(JSON.stringify({ jsonrpc: '2.0', id: msg.id, result }), 'utf8'))
  );
}

function respondError(socket, msg, error) {
  socket.write(
    buildFrame(
      0x1,
      Buffer.from(JSON.stringify({ jsonrpc: '2.0', id: msg.id, error }), 'utf8')
    )
  );
}

function routeRpc(socket, msg, runtime) {
  const method = msg.method;
  const params = msg.params || {};

  if (method === 'initialize') {
    return respond(socket, msg, {
      protocolVersion: params.protocolVersion || '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: 'jiangshan-atlas-mcp-ws', version: '1.0.0' },
    });
  }
  if (method === 'ping') return respond(socket, msg, {});
  if (method === 'tools/list') {
    return respond(socket, msg, { tools: [...MCP_TOOLS, ASK_QUESTION_TOOL] });
  }
  if (method === 'tools/call') {
    const name = params.name;
    const args = params.arguments || {};
    if (name === 'ask_question') {
      askQuestion(runtime, String(args.question || ''), args.lang || 'zh')
        .then((data) => respond(socket, msg, {
          content: [{ type: 'text', text: JSON.stringify(data, null, 2) }],
        }))
        .catch((err) => respond(socket, msg, {
          isError: true,
          content: [{
            type: 'text',
            text: String(err && err.message ? err.message : err),
          }],
        }));
      return;
    }
    try {
      const data = executeMcpTool(runtime, name, args);
      respond(socket, msg, { content: [{ type: 'text', text: JSON.stringify(data, null, 2) }] });
    } catch (err) {
      respond(socket, msg, {
        isError: true,
        content: [{ type: 'text', text: String(err && err.message ? err.message : err) }],
      });
    }
    return;
  }
  respondError(socket, msg, { code: -32601, message: `Method not found: ${method}` });
}

// -------------------------------------------------------------------------- server

function argValue(flag) {
  const i = process.argv.indexOf(flag);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({
      ok: true,
      llm: LLM_BASE_URL ? 'configured' : 'not configured',
      model: LLM_MODEL || null,
    }));
    return;
  }
  res.writeHead(404);
  res.end('not found');
});

server.on('upgrade', (req, socket) => {
  console.log(`[ws] upgrade 请求: ${req.url}`);
  let url;
  try {
    url = new URL(req.url, 'http://localhost');
  } catch (_) {
    socket.destroy();
    return;
  }
  if (url.pathname !== '/mcp' || !/^websocket$/i.test(req.headers.upgrade || '')) {
    socket.destroy();
    return;
  }

  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${wsAccept(req.headers['sec-websocket-key'] || '')}\r\n\r\n`
  );

  const runtime = createMcpRuntime();
  const sendText = (obj) =>
    socket.write(buildFrame(0x1, Buffer.from(JSON.stringify(obj), 'utf8')));

  let buffer = Buffer.alloc(0);

  socket.on('data', (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    for (;;) {
      const frame = parseFrame(buffer);
      if (!frame) break;
      buffer = buffer.subarray(frame.consumed);

      if (frame.opcode === 0x8) { socket.end(); return; }
      if (frame.opcode === 0x9) { socket.write(buildFrame(0xa, frame.payload)); continue; }
      if (frame.opcode !== 0x1) continue;

      let msg;
      try {
        msg = JSON.parse(frame.payload.toString('utf8'));
      } catch (_) {
        continue;
      }
      if (msg.id === undefined || msg.id === null) continue; // 通知
      routeRpc(socket, msg, runtime);
    }
  });

  socket.on('error', () => socket.destroy());
});

server.listen(PORT, HOST, () => {
  console.log('江山时序 MCP WebSocket server');
  console.log(`  ws://${HOST}:${PORT}/mcp`);
  console.log(`  健康检查 http://${HOST}:${PORT}/health`);
  console.log(
    `  LLM: ${LLM_BASE_URL ? `${LLM_BASE_URL} (model ${LLM_MODEL || 'default'})` : '未配置（ask_question 不可用）'}`
  );
});
