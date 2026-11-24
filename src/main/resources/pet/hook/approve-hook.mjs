#!/usr/bin/env node
/**
 * CodeBuddy 桌宠审批桥（PreToolUse hook）
 *
 * 职责：宿主在工具执行前把事件写进 stdin，这里投影出极少字段后
 * 向本机桌宠（127.0.0.1:8793）要一个决策，再把决策以 hook 输出返回。
 *
 * 约定：
 *  - 只有拿到精确的 allow / deny 才写 stdout；其余情况一律不输出并 exit 0，
 *    宿主会回退自己的原生确认框（fail-open，不静默放行）。
 *  - 不读提示词、不读工具结果，只投影 tool_name 与命令 / 路径摘要。
 *  - 诊断事件追加到 ~/.codebuddy/codebuddy-pet/hook-events.jsonl（限 512KB 轮转），
 *    用于核对宿主真实 payload；内容同样只有上面的白名单字段。
 */

import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';

/** 与 HookApprovalServer.PORT 保持一致 */
const PORT = 8793;
/** 必须小于宿主 hook 的超时（90s） */
const TIMEOUT_MS = 55_000;
const MAX_STDIN_BYTES = 256 * 1024;
const MAX_TOOL_NAME = 128;
/** 传给桌宠的摘要上限：留足长度让危险关键词不被截掉；真正的展示截断在桌宠侧做 */
const MAX_DETAIL = 2000;
/** 诊断日志只记开头，避免日志被长命令撑爆 */
const LOG_DETAIL = 500;
const MAX_RESPONSE_BYTES = 64;
const EVENT_LOG_MAX = 512 * 1024;
/** PostToolUse 结果上报路径，与 HookApprovalServer.RESULT_PATH 保持一致 */
const RESULT_PATH = '/result';
/** 回传结果的摘要上限：够看出成功/失败与关键输出即可 */
const MAX_RESULT = 600;
/** 结果上报超时：只是记账，不该拖住宿主 */
const RESULT_TIMEOUT_MS = 2_000;

const DATA_DIR = path.join(process.env.USERPROFILE || os.homedir(), '.codebuddy', 'codebuddy-pet');
const EVENT_LOG = path.join(DATA_DIR, 'hook-events.jsonl');

function str(value) {
  return typeof value === 'string' ? value : '';
}

function bounded(value, max) {
  const text = str(value);
  return text.length > max ? text.slice(0, max) : text;
}

function readStdin() {
  try {
    const raw = fs.readFileSync(0, 'utf8');
    if (!raw || raw.length > MAX_STDIN_BYTES) return '';
    return raw;
  } catch {
    return '';
  }
}

function parsePayload(raw) {
  if (!raw) return {};
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

/** 只投影白名单字段：工具名 + 一句展示摘要 */
function project(payload) {
  const toolInput =
    payload.tool_input && typeof payload.tool_input === 'object' && !Array.isArray(payload.tool_input)
      ? payload.tool_input
      : {};
  const toolName = bounded(payload.tool_name || payload.hook_event_name || '?', MAX_TOOL_NAME) || '?';
  // 命令类取命令原文；写 / 编辑类取目标路径（宿主用驼峰 filePath，实测 write_to_file 只给 filePath+content）；
  // 绝不取 content —— 那是整篇文件正文，既撑爆摘要也会让风险判定误读代码文本
  const rawDetail =
    toolInput.command || toolInput.filePath || toolInput.file_path ||
    toolInput.path || toolInput.url || '';
  const truncated = str(rawDetail).length > MAX_DETAIL;
  const detail = bounded(rawDetail, MAX_DETAIL);
  const event = bounded(payload.hook_event_name || process.argv[2] || 'PreToolUse', 64) || 'PreToolUse';
  // 只取工作目录，用来标出「哪个项目」；不读提示词 / 结果
  const cwd = bounded(payload.cwd || process.cwd(), 400);
  // 会话 ID：桌宠用它反查项目与对话标题（宿主的 cwd 是 IDE 进程目录，不是项目目录）
  const sessionId = bounded(payload.session_id, 80);
  // 会话转写文件路径：索引还没落盘时，桌宠用它兜底认 workspace
  const transcriptPath = bounded(payload.transcript_path, 400);
  return { toolName, detail, event, truncated, cwd, sessionId, transcriptPath };
}

function appendEvent(record) {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    try {
      if (fs.statSync(EVENT_LOG).size > EVENT_LOG_MAX) fs.renameSync(EVENT_LOG, EVENT_LOG + '.1');
    } catch {
      // 文件不存在或轮转失败：继续追加即可
    }
    fs.appendFileSync(EVENT_LOG, JSON.stringify(record) + '\n', 'utf8');
  } catch {
    // 日志不可写不影响审批
  }
}

/** 向桌宠要决策；任何异常都返回 null（无决策） */
function requestDecision(body) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (value) => {
      if (settled) return;
      settled = true;
      resolve(value);
    };

    let req;
    try {
      const data = Buffer.from(JSON.stringify(body), 'utf8');
      req = http.request(
        {
          host: '127.0.0.1',
          port: PORT,
          path: '/approve',
          method: 'POST',
          // 短连接，避免 keep-alive socket 挂住进程
          agent: false,
          headers: { 'Content-Type': 'application/json', 'Content-Length': data.length },
        },
        (res) => {
          if (res.statusCode !== 200) {
            res.resume();
            finish(null);
            return;
          }
          const chunks = [];
          let size = 0;
          res.on('data', (chunk) => {
            size += chunk.length;
            if (size > MAX_RESPONSE_BYTES) {
              res.destroy();
              finish(null);
              return;
            }
            chunks.push(chunk);
          });
          res.on('end', () => {
            const text = Buffer.concat(chunks).toString('utf8').trim();
            finish(text === 'allow' || text === 'deny' ? text : null);
          });
          res.on('error', () => finish(null));
        },
      );
      req.setTimeout(TIMEOUT_MS, () => {
        req.destroy();
        finish(null);
      });
      req.on('error', () => finish(null));
      req.write(data);
      req.end();
    } catch {
      try {
        req?.destroy();
      } catch {
        // 忽略
      }
      finish(null);
    }
  });
}

