import fs from 'node:fs';
import path from 'node:path';

const AUTH_DIR = path.join(process.env.LOCALAPPDATA, 'CodeBuddyExtension', 'Data', 'Public', 'auth');
const ACTIVE = path.join(AUTH_DIR, 'workbuddy-desktop.info');

function describe(v, depth = 0, key = '') {
  const pad = '  '.repeat(depth);
  if (v === null) return pad + key + ': null';
  if (Array.isArray(v)) return pad + key + ': Array(' + v.length + ')';
  const t = typeof v;
  if (t === 'string') {
    const looksSecret = /token|secret|key|password|credential|session/i.test(key);
    const shown = looksSecret ? v.slice(0, 6) + '…(len=' + v.length + ')' : v.slice(0, 60) + (v.length > 60 ? '…' : '');
    return pad + key + ': string "' + shown + '"';
  }
  if (t === 'number' || t === 'boolean') return pad + key + ': ' + t + ' ' + v;
  if (t === 'object') {
    const lines = [pad + key + ': {'];
    for (const [k, val] of Object.entries(v)) {
      if (val && typeof val === 'object' && !Array.isArray(val) && depth < 4) {
        lines.push(describe(val, depth + 1, k));
      } else {
        lines.push(describe(val, depth + 1, k));
      }
    }
    lines.push(pad + '}');
    return lines.join('\n');
  }
  return pad + key + ': ' + t;
}

function load(p) {
  const raw = fs.readFileSync(p, 'utf8');
  console.log('--- ' + path.basename(p) + ' ---');
  console.log('bytes=' + raw.length + ', startsWith=' + JSON.stringify(raw.slice(0, 40)));
  try {
    const j = JSON.parse(raw);
    console.log(describe(j, 0, 'root'));
  } catch (e) {
    console.log('NOT JSON: ' + e.message);
    console.log('raw head: ' + raw.slice(0, 200));
  }
  console.log('');
}

const files = fs.readdirSync(AUTH_DIR).filter(n => n.endsWith('.info'));
for (const n of files) load(path.join(AUTH_DIR, n));
