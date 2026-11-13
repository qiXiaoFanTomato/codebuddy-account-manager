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
  const rawDetail =
    toolInput.command || toolInput.file_path || toolInput.path || toolInput.url || '';
  const truncated = str(rawDetail).length > MAX_DETAIL;
  const detail = bounded(rawDetail, MAX_DETAIL);
  const event = bounded(payload.hook_event_name || process.argv[2] || 'PreToolUse', 64) || 'PreToolUse';
  return { toolName, detail, event, truncated };
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
  const { toolName, detail, event, truncated } = project(payload);

  appendEvent({
    ts: new Date().toISOString(),
    event,
    tool: toolName,
    detail: bounded(detail, LOG_DETAIL),
    truncated,
    stage: 'received',
  });

  const decision = await requestDecision({
    tool_name: toolName,
    detail,
    event,
    detail_truncated: truncated,
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