/**
 * 从 PostToolUse 的 payload 里压一句执行结果。
 *
 * 宿主不同版本把结果放在 tool_response 里，可能是字符串，也可能是
 * { stdout, stderr, exitCode, isError } 这类对象；只取白名单字段，别的都不读。
 */
function summarizeResult(payload) {
  const r = payload.tool_response;
  if (r === undefined || r === null) return { ok: '', text: '' };
  if (typeof r === 'string') return { ok: '', text: bounded(r, MAX_RESULT) };
  const parts = [];
  let ok = '';
  try {
    for (const key of ['stdout', 'stderr', 'output', 'content', 'message', 'error']) {
      const v = r[key];
      if (v !== undefined && v !== null && v !== '') parts.push(`${key}=${bounded(String(v), 300)}`);
    }
    const code = r.exitCode !== undefined ? r.exitCode : r.exit_code;
    if (code !== undefined && code !== null) {
      ok = String(code === 0 || code === '0');
      parts.push(`exitCode=${code}`);
    }
    if (r.isError !== undefined && ok === '') ok = String(!r.isError);
    if (r.interrupted === true) {
      ok = 'false';
      parts.push('interrupted=true');
    }
  } catch {
    // 结构不认识就退化成整段截断
  }
  if (parts.length === 0) {
    try {
      return { ok, text: bounded(JSON.stringify(r), MAX_RESULT) };
    } catch {
      return { ok, text: '' };
    }
  }
  return { ok, text: bounded(parts.join(' | '), MAX_RESULT) };
}

/** 把结果送给桌宠记账；拿不到回应也无所谓，绝不阻断宿主 */
function reportResult(body) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = () => {
      if (settled) return;
      settled = true;
      resolve();
    };
    try {
      const data = Buffer.from(JSON.stringify(body), 'utf8');
      const req = http.request(
        {
          host: '127.0.0.1',
          port: PORT,
          path: RESULT_PATH,
          method: 'POST',
          agent: false,
          headers: { 'Content-Type': 'application/json', 'Content-Length': data.length },
        },
        (res) => {
          res.resume();
          res.on('end', finish);
          res.on('error', finish);
        },
      );
      req.setTimeout(RESULT_TIMEOUT_MS, () => {
        req.destroy();
        finish();
      });
      req.on('error', finish);
      req.write(data);
      req.end();
    } catch {
      finish();
    }
  });
}

/** 三套 schema 冗余写，兼容宿主不同版本对决策字段的读法 */
function decisionOutput(decision, event) {
  if (decision === 'allow') {
    const reason = 'allowed (decided via codebuddy-pet)';
    return {
      decision: 'approve',
      reason,
      hookSpecificOutput: {
        hookEventName: event,
        permissionDecision: 'allow',
        permissionDecisionReason: reason,
        decision: { behavior: 'allow' },
      },
    };
  }
  if (decision === 'deny') {
    const reason = 'denied (decided via codebuddy-pet)';
    return {
      decision: 'block',
      reason,
      hookSpecificOutput: {
        hookEventName: event,
        permissionDecision: 'deny',
        permissionDecisionReason: reason,
        decision: { behavior: 'deny', message: reason },
      },
    };
  }
  return null;
}

async function main() {
  const raw = readStdin();
  const payload = parsePayload(raw);
  const { toolName, detail, event, truncated, cwd, sessionId, transcriptPath } = project(payload);

  // PostToolUse：只把执行结果回传给桌宠记账，不参与决策、不写 stdout
  if (event === 'PostToolUse') {
    const summary = summarizeResult(payload);
    appendEvent({
      ts: new Date().toISOString(),
      event,
      tool: toolName,
      ok: summary.ok,
      result: bounded(summary.text, LOG_DETAIL),
      cwd: bounded(cwd, 200),
      stage: 'result',
    });
    await reportResult({ cwd, session_id: sessionId, tool: toolName, ok: summary.ok, result: summary.text });
    return;
  }

  appendEvent({
    ts: new Date().toISOString(),
    event,
    tool: toolName,
    detail: bounded(detail, LOG_DETAIL),
    truncated,
    cwd: bounded(cwd, 200),
    // 只记顶层键名（不记值），用来核对宿主到底给了哪些可用的项目 / 会话线索
    keys: Object.keys(payload).slice(0, 24).join(','),
    sid: bounded(payload.session_id, 80),
    transcript: bounded(transcriptPath, 200),
    stage: 'received',
  });

  const decision = await requestDecision({
    tool_name: toolName,
    detail,
    event,
    detail_truncated: truncated,
    cwd,
    session_id: sessionId,
    transcript_path: transcriptPath,
  });

  appendEvent({ ts: new Date().toISOString(), event, tool: toolName, decision: decision || 'none' });

  const output = decisionOutput(decision, event);
  if (!output) return; // fail-open：无输出 + exit 0，宿主回退原生流程
  process.stdout.write(JSON.stringify(output) + '\n');
}

main().catch(() => {
  // 任何异常都不阻断宿主的工具调用
  process.exit(0);
});
